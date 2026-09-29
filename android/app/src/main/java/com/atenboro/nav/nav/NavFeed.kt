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
 * Приоритет: notif (карман) > a11y/hud скрин.
 */
object NavFeed {
    private const val TAG = "AtenboroNavFeed"
    private const val THROTTLE_MS = 350L
    private const val HEARTBEAT_MS = 2000L
    private const val NOTIF_HOLD_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val esp = EspClient()
    private val mutex = Mutex()

    @Volatile private var lastSent: NavUpdate? = null
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastLogAt = 0L
    @Volatile private var navigating = false
    @Volatile private var lastNotifAt = 0L
    @Volatile private var lastNotif: NavUpdate? = null

    fun isUseful(u: NavUpdate): Boolean {
        if (u.camera) return true
        if (u.turn != "none") return true
        if (!u.iconHex.isNullOrEmpty() && u.distM in 0..2_500) return true
        // Карманный баннер «N m — улица» без манёвра всё же полезен (дистанция/улица)
        if (u.distM in 30..2_500 && !u.street.isNullOrBlank()) return true
        return false
    }

    fun clearNavigating(context: Context, source: String) {
        navigating = false
        lastNotifAt = 0L
        lastNotif = null
        DebugStore.get(context).info("$source nav ended")
    }

    fun publish(context: Context, update: NavUpdate, source: String) {
        val store = DebugStore.get(context)
        if (update.navigating) navigating = true

        val now = System.currentTimeMillis()
        val fromNotif = source.startsWith("notif")
        val fromA11y = source.startsWith("a11y")

        if (fromNotif && isUseful(update)) {
            lastNotifAt = now
            lastNotif = update
        }

        // a11y/hud не перебивают свежий карманный notif
        if (fromA11y && now - lastNotifAt < NOTIF_HOLD_MS) {
            val held = lastNotif
            if (held != null && isUseful(held)) {
                // Разрешаем a11y только усилить уверенный манёвр/камеру, не затирать
                val improved =
                    (update.turn != "none" && update.turn != "straight" && held.turn == "straight") ||
                        (update.camera && !held.camera) ||
                        (update.distM in 0..2_500 && held.distM < 0)
                if (!improved) {
                    if (now - lastLogAt > 4_000) {
                        lastLogAt = now
                        store.info("$source deferred (notif hold) keep=${held.turn}/${held.distM}")
                    }
                    return
                }
            }
        }

        val merged = mergeSticky(preferNotifFields(update, fromA11y))
        NavBus.publish(merged)

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

    /** a11y не должен затирать дистанцию/улицу свежего notif слабыми значениями. */
    private fun preferNotifFields(update: NavUpdate, fromA11y: Boolean): NavUpdate {
        if (!fromA11y) return update
        val held = lastNotif ?: return update
        if (System.currentTimeMillis() - lastNotifAt >= NOTIF_HOLD_MS) return update

        var turn = update.turn
        var dist = update.distM
        var street = update.street
        var icon = update.iconHex

        // Не даём слабому straight из a11y сбить боковой манёвр notif
        if (turn == "straight" && held.turn != "none" && held.turn != "straight") {
            turn = held.turn
            if (icon.isNullOrEmpty()) icon = held.iconHex
        }
        if (turn == "none" && held.turn != "none") turn = held.turn

        if (held.distM in 0..2_500) {
            if (dist < 0 || dist > held.distM * 2 && held.distM in 50..2_000) {
                dist = held.distM
            }
        }
        if (street.isNullOrBlank() && !held.street.isNullOrBlank()) street = held.street

        return update.copy(turn = turn, distM = dist, street = street, iconHex = icon)
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

        if (turn == "none" && prev.turn != "none") {
            // Не поднимаем старый манёвр поверх карманного баннера без иконки —
            // иначе залипает ошибочный straight/left
            val pocketOnly = update.distM in 30..2_500 && !update.street.isNullOrBlank()
            if (!pocketOnly) turn = prev.turn
        }
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
