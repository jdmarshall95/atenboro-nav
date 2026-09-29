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
 * Шлём только полезные апдейты + редкий heartbeat полезного состояния.
 */
object NavFeed {
    private const val TAG = "AtenboroNavFeed"
    private const val THROTTLE_MS = 400L
    private const val HEARTBEAT_MS = 2500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val esp = EspClient()
    private val mutex = Mutex()

    @Volatile private var lastSent: NavUpdate? = null
    @Volatile private var lastSendAt = 0L
    @Volatile private var lastLogAt = 0L

    fun isUseful(u: NavUpdate): Boolean {
        if (u.camera) return true
        if (u.turn != "none") return true
        // Короткий маневр без явного слова поворота всё же полезен
        if (u.distM in 0..2_500) return true
        return false
    }

    fun publish(context: Context, update: NavUpdate, source: String) {
        val store = DebugStore.get(context)
        NavBus.publish(update)

        val now = System.currentTimeMillis()
        if (now - lastLogAt > 3_000) {
            lastLogAt = now
            val sample = update.allTexts.take(6).joinToString(" | ").take(120)
            store.info("$source parse turn=${update.turn} d=${update.distM} texts=$sample")
        }

        if (!isUseful(update)) {
            // Не забиваем OLED мусором вроде "17 км" без манёвра
            return
        }

        scope.launch {
            mutex.withLock {
                val t = System.currentTimeMillis()
                val prev = lastSent
                val changed = prev == null ||
                    prev.turn != update.turn ||
                    prev.distM != update.distM ||
                    prev.camera != update.camera ||
                    prev.camM != update.camM
                if (t - lastSendAt < THROTTLE_MS) return@withLock
                if (!changed && t - lastSendAt < HEARTBEAT_MS) return@withLock

                lastSent = update
                lastSendAt = t
                val result = esp.sendNav(update, context)
                if (result.isFailure) {
                    Log.w(TAG, "send fail: ${result.exceptionOrNull()?.message}")
                    store.error("$source send fail: ${result.exceptionOrNull()?.message}")
                    NavBus.publish(
                        update.copy(
                            httpStatus = "ошибка",
                            lastError = result.exceptionOrNull()?.message
                        )
                    )
                } else {
                    if (changed) store.info("$source sent ${update.turn} ${update.distM}m")
                    NavBus.publish(update.copy(httpStatus = "OK", lastError = null))
                }
            }
        }
    }
}
