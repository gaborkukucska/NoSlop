// FILE: app/src/test/java/com/noslop/app/ui/QrFrameDecodeTest.kt
package com.noslop.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Regression test for the camera QR scanner: CameraX delivers the Y plane row-padded
 * (rowStride > width) and sometimes with pixelStride > 1. The analyzer must repack it before
 * handing it to ZXing; feeding the padded buffer directly shears the image and never decodes.
 */
class QrFrameDecodeTest {

    private val payload =
        """{"handle":"alice","publicKey":"MCowBQYDK2VwAyEAK12RuYO4u9W6tuUc2Hr7ZkcYTuUs6QSR8P4ePGKKWXg=","onionAddress":"fnozdomdxc55lovw4uonq6x3mzdrqtxfftuqjepq7ypdyyuklf4i7cqd.onion","encPublicKey":"MCowBQYDK2VuAyEAfFAMyI71qis42am7fyx0L8Th/giuitRXwFfSEmnX/Ws="}"""

    private fun reader() = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    /** Renders [text] as a QR into a simulated camera Y plane: white page, code in the middle. */
    private fun cameraFrame(text: String, width: Int, height: Int, rowStride: Int, pixelStride: Int, inverted: Boolean = false): ByteBuffer {
        val size = minOf(width, height) * 3 / 4
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val buf = ByteArray(rowStride * (height - 1) + (width - 1) * pixelStride + 1)
        val ox = (width - size) / 2
        val oy = (height - size) / 2
        for (y in 0 until height) for (x in 0 until width) {
            val inCode = x - ox in 0 until size && y - oy in 0 until size && matrix[x - ox, y - oy]
            var dark = inCode
            if (inverted) dark = !dark
            buf[y * rowStride + x * pixelStride] = (if (dark) 20 else 235).toByte()
        }
        return ByteBuffer.wrap(buf)
    }

    @Test
    fun paddedRows_areRepackedAndDecoded() {
        val w = 640; val h = 480
        val plane = cameraFrame(payload, w, h, rowStride = 704, pixelStride = 1)
        val lum = packLuminancePlane(plane, w, h, rowStride = 704, pixelStride = 1)
        assertEquals(payload, decodeQrLuminance(lum, w, h, reader()))
    }

    @Test
    fun pixelStrideTwo_isRepackedAndDecoded() {
        val w = 640; val h = 480
        val plane = cameraFrame(payload, w, h, rowStride = 1280, pixelStride = 2)
        val lum = packLuminancePlane(plane, w, h, rowStride = 1280, pixelStride = 2)
        assertEquals(payload, decodeQrLuminance(lum, w, h, reader()))
    }

    @Test
    fun tightlyPackedFrame_decodes() {
        val w = 640; val h = 480
        val plane = cameraFrame(payload, w, h, rowStride = w, pixelStride = 1)
        assertEquals(payload, decodeQrLuminance(packLuminancePlane(plane, w, h, w, 1), w, h, reader()))
    }

    @Test
    fun invertedCode_decodes() {
        val w = 640; val h = 480
        val plane = cameraFrame(payload, w, h, rowStride = 704, pixelStride = 1, inverted = true)
        assertEquals(payload, decodeQrLuminance(packLuminancePlane(plane, w, h, 704, 1), w, h, reader()))
    }

    @Test
    fun frameWithoutCode_returnsNull() {
        val w = 320; val h = 240
        val blank = ByteBuffer.wrap(ByteArray(352 * h) { 128.toByte() })
        assertNull(decodeQrLuminance(packLuminancePlane(blank, w, h, 352, 1), w, h, reader()))
    }
}
