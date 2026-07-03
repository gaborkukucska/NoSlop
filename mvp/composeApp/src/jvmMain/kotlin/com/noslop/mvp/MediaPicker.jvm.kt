package com.noslop.mvp

import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Desktop picker: AWT FileDialog + an ImageIO-scaled ~90 px JPEG thumbnail (what Android posts carry). */
actual object ImagePicker {
    actual val isAvailable: Boolean get() = !GraphicsEnvironment.isHeadless()

    actual fun pick(onResult: (PickedMedia?) -> Unit) {
        // FileDialog blocks its calling thread; keep the caller (Compose) responsive.
        Thread {
            val dialog = FileDialog(null as Frame?, "Choose an image", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name ->
                name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
            }
            dialog.isVisible = true
            val file = dialog.files.firstOrNull()
            if (file == null || !file.exists()) { onResult(null); return@Thread }
            val mime = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                "webp" -> "image/webp"; "gif" -> "image/gif"
                else -> "application/octet-stream"
            }
            onResult(PickedMedia(file.absolutePath, mime, file.length(), thumbnailB64(file)))
        }.start()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun thumbnailB64(file: File): String? = runCatching {
        val src = ImageIO.read(file) ?: return null
        val h = (90.0 * src.height / src.width).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(90, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            drawImage(src.getScaledInstance(90, h, Image.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "jpg", out)
        Base64.encode(out.toByteArray())
    }.getOrNull()
}
