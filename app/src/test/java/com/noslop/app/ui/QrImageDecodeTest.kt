// FILE: app/src/test/java/com/noslop/app/ui/QrImageDecodeTest.kt
package com.noslop.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R5: picking a QR image from the gallery said "No QR code found in this image" for images the camera
 * reads fine. decodeQrFromArgb must read the shapes a shared or screenshotted NoSlop QR actually has.
 */
class QrImageDecodeTest {

    private val payload =
        """{"handle":"alice","publicKey":"MCowBQYDK2VwAyEAK12RuYO4u9W6tuUc2Hr7ZkcYTuUs6QSR8P4ePGKKWXg=","onionAddress":"fnozdomdxc55lovw4uonq6x3mzdrqtxfftuqjepq7ypdyyuklf4i7cqd.onion","encPublicKey":"MCowBQYDK2VuAyEAfFAMyI71qis42am7fyx0L8Th/giuitRXwFfSEmnX/Ws="}"""

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val transparent = 0x00000000

    /** An image of [width] x [height] with the code ([codeSize] px) in the middle. */
    private fun image(width: Int, height: Int, codeSize: Int, dark: Int, light: Int, background: Int = light): IntArray {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, codeSize, codeSize, mapOf(EncodeHintType.MARGIN to 2))
        val ox = (width - codeSize) / 2
        val oy = (height - codeSize) / 2
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val cx = x - ox
            val cy = y - oy
            if (cx in 0 until codeSize && cy in 0 until codeSize) {
                if (matrix[cx, cy]) dark else light
            } else background
        }
    }

    @Test
    fun darkOnLight_decodes() {
        assertEquals(payload, decodeQrFromArgb(image(600, 800, 500, black, white), 600, 800))
    }

    @Test
    fun lightOnDark_decodes() {
        assertEquals(payload, decodeQrFromArgb(image(600, 800, 500, white, black), 600, 800))
    }

    @Test
    fun darkModulesOnTransparentBackground_decode() {
        // A PNG whose light areas are transparent: read as black before, so the code vanished.
        assertEquals(payload, decodeQrFromArgb(image(600, 600, 500, black, transparent), 600, 600))
    }

    @Test
    fun lightModulesOnTransparentBackground_decode() {
        assertEquals(payload, decodeQrFromArgb(image(600, 600, 500, white, transparent), 600, 600))
    }

    @Test
    fun codeInsideALargePhoto_decodes() {
        // A screenshot or photo where the code fills a modest part of a large frame.
        assertEquals(payload, decodeQrFromArgb(image(2048, 1536, 900, black, white, background = 0xFF808080.toInt()), 2048, 1536))
    }

    @Test
    fun imageWithoutCode_returnsNull() {
        assertNull(decodeQrFromArgb(IntArray(400 * 300) { 0xFF777777.toInt() }, 400, 300))
    }

    @Test
    fun halveLuminance_averagesBlocks() {
        val lum = byteArrayOf(0, 100, 200.toByte(), 255.toByte(), 100, 100, 255.toByte(), 255.toByte())
        val half = halveLuminance(lum, 4, 2)
        assertEquals(2, half.size)
        assertEquals(75, half[0].toInt() and 0xFF)
        assertEquals(241, half[1].toInt() and 0xFF)
    }
}
