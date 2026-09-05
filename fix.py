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

YT_CLIENT = "app/src/main/java/com/noslop/app/feeds/api/YouTubeInternalClient.kt"

# 1. Provide default parameter for playerClient
OLD_PLAYER_DECL = """    private fun playerClient(streamId: String) =
        com.noslop.app.net.HttpClientProvider.getOrCreateIsolatedMediaClient(streamId)
            .newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()"""

NEW_PLAYER_DECL = """    private fun playerClient(streamId: String = "yt_default") =
        com.noslop.app.net.HttpClientProvider.getOrCreateIsolatedMediaClient(streamId)
            .newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()"""

edit(YT_CLIENT, OLD_PLAYER_DECL, NEW_PLAYER_DECL, "YouTubeInternalClient.kt: default param for playerClient")

# 2. Fix line 806: proxy bypass retry
OLD_PROXY_RETRY = """                        response.close()
                        response = playerClient.newCall(directReqBuilder.build()).execute()"""

NEW_PROXY_RETRY = """                        response.close()
                        response = activePlayerClient.newCall(directReqBuilder.build()).execute()"""

edit(YT_CLIENT, OLD_PROXY_RETRY, NEW_PROXY_RETRY, "YouTubeInternalClient.kt: line 806 use activePlayerClient")

# 3. Fix line 854: direct-over-tor retry
OLD_DIRECT_RETRY = """                                    val directResponse = playerClient.newCall(retryDirect).execute()"""

NEW_DIRECT_RETRY = """                                    val directResponse = activePlayerClient.newCall(retryDirect).execute()"""

edit(YT_CLIENT, OLD_DIRECT_RETRY, NEW_DIRECT_RETRY, "YouTubeInternalClient.kt: line 854 use activePlayerClient")

print("\n=== PATCH EXECUTION RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print(f"\nAll {len(APPLIED)} compilation fixes applied successfully!")
