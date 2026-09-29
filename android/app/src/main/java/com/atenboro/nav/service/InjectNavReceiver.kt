package com.atenboro.nav.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.debug.OledScreenDecoder
import com.atenboro.nav.model.NavUpdate
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.net.EspClient
import com.atenboro.nav.net.EspNetwork
import com.atenboro.nav.parse.ManeuverIconClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Фоновый inject без Activity — тот же NavFeed, что и у notification listener.
 * Для stage-тестов с заблокированным экраном (карманный сценарий).
 *
 * adb shell am broadcast -a com.atenboro.nav.INJECT_NAV \
 *   -n com.atenboro.nav/.service.InjectNavReceiver \
 *   --es turn left --ei dist_m 80 --ez camera true --ei cam_kmh 60
 */
class InjectNavReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val turn = intent.getStringExtra("turn") ?: return
        val dist = intent.getIntExtra("dist_m", -1)
        val camera = intent.getBooleanExtra("camera", false)
        val camM = intent.getIntExtra("cam_m", -1)
        val camKmh = intent.getIntExtra("cam_kmh", -1)
        val street = intent.getStringExtra("street")
        val icon = intent.getStringExtra("icon")
            ?: ManeuverIconClassifier.fallbackGlyphHex(turn)

        val pm = context.getSystemService(PowerManager::class.java)
        val interactive = pm?.isInteractive == true
        val store = DebugStore.get(context)
        store.info("locked-inject begin turn=$turn d=$dist interactive=$interactive")

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EspNetwork.bindIfReachable(app)
                val update = NavUpdate(
                    turn = turn,
                    distM = dist,
                    camera = camera,
                    camM = camM,
                    camKmh = camKmh,
                    street = street,
                    iconHex = icon,
                    navigating = true
                )
                NavFeed.publish(app, update, "locked-inject")
                kotlinx.coroutines.delay(700)
                val esp = EspClient()
                val screen = esp.getScreen(app)
                if (screen.isSuccess) {
                    val cap = screen.getOrThrow()
                    store.info("locked-screen ${cap.meta} interactive=$interactive")
                    val bmp = OledScreenDecoder.fromPageBuffer(cap.buffer, cap.width, cap.height)
                    if (bmp != null) {
                        val dir = File(app.filesDir, "debug").also { it.mkdirs() }
                        val f = File(dir, "oled-locked-${System.currentTimeMillis()}.png")
                        FileOutputStream(f).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        store.info("locked-screen saved ${f.name}")
                    }
                } else {
                    store.error(
                        "locked-screen fail: ${screen.exceptionOrNull()?.message} interactive=$interactive"
                    )
                }
            } catch (e: Exception) {
                store.error("locked-inject fail: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
