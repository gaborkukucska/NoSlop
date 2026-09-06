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
# 1. YouTubeInternalClient.kt: Stop discarding valid geo-locked streams
# ---------------------------------------------------------------------------
YT_CLIENT = "app/src/main/java/com/noslop/app/feeds/api/YouTubeInternalClient.kt"

OLD_GEO_FALLBACK_VAR = """        // --- NOSLOP_GEO_LOCK_V1 ---
        // Holds a URL that resolved fine but is pinned to a country we will not
        // be fetching from. Used only as a last resort, after the failover.
        var geoLockedFallback: String? = null"""

NEW_GEO_FALLBACK_VAR = """        // Stream isolation guarantees the resolving exit IP matches media playback,
        // so streams are valid regardless of geographical region tags."""

edit(YT_CLIENT, OLD_GEO_FALLBACK_VAR, NEW_GEO_FALLBACK_VAR, "YouTubeInternalClient.kt: remove geoLockedFallback var")

OLD_GEO_CHECK = """                            if (playability == "OK") {
                                val url = extractUrlFromPlayerResponse(root, quality)
                                if (url != null) {
                                    val geoLock = GEO_LOCK_PATTERN.find(url)?.groupValues?.get(1)
                                    if (geoLock != null && isTor) {
                                        Logger.warn(
                                            TAG,
                                            "${config.clientName} returned a stream for $videoId " +
                                                "geo-locked to '$geoLock' — it was signed for the API " +
                                                "proxy's country and will 403 when fetched over a Tor " +
                                                "exit elsewhere. Trying another route first."
                                        )
                                        if (geoLockedFallback == null) geoLockedFallback = url
                                        response.close()
                                        continue
                                    }
                                    Logger.info(TAG, "Resolved direct video stream using ${config.clientName} (circuit: $currentStreamId) for $videoId")
                                    registerStreamId(url, videoId, currentStreamId)
                                    response.close()
                                    return@withContext url
                                } else {
                                    Logger.warn(TAG, "No URL found in player response for ${config.clientName} despite OK status")
                                }
                            }"""

NEW_GEO_CHECK = """                            if (playability == "OK") {
                                val url = extractUrlFromPlayerResponse(root, quality)
                                if (url != null) {
                                    Logger.info(TAG, "Resolved direct video stream using ${config.clientName} (circuit: $currentStreamId) for $videoId")
                                    registerStreamId(url, videoId, currentStreamId)
                                    response.close()
                                    return@withContext url
                                } else {
                                    Logger.warn(TAG, "No URL found in player response for ${config.clientName} despite OK status")
                                }
                            }"""

edit(YT_CLIENT, OLD_GEO_CHECK, NEW_GEO_CHECK, "YouTubeInternalClient.kt: accept valid stream immediately without geo-discard")

OLD_GEO_END = """        // --- NOSLOP_GEO_LOCK_V1 ---
        // Over Tor, a geo-locked URL is guaranteed to fail with 403 and cause
        // stalling/circuit-rotation storms. Only use it when NOT routing over Tor.
        if (!isTor) {
            geoLockedFallback?.let {
                Logger.warn(TAG, "Falling back to the geo-locked stream for $videoId — it may 403")
                return@withContext it
            }
        } else if (geoLockedFallback != null) {
            Logger.warn(TAG, "Discarding geo-locked stream for $videoId because Tor routing is active")
        }

        return@withContext null"""

NEW_GEO_END = """        return@withContext null"""

edit(YT_CLIENT, OLD_GEO_END, NEW_GEO_END, "YouTubeInternalClient.kt: clean up end of resolveStreamUrlInner")

# ---------------------------------------------------------------------------
# 2. UnifiedFeedTab.kt: Snappy pre-warming for upcoming slides
# ---------------------------------------------------------------------------
UNIFIED_FEED = "app/src/main/java/com/noslop/app/ui/UnifiedFeedTab.kt"

OLD_PRELOAD_STAGGER = """                    val targetIndex = i
                    val delayMs = firstPreloadDelayMs + (preloadedForwardCount * 1500L)
                    preloadScope.launch { 
                        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                        if (kotlin.math.abs(pagerState.currentPage - targetIndex) <= 2) {
                            com.noslop.app.ui.PreloadManager.preWarm(context, rawUrl, forcedUrl) 
                        }
                    }"""

NEW_PRELOAD_STAGGER = """                    val targetIndex = i
                    val delayMs = if (preloadedForwardCount == 0) 50L else 300L
                    preloadScope.launch { 
                        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                        if (kotlin.math.abs(pagerState.currentPage - targetIndex) <= 2) {
                            com.noslop.app.ui.PreloadManager.preWarm(context, rawUrl, forcedUrl) 
                        }
                    }"""

edit(UNIFIED_FEED, OLD_PRELOAD_STAGGER, NEW_PRELOAD_STAGGER, "UnifiedFeedTab.kt: reduce preload stagger to 50ms / 300ms")

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
