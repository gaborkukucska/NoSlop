package com.noslop.app.mesh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavior-locking tests for the gossip firewall pipeline in [GossipService.processIncoming]:
 * TTL (hops) expiry, duplicate suppression (bounded LRU), and per-sender rate limiting.
 *
 * WHY THIS FILE EXISTS:
 * These three guards are what stop the mesh from looping packets forever, reprocessing duplicates,
 * and being flooded by a single peer. They are protocol-defining behavior — a cross-platform node
 * MUST enforce the same drops or it will either melt the network or diverge from peers. We pin the
 * exact thresholds (DEFAULT_MAX_HOPS=6, dedup cap 1000 / evict 100, 20 packets / 10s / sender) here.
 *
 * WHY no Robolectric: `processIncoming` touches no `android.util.Base64`; its only Android dependency
 * is `Logger` -> `android.util.Log`, which is neutralized by `unitTests.isReturnDefaultValues`.
 *
 * WHY null deps: with `peerDao`/`transport` never initialized, the firewall step (needs `peerDao`)
 * and the forwarding step (needs `transport`) short-circuit harmlessly, so each call exercises ONLY
 * the TTL -> dedup -> rate-limit pipeline we want to assert.
 *
 * WHY reflection reset: [GossipService] is a singleton `object` with process-wide mutable state
 * (`processedPacketIds`, `senderRateLimits`). We clear it before each test for isolation.
 */
class GossipServiceTest {

    /** A public POST packet processes locally (returns true) when it survives every guard. */
    private fun post(id: String, sender: String, hops: Int? = null) =
        NetworkPacket(id = id, hops = hops, senderId = sender, type = "POST")

    @Before
    fun resetGossipState() {
        val cls = GossipService::class.java
        @Suppress("UNCHECKED_CAST")
        val processed = cls.getDeclaredField("processedPacketIds")
            .apply { isAccessible = true }.get(GossipService) as MutableSet<String>
        processed.clear()
        @Suppress("UNCHECKED_CAST")
        val limits = cls.getDeclaredField("senderRateLimits")
            .apply { isAccessible = true }.get(GossipService) as MutableMap<String, *>
        limits.clear()
        
        cls.getDeclaredField("peerDao").apply { isAccessible = true }.set(GossipService, null)
        cls.getDeclaredField("transport").apply { isAccessible = true }.set(GossipService, null)
        cls.getDeclaredField("localPublicKeyB64").apply { isAccessible = true }.set(GossipService, "")
        cls.getDeclaredField("getMeshFilterSettings").apply { isAccessible = true }.set(GossipService, null)
        cls.getDeclaredField("checkEntityExists").apply { isAccessible = true }.set(GossipService, null)
    }

    // --- TTL / hops ---

    @Test
    fun ttl_dropsPacketWithZeroHops() = runBlocking {
        assertFalse("hops == 0 must be dropped as TTL-expired",
            GossipService.processIncoming(post("ttl-0", "s-ttl", hops = 0)))
    }

    @Test
    fun ttl_dropsPacketWithNegativeHops() = runBlocking {
        assertFalse("negative hops must be dropped",
            GossipService.processIncoming(post("ttl-neg", "s-ttl", hops = -3)))
    }

    @Test
    fun ttl_allowsPacketWithRemainingHops() = runBlocking {
        assertTrue("a packet with hops > 0 survives the TTL guard",
            GossipService.processIncoming(post("ttl-ok", "s-ttl", hops = 1)))
    }

    @Test
    fun ttl_nullHopsDefaultsToMaxAndSurvives() = runBlocking {
        // WHY: a missing `hops` field defaults to DEFAULT_MAX_HOPS (6), so it is NOT treated as expired.
        assertTrue("null hops defaults to max and survives",
            GossipService.processIncoming(post("ttl-null", "s-ttl-null", hops = null)))
    }

    // --- Dedup ---

    @Test
    fun dedup_dropsSecondDeliveryOfSamePacketId() = runBlocking {
        assertTrue("first delivery processed", GossipService.processIncoming(post("dup-1", "s-a")))
        assertFalse("duplicate id dropped on second delivery",
            GossipService.processIncoming(post("dup-1", "s-a")))
    }

    @Test
    fun dedup_distinctIdsAreNotSuppressed() = runBlocking {
        assertTrue(GossipService.processIncoming(post("uniq-1", "s-a")))
        assertTrue("a different id is independent",
            GossipService.processIncoming(post("uniq-2", "s-a")))
    }

