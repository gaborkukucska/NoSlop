package com.noslop.mvp.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

/**
 * Image loading for the feed. Coil's image components ([BlurredImageBackground], [SegmentedArticleReader])
 * take an injected `imageContent` lambda (loading is platform-specific); [NetworkImage] is that adapter,
 * backed by Coil 3 over the ktor engine we already ship (Darwin on iOS, OkHttp on JVM/Android).
 */

/** Register Coil's singleton loader with the ktor network fetcher. Call once, high in the UI tree (App). */
@Composable
fun InstallCoilLoader() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
}

/** Matches the `imageContent` lambda the media components expect: load [url] and fit per [contentScale]. */
@Composable
fun NetworkImage(url: String, contentScale: ContentScale, modifier: Modifier) {
    AsyncImage(model = url, contentDescription = null, contentScale = contentScale, modifier = modifier)
}
