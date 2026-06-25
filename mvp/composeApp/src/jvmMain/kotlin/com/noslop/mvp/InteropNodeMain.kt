package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A desktop **test node** that stands in for the iPhone to validate Android interop without the phone: it
 * connects to a locally-running bridge, then (signed with its own identity, exactly like the iOS app would)
 * sends Gabor a CONNECTION_REQUEST + a DM through the bridge. Proves the full pipeline — signed handshake,
 * id/protocol translation, e2e DM — against a real Android node.
 *
 * Its own identity via NOSLOP_HOME (so it's not the bridge). Run (bridge must be up):
 *   ./gradlew :composeApp:runInteropNode
 */
fun main() = runBlocking {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val identity = loadIdentity("noslop-desktop")
    val client = MeshClient(identity, null, scope)

    // Gabor's contact (from his QR; onion = the one the bridge last learned from the phone). Identity/enc key
    // are stable; only his onion rotates — update GABOR_ONION if his node has a new address.
    val gabor = MeshContact(
        handle = "Gabby",
        nodeId = NodeId.androidToHex("MCowBQYDK2VwAyEAlzKv27Ry67yMPKBkBk7dIBLy9TqclTiEXy2zuhxF1aA="),
        encPublicKeyX509B64 = "MCowBQYDK2VuAyEAJvSMafyFL+btlHuwUWRl50wYwKRcq0mFX4ueT24hU0w=",
        onionAddress = "xtreloutbkax643h5gv2nblq2zrhlexk6sisvideqvqoynubgq7snmyd.onion",
    )
    val bridgeOnion = "latzrm6zdbeph5pl2morw5jcgyqmur6t7uxdmoqg3vrvg2hehb7e4oid.onion"

    client.onDm = { from, _, text, _ -> println("\n[desktop] 📩 DM FROM GABOR (${from.take(12)}…): \"$text\"\n") }

    client.connect("127.0.0.1", 9876, null)
    println("[desktop] connected to bridge as ${identity.handle} — node ${client.nodeId.take(16)}…")
    println("[desktop] my Android id (what Gabor will see): ${NodeId.hexToAndroid(client.nodeId).take(20)}…")

    // 1) Tell the bridge to dial Gabor + keep sending the signed connection request.
    scope.launch {
        client.requestDial(gabor)
        repeat(12) {
            val ok = client.sendConnectionRequest(gabor, bridgeOnion)
            println("[desktop] → CONNECTION_REQUEST to Gabor (signed=$ok, attempt ${it + 1})")
            delay(15_000)
        }
    }

    // 2) Once he's had a chance to accept, send DMs too.
    scope.launch {
        delay(45_000)
        repeat(8) {
            val ok = client.sendDm(gabor, "Hey Gabor — NoSlop desktop test node here 👋 (cross-platform DM test)") != null
            println("[desktop] → DM to Gabor (sent=$ok, attempt ${it + 1})")
            delay(30_000)
        }
    }

    while (true) { delay(60_000); println("[desktop] alive — links=${client.linkCount}") }
}
