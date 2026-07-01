package com.noslop.mvp.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.noslop.mvp.httpClientEngineFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Decode encoded image bytes (jpg/png/webp/gif) to a Compose bitmap, or null on failure. Platform codec. */
expect fun decodeImageBitmapOrNull(bytes: ByteArray): ImageBitmap?

/**
 * Render a base64-encoded image with **no network fetch** — for bytes that already travelled in-band, e.g.
 * an Android post's `MediaMetadata.thumbnail_b64` (a ~90px JPEG). Safe for untrusted mesh content: nothing
 * is fetched (no Tor deanon vector), and a bad/oversized string just decodes to null → empty box. Thumbnails
 * are tiny, so the decode runs in `remember`; fail-safe, never throws.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun Base64Image(base64: String, contentScale: ContentScale, modifier: Modifier) {
    val bmp = remember(base64) {
        runCatching { decodeImageBitmapOrNull(Base64.decode(base64)) }.getOrNull()
    }
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = contentScale, modifier = modifier)
    else Box(modifier.fillMaxSize())
}

/**
 * Render an image from an **absolute local file path** — for media that already arrived over the mesh
 * (Roadmap #5b downloads land in [com.noslop.mvp.MediaFiles]). Reads from disk, never the network, so
 * it's safe for untrusted mesh content (bad bytes decode to null → empty box). Full-size images can be
 * multi-MB, so the read+decode runs off the UI thread.
 */
@Composable
fun LocalFileImage(path: String, contentScale: ContentScale, modifier: Modifier) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.Default) {
            runCatching { com.noslop.mvp.MediaFiles.readBytes(path)?.let { decodeImageBitmapOrNull(it) } }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = contentScale, modifier = modifier)
    else Box(modifier.fillMaxSize())
}

// One shared client for all image fetches — reuses the ktor engine we already ship (NOT Coil, so it can't
// drag in its own ktor and crash the iOS Darwin engine — see the ktor-3.0.3 pin).
private val imageClient: HttpClient by lazy { httpClientEngineFactory() }

/**
 * Minimal, dependency-free network image: fetch bytes with our ktor client, decode via the platform codec
 * (Skia on iOS/desktop, BitmapFactory on Android). The media components ([BlurredImageBackground],
 * [SegmentedArticleReader]) take this as their injected `imageContent`. Fail-safe: any error just leaves an
 * empty box — it never throws, so a bad URL or decode can't crash the feed.
 */
@Composable
fun NetworkImage(url: String, contentScale: ContentScale, modifier: Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = runCatching {
            val bytes: ByteArray = imageClient.get(url) { header("User-Agent", "NoSlopMVP/0.1") }.body()
            withContext(Dispatchers.Default) { decodeImageBitmapOrNull(bytes) }
        }.getOrNull()
    }
    val bmp = bitmap
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, contentScale = contentScale, modifier = modifier)
    else Box(modifier.fillMaxSize())   // neutral placeholder while loading / on failure
}
