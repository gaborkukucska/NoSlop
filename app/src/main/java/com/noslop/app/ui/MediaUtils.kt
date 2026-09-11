// app/src/main/java/com/noslop/app/ui/MainScreen.kt
package com.noslop.app.ui

import com.noslop.app.util.tr



/**
 * Resolves a media URL, handling clearnet, protocol-relative, and decentralized 
 * noslop:// schemes correctly for consumption by the local proxy or system player.
 */
internal fun resolveMediaUrl(mediaUrl: String?, context: android.content.Context): String? {
    if (mediaUrl == null) return null
    if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) return mediaUrl
    
    if (mediaUrl.startsWith("noslop-gif://")) return mediaUrl
    if (mediaUrl.startsWith("//")) return "https:$mediaUrl"

    if (mediaUrl.startsWith("noslop://")) {
        val path = mediaUrl.removePrefix("noslop://")
        val onion = path.substringBefore("/")
        val id = path.substringAfter("/")
        
        val type = if (id.endsWith(".jpg") || id.endsWith(".png") || id.endsWith(".gif") || id.contains("image") || id.contains("thumb")) "image" else null
        val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(id, type)
        
        // Wait until it is COMPLETELY downloaded before handing the file path to Coil
        if (localFile != null && localFile.exists() && localFile.length() > 0 && !com.noslop.app.mesh.MediaManager.isMediaDownloadingOrRecovering(id)) {
            return "file://${localFile.absolutePath}"
        }
        
        return com.noslop.app.mesh.MediaProxyService.buildProxyUrl(onion, id)
    }

    return com.noslop.app.mesh.MediaProxyService.buildProxyUrl("", mediaUrl)
}




/**
 * P4-3: Shared image downscaling and compression routine preserving EXIF orientation.
 * Returns the compressed file, or the original file if compression failed or produced
 * a larger file.
 */
internal fun compressImageFile(file: java.io.File, destDir: java.io.File, imageQuality: String): java.io.File {
    return try {
        val bitmap = ExifUtils.decodeOriented(file) ?: return file
        val maxDim = when (imageQuality) {
            "low" -> 640
            "medium" -> 960
            else -> 1280
        }
        val compressQuality = when (imageQuality) {
            "low" -> 60
            "medium" -> 75
            else -> 85
        }
        val width = bitmap.width
        val height = bitmap.height
        var newWidth = width
        var newHeight = height
        if (width > maxDim || height > maxDim) {
            val ratio = Math.min(maxDim.toFloat() / width, maxDim.toFloat() / height)
            newWidth = (width * ratio).toInt()
            newHeight = (height * ratio).toInt()
        }
        val scaled = if (newWidth != width || newHeight != height) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else bitmap

        val compressedFile = java.io.File(destDir, "compressed_${file.name}.jpg")
        java.io.FileOutputStream(compressedFile).use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, compressQuality, out)
        }

        if (compressedFile.length() < file.length()) compressedFile else file
    } catch (e: Exception) {
        com.noslop.app.debug.Logger.error("COMPRESS", "Error compressing image: ${e.message}")
        file
    }
}
