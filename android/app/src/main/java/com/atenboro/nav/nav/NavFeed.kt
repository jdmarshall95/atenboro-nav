package com.atenboro.nav.nav

import android.content.Context
import android.util.Log
import com.atenboro.nav.NavBus
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.model.NavUpdate
import com.atenboro.nav.net.EspClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Единая точка публикации HUD на ESP.
 * Пока идёт навигация — держим манёвр на плате heartbeat'ом (не откатываем на splash).
 * Приоритет: notif (largeIcon) > a11y/hud скрин.
 */
object NavFeed {
    private const val TAG = "AtenboroNavFeed"
    private const val THROTTLE_MS = 350L
    private const val HEARTBEAT_MS = 2000L
    private const val HUD_DEFER_MS = 8000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val esp = EspClient()
    private val mutex = Mutex()

    @Volatile private var lastSent: NavUpdate? = null
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastLogAt = 0L
    @Volatile private var navigating = false
    @Volatile private var lastNotifIconAt = 0L

    fun isUseful(u: NavUpdate): Boolean {
        if (u.navigating && (u.turn != "none" || !u.iconHex.isNullOrEmpty())) return true
        if (u.camera) return true
        if (u.turn != "none") return true
        if (!u.iconHex.isNullOrEmpty()) return true
        if (u.distM in 0..2_500) return true
        return false
    }

    fun clearNavigating(context: Context, source: String) {
        navigating = false
        lastNotifIconAt = 0L
        DebugStore.get(context).info("$source nav ended")
    }

    fun publish(context: Context, update: NavUpdate, source: String) {
        val store = DebugStore.get(context)
        if (update.navigating) navigating = true

        if (source.startsWith("notif") && (!update.iconHex.isNullOrEmpty() || update.turn != "none")) {
            lastNotifIconAt = System.currentTimeMillis()
        }

        // HUD-скрин не перебивает свежий notif-манёвр
        if (source.contains("hud") && System.currentTimeMillis() - lastNotifIconAt < HUD_DEFER_MS) {
            val prev = lastSent
            if (prev != null && (prev.turn != "none" || !prev.iconHex.isNullOrEmpty())) {
                return
            }
        }

        val merged = mergeSticky(update)
        NavBus.publish(merged)

        val now = System.currentTimeMillis()
        if (now - lastLogAt > 3_000) {
            lastLogAt = now
            val sample = merged.allTexts.take(4).joinToString(" | ").take(100)
            store.info(
                "$source turn=${merged.turn} d=${merged.distM} icon=${!merged.iconHex.isNullOrEmpty()} " +
                    "nav=${merged.navigating} texts=$sample"
            )
        }

        if (!isUseful(merged) && !(navigating && lastSent != null)) {
            return
        }

        val toSend = if (isUseful(merged)) merged else lastSent!!.copy(
            navigating = true,
            ts = System.currentTimeMillis() / 1000
        )

        scope.launch {
            mutex.withLock {
                val t = System.currentTimeMillis()
                val prev = lastSent
                val changed = prev == null ||
                    prev.turn != toSend.turn ||
                    prev.distM != toSend.distM ||
                    prev.camera != toSend.camera ||
                    prev.camM != toSend.camM ||
                    prev.camKmh != toSend.camKmh ||
                    prev.iconHex != toSend.iconHex ||
                    prev.street != toSend.street
                if (t - lastSendAt < THROTTLE_MS) return@withLock
                if (!changed && t - lastSendAt < HEARTBEAT_MS) return@withLock

                lastSent = toSend
                lastSendAt = t
                val result = esp.sendNav(toSend, context)
                if (result.isFailure) {
                    Log.w(TAG, "send fail: ${result.exceptionOrNull()?.message}")
                    store.error("$source send fail: ${result.exceptionOrNull()?.message}")
                    NavBus.publish(
                        toSend.copy(
                            httpStatus = "ошибка",
                            lastError = result.exceptionOrNull()?.message
                        )
                    )
                } else {
                    if (changed) store.info("$source sent ${toSend.turn} ${toSend.distM}m")
                    NavBus.publish(toSend.copy(httpStatus = "OK", lastError = null))
                }
            }
        }
    }

    private fun mergeSticky(update: NavUpdate): NavUpdate {
        val prev = lastSent ?: return update
        if (!navigating && !update.navigating) return update

        var turn = update.turn
        var icon = update.iconHex
        var dist = update.distM
        var street = update.street
        var camera = update.camera
        var camM = update.camM
        var camKmh = update.camKmh

        if (turn == "none" && prev.turn != "none") turn = prev.turn
        // null = поле не пришло (держать sticky); "" = явно сбросить иконку
        if (update.iconHex == "") {
            icon = null
        } else if (icon.isNullOrEmpty() && !prev.iconHex.isNullOrEmpty()) {
            icon = prev.iconHex
        }
        if (dist < 0 && prev.distM >= 0) dist = prev.distM
        if (street.isNullOrBlank() && !prev.street.isNullOrBlank()) street = prev.street
        if (!camera && prev.camera) {
            camera = true
            camM = prev.camM
            if (camKmh <= 0) camKmh = prev.camKmh
        } else if (camera && camKmh <= 0 && prev.camKmh > 0) {
            camKmh = prev.camKmh
        }

        return update.copy(
            turn = turn,
            iconHex = icon,
            distM = dist,
            street = street,
            camera = camera,
            camM = camM,
            camKmh = camKmh,
            navigating = navigating || update.navigating
        )
    }
}
