package com.noslop.mvp

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a string as a QR code drawn in the terminal, so the HUB can literally show a code the phone
 * scans to configure itself (the [MeshInvite] descriptor). Uses Unicode half-blocks (▀ ▄ █) so each text
 * row packs two QR rows — keeping the code roughly square (terminal cells are ~2:1) and compact enough to
 * fit + scan. A 2-module quiet zone is added (QR scanners need the margin).
 */
object QrConsole {
    fun render(text: String): String {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2),
        )
        val w = matrix.width
        val h = matrix.height
        val sb = StringBuilder()
        var y = 0
        while (y < h) {
            for (x in 0 until w) {
                val top = matrix.get(x, y)            // true = dark module
                val bottom = if (y + 1 < h) matrix.get(x, y + 1) else false
                sb.append(
                    when {
                        top && bottom -> '█'     // █ both dark
                        top && !bottom -> '▀'    // ▀ top dark
                        !top && bottom -> '▄'    // ▄ bottom dark
                        else -> ' '                   // both light
                    },
                )
            }
            sb.append('\n')
            y += 2
        }
        return sb.toString()
    }
}
