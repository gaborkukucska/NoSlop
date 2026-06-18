package com.noslop.mvp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A desktop **leaf** for testing leaf-to-leaf DMs through a blind hub — it stands in for a second phone
 * (e.g. "Gabour") without needing a second device. It connects to a locally-running hub over loopback,
 * prints its contact QR (scan it on the phone to add it as a DM contact), and on each incoming DM prints
 * the plaintext and sends back an encrypted auto-reply. The hub only ever relays ciphertext.
 *
 * Give it its OWN identity (distinct nodeId) via NOSLOP_HOME — the runLeaf task sets it. Run (hub up first):
 *   ./gradlew :composeApp:runHub  --args="9876 tor"      # terminal 1 (or plain "9876")
 *   ./gradlew :composeApp:runLeaf --args="9876"          # terminal 2
 */
fun main(args: Array<String>) = runBlocking {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 9876
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val identity = loadIdentity(HandleStore.load().let { if (it == "hub") "creator" else it })
    val client = MeshClient(identity, null, scope)

    client.onDm = { fromNodeId, fromEncPub, text ->
        println("\n[leaf] 📩 DM from ${fromNodeId.take(16)}…: \"$text\"")
        scope.launch {
            val ok = client.sendDm(MeshContact("them", fromNodeId, fromEncPub), "ack from the creator: \"$text\" ✓")
            println("[leaf] ↩︎ auto-reply ${if (ok) "sent" else "FAILED (dm crypto?)"}")
        }
    }

    client.connect("127.0.0.1", port, null)
    println("[leaf] connected to hub 127.0.0.1:$port as ${identity.handle} — node ${client.nodeId.take(16)}…")
    val card = client.contactCard()
    println("\n[leaf] Scan this CONTACT QR on the phone (DM section) to message this leaf:\n")
    println(QrConsole.render(card.toQrJson()))
    println(card.toQrJson() + "\n")

    while (true) { delay(60_000); println("[leaf] alive — links=${client.linkCount}") }
}
