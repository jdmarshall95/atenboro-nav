package com.atenboro.nav.debug

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Локальное хранилище отладочных событий (NDJSON) + снимок с платы.
 */
class DebugStore(context: Context) {
    private val dir = File(context.filesDir, "debug").also { it.mkdirs() }
    private val sessionFile: File
    private val memory = CopyOnWriteArrayList<DebugEvent>()

    init {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        sessionFile = File(dir, "session-$day.ndjson")
        if (sessionFile.exists()) {
            sessionFile.readLines().forEach { line ->
                parseLine(line)?.let { memory.add(it) }
            }
        }
    }

    fun append(event: DebugEvent) {
        memory.add(event)
        sessionFile.appendText(event.toJsonObject() + "\n")
        // cap in-memory
        while (memory.size > MAX_MEMORY) {
            memory.removeAt(0)
        }
    }

    fun info(msg: String, src: String = "phone") =
        append(DebugEvent(src = src, lvl = "i", msg = msg))

    fun warn(msg: String, src: String = "phone") =
        append(DebugEvent(src = src, lvl = "w", msg = msg))

    fun error(msg: String, src: String = "phone") =
        append(DebugEvent(src = src, lvl = "e", msg = msg))

    fun mergeBoardSnapshot(json: String) {
        val root = JSONObject(json)
        val events = root.optJSONArray("events") ?: return
        val stamp = File(dir, "board-${System.currentTimeMillis()}.json")
        stamp.writeText(json)
        for (i in 0 until events.length()) {
            val o = events.getJSONObject(i)
            append(
                DebugEvent(
                    t = o.optLong("t", System.currentTimeMillis()),
                    src = o.optString("src", "esp"),
                    lvl = o.optString("lvl", "i"),
                    msg = o.optString("msg", ""),
                    origin = "board"
                )
            )
        }
        info(
            "board sync uptime=${root.optLong("uptime_ms")} stations=${root.optInt("stations")} oled=${root.optBoolean("oled")}",
            src = "sync"
        )
    }

    fun recent(limit: Int = 200): List<DebugEvent> =
        memory.takeLast(limit)

    fun exportAll(): File {
        val out = File(dir, "export-${System.currentTimeMillis()}.txt")
        out.writeText(recent(1000).joinToString("\n") { it.line() })
        return out
    }

    fun pendingPhoneEvents(limit: Int = 20): JSONArray {
        val arr = JSONArray()
        recent(limit)
            .filter { it.origin == "local" && it.src == "phone" }
            .takeLast(limit)
            .forEach { e ->
                arr.put(
                    JSONObject()
                        .put("lvl", e.lvl)
                        .put("msg", e.msg.take(55))
                )
            }
        return arr
    }

    fun sessionPath(): String = sessionFile.absolutePath

    private fun parseLine(line: String): DebugEvent? {
        return try {
            val o = JSONObject(line)
            DebugEvent(
                t = o.optLong("t"),
                src = o.optString("src", "phone"),
                lvl = o.optString("lvl", "i"),
                msg = o.optString("msg", ""),
                origin = o.optString("origin", "local")
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_MEMORY = 500
        @Volatile private var instance: DebugStore? = null

        fun get(context: Context): DebugStore {
            return instance ?: synchronized(this) {
                instance ?: DebugStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
