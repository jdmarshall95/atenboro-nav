package com.atenboro.nav.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.atenboro.nav.NavBus
import com.atenboro.nav.debug.DebugDump
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.parse.NavParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class NavAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var debugStore: DebugStore
    private var dumpJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        debugStore = DebugStore.get(this)
        debugStore.info("a11y service connected")
        NavBus.setA11yConnected(true)
        dumpJob = scope.launch {
            NavBus.dumpRequest.collectLatest { dumpNow() }
        }
        Log.i(TAG, "Accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        // События 2ГИС + иногда системные нотификации
        val from2gis = pkg.startsWith("ru.dublgis")
        val fromNotifShade = pkg == "com.android.systemui"
        if (!from2gis && !fromNotifShade) return

        val texts = collectTwoGisTexts()
        if (texts.isEmpty()) return
        val parsed = NavParser.parse(texts)
        NavFeed.publish(this, parsed, "a11y")
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        NavBus.setA11yConnected(false)
        dumpJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** Ищем окна именно 2ГИС, а не активное окно Atenboro. */
    private fun collectTwoGisTexts(): List<String> {
        val out = mutableListOf<String>()
        val wins: List<AccessibilityWindowInfo> = try {
            windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        for (w in wins) {
            val root = w.root ?: continue
            try {
                val pkg = root.packageName?.toString().orEmpty()
                if (!pkg.startsWith("ru.dublgis")) continue
                out += DebugDump.collectTexts(root)
            } finally {
                root.recycle()
            }
        }

        if (out.isEmpty()) {
            // fallback: active window only if это 2ГИС
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val pkg = root.packageName?.toString().orEmpty()
                    if (pkg.startsWith("ru.dublgis")) {
                        out += DebugDump.collectTexts(root)
                    }
                } finally {
                    root.recycle()
                }
            }
        }
        return out.distinct()
    }

    private fun dumpNow() {
        val sb = StringBuilder()
        val wins = try {
            windows ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        sb.append("windows=").append(wins.size).append('\n')
        var dumped = false
        for (w in wins) {
            val root = w.root ?: continue
            try {
                val pkg = root.packageName?.toString().orEmpty()
                sb.append("window pkg=").append(pkg).append(" type=").append(w.type).append('\n')
                if (pkg.startsWith("ru.dublgis")) {
                    sb.append(DebugDump.dumpTree(root))
                    dumped = true
                }
            } finally {
                root.recycle()
            }
        }
        if (!dumped) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    sb.append("FALLBACK active pkg=")
                        .append(root.packageName)
                        .append('\n')
                    sb.append(DebugDump.dumpTree(root))
                } finally {
                    root.recycle()
                }
            } else {
                sb.append("нет окон\n")
            }
        }
        val dir = File(getExternalFilesDir(null), "dumps")
        val file = DebugDump.writeToFile(dir, sb.toString())
        NavBus.setLastDumpPath(file.absolutePath)
        debugStore.info("a11y dump ${file.name}")
        Log.i(TAG, "Dump written: ${file.absolutePath}")
    }

    companion object {
        private const val TAG = "AtenboroA11y"
    }
}
