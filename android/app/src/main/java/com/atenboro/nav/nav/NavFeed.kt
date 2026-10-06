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
 *
 * Дистанция: новое явное значение (>=0) всегда побеждает sticky —
 * иначе на подъезде <30 м / при Doze залипала старая цифра (баг поля 0.1.6).
 */
object NavFeed {
    private const val TAG = "AtenboroNavFeed"
    private const val THROTTLE_MS = 350L
    private const val HEARTBEAT_MS = 2000L
    private const val NOTIF_HOLD_MS = 10_000L
    /** Камера липнет кратко — иначе мигающий кадр без «камер» гасит HUD; дольше — не снять алерт. */
    private const val CAM_STICKY_MS = 3_500L
    /** Верхняя граница «ещё манёвр», не весь маршрут (км). */
    private const val DIST_USEFUL_MAX = 200_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val esp = EspClient()
    private val mutex = Mutex()

    @Volatile private var lastSent: NavUpdate? = null
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastLogAt = 0L
    @Volatile private var navigating = false
    @Volatile private var lastNotifAt = 0L
    @Volatile private var lastNotif: NavUpdate? = null
    @Volatile private var lastCameraTrueAt = 0L

    fun isUseful(u: NavUpdate): Boolean {
        if (u.camera) return true
        if (u.turn != "none") return true
        if (!u.iconHex.isNullOrEmpty() && u.distM in 0..DIST_USEFUL_MAX) return true
        // Карман: явная дистанция в т.ч. <30 м (раньше отбрасывалась → OLED слал lastSent)
        if (u.distM in 0..DIST_USEFUL_MAX && (u.navigating || !u.street.isNullOrBlank())) return true
        return false
    }

    fun clearNavigating(context: Context, source: String) {
        navigating = false
        lastNotifAt = 0L
        lastNotif = null
        lastCameraTrueAt = 0L
        DebugStore.get(context).info("$source nav ended")
    }

    fun isNavigating(): Boolean = navigating

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

        // a11y/hud не перебивают свежий карманный notif — кроме countdown дистанции / усиления
        if (fromA11y && now - lastNotifAt < NOTIF_HOLD_MS) {
            val held = lastNotif
            if (held != null && isUseful(held)) {
                val improved =
                    (update.turn != "none" && update.turn != "straight" && held.turn == "straight") ||
                        (update.camera && !held.camera) ||
                        (update.distM in 0..DIST_USEFUL_MAX && held.distM < 0) ||
                        // countdown: меньшая дистанция с a11y важнее залипшего notif
                        (update.distM in 0..DIST_USEFUL_MAX && held.distM >= 0 &&
                            update.distM < held.distM)
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
                val distDecreased =
                    prev != null && toSend.distM >= 0 && prev.distM >= 0 && toSend.distM < prev.distM
                val changed = prev == null ||
                    prev.turn != toSend.turn ||
                    prev.distM != toSend.distM ||
                    prev.camera != toSend.camera ||
                    prev.camM != toSend.camM ||
                    prev.camKmh != toSend.camKmh ||
                    prev.iconHex != toSend.iconHex ||
                    prev.street != toSend.street
                // Countdown не режем throttle — иначе на Locked OLED отстаёт
                if (!distDecreased && t - lastSendAt < THROTTLE_MS) return@withLock
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

        if (turn == "straight" && held.turn != "none" && held.turn != "straight") {
            turn = held.turn
            if (icon.isNullOrEmpty()) icon = held.iconHex
        }
        if (turn == "none" && held.turn != "none") turn = held.turn

        dist = mergeDistance(newDist = dist, prevDist = held.distM, allowCountdownFromNew = true)

        // Если a11y без дистанции — берём notif; если a11y дальше notif ×2 — шум, держим notif
        if (held.distM in 0..2_500 && update.distM > held.distM * 2 && held.distM in 50..2_000) {
            dist = held.distM
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
            val pocketOnly = update.distM in 0..DIST_USEFUL_MAX && !update.street.isNullOrBlank()
            if (!pocketOnly) turn = prev.turn
        }
        if (update.iconHex == "") {
            icon = null
        } else if (icon.isNullOrEmpty() && !prev.iconHex.isNullOrEmpty()) {
            icon = prev.iconHex
        }

        // Явная новая дистанция всегда проходит (countdown / смена манёвра)
        dist = mergeDistance(newDist = dist, prevDist = prev.distM, allowCountdownFromNew = true)

        if (street.isNullOrBlank() && !prev.street.isNullOrBlank()) street = prev.street

        val now = System.currentTimeMillis()
        // Обновляем метку только при явном camera=true — иначе sticky никогда не истечёт
        if (update.camera) lastCameraTrueAt = now
        val cam = mergeCamera(
            newCamera = update.camera,
            newCamM = update.camM,
            newCamKmh = update.camKmh,
            prevCamera = prev.camera,
            prevCamM = prev.camM,
            prevCamKmh = prev.camKmh,
            lastCameraTrueAt = lastCameraTrueAt,
            now = now
        )
        camera = cam.camera
        camM = cam.camM
        camKmh = cam.camKmh

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

    /**
     * Камера: короткий sticky против дырявых notif-кадров; после [CAM_STICKY_MS]
     * явный camera=false снимает алерт (иначе OLED мигает вечно).
     */
    internal data class CamMerge(val camera: Boolean, val camM: Int, val camKmh: Int)

    internal fun mergeCamera(
        newCamera: Boolean,
        newCamM: Int,
        newCamKmh: Int,
        prevCamera: Boolean,
        prevCamM: Int,
        prevCamKmh: Int,
        lastCameraTrueAt: Long,
        now: Long,
        stickyMs: Long = CAM_STICKY_MS
    ): CamMerge {
        if (newCamera) {
            val kmh = if (newCamKmh > 0) newCamKmh else prevCamKmh
            val m = if (newCamM >= 0) newCamM else prevCamM
            return CamMerge(true, m, kmh)
        }
        if (!prevCamera) {
            return CamMerge(false, -1, -1)
        }
        val age = now - lastCameraTrueAt
        return if (age < stickyMs) {
            CamMerge(
                true,
                if (prevCamM >= 0) prevCamM else -1,
                if (prevCamKmh > 0) prevCamKmh else -1
            )
        } else {
            CamMerge(false, -1, -1)
        }
    }

    /**
     * @param allowCountdownFromNew если new>=0 — всегда new (в т.ч. меньше prev).
     *   Иначе только заполняем дыру prev при new<0.
     */
    internal fun mergeDistance(newDist: Int, prevDist: Int, allowCountdownFromNew: Boolean = true): Int {
        if (newDist >= 0 && allowCountdownFromNew) return newDist
        if (newDist >= 0) return newDist
        if (prevDist >= 0) return prevDist
        return -1
    }
}
