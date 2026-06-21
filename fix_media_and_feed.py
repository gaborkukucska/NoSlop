import os

def patch_file(filepath, replacements):
    if not os.path.exists(filepath):
        print(f"❌ File not found: {filepath}")
        return
    
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
        
    for old, new in replacements:
        if old in content:
            content = content.replace(old, new)
            print(f"✅ Successfully patched a block in {filepath}")
        else:
            print(f"⚠️ Could not find specific snippet in {filepath} (Already patched?)")
            
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)

# 1. MediaManager.kt - Fix SOCKS5 routing & missing sender IDs
media_manager_path = "app/src/main/java/com/noslop/app/mesh/MediaManager.kt"
media_manager_replacements = [
    (
        "        // 0. If it's a metadata request (chunkSize == 0)\n        if (payload.chunkSize == 0) {",
        "        val targetOnion = repo.peerDao.getPeerByPublicKey(senderId)?.onionAddress\n        if (targetOnion.isNullOrBlank()) {\n            Logger.warn(TAG, \"Cannot send media to $senderId: no onion address found\")\n            return\n        }\n\n        // 0. If it's a metadata request (chunkSize == 0)\n        if (payload.chunkSize == 0) {"
    ),
    (
        "repo.meshTransport.sendPacket(senderId, Constants.MESH_PORT, packet)",
        "repo.meshTransport.sendPacket(targetOnion, Constants.MESH_PORT, packet)"
    ),
    (
        "senderId = \"\", // Placeholder\n                    type = \"MEDIA_CHUNK\",",
        "senderId = repo.getLocalIdentity()?.publicKeyB64 ?: \"\",\n                    type = \"MEDIA_CHUNK\","
    ),
    (
        "senderId = \"\", // Placeholder\n                type = \"MEDIA_TRANSFER_ACK\",",
        "senderId = repo.getLocalIdentity()?.publicKeyB64 ?: \"\",\n                type = \"MEDIA_TRANSFER_ACK\","
    )
]

# 2. NoSlopViewModel.kt - Fix disappearing mesh posts on tab switch
noslop_viewmodel_path = "app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt"
noslop_viewmodel_replacements = [
    (
        "    fun loadMoreFeedItems(filterMode: String? = null) {\n        val currentIds = _unifiedFeed.value.map { it.id }.toSet()",
        "    private var lastFilterMode: String? = null\n\n    fun loadMoreFeedItems(filterMode: String? = null) {\n        if (lastFilterMode != filterMode) {\n            _unifiedFeed.value = emptyList()\n            lastFilterMode = filterMode\n        }\n        val currentIds = _unifiedFeed.value.map { it.id }.toSet()"
    )
]

# 3. VideoPlayer.kt - Bypass HTTP proxy for locally downloaded videos
video_player_path = "app/src/main/java/com/noslop/app/ui/components/VideoPlayer.kt"
video_player_replacements = [
    (
        "        // ── 1. Direct file / local proxy ─────────────────────────────────\n        isDirectFileUrl(rawUrl) -> VideoSource.Direct(rawUrl)",
        """        // ── 1. Direct file / local proxy ─────────────────────────────────
        isDirectFileUrl(rawUrl) -> {
            if (rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) {
                val id = rawUrl.substringAfter("id=").substringBefore("&")
                if (id.isNotBlank()) {
                    val localFile = com.noslop.app.mesh.MediaManager.getLocalFile(id, "video")
                    if (localFile != null && localFile.exists()) {
                        Logger.info("VIDEO_RESOLVE", "Found local file, bypassing proxy: ${localFile.absolutePath}")
                        return@withContext VideoSource.Direct("file://${localFile.absolutePath}")
                    }
                }
            }
            VideoSource.Direct(rawUrl)
        }"""
    )
]

# Execute patches
print("--- Applying NoSlop Patches ---")
patch_file(media_manager_path, media_manager_replacements)
patch_file(noslop_viewmodel_path, noslop_viewmodel_replacements)
patch_file(video_player_path, video_player_replacements)
print("--- Done ---")
