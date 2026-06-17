package com.noslop.mvp

/**
 * On-device proof of the mesh engine: builds three nodes — leafA, hub, leafB — over [InMemoryTransport]
 * in a star (each leaf links only to the hub; the leaves are NOT linked to each other). leafA broadcasts
 * a post; success means it arrived at leafB, which is only reachable through the hub — so this exercises
 * the full ADR-002 leaf→HUB→leaf relay path, the firewall, and the wire protocol together.
 */
object MeshSelfTest {
    suspend fun run(): String {
        val clock = { 0L } // a handful of packets; the rate-limit window is irrelevant here
        val tA = InMemoryTransport("leafA")
        val tHub = InMemoryTransport("hub")
        val tB = InMemoryTransport("leafB")
        InMemoryTransport.link(tA, tHub)
        InMemoryTransport.link(tB, tHub) // star: leafA ↔ hub ↔ leafB; leafA and leafB are NOT linked

        val sinkB = RecordingSink()
        val leafA = MeshNode("leafA", tA, RecordingSink(), MeshFirewall(clock))
        MeshNode("hub", tHub, RecordingSink(), MeshFirewall(clock))
        MeshNode("leafB", tB, sinkB, MeshFirewall(clock))

        val post = PostPayload(
            id = "selftest-post",
            authorId = "leafA",
            authorName = "leafA",
            authorPublicKey = "leafA",
            content = "relayed through the hub",
            timestamp = 0L,
        )
        leafA.broadcast(postPacket(senderId = "leafA", post = post))

        val arrived = sinkB.posts.any { it.id == "selftest-post" }
        return if (arrived) "Mesh ✓ — post relayed leafA→hub→leafB" else "Mesh self-test failed"
    }
}
