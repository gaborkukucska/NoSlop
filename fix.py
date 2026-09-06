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
# 1. HttpClientProvider.kt: Increase Tor SOCKS connect timeout from 20s to 35s
#    to prevent code=2004 connection timeouts on mobile
# ---------------------------------------------------------------------------
HTTP_CLIENT = "app/src/main/java/com/noslop/app/net/HttpClientProvider.kt"

OLD_TIMEOUT_MEDIA = """                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)"""

NEW_TIMEOUT_MEDIA = """                .connectTimeout(35, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)"""

edit(HTTP_CLIENT, OLD_TIMEOUT_MEDIA, NEW_TIMEOUT_MEDIA, "HttpClientProvider.kt: bump media client connectTimeout to 35s")

OLD_HANDSHAKE_TIMEOUT = """        val handshakeTimeout = if (timeout > 0) timeout else 20000"""
NEW_HANDSHAKE_TIMEOUT = """        val handshakeTimeout = if (timeout > 0) timeout else 35000"""

edit(HTTP_CLIENT, OLD_HANDSHAKE_TIMEOUT, NEW_HANDSHAKE_TIMEOUT, "HttpClientProvider.kt: bump TorSocksSocket handshakeTimeout to 35s")

# ---------------------------------------------------------------------------
# 2. YouTubeInternalClient.kt: Always increment nonce on retry/re-resolve
# ---------------------------------------------------------------------------
YT_CLIENT = "app/src/main/java/com/noslop/app/feeds/api/YouTubeInternalClient.kt"

OLD_NONCE_START = """        var streamNonce = videoStreamNonces.compute(videoId) { _, n -> n ?: 0 }"""
NEW_NONCE_START = """        var streamNonce = videoStreamNonces.compute(videoId) { _, n -> if (n != null) n + 1 else 0 }"""

edit(YT_CLIENT, OLD_NONCE_START, NEW_NONCE_START, "YouTubeInternalClient.kt: advance nonce on fresh resolve/retry")

# ---------------------------------------------------------------------------
# 3. VideoPlayer.kt: Ignore micro-resumes (< 8s) to prevent discarding preloaded buffers
# ---------------------------------------------------------------------------
VIDEO_PLAYER = "app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt"

OLD_SAVE_POS = """                    val currentPos = player.currentPosition
                    Logger.debug("VIDEO_DEBUG", "LaunchedEffect isVisible=false. currentPos=$currentPos, duration=${player.duration}, rawUrl=$rawUrl")
                    if (currentPos > 0L) {
                        PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                    }"""

NEW_SAVE_POS = """                    val currentPos = player.currentPosition
                    Logger.debug("VIDEO_DEBUG", "LaunchedEffect isVisible=false. currentPos=$currentPos, duration=${player.duration}, rawUrl=$rawUrl")
                    // Only store resume positions if user watched at least 8 seconds; swiping past should not poison the buffer
                    if (currentPos >= 8000L) {
                        PlaybackPositionStore.save(rawUrl, currentPos, player.duration)
                    }"""

edit(VIDEO_PLAYER, OLD_SAVE_POS, NEW_SAVE_POS, "VideoPlayer.kt: only save resume pos if >= 8s")

OLD_PRELOAD_SEEK = """                val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                if (resumeMs > 0L && Math.abs(currentPosition - resumeMs) > 1000L) {
                    Logger.info("VIDEO", "Resuming preloaded video at ${resumeMs}ms: $rawUrl")
                    seekTo(resumeMs)
                }"""

NEW_PRELOAD_SEEK = """                val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                // Only seek preloaded video if user watched deeply (>= 8s); micro-seeks destroy the pre-warmed buffer
                if (resumeMs >= 8000L && Math.abs(currentPosition - resumeMs) > 3000L) {
                    Logger.info("VIDEO", "Resuming preloaded video at ${resumeMs}ms: $rawUrl")
                    seekTo(resumeMs)
                }"""

edit(VIDEO_PLAYER, OLD_PRELOAD_SEEK, NEW_PRELOAD_SEEK, "VideoPlayer.kt: only seek preloaded video if >= 8s")

OLD_FRESH_SEEK = """                    val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                    if (resumeMs > 0L) {
                        Logger.info("VIDEO", "Resuming video at ${resumeMs}ms: $rawUrl")
                        seekTo(resumeMs)
                    }"""

NEW_FRESH_SEEK = """                    val resumeMs = PlaybackPositionStore.resumePositionFor(rawUrl)
                    if (resumeMs >= 8000L) {
                        Logger.info("VIDEO", "Resuming video at ${resumeMs}ms: $rawUrl")
                        seekTo(resumeMs)
                    }"""

edit(VIDEO_PLAYER, OLD_FRESH_SEEK, NEW_FRESH_SEEK, "VideoPlayer.kt: only seek fresh video if >= 8s")

# ---------------------------------------------------------------------------
# 4. PreloadManager.kt: Increase MAX_PRELOAD from 3 to 4 for headroom
# ---------------------------------------------------------------------------
PRELOAD_MANAGER = "app/src/main/java/com/noslop/app/ui/PreloadManager.kt"

OLD_MAX_PRELOAD = """    private const val MAX_PRELOAD = 3"""
NEW_MAX_PRELOAD = """    private const val MAX_PRELOAD = 4"""

edit(PRELOAD_MANAGER, OLD_MAX_PRELOAD, NEW_MAX_PRELOAD, "PreloadManager.kt: increase MAX_PRELOAD to 4")

print("\n=== PATCH EXECUTION RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print(f"\nAll {len(APPLIED)} stability patches applied successfully!")
