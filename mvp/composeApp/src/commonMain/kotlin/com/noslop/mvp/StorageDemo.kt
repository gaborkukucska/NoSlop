package com.noslop.mvp

import com.noslop.mvp.db.MeshPost
import com.noslop.mvp.db.Peer

/** Lazily opens (and caches) the one [MeshStore] for the app, if a platform driver is available. */
object MeshStoreProvider {
    private var cached: MeshStore? = null
    val isAvailable: Boolean get() = DbDriverFactory.isAvailable
    fun get(): MeshStore? {
        if (!DbDriverFactory.isAvailable) return null
        return cached ?: runCatching { MeshStore(DbDriverFactory.create()) }.getOrNull()?.also { cached = it }
    }
}

private const val WELCOME_ID = "welcome"

/**
 * On-launch persistence demo, shown in the identity card. Increments a launch counter (proves the DB
 * survives restarts), then re-seeds a stable welcome post + a self peer keyed by fixed ids. Because the
 * post id never changes, each launch is a "re-gossip": the row is deduped (stays 1) and its gossip count
 * climbs — so a rising `launch #N` with `gossip×N` is live proof both persistence AND dedup work.
 */
fun runStorageDemo(identity: Identity): String {
    val store = MeshStoreProvider.get() ?: return "Storage unavailable (no DB driver)"
    return runCatching {
        val launches = store.bumpCounter("launches")
        store.savePost(welcomePost(identity))
        store.upsertPeer(selfPeer(identity))
        val gossip = store.post(WELCOME_ID)?.gossipCount ?: 0
        "SQLDelight ✓ — launch #$launches · ${store.postCount()} post (gossip×$gossip) · ${store.peerCount()} peer"
    }.getOrElse { "Storage error: ${it.message}" }
}

private fun welcomePost(id: Identity) = MeshPost(
    id = WELCOME_ID,
    authorPublicKeyB64 = id.publicKeyHex,
    authorHandle = id.handle,
    authorTripcode = id.tripcode,
    authorAvatarB64 = null,
    content = "Welcome to NoSlop — this post is stored locally and survives restarts.",
    timestamp = 0L,
    signature = "self",
    mediaUrl = null, mediaType = null,
    gossipCount = 1,
    privacy = "public",
    thumbnailB64 = null, clearnetUrl = null, clearnetTitle = null, clearnetThumbnailUrl = null,
    isOrphaned = false,
)

private fun selfPeer(id: Identity) = Peer(
    publicKeyB64 = id.publicKeyHex,
    handle = id.handle,
    tripcode = id.tripcode,
    onionAddress = id.onionAddress,
    encPublicKeyB64 = "",
    isTrusted = true,
    isOnline = true,
    lastSeenAt = 0L,
    authorAvatarB64 = null,
)
