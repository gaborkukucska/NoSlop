#!/usr/bin/env python3
import os
import sys

APPLIED = []
FAILED = []

def edit(path, old, new, label):
    if not os.path.exists(path):
        FAILED.append(f"{label}: file not found {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        src = f.read()
    if old not in src:
        FAILED.append(f"{label}: anchor not found in {path}")
        return
    if src.count(old) != 1:
        FAILED.append(f"{label}: anchor matched {src.count(old)} times, expected 1 in {path}")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(src.replace(old, new, 1))
    APPLIED.append(label)

# ---------------------------------------------------------------------------
# 1. YouTubeInternalClient.kt: persistent nonce per video, threshold=2, maxAttempts=3
# ---------------------------------------------------------------------------
YT_CLIENT = "app/src/main/java/com/noslop/app/feeds/api/YouTubeInternalClient.kt"

OLD_NONCE_MAP = """    private val urlToStreamId = java.util.concurrent.ConcurrentHashMap<String, String>()"""

NEW_NONCE_MAP = """    private val urlToStreamId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val videoStreamNonces = java.util.concurrent.ConcurrentHashMap<String, Int>()"""

edit(YT_CLIENT, OLD_NONCE_MAP, NEW_NONCE_MAP, "YouTubeInternalClient.kt: add videoStreamNonces map")

OLD_THRESHOLD = """    // Allow trying client configs (especially ANDROID_VR and TVHTML5) before declaring blocked exit.
    private const val EXIT_BLOCKED_THRESHOLD = 4"""

NEW_THRESHOLD = """    // Fast-fail to a fresh circuit after 2 clients return LOGIN_REQUIRED on the same exit
    private const val EXIT_BLOCKED_THRESHOLD = 2"""

edit(YT_CLIENT, OLD_THRESHOLD, NEW_THRESHOLD, "YouTubeInternalClient.kt: lower EXIT_BLOCKED_THRESHOLD to 2")

OLD_LOOP_INIT = """        var streamNonce = 0
        var attempt = 0
        val maxAttempts = if (isTor) 2 else 1

        while (attempt < maxAttempts) {
            attempt++
            val currentStreamId = if (isTor) "yt_${videoId}_$streamNonce" else videoId"""

NEW_LOOP_INIT = """        var streamNonce = videoStreamNonces.compute(videoId) { _, n -> n ?: 0 }
        var attempt = 0
        val maxAttempts = if (isTor) 3 else 1

        while (attempt < maxAttempts) {
            attempt++
            val currentStreamId = if (isTor) "yt_${videoId}_$streamNonce" else videoId"""

edit(YT_CLIENT, OLD_LOOP_INIT, NEW_LOOP_INIT, "YouTubeInternalClient.kt: persistent streamNonce & maxAttempts=3")

OLD_BUMP_NONCE = """                                if (playability == "LOGIN_REQUIRED") {
                                    refusedThisAttempt++
                                    if (refusedThisAttempt >= EXIT_BLOCKED_THRESHOLD) {
                                        Logger.warn(
                                            TAG,
                                            "$refusedThisAttempt clients refused on circuit $currentStreamId for " +
                                                "$videoId — advancing stream isolation nonce to escape to a fresh circuit."
                                        )
                                        streamNonce++
                                        break
                                    }
                                }

                                if (config == configs.last() && attempt < maxAttempts) {
                                    streamNonce++
                                }"""

NEW_BUMP_NONCE = """                                if (playability == "LOGIN_REQUIRED") {
                                    refusedThisAttempt++
                                    if (refusedThisAttempt >= EXIT_BLOCKED_THRESHOLD) {
                                        Logger.warn(
                                            TAG,
                                            "$refusedThisAttempt clients refused on circuit $currentStreamId for " +
                                                "$videoId — advancing stream isolation nonce to escape to a fresh circuit."
                                        )
                                        streamNonce = videoStreamNonces.compute(videoId) { _, n -> (n ?: 0) + 1 }
                                        break
                                    }
                                }

                                if (config == configs.last() && attempt < maxAttempts) {
                                    streamNonce = videoStreamNonces.compute(videoId) { _, n -> (n ?: 0) + 1 }
                                }"""

edit(YT_CLIENT, OLD_BUMP_NONCE, NEW_BUMP_NONCE, "YouTubeInternalClient.kt: update videoStreamNonces on bump")

# ---------------------------------------------------------------------------
# 2. VideoPlayer.kt: Don't cache preload Unavailable, key activeVisible, fast auto-retry
# ---------------------------------------------------------------------------
VIDEO_PLAYER = "app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt"

OLD_CACHE_STORE = """        val result = doResolve(rawUrl, quality, isPreload)
        val expiryMs = if ((result is VideoSource.Embed || result is VideoSource.Unavailable) && !HttpClientProvider.isNetworkReady) {
            System.currentTimeMillis() + 10_000L
        } else {
            expiryOfSource(result)
        }
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — stamp the route this was resolved on.
        sourceCache[cacheKey] = CachedSource(
            source = result,
            expiresAtMs = expiryMs,
            overTor = HttpClientProvider.useTorForClearnet,
            circuitGeneration = com.noslop.app.tor.TorService.circuitGeneration
        )
        result"""

NEW_CACHE_STORE = """        val result = doResolve(rawUrl, quality, isPreload)
        val expiryMs = if ((result is VideoSource.Embed || result is VideoSource.Unavailable) && !HttpClientProvider.isNetworkReady) {
            System.currentTimeMillis() + 10_000L
        } else {
            expiryOfSource(result)
        }
        // NOSLOP_ROUTE_AWARE_CACHE_V1 — stamp the route this was resolved on.
        // Never poison sourceCache with an Unavailable result from a speculative background preload.
        if (!(isPreload && result is VideoSource.Unavailable)) {
            sourceCache[cacheKey] = CachedSource(
                source = result,
                expiresAtMs = expiryMs,
                overTor = HttpClientProvider.useTorForClearnet,
                circuitGeneration = com.noslop.app.tor.TorService.circuitGeneration
            )
        }
        result"""

edit(VIDEO_PLAYER, OLD_CACHE_STORE, NEW_CACHE_STORE, "VideoPlayer.kt: do not cache preload Unavailable")

OLD_ACTIVE_VISIBLE = """    val isActiveOrNext = isVisible || isNextSlide
    var activeVisible by remember { mutableStateOf(isActiveOrNext) }
    LaunchedEffect(isActiveOrNext) {
        if (isActiveOrNext) {
            activeVisible = true
        } else {
            kotlinx.coroutines.delay(500)
            activeVisible = false
            isVideoReady = false
        }
    }"""

NEW_ACTIVE_VISIBLE = """    val isActiveOrNext = isVisible || isNextSlide
    var activeVisible by remember(url) { mutableStateOf(isActiveOrNext) }
    LaunchedEffect(isActiveOrNext, url) {
        if (isActiveOrNext) {
            activeVisible = true
        } else {
            kotlinx.coroutines.delay(500)
            activeVisible = false
            isVideoReady = false
        }
    }"""

edit(VIDEO_PLAYER, OLD_ACTIVE_VISIBLE, NEW_ACTIVE_VISIBLE, "VideoPlayer.kt: key activeVisible on url")

OLD_RETRY_DELAY = """        if (resolvedSource is VideoSource.Unavailable && retryTrigger == 0 && activeVisible) {
            kotlinx.coroutines.delay(2500L)
            Logger.info("VIDEO", "Auto-retrying unavailable resolve for $url on a fresher circuit")
            retryTrigger++
        }"""

NEW_RETRY_DELAY = """        if (resolvedSource is VideoSource.Unavailable && retryTrigger == 0 && activeVisible) {
            kotlinx.coroutines.delay(300L)
            Logger.info("VIDEO", "Auto-retrying unavailable resolve for $url on a fresher circuit")
            retryTrigger++
        }"""

edit(VIDEO_PLAYER, OLD_RETRY_DELAY, NEW_RETRY_DELAY, "VideoPlayer.kt: quick 300ms auto-retry on Unavailable")

# ---------------------------------------------------------------------------
# 3. UnifiedFeedTab.kt: Preload 2 slides ahead over Tor
# ---------------------------------------------------------------------------
UNIFIED_FEED = "app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt"

OLD_PRELOAD_LIMIT = """        val overTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        val forwardPreloadLimit = if (overTor) 1 else 2
        val preloadPreviousSlide = !overTor
        // Start preloading the immediate next slide promptly (400ms) after settling
        val firstPreloadDelayMs = 400L"""

NEW_PRELOAD_LIMIT = """        val overTor = com.noslop.app.net.HttpClientProvider.useTorForClearnet
        // Stream isolation guarantees separate circuits, allowing 2 forward preloads without circuit contention
        val forwardPreloadLimit = 2
        val preloadPreviousSlide = !overTor
        // Start preloading the immediate next slide promptly (400ms) after settling
        val firstPreloadDelayMs = 400L"""

edit(UNIFIED_FEED, OLD_PRELOAD_LIMIT, NEW_PRELOAD_LIMIT, "UnifiedFeedTab.kt: preload 2 slides ahead")

print("\n=== PATCH EXECUTION RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print(f"\nAll {len(APPLIED)} patches applied successfully!")
