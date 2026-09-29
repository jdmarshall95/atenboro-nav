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
        val mono32: ByteArray?,
        /** 0..1 — насколько уверен классификатор (слабый straight лучше игнорировать) */
        val confidence: Float = 1f
    ) {
        val isConfident: Boolean get() = turn != "none" && confidence >= 0.45f
    }

    fun analyze(src: Bitmap?): Result {
        if (src == null || src.width < 8 || src.height < 8) {
            return Result("none", null, 0f)
        }
        val bmp = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: return Result("none", null, 0f)
        } else {
            src
        }
        val (turn, confidence) = classify(bmp)
        val mono = toMono32(bmp)
        return Result(turn, mono, confidence)
    }

    fun toMono32(src: Bitmap): ByteArray? {
        val scaled = Bitmap.createScaledBitmap(src, 32, 32, true)
        val out = ByteArray(128)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        val (bgLum, bgIsLight) = estimateBackground(scaled)
        var inkCount = 0
        for (y in 0 until 32) {
            for (xByte in 0 until 4) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    val c = pixels[y * 32 + x]
                    if (isForeground(c, bgLum, bgIsLight)) {
                        b = b or (0x80 shr bit)
                        inkCount++
                    }
                }
                out[y * 4 + xByte] = b.toByte()
            }
        }
        if (scaled !== src) scaled.recycle()
        // Слишком плотная заливка = фон распознан как стрелка → лучше встроенный глиф
        if (inkCount < 24 || inkCount > 32 * 32 * 0.42f) return null
        return out
    }

    fun monoToHex(mono: ByteArray): String {
        val sb = StringBuilder(mono.size * 2)
        for (byte in mono) {
            sb.append(String.format("%02x", byte.toInt() and 0xff))
        }
        return sb.toString()
    }

    /** Встроенные глифы OLED (совпадают с firmware BMP_*) — если mono с largeIcon бракованный. */
    fun fallbackGlyphHex(turn: String): String? = FALLBACK_GLYPHS[turn]
        ?: when (turn) {
            "slight_left" -> FALLBACK_GLYPHS["left"]
            "slight_right" -> FALLBACK_GLYPHS["right"]
            "arrive" -> FALLBACK_GLYPHS["straight"]
            else -> null
        }

    private val FALLBACK_GLYPHS = mapOf(
        "left" to "0000000000180000003800000078000000f8000001f8000003f8000007ffff000fffff001fffff003fffff007fffff003fffff001fffff000fffff0007ffff0003f8000001f8000000f8000000780000003800000018000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "right" to "000000000000180000001c0000001e0000001f0000001f8000001fc000ffffe000fffff000fffff800fffffc00fffffe00fffffc00fffff800fffff000ffffe000001fc000001f8000001f0000001e0000001c000000180000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "straight" to "000180000003c0000007e000000ff000001ff800003ffc00007ffe0000ffff00000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff000000ff00000000000",
        "u_turn" to "003ffc0000ffff0001ffff8003f00fc007c003e0078001e00f0000f00e0000700e0000700e0000700e0000700e0018700e003c700e007e700e00ff700e01fff00e00ff700e007e700e003c700e0018700e0000700e0000700e0000700e0000700e0000700e000070000000000000000000000000000000000000000000000000",
        "roundabout" to "000ff000003ffc00007ffe0000f81f0001e0078003c003c0038001c0070000e0070000e00e0000700e0180700e03c0700e07e0700e0ff0700e07e0700e03c0700e0180700e000070070000e0070000e0038001c003c003c001e0078000f81f00007ffe00003ffc00000ff0000000000000000000000000000000000000000000"
    )

    private fun classify(src: Bitmap): Pair<String, Float> {
        val w = src.width
        val h = src.height
        val (bgLum, bgIsLight) = estimateBackground(src)

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
        // Tip mass: leftmost / rightmost 22% of bounding box
        var tipLeft = 0
        var tipRight = 0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (!isForeground(src.getPixel(x, y), bgLum, bgIsLight)) continue
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

        if (ink < 20) return "none" to 0f

        val bw = (maxX - minX + 1).toFloat()
        val bh = (maxY - minY + 1).toFloat()
        val fill = ink.toFloat() / ((bw / step) * (bh / step)).coerceAtLeast(1f)
        val tipBand = (bw * 0.22f).coerceAtLeast(1f)

        // Квадранты bbox — ловят угловой манёвр 2ГИС (ствол вниз + остриё вбок)
        var qTL = 0
        var qTR = 0
        var qBL = 0
        var qBR = 0
        val midX = (minX + maxX) / 2
        val midY = (minY + maxY) / 2
        for (y in minY..maxY step step) {
            for (x in minX..maxX step step) {
                if (!isForeground(src.getPixel(x, y), bgLum, bgIsLight)) continue
                if (x <= minX + tipBand) tipLeft++
                if (x >= maxX - tipBand) tipRight++
                val left = x <= midX
                val top = y <= midY
                when {
                    left && top -> qTL++
                    !left && top -> qTR++
                    left && !top -> qBL++
                    else -> qBR++
                }
            }
        }

        // Угол направо: почти пустой BR, масса в TR + ствол слева/снизу
        val cornerRight =
            qBR < ink * 0.08f && qTR > ink * 0.22f && (qTL + qBL) > ink * 0.28f
        val cornerLeft =
            qBL < ink * 0.08f && qTL > ink * 0.22f && (qTR + qBR) > ink * 0.28f
        if (cornerRight && !cornerLeft) return "right" to 0.9f
        if (cornerLeft && !cornerRight) return "left" to 0.9f

        // U-turn: петля сверху + ножка сбоку (часто пусто внизу-центре)
        if (inkTop > inkBottom * 1.2f && fill in 0.12f..0.50f) {
            val tall = bh / bw.coerceAtLeast(1f)
            val sideHeavy = max(inkLeft, inkRight) > ink * 0.32f
            if (tall > 0.9f && sideHeavy && inkCenter < ink * 0.22f) {
                return "u_turn" to 0.75f
            }
        }

        // Круговое / кольцо: много краёв, мало центра
        if (fill in 0.15f..0.55f && inkEdge > inkCenter * 2.2f && abs(inkLeft - inkRight) < ink * 0.18f) {
            return "roundabout" to 0.7f
        }

        val lr = (inkLeft - inkRight).toFloat() / ink
        val tb = (inkTop - inkBottom).toFloat() / ink
        // У шеврона масса у основания; остриё — сторона с меньшей tip-массой
        val tipLr = (tipLeft - tipRight).toFloat() / max(tipLeft + tipRight, 1)
        val tipPointsLeft = tipLeft < tipRight
        val tipPointsRight = tipRight < tipLeft
        val wide = bw / bh.coerceAtLeast(1f)
        val tall = bh / bw.coerceAtLeast(1f)

        // Горизонтальный шеврон: направление = остриё (против массы)
        if (wide >= 0.85f && (tipPointsLeft || tipPointsRight || abs(lr) >= 0.10f || abs(tipLr) >= 0.12f)) {
            val left = when {
                tipPointsLeft && !tipPointsRight -> true
                tipPointsRight && !tipPointsLeft -> false
                abs(tipLr) >= abs(lr) && abs(tipLr) >= 0.12f -> tipLr < 0
                else -> lr < 0
            }
            // Не путать с вертикальным «прямо»: нужен явный боковой tip/масса
            if (abs(tipLr) >= 0.12f || abs(lr) >= 0.10f || tipPointsLeft || tipPointsRight) {
                return if (left) {
                    (if (tb > 0.18f) "slight_left" else "left") to 0.85f
                } else {
                    (if (tb > 0.18f) "slight_right" else "right") to 0.85f
                }
            }
        }

        // Явный боковой перевес массы → остриё на противоположной стороне
        if (lr > 0.22f || tipLr > 0.25f) {
            return (if (tb > 0.15f) "slight_right" else "right") to 0.75f
        }
        if (lr < -0.22f || tipLr < -0.25f) {
            return (if (tb > 0.15f) "slight_left" else "left") to 0.75f
        }

        // Вертикальная «стрелка вверх»: только если правда узкая/высокая и баланс L/R
        if (abs(lr) < 0.10f && abs(tipLr) < 0.12f && tall >= 1.05f && tb <= 0.30f) {
            return "straight" to 0.7f
        }

        // Лёгкий дисбаланс — slight (масса напротив направления)
        if (abs(lr) in 0.10f..0.22f && abs(tb) < 0.35f) {
            return (if (lr > 0) "slight_right" else "slight_left") to 0.55f
        }

        // Слабый straight без уверенности — лучше none, чем ложный прямо
        if (abs(lr) < 0.10f && abs(tipLr) < 0.12f) {
            return "straight" to 0.35f
        }

        return when {
            lr > 0.08f -> "slight_right" to 0.45f
            lr < -0.08f -> "slight_left" to 0.45f
            else -> "straight" to 0.3f
        }
    }

    /**
     * Фон largeIcon 2ГИС часто полностью прозрачный по углам.
     * Нельзя считать alpha=0 «белым» — иначе чёрная подложка становится «чернилами».
     */
    private fun estimateBackground(src: Bitmap): Pair<Int, Boolean> {
        val w = src.width
        val h = src.height
        val samples = mutableListOf<Int>()
        val corners = listOf(
            0 to 0, (w - 1) to 0, 0 to (h - 1), (w - 1) to (h - 1)
        )
        for ((cx, cy) in corners) {
            var found: Int? = null
            for (d in 0..min(w, h) / 4) {
                val x = (cx + if (cx == 0) d else -d).coerceIn(0, w - 1)
                val y = (cy + if (cy == 0) d else -d).coerceIn(0, h - 1)
                val c = src.getPixel(x, y)
                if (Color.alpha(c) >= 40) {
                    found = luminance(c)
                    break
                }
            }
            if (found != null) samples += found
        }
        if (samples.isEmpty()) {
            // Полностью прозрачная рамка — типичная белая стрелка на прозрачном
            return 0 to false
        }
        val bgLum = samples.sorted()[samples.size / 2]
        return bgLum to (bgLum > 140)
    }

    private fun luminance(color: Int): Int {
        val a = Color.alpha(color)
        if (a < 40) return -1 // не участвует в оценке фона
        return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
    }

    /** Пиксель стрелки относительно оценённого фона (белый на синем / синий на белом). */
    private fun isForeground(color: Int, bgLum: Int, bgIsLight: Boolean): Boolean {
        val a = Color.alpha(color)
        if (a < 40) return false
        val lum = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
        return if (bgIsLight) {
            // Светлый фон — тёмная/цветная стрелка
            lum < bgLum - 35 || isColoredInk(color)
        } else {
            // Тёмный/прозрачный фон — светлая стрелка
            lum > max(bgLum + 40, 80)
        }
    }

    private fun isColoredInk(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return b > r + 15 && b > g + 5 && b > 60
    }
}
