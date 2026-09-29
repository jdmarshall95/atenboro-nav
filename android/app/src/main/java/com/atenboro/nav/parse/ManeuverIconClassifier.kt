package com.atenboro.nav.parse

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Классифицирует иконку следующего манёвра 2ГИС (largeIcon / notification_icon)
 * и готовит 32×32 mono для OLED.
 */
object ManeuverIconClassifier {

    data class Result(
        val turn: String,
        /** 128 байт: 32×32, MSB слева, ряд за рядом */
        val mono32: ByteArray?
    )

    fun analyze(src: Bitmap?): Result {
        if (src == null || src.width < 8 || src.height < 8) {
            return Result("none", null)
        }
        val bmp = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: return Result("none", null)
        } else {
            src
        }
        val mono = toMono32(bmp)
        val turn = classify(bmp)
        return Result(turn, mono)
    }

    fun toMono32(src: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(src, 32, 32, true)
        val out = ByteArray(128)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        for (y in 0 until 32) {
            for (xByte in 0 until 4) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    val c = pixels[y * 32 + x]
                    if (isInk(c)) {
                        b = b or (0x80 shr bit)
                    }
                }
                out[y * 4 + xByte] = b.toByte()
            }
        }
        if (scaled !== src) scaled.recycle()
        return out
    }

    fun monoToHex(mono: ByteArray): String {
        val sb = StringBuilder(mono.size * 2)
        for (byte in mono) {
            sb.append(String.format("%02x", byte.toInt() and 0xff))
        }
        return sb.toString()
    }

    private fun classify(src: Bitmap): String {
        val w = src.width
        val h = src.height
        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0
        var ink = 0
        val step = max(1, min(w, h) / 64)

        var inkLeft = 0
        var inkRight = 0
        var inkTop = 0
        var inkBottom = 0
        var inkCenter = 0
        var inkEdge = 0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (!isInk(src.getPixel(x, y))) continue
                ink++
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                val nx = x.toFloat() / w
                val ny = y.toFloat() / h
                if (nx < 0.45f) inkLeft++ else if (nx > 0.55f) inkRight++
                if (ny < 0.45f) inkTop++ else if (ny > 0.55f) inkBottom++
                val dx = abs(nx - 0.5f)
                val dy = abs(ny - 0.5f)
                if (dx < 0.18f && dy < 0.18f) inkCenter++
                if (dx > 0.32f || dy > 0.32f) inkEdge++
            }
        }

        if (ink < 20) return "none"

        val bw = (maxX - minX + 1).toFloat()
        val bh = (maxY - minY + 1).toFloat()
        val fill = ink.toFloat() / ((bw / step) * (bh / step)).coerceAtLeast(1f)

        // Круговое / кольцо: много краёв, мало центра
        if (fill in 0.15f..0.55f && inkEdge > inkCenter * 2.2f && abs(inkLeft - inkRight) < ink * 0.18f) {
            return "roundabout"
        }

        val lr = (inkLeft - inkRight).toFloat() / ink
        val tb = (inkTop - inkBottom).toFloat() / ink

        // Вертикальная «стрелка вверх»: баланс L/R
        if (abs(lr) < 0.12f && tb < 0.25f) {
            return "straight"
        }
        // Лёгкий дисбаланс при вертикальной стрелке — slight
        if (abs(lr) in 0.12f..0.28f && abs(tb) < 0.35f) {
            return if (lr > 0) "slight_left" else "slight_right"
        }

        if (lr > 0.28f) {
            return if (tb > 0.12f) "slight_left" else "left"
        }
        if (lr < -0.28f) {
            return if (tb > 0.12f) "slight_right" else "right"
        }

        return when {
            lr > 0.08f -> "slight_left"
            lr < -0.08f -> "slight_right"
            tb < -0.05f -> "straight"
            else -> "straight"
        }
    }

    private fun isInk(color: Int): Boolean {
        val a = Color.alpha(color)
        if (a < 40) return false
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Фон белый/светлый — отбрасываем
        if (r > 210 && g > 210 && b > 210) return false
        // Очень тёмный / насыщенный цвет стрелки
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val sat = maxC - minC
        val lum = (r + g + b) / 3
        // Синяя/цветная стрелка 2ГИС или монохромная
        if (b > r + 15 && b > g + 5 && b > 60) return true
        if (lum < 140 && sat > 25) return true
        if (lum < 90) return true
        return false
    }
}
