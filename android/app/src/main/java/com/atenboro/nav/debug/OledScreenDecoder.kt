package com.atenboro.nav.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64

/**
 * Буфер SSD1306 Adafruit: page-major, 8 вертикальных пикселей на байт.
 */
object OledScreenDecoder {

    fun fromBase64PageBuffer(b64: String, width: Int = 128, height: Int = 64): Bitmap? {
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            fromPageBuffer(raw, width, height)
        } catch (_: Exception) {
            null
        }
    }

    fun fromPageBuffer(buf: ByteArray, width: Int, height: Int): Bitmap? {
        val pages = (height + 7) / 8
        if (buf.size < width * pages) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val on = Color.WHITE
        val off = Color.BLACK
        for (page in 0 until pages) {
            for (x in 0 until width) {
                val b = buf[page * width + x].toInt() and 0xff
                for (bit in 0 until 8) {
                    val y = page * 8 + bit
                    if (y >= height) continue
                    bmp.setPixel(x, y, if ((b shr bit) and 1 != 0) on else off)
                }
            }
        }
        return bmp
    }
}