    @Test
    fun dedup_evictsOldestWhenSetIsFull() = runBlocking {
        // Fill the dedup set to its 1000-id cap, each from a UNIQUE sender so rate limiting never fires.
        for (i in 0 until 1000) {
            assertTrue(GossipService.processIncoming(post("p$i", "sender-$i")))
        }
        // The 1001st distinct packet trips the cap and evicts the oldest 100 ids (p0..p99).
        assertTrue(GossipService.processIncoming(post("trigger", "sender-trigger")))

        // p0 was evicted -> it is no longer "seen" and processes again.
        assertTrue("evicted oldest id is reprocessable",
            GossipService.processIncoming(post("p0", "sender-0")))
        // p500 is still within the retained window -> still suppressed as a duplicate.
        assertFalse("a still-retained id stays deduped",
            GossipService.processIncoming(post("p500", "sender-500")))
    }

    // --- Rate limit ---

    @Test
    fun rateLimit_blocks21stPacketFromSameSenderInWindow() = runBlocking {
        val sender = "flooder"
        // 20 distinct-id packets from one sender are allowed within the 10s window.
        for (i in 0 until 20) {
            assertTrue("packet $i within limit",
                GossipService.processIncoming(post("rl-$i", sender)))
        }
        // The 21st (distinct id, so not a dedup drop) exceeds 20/10s and is dropped.
        assertFalse("21st packet from same sender is rate-limited",
            GossipService.processIncoming(post("rl-20", sender)))
    }

    @Test
    fun rateLimit_isPerSender_notGlobal() = runBlocking {
        val noisy = "noisy"
        for (i in 0 until 20) {
            GossipService.processIncoming(post("n-$i", noisy))
        }
        assertFalse("noisy sender is now limited",
            GossipService.processIncoming(post("n-20", noisy)))
        // A different sender is unaffected by the noisy peer's limit.
        assertTrue("a quiet sender still passes",
            GossipService.processIncoming(post("q-0", "quiet")))
    }

    // --- Firewall & Mesh Filters ---

    @Test
    fun firewall_dropsUntrustedSender_butAllowsConnectionPackets() = runBlocking {
        val mockDao = io.mockk.mockk<com.noslop.app.data.PeerDao>(relaxed = true)
        io.mockk.coEvery { mockDao.getPeerByPublicKey("trusted") } returns com.noslop.app.data.Peer("trusted", "", "", "", isTrusted = true)
        io.mockk.coEvery { mockDao.getPeerByPublicKey("untrusted") } returns com.noslop.app.data.Peer("untrusted", "", "", "", isTrusted = false)
        io.mockk.coEvery { mockDao.getPeerByPublicKey("unknown") } returns null

        val mockTx = io.mockk.mockk<MeshTransport>(relaxed = true)
        GossipService.initialize(mockDao, mockTx, "local-key")

        assertTrue("Trusted sender allowed for POST", GossipService.processIncoming(post("fw-1", "trusted")))
        assertFalse("Untrusted sender dropped for POST", GossipService.processIncoming(post("fw-2", "untrusted")))
        assertFalse("Unknown sender dropped for POST", GossipService.processIncoming(post("fw-3", "unknown")))

        // Connection packets should bypass trust check
        val connReq = NetworkPacket(id = "fw-4", hops = 6, senderId = "unknown", type = "CONNECTION_REQUEST")
        assertTrue("CONNECTION_REQUEST allowed from unknown sender", GossipService.processIncoming(connReq))
    }

