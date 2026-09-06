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

TRANSPORT_FILE = "app/src/main/java/com/noslop/app/mesh/MeshTransport.kt"
MEDIA_FILE = "app/src/main/java/com/noslop/app/mesh/MediaManager.kt"
VM_FILE = "app/src/main/java/com/noslop/app/ui/NoSlopViewModel.kt"
SYNC_FILE = "app/src/main/java/com/noslop/app/mesh/SyncPacketHandler.kt"

# ---------------------------------------------------------------------------
# 1. MeshTransport.kt: Add media packets to isCriticalPacket so cooldowns don't stall downloads
# ---------------------------------------------------------------------------
OLD_CRITICAL_PACKETS = '''        val isCriticalPacket = packet.type == "CONNECTION_REQUEST" ||
            packet.type == "USER_HANDSHAKE" || packet.type == "MESSAGE" ||
            packet.type == "GROUP_INVITE" || packet.type == "GROUP_UPDATE" ||
            packet.type == "GROUP_DELETE" || packet.type == "DELETE_MESSAGE" ||
            packet.type == "CHAT_REACTION" || packet.type == "INVENTORY_SYNC_REQUEST" ||
            packet.type == "SYNC_RESPONSE" || packet.type == "SYNC_REQUEST"'''

NEW_CRITICAL_PACKETS = '''        val isCriticalPacket = packet.type == "CONNECTION_REQUEST" ||
            packet.type == "USER_HANDSHAKE" || packet.type == "MESSAGE" ||
            packet.type == "GROUP_INVITE" || packet.type == "GROUP_UPDATE" ||
            packet.type == "GROUP_DELETE" || packet.type == "DELETE_MESSAGE" ||
            packet.type == "CHAT_REACTION" || packet.type == "INVENTORY_SYNC_REQUEST" ||
            packet.type == "SYNC_RESPONSE" || packet.type == "SYNC_REQUEST" ||
            packet.type == "MEDIA_REQUEST" || packet.type == "MEDIA_CHUNK" ||
            packet.type == "MEDIA_TRANSFER_ACK"'''

edit(TRANSPORT_FILE, OLD_CRITICAL_PACKETS, NEW_CRITICAL_PACKETS, "MeshTransport.kt: add media transfer packets to isCriticalPacket")

# ---------------------------------------------------------------------------
# 2. MediaManager.kt: Optimize chunk size and raise recovery threshold
# ---------------------------------------------------------------------------
OLD_AIMD_START = '''        // AIMD State for chunk size and concurrency
        var currentChunkSize = 256 * 1024 // Start with 256KB for better Tor circuit payload efficiency'''

NEW_AIMD_START = '''        // AIMD State for chunk size and concurrency
        var currentChunkSize = 512 * 1024 // Start with 512KB for higher throughput over Tor circuits'''

edit(MEDIA_FILE, OLD_AIMD_START, NEW_AIMD_START, "MediaManager.kt: increase initial chunk size to 512KB")

OLD_CONSEC_TIMEOUT = '''                    if (dl.consecutiveTimeouts >= 6 && dl.status == ActiveDownload.Status.ACTIVE) {'''
NEW_CONSEC_TIMEOUT = '''                    if (dl.consecutiveTimeouts >= 15 && dl.status == ActiveDownload.Status.ACTIVE) {'''

edit(MEDIA_FILE, OLD_CONSEC_TIMEOUT, NEW_CONSEC_TIMEOUT, "MediaManager.kt: raise direct send failure threshold before recovery")

# ---------------------------------------------------------------------------
# 3. NoSlopViewModel.kt: Do not mark mesh posts as viewed if media is not yet downloaded
# ---------------------------------------------------------------------------
OLD_MARK_VIEWED = '''    fun markItemViewed(itemId: String, isMesh: Boolean) {
        viewModelScope.launch {
            val item = _unifiedFeed.value.find { it.id == itemId }
                ?: allMeshes.find { it.id == itemId }?.let { UnifiedItem.Mesh(it) }
            val (url, cKey) = when (item) {
                is UnifiedItem.Feed -> Pair(item.item.url, com.noslop.app.data.getCanonicalItemKey(item))
                is UnifiedItem.Mesh -> Pair(item.post.clearnetUrl, com.noslop.app.data.getCanonicalItemKey(item))
                else -> Pair(null, null)
            }
            repository.markAsViewed(itemId, if (isMesh) "mesh" else "feed", url, cKey)
            cachedViewedIds = repository.getViewedItemIds()
        }
    }'''

NEW_MARK_VIEWED = '''    fun markItemViewed(itemId: String, isMesh: Boolean) {
        viewModelScope.launch {
            val item = _unifiedFeed.value.find { it.id == itemId }
                ?: allMeshes.find { it.id == itemId }?.let { UnifiedItem.Mesh(it) }

            // Do NOT mark mesh post as viewed if attached media has not finished downloading yet
            if (item is UnifiedItem.Mesh) {
                val post = item.post
                val hasMedia = !post.mediaUrl.isNullOrBlank() || (!post.mediaType.isNullOrBlank() && post.mediaType != "text")
                if (hasMedia) {
                    val rawMediaId = post.mediaUrl?.substringAfterLast("/") ?: ""
                    val isDownloaded = if (rawMediaId.isNotBlank()) {
                        com.noslop.app.mesh.MediaManager.isMediaDownloaded(rawMediaId, post.mediaType)
                    } else false

                    if (!isDownloaded) {
                        return@launch
                    }
                }
            }

            val (url, cKey) = when (item) {
                is UnifiedItem.Feed -> Pair(item.item.url, com.noslop.app.data.getCanonicalItemKey(item))
                is UnifiedItem.Mesh -> Pair(item.post.clearnetUrl, com.noslop.app.data.getCanonicalItemKey(item))
                else -> Pair(null, null)
            }
            repository.markAsViewed(itemId, if (isMesh) "mesh" else "feed", url, cKey)
            cachedViewedIds = repository.getViewedItemIds()
        }
    }'''

edit(VM_FILE, OLD_MARK_VIEWED, NEW_MARK_VIEWED, "NoSlopViewModel.kt: skip markItemViewed if mesh media is not yet downloaded")

# ---------------------------------------------------------------------------
# 4. SyncPacketHandler.kt: Fallback signature verification for legacy synced reactions
# ---------------------------------------------------------------------------
OLD_SYNC_REACTIONS = '''            val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
                r.postId, r.reactionType, r.authorId, r.timestamp.toString()
            )
            val isValid = CryptoService.verify(payloadToVerify, r.signature, r.authorId)'''

NEW_SYNC_REACTIONS = '''            val payloadToVerify = com.noslop.app.crypto.CryptoService.encodeForSigning(
                r.postId, r.reactionType, r.authorId, r.timestamp.toString()
            )
            val legacyPipePayload = "${r.postId}|${r.reactionType}|${r.authorId}|${r.timestamp}"
            val isValid = CryptoService.verify(payloadToVerify, r.signature, r.authorId) ||
                CryptoService.verify(legacyPipePayload, r.signature, r.authorId)'''

edit(SYNC_FILE, OLD_SYNC_REACTIONS, NEW_SYNC_REACTIONS, "SyncPacketHandler.kt: accept legacy reaction signatures in sync")

print("\n=== PATCH EXECUTION RESULTS ===")
for item in APPLIED:
    print(f"  [APPLIED] {item}")

if FAILED:
    print("\nErrors:")
    for item in FAILED:
        print(f"  [FAILED]  {item}")
    sys.exit(1)
else:
    print("\nAll media download and viewed-state patches applied successfully!")
