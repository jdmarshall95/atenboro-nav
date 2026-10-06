package com.atenboro.nav.parse

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ManeuverIconClassifierTest {

    private fun blank(): Bitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.TRANSPARENT) }

    private fun stamp(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, r: Int = 3) {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0), 1) * 2
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val cx = (x0 + (x1 - x0) * t).toInt()
            val cy = (y0 + (y1 - y0) * t).toInt()
            for (dy in -r..r) for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until bmp.width && y in 0 until bmp.height) {
                    bmp.setPixel(x, y, Color.WHITE)
                }
            }
        }
    }

    @Test
    fun classifiesHardRightCorner() {
        val bmp = blank()
        // ⌊→ : ствол снизу-слева, плечо горизонтально вправо на mid Y
        stamp(bmp, 16, 56, 16, 30, 4)
        stamp(bmp, 16, 30, 52, 30, 4)
        stamp(bmp, 52, 30, 42, 22, 3)
        stamp(bmp, 52, 30, 42, 38, 3)
        val r = ManeuverIconClassifier.analyze(bmp)
        assertEquals("got ${r.turn}@${r.confidence}", "right", r.turn)
    }

    @Test
    fun classifiesSlightRightDiagonal() {
        val bmp = blank()
        // диагональ к верхнему правому острию
        stamp(bmp, 24, 56, 24, 42, 4)
        stamp(bmp, 24, 42, 50, 12, 4)
        stamp(bmp, 50, 12, 36, 16, 3)
        stamp(bmp, 50, 12, 46, 26, 3)
        val r = ManeuverIconClassifier.analyze(bmp)
        assertEquals("got ${r.turn}@${r.confidence}", "slight_right", r.turn)
        assertTrue(r.confidence >= 0.45f)
    }

    @Test
    fun classifiesSlightLeftDiagonal() {
        val bmp = blank()
        stamp(bmp, 40, 56, 40, 42, 4)
        stamp(bmp, 40, 42, 14, 12, 4)
        stamp(bmp, 14, 12, 28, 16, 3)
        stamp(bmp, 14, 12, 18, 26, 3)
        val r = ManeuverIconClassifier.analyze(bmp)
        assertEquals("got ${r.turn}@${r.confidence}", "slight_left", r.turn)
    }

    @Test
    fun classifiesUTurn() {
        val bmp = blank()
        // классическая U: левая стойка + арка + правая стойка вниз с остриём
        stamp(bmp, 16, 36, 16, 18, 3)
        stamp(bmp, 16, 18, 32, 10, 3)
        stamp(bmp, 32, 10, 48, 18, 3)
        stamp(bmp, 48, 18, 48, 56, 3)
        stamp(bmp, 48, 56, 38, 48, 3)
        stamp(bmp, 48, 56, 56, 48, 3)
        val r = ManeuverIconClassifier.analyze(bmp)
        assertEquals("got ${r.turn}@${r.confidence}", "u_turn", r.turn)
        assertTrue(r.confidence >= 0.5f)
    }

    @Test
    fun fallbackGlyphsCoverSlightAndUTurn() {
        assertTrue(!ManeuverIconClassifier.fallbackGlyphHex("slight_left").isNullOrEmpty())
        assertTrue(!ManeuverIconClassifier.fallbackGlyphHex("u_turn").isNullOrEmpty())
    }
}