    @Test
    fun firewall_dropsFriendsOnlyFromTemporaryOrCreatorOrBurnable() = runBlocking {
        val prevEnforce = MeshPacketVerifier.enforce
        MeshPacketVerifier.enforce = false
        try {
            val mockDao = io.mockk.mockk<com.noslop.app.data.PeerDao>(relaxed = true)
            // Trusted direct friend
            io.mockk.coEvery { mockDao.getPeerByPublicKey("direct-friend") } returns com.noslop.app.data.Peer("direct-friend", "", "", "friend.onion", isTrusted = true, isTemporary = false, isCreator = false)
            // Temporary contact
            io.mockk.coEvery { mockDao.getPeerByPublicKey("temp-contact") } returns com.noslop.app.data.Peer("temp-contact", "", "", "temp.onion", isTrusted = true, isTemporary = true, isCreator = false)
            // Creator node
            io.mockk.coEvery { mockDao.getPeerByPublicKey("creator-node") } returns com.noslop.app.data.Peer("creator-node", "", "", "creator.onion", isTrusted = true, isTemporary = false, isCreator = true)

            val mockRepo = io.mockk.mockk<com.noslop.app.data.NoSlopRepository>(relaxed = true)
            io.mockk.coEvery { mockRepo.getAppSetting("contact_identity_direct-friend") } returns null
            io.mockk.coEvery { mockRepo.getAppSetting("contact_identity_temp-contact") } returns "burnable"
            io.mockk.coEvery { mockRepo.getAppSetting("contact_identity_creator-node") } returns "burnable"

            val mockTx = io.mockk.mockk<MeshTransport>(relaxed = true)
            io.mockk.every { mockTx.repository } returns mockRepo

            GossipService.initialize(mockDao, mockTx, "local-key")

            val friendsPostPayload = com.noslop.app.mesh.PostPayload(
                id = "f-1", authorId = "any", authorName = "any", authorPublicKey = "any",
                originNode = null, content = "friends only", timestamp = System.currentTimeMillis(),
                privacy = "friends"
            )
            val jsonPayload = com.google.gson.Gson().toJsonTree(friendsPostPayload)

            val packetFromFriend = NetworkPacket(id = "p-1", hops = 1, senderId = "direct-friend", type = "POST", payload = jsonPayload)
            val packetFromTemp = NetworkPacket(id = "p-2", hops = 1, senderId = "temp-contact", type = "POST", payload = jsonPayload)
            val packetFromCreator = NetworkPacket(id = "p-3", hops = 1, senderId = "creator-node", type = "POST", payload = jsonPayload)

            assertTrue("Direct friend can send friends-only post", GossipService.processIncoming(packetFromFriend))
            assertFalse("Temporary contact blocked from sending friends-only post", GossipService.processIncoming(packetFromTemp))
            assertFalse("Creator node blocked from sending friends-only post", GossipService.processIncoming(packetFromCreator))
        } finally {
            MeshPacketVerifier.enforce = prevEnforce
        }
    }

    @Test
    fun broadcast_excludesTemporaryContactsAndCreatorNodesForFriendsOnly() = runBlocking {
        val mockDao = io.mockk.mockk<com.noslop.app.data.PeerDao>(relaxed = true)
        val friendPeer = com.noslop.app.data.Peer("direct-friend", "", "", "friend.onion", isTrusted = true, isTemporary = false, isCreator = false)
        val tempPeer = com.noslop.app.data.Peer("temp-contact", "", "", "temp.onion", isTrusted = true, isTemporary = true, isCreator = false)
        val creatorPeer = com.noslop.app.data.Peer("creator-node", "", "", "creator.onion", isTrusted = true, isTemporary = false, isCreator = true)

        io.mockk.coEvery { mockDao.getAllPeersList() } returns listOf(friendPeer, tempPeer, creatorPeer)

        val mockRepo = io.mockk.mockk<com.noslop.app.data.NoSlopRepository>(relaxed = true)
        io.mockk.coEvery { mockRepo.getAppSetting("contact_identity_direct-friend") } returns null
        io.mockk.coEvery { mockRepo.getAppSetting("contact_identity_temp-contact") } returns "burnable"
        io.mockk.coEvery { mockRepo.getAppSetting("contact_identity_creator-node") } returns "burnable"
        io.mockk.coEvery { mockRepo.getAppSetting("hub_deployment_status") } returns null

        val sentOnions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockTx = io.mockk.mockk<MeshTransport>(relaxed = true)
        io.mockk.every { mockTx.repository } returns mockRepo
        io.mockk.coEvery { mockTx.sendPacket(capture(sentOnions), any(), any()) } returns true

        GossipService.initialize(mockDao, mockTx, "local-key")

        val friendsPostPayload = com.noslop.app.mesh.PostPayload(
            id = "f-bcast", authorId = "local-key", authorName = "me", authorPublicKey = "local-key",
            originNode = null, content = "friends only broadcast", timestamp = System.currentTimeMillis(),
            privacy = "friends"
        )
        val friendsPacket = NetworkPacket(id = "p-bcast", hops = 1, senderId = "local-key", type = "POST", payload = com.google.gson.Gson().toJsonTree(friendsPostPayload))

        GossipService.broadcast(friendsPacket)

        // Allow launched coroutines to complete
        kotlinx.coroutines.delay(100)

        assertTrue("Direct friend must receive friends-only broadcast", sentOnions.contains("friend.onion"))
        assertFalse("Temporary contact must NOT receive friends-only broadcast", sentOnions.contains("temp.onion"))
        assertFalse("Creator node must NOT receive friends-only broadcast", sentOnions.contains("creator.onion"))
    }

