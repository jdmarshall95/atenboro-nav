package com.atenboro.nav.parse

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Скриншот дисплея через AccessibilityService → кроп карточки манёвра 2ГИС (левый верх).
 * Нужен, когда largeIcon нотификации — не стрелка, а pin.
 */
object ManeuverHudScanner {
    private const val TAG = "AtenboroHudScan"
    private val busy = AtomicBoolean(false)
    @Volatile private var lastAt = 0L

    fun maybeScan(
        service: AccessibilityService,
        executor: Executor,
        minIntervalMs: Long = 1200L,
        onResult: (ManeuverIconClassifier.Result) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        if (now - lastAt < minIntervalMs) return
        if (!busy.compareAndSet(false, true)) return
        lastAt = now

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    @RequiresApi(Build.VERSION_CODES.R)
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val full = wrapBitmap(screenshot) ?: return
                            val card = cropManeuverCard(full)
                            full.recycle()
                            if (card == null) return
                            val result = ManeuverIconClassifier.analyze(card)
                            card.recycle()
                            if (result.turn != "none" || result.mono32 != null) {
                                onResult(result)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "scan fail: ${e.message}")
                        } finally {
                            busy.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "screenshot fail code=$errorCode")
                        busy.set(false)
                    }
                }
            )
        } catch (e: Exception) {
            busy.set(false)
            Log.w(TAG, "takeScreenshot: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun wrapBitmap(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer: HardwareBuffer = screenshot.hardwareBuffer
        val cs: ColorSpace? = screenshot.colorSpace
        val hw = Bitmap.wrapHardwareBuffer(buffer, cs) ?: return null
        val copy = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        buffer.close()
        return copy
    }

    /** Карточка манёвра 2ГИС — белая плашка слева сверху. */
    fun cropManeuverCard(full: Bitmap): Bitmap? {
        val w = full.width
        val h = full.height
        if (w < 200 || h < 200) return null

        val x0 = (w * 0.02f).toInt()
        val y0 = (h * 0.05f).toInt()
        val x1 = (w * 0.42f).toInt()
        val y1 = (h * 0.22f).toInt()
        val cw = (x1 - x0).coerceAtLeast(64)
        val ch = (y1 - y0).coerceAtLeast(64)
        if (x0 + cw > w || y0 + ch > h) return null

        val region = Bitmap.createBitmap(full, x0, y0, cw, ch)
        // Ищем белую карточку внутри региона и кропаем стрелку
        val tight = findWhiteCardArrow(region) ?: region
        if (tight !== region) region.recycle()
        return tight
    }

    private fun findWhiteCardArrow(region: Bitmap): Bitmap? {
        val w = region.width
        val h = region.height
        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0
        var white = 0
        val step = 3
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val c = region.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                if (r > 220 && g > 220 && b > 220) {
                    white++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (white < 40 || maxX - minX < 40 || maxY - minY < 40) return null
        // Чуть расширяем и берём верхнюю часть карточки со стрелкой
        val pad = 4
        val left = (minX - pad).coerceAtLeast(0)
        val top = (minY - pad).coerceAtLeast(0)
        val right = (maxX + pad).coerceAtMost(w - 1)
        val bottom = (minY + (maxY - minY) * 2 / 3 + pad).coerceAtMost(h - 1)
        val bw = right - left
        val bh = bottom - top
        if (bw < 32 || bh < 32) return null
        return Bitmap.createBitmap(region, left, top, bw, bh)
    }
}
