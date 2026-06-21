import os
import re

filepath = "app/src/main/java/com/noslop/app/mesh/MediaManager.kt"
if not os.path.exists(filepath):
    print(f"❌ File not found: {filepath}")
    exit(1)

with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# The properly scoped version of the finishDownload function
new_func = """    private fun finishDownload(dl: ActiveDownload) {
        val repo = repository ?: return
        try {
            val mediaDir = getMediaDirectory(dl.metadata.type)
            val file = File(mediaDir, dl.metadata.id)
            file.outputStream().use { out ->
                dl.chunks.forEach { chunk ->
                    if (chunk != null) out.write(chunk)
                }
            }
            
            dl.status = ActiveDownload.Status.COMPLETED
            updateProgress(dl.metadata.id, 100)
            Logger.info(TAG, "Download completed for ${dl.metadata.id}")
            updateWakeLock()
            
            // Send ACK
            val ack = MediaTransferAckPayload(mediaId = dl.metadata.id)
            val peer = dl.peerOnion
            if (peer != null) {
                scope.launch {
                    val packet = NetworkPacket(
                        id = UUID.randomUUID().toString(),
                        hops = 1,
                        senderId = repo.getLocalIdentity()?.publicKeyB64 ?: "",
                        type = "MEDIA_TRANSFER_ACK",
                        payload = com.google.gson.Gson().toJsonTree(ack)
                    )
                    repo.meshTransport.sendPacket(peer, Constants.MESH_PORT, packet)
                }
            }
            
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to finish download: ${e.message}")
            dl.status = ActiveDownload.Status.ERROR
        }
    }"""

# Find the finishDownload function and replace it
pattern = re.compile(r"    private fun finishDownload\(dl: ActiveDownload\) \{.*?\n    \}", re.DOTALL)
if pattern.search(content):
    content = pattern.sub(new_func, content)
    print("✅ Fixed compiler error in MediaManager.kt")
else:
    print("❌ Could not find finishDownload function")

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
