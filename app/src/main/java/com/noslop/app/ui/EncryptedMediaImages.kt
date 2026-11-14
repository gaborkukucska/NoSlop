// FILE: app/src/main/java/com/noslop/app/ui/EncryptedMediaImages.kt
package com.noslop.app.ui

import coil.ComponentRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.Options
import com.noslop.app.debug.Logger
import com.noslop.app.mesh.MediaCrypto
import com.noslop.app.mesh.MediaVault
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

/**
 * NOSLOP_MEDIA_ENCRYPTION_V1: lets the image loader show pictures that are encrypted on the device.
 *
 * Every image loader the app builds must call [install]. It adds two things:
 *
 * 1. A fetcher for files. When a file is an encrypted media file, the loader is handed its decrypted
 *    content as a stream. Nothing decrypted is written to storage by this class. A plain file is left to
 *    the loader's own file handling, exactly as before.
 * 2. A rule that a picture fetched from the app's local media server is never kept in the loader's disk
 *    cache. That server hands out decrypted media, and the disk cache is a plain folder.
 *
 * Known limit: on Android 9 and 10 the system decoder for animated pictures (GIF, animated WebP) needs a
 * file, so the image library writes the picture to the app's private cache folder while it decodes it
 * and deletes it afterwards. Still pictures, and animated ones on Android 11 and later, stay in memory.
 */
internal object EncryptedMediaImages {

    private const val TAG = "MEDIA_CRYPTO"

    /** The start of every address on the local media server (MediaProxyService.buildProxyUrl). */
    private const val LOCAL_SERVER_PREFIX = "http://127.0.0.1:"

    fun install(components: ComponentRegistry.Builder) {
        components.add(LocalServerNotOnDisk)
        components.add(EncryptedFileFetcherFactory, File::class.java)
    }

    internal fun isLocalServerUrl(data: Any?): Boolean = data is String && data.startsWith(LOCAL_SERVER_PREFIX)

    private object LocalServerNotOnDisk : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val request = chain.request
            if (!isLocalServerUrl(request.data)) return chain.proceed(request)
            return chain.proceed(request.newBuilder().diskCachePolicy(CachePolicy.DISABLED).build())
        }
    }

    private object EncryptedFileFetcherFactory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? {
            // Null hands a plain file back to the loader's own file fetcher.
            if (!MediaCrypto.isEncrypted(data)) return null
            return EncryptedFileFetcher(data, options)
        }
    }

    private class EncryptedFileFetcher(private val file: File, private val options: Options) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val input = try {
                MediaCrypto.openInput(file, MediaVault.key())
            } catch (e: IOException) {
                Logger.warn(TAG, "An encrypted picture could not be opened", "file=${file.name} | error=${e.message}")
                throw e
            }
            val extension = file.extension.lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            return SourceResult(
                source = ImageSource(input.source().buffer(), options.context),
                mimeType = mimeType,
                dataSource = DataSource.DISK
            )
        }
    }
}
