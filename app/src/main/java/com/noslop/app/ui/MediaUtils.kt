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


