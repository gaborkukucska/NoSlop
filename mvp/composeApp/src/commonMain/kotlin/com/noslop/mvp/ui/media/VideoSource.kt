package com.noslop.mvp.ui.media

import com.noslop.mvp.debug.Logger
import com.noslop.mvp.util.ConcurrentMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ─────────────────────────────────────────────────────────────────────────────
// VideoSource — resolved once per unique URL, cached for the lifetime of the
// Composition so we never resolve the same video twice.
// ─────────────────────────────────────────────────────────────────────────────

/** What the player should actually load. */
internal sealed class VideoSource {
    /** A direct media URL (mp4, m3u8, webm …) — plays in MediaPlayer. */
    data class Direct(val url: String) : VideoSource()
    /** A web-embeddable URL — plays in a minimal WebView. */
    data class Embed(val url: String) : VideoSource()
    /** Resolve step completed but produced nothing we can play. */
    data object Unavailable : VideoSource()
}

/** In-memory cache: raw mediaUrl → resolved VideoSource. */
private val sourceCache = ConcurrentMap<String, VideoSource>()

internal fun isSourceCached(url: String): Boolean = sourceCache.containsKey(url)

/** Mutexes for in-flight resolutions to prevent duplicate network calls. */
private val resolveMutexes = ConcurrentMap<String, Mutex>()

/**
 * Determine which [VideoSource] to use for [rawUrl].
 *
 * Routing priority:
 *  1. Direct file extensions / 127.0.0.1 proxy  → Direct
 *  2. YouTube / youtube-nocookie / youtu.be      → youtube-nocookie embed → Embed
 *  3. player.vimeo.com / vimeo.com               → Vimeo player iframe   → Embed
 *  4. archive.org/embed or /details              → Embed
 *  5. Anything else already http(s)              → Direct (treat as raw stream)
 */
internal suspend fun resolveSource(rawUrl: String, forceRefresh: Boolean = false): VideoSource {
    if (!forceRefresh) {
        sourceCache.get(rawUrl)?.let { return it }
    }

    val mutex = resolveMutexes.computeIfAbsent(rawUrl) { Mutex() }

    return mutex.withLock {
        if (!forceRefresh) {
            sourceCache.get(rawUrl)?.let { return@withLock it }
        }

        try {
            val result = doResolve(rawUrl)
            sourceCache.put(rawUrl, result)
            result
        } finally {
            resolveMutexes.remove(rawUrl)
        }
    }
}

/** Performs the actual resolution work. */
private fun doResolve(rawUrl: String): VideoSource {
    if (rawUrl.isBlank()) return VideoSource.Unavailable

    // Failsafe: catch invalid local proxy URLs
    if ((rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) &&
        rawUrl.substringAfter("id=", "").substringBefore("&").isBlank()) {
        Logger.warn("VIDEO_RESOLVE", "Caught invalid local proxy URL with blank ID: $rawUrl")
        return VideoSource.Unavailable
    }

    // Catch mislabeled images
    if (isImageUrl(rawUrl)) {
        Logger.warn("VIDEO_RESOLVE", "Caught image URL passed to VideoPlayer, marking unavailable: $rawUrl")
        return VideoSource.Unavailable
    }

    return when {
        // 1. Direct file / local proxy
        isDirectFileUrl(rawUrl) -> VideoSource.Direct(rawUrl)

        // 2. YouTube → embed fallback (Invidious API is Android-specific via OkHttp, so we use embed for KMP)
        isYouTubeUrl(rawUrl) -> resolveYouTubeSource(rawUrl)

        // 3. Vimeo → embed
        isVimeoUrl(rawUrl) -> resolveVimeoSource(rawUrl)

        // 4. Archive.org embed / details
        rawUrl.contains("archive.org/embed") ||
        rawUrl.contains("archive.org/details") -> {
            val embedUrl = if (rawUrl.contains("/details/")) {
                val id = rawUrl.substringAfter("/details/").substringBefore("?").substringBefore("/")
                "https://archive.org/embed/$id"
            } else {
                rawUrl
            }
            VideoSource.Embed(embedUrl)
        }

        // 5. Generic http(s)
        rawUrl.startsWith("http") -> VideoSource.Direct(rawUrl)

        else -> VideoSource.Unavailable
    }
}

private fun isImageUrl(url: String): Boolean {
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
           lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
}

private fun isDirectFileUrl(url: String): Boolean {
    if (url.contains("127.0.0.1") || url.contains("localhost")) return true
    val lower = url.lowercase().substringBefore("?")
    return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
           lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ts") ||
           lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".ogg") ||
           lower.contains("/download/") || lower.contains("archive.org/download/")
}

private fun isYouTubeUrl(url: String): Boolean =
    url.contains("youtube.com") || url.contains("youtu.be") || url.contains("youtube-nocookie.com")

private fun isVimeoUrl(url: String): Boolean =
    url.contains("vimeo.com")

/** Extract a YouTube video ID from any YouTube URL shape. */
private fun extractYouTubeId(url: String): String? = when {
    url.contains("v=")      -> url.substringAfter("v=").substringBefore("&").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("/embed/") -> url.substringAfter("/embed/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> null
}

/** Extract a Vimeo video ID. */
private fun extractVimeoId(url: String): String? = when {
    url.contains("/video/") -> url.substringAfter("/video/").substringBefore("?").substringBefore("/").takeIf { it.isNotBlank() }
    else -> url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } }
}

private fun resolveYouTubeSource(url: String): VideoSource {
    val videoId = extractYouTubeId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract YouTube video ID from: $url")
        return VideoSource.Unavailable
    }
    // Use youtube-nocookie embed (privacy-friendly, works cross-platform)
    val embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
    Logger.info("VIDEO_RESOLVE", "YouTube resolved to embed: $embedUrl")
    return VideoSource.Embed(embedUrl)
}

private fun resolveVimeoSource(url: String): VideoSource {
    val videoId = extractVimeoId(url) ?: run {
        Logger.warn("VIDEO_RESOLVE", "Could not extract Vimeo video ID from: $url")
        return VideoSource.Unavailable
    }
    val embedUrl = "https://player.vimeo.com/video/$videoId?autoplay=1&background=0"
    Logger.info("VIDEO_RESOLVE", "Vimeo resolved to embed: $embedUrl")
    return VideoSource.Embed(embedUrl)
}
