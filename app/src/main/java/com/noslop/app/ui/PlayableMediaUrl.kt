// FILE: app/src/main/java/com/noslop/app/ui/PlayableMediaUrl.kt
package com.noslop.app.ui

import com.noslop.app.mesh.MediaCrypto
import com.noslop.app.mesh.MediaProxyService
import java.io.File

/**
 * NOSLOP_MEDIA_ENCRYPTION_V1: the address a video or audio player should open for [url].
 *
 * A player reads a `file://` address straight from storage, so it cannot play a media file that is
 * encrypted on the device. For such a file this returns the address of the same media on the app's own
 * local media server (MediaProxyService), which decrypts as it streams and answers range requests, so
 * seeking still works. Every other address, and a plain or missing file, comes back unchanged.
 *
 * Pictures do not need this: the image loader decrypts them itself (EncryptedMediaImages).
 */
internal fun playableMediaUrl(
    url: String,
    proxyUrlFor: (mediaId: String) -> String = { MediaProxyService.buildProxyUrl("", it) }
): String {
    if (!url.startsWith("file://")) return url
    val file = File(url.removePrefix("file://"))
    // A media file is stored under its media id, which is how the local server finds it again.
    return if (MediaCrypto.isEncrypted(file)) proxyUrlFor(file.name) else url
}