    @Test
    fun firewall_rateLimitsUntrustedDirectedMessages() = runBlocking {
        val mockDao = io.mockk.mockk<com.noslop.app.data.PeerDao>(relaxed = true)
        io.mockk.coEvery { mockDao.getPeerByPublicKey("untrusted-spammer") } returns null

        val mockTx = io.mockk.mockk<MeshTransport>(relaxed = true)
        GossipService.initialize(
            peerDao = mockDao,
            transport = mockTx,
            localPublicKeyB64 = "local-key",
            checkIsLocalUser = { it == "local-key" }
        )

        // 10 packets within 60s are allowed
        for (i in 0 until 10) {
            val dmPacket = NetworkPacket(
                id = "dm-ok-$i", hops = 1, senderId = "untrusted-spammer", targetUserId = "local-key",
                type = "MESSAGE", payload = com.google.gson.JsonObject()
            )
            assertTrue("DM $i within rate limit", GossipService.processIncoming(dmPacket))
        }

        // 11th packet from untrusted sender should be blocked by dedicated DM rate limit
        val dmExcess = NetworkPacket(
            id = "dm-excess", hops = 1, senderId = "untrusted-spammer", targetUserId = "local-key",
            type = "MESSAGE", payload = com.google.gson.JsonObject()
        )
        assertFalse("11th unauthenticated DM dropped by firewall rate limit", GossipService.processIncoming(dmExcess))
    }

    @Test
    fun mediaManager_isValidMediaId_validatesCharactersAndNoLeadingDot() {
        assertTrue(MediaManager.isValidMediaId("valid_media-123.mp4"))
        assertTrue(MediaManager.isValidMediaId("abc123XYZ_photo.jpg"))
        assertFalse("Lone dot rejected", MediaManager.isValidMediaId("."))
        assertFalse("Double dot rejected", MediaManager.isValidMediaId(".."))
        assertFalse("Leading dot rejected", MediaManager.isValidMediaId(".hidden"))
        assertFalse("Path traversal rejected", MediaManager.isValidMediaId("../secret"))
        assertFalse("Path traversal backslash rejected", MediaManager.isValidMediaId("..\secret"))
        assertFalse("Slash rejected", MediaManager.isValidMediaId("folder/file.mp4"))
        assertFalse("Empty string rejected", MediaManager.isValidMediaId(""))
    }

    @Test
    fun firewall_respectsMeshFilters() = runBlocking {
        val mockDao = io.mockk.mockk<com.noslop.app.data.PeerDao>(relaxed = true)
        io.mockk.coEvery { mockDao.getPeerByPublicKey("trusted") } returns com.noslop.app.data.Peer("trusted", "", "", "", isTrusted = true)
        
        val mockTx = io.mockk.mockk<MeshTransport>(relaxed = true)
        
        // Setup strict filters
        val strictFilters = com.noslop.app.data.MeshFilterSettings(
            allowIncomingTextPosts = false,
            allowIncomingClearnetShares = false,
            allowIncomingImagePosts = false,
            allowIncomingVideoPosts = false
        )
        
        GossipService.initialize(
            peerDao = mockDao,
            transport = mockTx,
            localPublicKeyB64 = "local-key",
            getMeshFilterSettings = { strictFilters },
            checkEntityExists = { _, _ -> false } // No anchors exist
        )

        val reactionPacket = NetworkPacket(
            id = "mf-1", hops = 6, senderId = "trusted", type = "REACTION",
            payload = com.google.gson.JsonObject().apply { addProperty("postId", "missing-post") }
        )
        assertFalse("Reaction dropped due to strict filter + missing anchor", GossipService.processIncoming(reactionPacket))

        val textPost = NetworkPacket(
            id = "mf-2", hops = 6, senderId = "trusted", type = "POST",
            payload = com.google.gson.JsonObject().apply { addProperty("content", "hello") }
        )
        assertFalse("Text post dropped due to strict filter", GossipService.processIncoming(textPost))
    }
}
