package com.atenboro.nav.service

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.parse.NavParser

/**
 * Читает ongoing-уведомления 2ГИС (в т.ч. custom RemoteViews).
 * Работает при заблокированном экране / приложении в фоне — мото-сценарий.
 */
class NavNotificationListener : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        DebugStore.get(this).info("notif listener connected")
        // Сразу подтянуть активные
        activeNotifications?.forEach { handle(it, "active") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handle(sbn, "posted")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // ignore
    }

    private fun handle(sbn: StatusBarNotification, reason: String) {
        val pkg = sbn.packageName ?: return
        if (!pkg.startsWith("ru.dublgis")) return

        val n = sbn.notification ?: return
        // Кастомный layout лучше разбирать на main thread
        mainHandler.post {
            try {
                val texts = extractTexts(n)
                if (texts.isEmpty()) return@post
                val parsed = NavParser.parse(texts)
                NavFeed.publish(applicationContext, parsed, "notif/$reason")
            } catch (e: Exception) {
                Log.w(TAG, "notif parse fail", e)
                DebugStore.get(this).warn("notif parse fail: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractTexts(n: Notification): List<String> {
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

        listOfNotNull(n.bigContentView, n.contentView, n.headsUpContentView).forEach { rv ->
            try {
                val host = FrameLayout(this)
                val applied = rv.apply(this, host)
                if (applied != null) {
                    host.addView(applied)
                    collectTextViews(applied, out)
                }
            } catch (e: Exception) {
                Log.d(TAG, "RemoteViews apply failed: ${e.message}")
            }
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun collectTextViews(view: View, out: MutableList<String>) {
        when (view) {
            is TextView -> view.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    collectTextViews(view.getChildAt(i), out)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AtenboroNotif"

        fun settingsIntent(): Intent =
            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    }
}
