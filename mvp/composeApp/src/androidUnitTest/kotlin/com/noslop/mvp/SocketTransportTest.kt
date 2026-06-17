package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Integration test for [SocketTransport]: packets actually traverse real localhost TCP connections between
 * three [MeshNode]s (hub listening, two leaves dialing in). The leaves are NOT connected to each other, so
 * leafB receiving leafA's post proves it was relayed over the wire by the hub — the ADR-002 path, now over
 * real sockets instead of [InMemoryTransport].
 *
 * Runs on the JVM (the desktop/HUB platform) with real socket timing via [runBlocking]; the [SocketTransport]
 * class itself is commonMain and compiles for iOS/Native too. Cross-platform routing is covered by MeshNodeTest.
 */
class SocketTransportTest {

    private fun post(id: String, content: String) = PostPayload(
        id = id, authorId = "leafA", authorName = "leafA", authorPublicKey = "leafA",
        content = content, timestamp = 0L,
    )

    @Test
    fun postGossipsLeafToHubToLeaf_overRealTcp() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val hubT = SocketTransport("hub", scope)
        val leafAT = SocketTransport("leafA", scope)
        val leafBT = SocketTransport("leafB", scope)
        val sinkB = RecordingSink()
        try {
            val port = hubT.listen("127.0.0.1", 0)
            MeshNode("hub", hubT, RecordingSink(), MeshFirewall(now = { 0L }))
            val leafA = MeshNode("leafA", leafAT, RecordingSink(), MeshFirewall(now = { 0L }))
            MeshNode("leafB", leafBT, sinkB, MeshFirewall(now = { 0L }))

            leafAT.connect("127.0.0.1", port)
            leafBT.connect("127.0.0.1", port)
            withTimeout(5_000) { while (hubT.links.size < 2) delay(20) } // both leaves linked to the hub

            leafA.broadcast(postPacket("leafA", post("p1", "over the wire")))

            withTimeout(5_000) { while (sinkB.posts.isEmpty()) delay(20) }
            assertEquals(1, sinkB.posts.size, "leafB received the post relayed by the hub over TCP")
            assertEquals("over the wire", sinkB.posts.first().content)
        } finally {
            hubT.close(); leafAT.close(); leafBT.close()
        }
    }
}
