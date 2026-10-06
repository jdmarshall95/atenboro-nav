package com.atenboro.nav.service

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.model.NavUpdate
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.parse.ManeuverIconClassifier
import com.atenboro.nav.parse.NavParser
import com.atenboro.nav.parse.NavTextFilter
import com.atenboro.nav.parse.RemoteViewsReader

/**
 * Читает ongoing-уведомления 2ГИС (RemoteViews + largeIcon).
 * Манёвр для 2ГИС часто только в иконке, текст — улица/ETA.
 */
class NavNotificationListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                // Doze/locked: 2ГИС часто не шлёт onNotificationPosted на каждый тик дистанции —
                // периодически читаем activeNotifications сами.
                activeNotifications
                    ?.filter { it.packageName?.startsWith("ru.dublgis") == true }
                    ?.forEach { handle(it, "poll") }
            } catch (e: Exception) {
                Log.d(TAG, "poll: ${e.message}")
            }
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DebugStore.get(this).info("notif listener connected")
        activeNotifications?.forEach { handle(it, "active") }
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.postDelayed(pollRunnable, POLL_MS)
    }

    override fun onListenerDisconnected() {
        mainHandler.removeCallbacks(pollRunnable)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handle(sbn, "posted")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (!pkg.startsWith("ru.dublgis")) return
        // Навигация снята — сбрасываем sticky
        if (sbn.id == 13 || sbn.notification?.channelId?.contains("Navigation", ignoreCase = true) == true) {
            NavFeed.clearNavigating(applicationContext, "notif/removed")
        }
    }

    private fun handle(sbn: StatusBarNotification, reason: String) {
        val pkg = sbn.packageName ?: return
        if (!pkg.startsWith("ru.dublgis")) return

        val n = sbn.notification ?: return
        val channel = n.channelId.orEmpty()
        val navigating = channel.contains("Navigation", ignoreCase = true) ||
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

        mainHandler.post {
            try {
                val texts = mutableListOf<String>()
                texts += extractTextExtras(n)
                val bitmaps = mutableListOf<Bitmap>()

                listOfNotNull(n.bigContentView, n.contentView, n.headsUpContentView).forEach { rv ->
                    val reflected = RemoteViewsReader.read(rv)
                    texts += reflected.texts
                    bitmaps += reflected.bitmaps
                    try {
                        val host = FrameLayout(this)
                        val applied = rv.apply(this, host)
                        if (applied != null) {
                            host.addView(applied)
                            collectViews(applied, texts, bitmaps)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "RemoteViews apply failed: ${e.message}")
                    }
                }

                loadLargeIcon(n)?.let { bitmaps += it }

                val cleanTexts = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val parsed = NavParser.parse(cleanTexts)
                val pocket = NavTextFilter.parsePocketBanner(cleanTexts)

                // Среди bitmap выбираем манёвр: боковой > прямо; режем полоски/бары
                val candidates = bitmaps
                    .filter { isPlausibleManeuverIcon(it) }
                    .sortedByDescending { it.width * it.height }
                    .map { bmp -> bmp to ManeuverIconClassifier.analyze(bmp) }
                    .filter { (_, r) -> r.turn != "none" }
                val bestIcon = pickBestIcon(candidates.map { it.second })
                if (candidates.isNotEmpty()) {
                    val summary = candidates.take(6).joinToString("; ") { (bmp, r) ->
                        "${bmp.width}x${bmp.height}:${r.turn}@${"%.2f".format(r.confidence)}"
                    }
                    Log.d(TAG, "icon candidates: $summary -> ${bestIcon?.turn}@${bestIcon?.confidence}")
                }

                // Текст > уверенная иконка. Слабый straight без текста — none (не угадываем).
                val turn = when {
                    parsed.turn != "none" -> parsed.turn
                    bestIcon != null && bestIcon.isConfident -> bestIcon.turn
                    bestIcon != null && bestIcon.turn != "none" && bestIcon.turn != "straight" ->
                        bestIcon.turn
                    else -> "none"
                }

                val street = pocket?.street ?: guessStreet(cleanTexts)
                val distM = pocket?.distM?.takeIf { it >= 0 } ?: parsed.distM

                // На OLED всегда встроенный глиф по итоговому turn (не сырой mono 2ГИС)
                val iconHex = when {
                    turn != "none" -> ManeuverIconClassifier.fallbackGlyphHex(turn)
                    else -> ""
                }

                // Пустой карманный апдейт без манёвра — не шлём (кроме камеры)
                if (turn == "none" && distM < 0 && !parsed.camera && !navigating) {
                    return@post
                }

                val update = parsed.copy(
                    turn = turn,
                    distM = distM,
                    iconHex = iconHex,
                    street = street,
                    navigating = navigating || turn != "none" || distM >= 0,
                    allTexts = parsed.allTexts.ifEmpty { NavTextFilter.sanitize(cleanTexts) }
                )
                NavFeed.publish(applicationContext, update, "notif/$reason")
            } catch (e: Exception) {
                Log.w(TAG, "notif parse fail", e)
                DebugStore.get(this).warn("notif parse fail: ${e.message}")
            }
        }
    }

    private fun extractTextExtras(n: Notification): List<String> {
        val out = mutableListOf<String>()
        n.tickerText?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        val extras = n.extras
        listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_TITLE_BIG
        ).forEach { key ->
            extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { cs ->
            cs?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }

    private fun loadLargeIcon(n: Notification): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                val icon = n.getLargeIcon() ?: return null
                val d = icon.loadDrawable(this) ?: return null
                when (d) {
                    is BitmapDrawable -> d.bitmap
                    else -> {
                        val w = d.intrinsicWidth.coerceAtLeast(64)
                        val h = d.intrinsicHeight.coerceAtLeast(64)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        d.setBounds(0, 0, w, h)
                        d.draw(canvas)
                        bmp
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                n.largeIcon
            }
        } catch (e: Exception) {
            Log.d(TAG, "largeIcon: ${e.message}")
            null
        }
    }

    private fun collectViews(view: View, texts: MutableList<String>, bitmaps: MutableList<Bitmap>) {
        when (view) {
            is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            is ImageView -> {
                val d = view.drawable
                if (d is BitmapDrawable) {
                    d.bitmap?.let { bitmaps += it }
                }
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    collectViews(view.getChildAt(i), texts, bitmaps)
                }
            }
        }
    }

    /** Боковой манёвр важнее «прямо»; при равном типе — выше confidence. */
    private fun pickBestIcon(results: List<ManeuverIconClassifier.Result>): ManeuverIconClassifier.Result? {
        if (results.isEmpty()) return null
        fun rank(r: ManeuverIconClassifier.Result): Int = when (r.turn) {
            "u_turn" -> 4
            "slight_left", "slight_right" -> 3
            "left", "right" -> 3
            "roundabout" -> 2
            "straight" -> 1
            else -> 0
        }
        return results.maxWithOrNull(
            compareBy<ManeuverIconClassifier.Result> { rank(it) }
                .thenBy { it.confidence }
                .thenBy { if (it.isConfident) 1 else 0 }
        )
    }

    /** Отсекает progress-bar / декоративные полоски RemoteViews. */
    private fun isPlausibleManeuverIcon(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 24 || h < 24) return false
        if (w > 512 || h > 512) return false
        val aspect = w.toFloat() / h.toFloat()
        return aspect in 0.45f..2.2f
    }

    private fun guessStreet(texts: List<String>): String? {
        // «350 m — Сергия Радонежского»
        val dash = Regex("""^\d+[.,]?\d*\s*(?:м|m|км|km)\s*[—\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)
        for (line in texts.flatMap { it.split('\n') }.map { it.trim() }) {
            val m = dash.matchEntire(line)
            if (m != null) return m.groupValues[1].trim().take(60)
        }
        val skip = Regex("\\d+\\s*(мин|км|м)\\b|arrival|ETA|Update|Complete|route", RegexOption.IGNORE_CASE)
        return texts
            .flatMap { it.split('\n') }
            .map { it.trim() }
            .firstOrNull { line ->
                line.length in 3..60 &&
                    !skip.containsMatchIn(line) &&
                    line.any { it.isLetter() }
            }
    }

    companion object {
        private const val TAG = "AtenboroNotif"
        private const val POLL_MS = 1_000L

        fun settingsIntent(): Intent =
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    }
}
