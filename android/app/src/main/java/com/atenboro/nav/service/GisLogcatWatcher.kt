package com.atenboro.nav.service

import android.content.Context
import android.util.Log
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.model.NavUpdate
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.parse.GisLogParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Читает logcat 2ГИС (нужен READ_LOGS через pm grant).
 * Голосовые клипы DomainSynthesizer содержат тип поворота и дистанцию OverN.
 */
object GisLogcatWatcher {
    private const val TAG = "AtenboroGisLog"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            DebugStore.get(app).info("gis logcat watcher start")
            while (isActive) {
                try {
                    tailLogcat(app)
                } catch (e: Exception) {
                    Log.w(TAG, "logcat loop: ${e.message}")
                    DebugStore.get(app).warn("gis logcat: ${e.message}")
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun tailLogcat(app: Context) {
        // -v brief: I/2GIS(pid): msg
        val pb = ProcessBuilder(
            "logcat", "-v", "brief", "-T", "1",
            "2GIS:I", "*:S"
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    val hint = GisLogParser.parseLine(l)
                    if (hint != null && hint.turn != "none") {
                        DebugStore.get(app).info(
                            "gislog clip turn=${hint.turn} d=${hint.distM} ${hint.rawClip.take(80)}"
                        )
                        NavFeed.publish(
                            app,
                            NavUpdate(
                                turn = hint.turn,
                                distM = hint.distM,
                                navigating = true,
                                rawSnippet = hint.rawClip.take(200),
                                allTexts = listOf(hint.rawClip)
                            ),
                            "gislog/clip"
                        )
                        continue
                    }
                    val remain = GisLogParser.parseRouteRemain(l)
                    if (remain != null) {
                        // только для дебага — не шлём длину всего маршрута как манёвр
                        Log.d(TAG, "route remain ${remain}m")
                    }
                }
            }
        } finally {
            proc.destroy()
        }
    }
}
