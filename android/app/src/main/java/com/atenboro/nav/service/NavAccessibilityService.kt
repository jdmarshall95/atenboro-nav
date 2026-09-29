package com.atenboro.nav.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.atenboro.nav.NavBus
import com.atenboro.nav.debug.DebugDump
import com.atenboro.nav.debug.DebugStore
import com.atenboro.nav.nav.NavFeed
import com.atenboro.nav.parse.ManeuverHudScanner
import com.atenboro.nav.parse.ManeuverIconClassifier
import com.atenboro.nav.parse.NavParser
import com.atenboro.nav.parse.NavTextFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

class NavAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var debugStore: DebugStore
    private var dumpJob: Job? = null
    @Volatile private var lastHudTurn: String = "none"

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
        val from2gis = pkg.startsWith("ru.dublgis")
        val fromNotifShade = pkg == "com.android.systemui"
        if (!from2gis && !fromNotifShade) return

        val texts = collectTwoGisTexts()
        val clean = NavTextFilter.sanitize(texts)
        if (clean.isNotEmpty()) {
            val parsed = NavParser.parse(clean)
            // Не публикуем chrome-only апдейты без манёвра/баннера
            val pocket = NavTextFilter.parsePocketBanner(texts)
            val useful = parsed.turn != "none" ||
                pocket != null ||
                parsed.camera ||
                (parsed.distM in 30..2_500 && clean.any { it.contains('—') || it.contains('-') })
            if (useful) {
                val update = parsed.copy(
                    navigating = true,
                    distM = pocket?.distM ?: parsed.distM,
                    street = pocket?.street ?: parsed.street,
                    // a11y без уверенного поворота — пусть notif решит; не шлём fake straight
                    turn = parsed.turn,
                    iconHex = if (parsed.turn != "none") {
                        ManeuverIconClassifier.fallbackGlyphHex(parsed.turn)
                    } else {
                        null
                    }
                )
                NavFeed.publish(this, update, "a11y")
            }
        }

        // Манёвр рисуется на Qt/OpenGL — скрин карточки слева сверху
        if (from2gis && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ManeuverHudScanner.maybeScan(this, shotExecutor) { result ->
                if (!result.isConfident) return@maybeScan
                if (result.turn == lastHudTurn) return@maybeScan
                lastHudTurn = result.turn
                val fromTexts = if (clean.isNotEmpty()) NavParser.parse(clean) else null
                val pocket = NavTextFilter.parsePocketBanner(texts)
                val update = (fromTexts ?: com.atenboro.nav.model.NavUpdate()).copy(
                    turn = result.turn,
                    distM = pocket?.distM ?: fromTexts?.distM ?: -1,
                    street = pocket?.street ?: fromTexts?.street,
                    iconHex = ManeuverIconClassifier.fallbackGlyphHex(result.turn),
                    navigating = true,
                    allTexts = fromTexts?.allTexts ?: clean
                )
                NavFeed.publish(applicationContext, update, "a11y/hud")
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        NavBus.setA11yConnected(false)
        dumpJob?.cancel()
        scope.cancel()
        shotExecutor.shutdownNow()
        super.onDestroy()
    }

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
