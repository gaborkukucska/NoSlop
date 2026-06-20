package com.noslop.mvp

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Root MVP UI: three tabs — Identity, Feed, and Mesh (live hub connection). */
@Composable
fun App() {
    MaterialTheme {
        var tab by remember { mutableStateOf(0) }
        // Mesh state + the live connection live here (above the tab switch) so they survive tab changes —
        // switching to Feed and back keeps the scanned hub + the open link instead of tearing it down.
        val meshScope = rememberCoroutineScope()
        val mesh = remember { MeshUiState(meshScope) }
        // Start dialing the saved hub at launch (any tab) so the Tor circuit pre-builds in the background.
        LaunchedEffect(Unit) { mesh.autoConnectOnce() }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Identity") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Feed") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Mesh") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("DMs") })
                }
                when (tab) {
                    0 -> IdentityScreen()
                    1 -> FeedScreen()
                    2 -> MeshScreen(mesh)
                    else -> DmScreen(mesh)
                }
            }
        }
    }
}

@Composable
private fun IdentityScreen() {
    // Handle + keypair both persist (NSUserDefaults / SharedPreferences and Keychain / Keystore).
    var handle by remember { mutableStateOf(HandleStore.load()) }
    var identity by remember { mutableStateOf(loadIdentity(handle)) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NoSlop Identity", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Persisted on this device", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        OutlinedTextField(
            value = handle,
            onValueChange = { new ->
                val clean = new.filter { it.isLetterOrDigit() || it == '_' }.take(20)
                handle = clean
                HandleStore.save(clean)
                identity = identity.copy(handle = clean.ifBlank { "anon" })
            },
            label = { Text("Handle") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Display name", "${identity.handle}.${identity.tripcode}")
                Field("Tripcode", identity.tripcode)
                Field("Onion", identity.onionAddress)
                Field("Public key", identity.publicKeyHex)
                val signOk = remember { Ed25519SelfTest.run() }
                Text(
                    if (signOk) "Ed25519 sign/verify ✓ (RFC 8032 conformant)" else "Ed25519 self-test failed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (signOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val dmOk = remember { DmSelfTest.run() }
                Text(
                    if (dmOk) "DM crypto ✓ (X25519 · SHA3-256 · ChaCha20-Poly1305)" else "DM crypto self-test failed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (dmOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                val storage = remember { runStorageDemo(identity) }
                Text(
                    storage,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (storage.startsWith("SQLDelight ✓")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                var mesh by remember { mutableStateOf("Mesh self-test running…") }
                LaunchedEffect(Unit) { mesh = MeshSelfTest.run() }
                Text(
                    mesh,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (mesh.startsWith("Mesh ✓")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                if (!identity.isRealKeypair) {
                    Text(
                        "⚠︎ ephemeral fallback key — secure storage not wired here.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Button(onClick = { identity = regenerateIdentity(handle.ifBlank { "anon" }) }) {
            Text("Regenerate keypair")
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FeedScreen() {
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val repo = remember { FeedRepository() }
    var stories by remember { mutableStateOf<List<FeedStory>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var videoUrl by remember { mutableStateOf("https://www.w3schools.com/html/mov_bbb.mp4") }
    // Feed curation (the "NoSlop" core). Persisted raw text; applied live.
    val saved = remember { SavedFilters.loadTexts() }
    var mutedText by remember { mutableStateOf(saved.first) }
    var requiredText by remember { mutableStateOf(saved.second) }
    var showFilters by remember { mutableStateOf(false) }

    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { repo.loadFeed() }
                .onSuccess { stories = it }
                .onFailure { error = it.message ?: "Failed to load feed" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() } // auto-load on first open

    val filters = FeedFilters.from(mutedText, requiredText)
    val shown = filters.apply(stories)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Media playback: play a clearnet video URL full-screen via the native player.
        if (VideoPlayer.isAvailable) {
            OutlinedTextField(videoUrl, { videoUrl = it }, label = { Text("Video URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { focus.clearFocus(); VideoPlayer.play(videoUrl.trim()) }, enabled = videoUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("▶  Play video")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { load() }, enabled = !loading, modifier = Modifier.weight(1f)) { Text(if (loading) "Loading…" else "Refresh feed") }
            Button(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "Hide filters" else "🧹 Filters") }
        }
        // Curation panel: mute words (hide) + must-have words (only show matching). Persisted as you type.
        if (showFilters) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        mutedText, { mutedText = it; SavedFilters.saveTexts(it, requiredText) },
                        label = { Text("Mute words (comma-separated)") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
                    )
                    OutlinedTextField(
                        requiredText, { requiredText = it; SavedFilters.saveTexts(mutedText, it) },
                        label = { Text("Must-have words (optional)") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
                    )
                    Text("Hide anything containing a mute word; with must-have words set, show only matches.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (stories.isNotEmpty()) {
            val muted = stories.size - shown.size
            Text("Showing ${shown.size} of ${stories.size}${if (muted > 0) " · $muted filtered" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        when {
            loading && stories.isEmpty() -> CircularProgressIndicator()
            error != null && stories.isEmpty() -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.source.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(s.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            if (s.excerpt.isNotBlank()) {
                                Text(s.excerpt, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Live mesh: dial a HUB and gossip through it. Connecting opens a real TCP link to an always-on hub
 * (ADR-002); posting broadcasts to the hub, which relays to other connected nodes, and posts from others
 * arrive here. This is an iPhone participating in the mesh via a hub it can't be itself.
 */
/**
 * Mesh UI state + the live [MeshClient], held above the tab switch (see [App]) so the scanned hub and the
 * open link survive switching tabs. One client for the app's lifetime; the transport read-loops run on the
 * app-scoped [scope], so the connection isn't torn down when the Mesh tab is hidden.
 */
private class MeshUiState(private val scope: CoroutineScope) {
    private val identity = loadIdentity(HandleStore.load())
    private val client = MeshClient(identity, MeshStoreProvider.get(), scope)
    val received = mutableStateListOf<PostPayload>()
    val savedNodes = mutableStateListOf<MeshInvite>()      // persisted hubs, newest first (see SavedNodes)
    val contacts = mutableStateListOf<MeshContact>()       // DM contacts (in-memory for now)
    val dmLog = mutableStateListOf<DmEntry>()              // DM history this session, newest first
    // Reactions: aggregated counts keyed "postId|emoji", plus whether *I* reacted (for the toggle/highlight).
    val reactionCounts = mutableStateMapOf<String, Int>()
    val myReactions = mutableStateMapOf<String, Boolean>()
    private val reactionSeen = mutableSetOf<String>()      // "postId|emoji|authorId" — dedup across gossip echoes
    val reactionEmojis = listOf("👍", "❤️", "😂", "🔥")
    var host by mutableStateOf("127.0.0.1")
    var port by mutableStateOf("9876")
    var viaTor by mutableStateOf(false)
    var status by mutableStateOf("Not connected")
    var connected by mutableStateOf(false)
    var draft by mutableStateOf("Hello from my iPhone 👋")
    var dmDraft by mutableStateOf("")
    var selectedContact by mutableStateOf<MeshContact?>(null)
    private var autoTried = false
    var connecting by mutableStateOf(false)        // observable: a connect attempt is in flight
    private var activeInvite: MeshInvite? = null   // what the reconnect monitor re-dials
    private var connectJob: kotlinx.coroutines.Job? = null  // in-flight dial, cancelled when retargeting
    private var monitorStarted = false

    val linkCount: Int get() = client.linkCount

    init {
        client.onPost = { post -> scope.launch { if (received.none { it.id == post.id }) received.add(0, post) } }
        client.onDm = { fromNodeId, fromEncPub, text ->
            scope.launch {
                // Learn the sender as a contact (so the user can reply) if we don't have them yet — persisted.
                if (contacts.none { it.nodeId == fromNodeId }) {
                    val c = MeshContact("${fromNodeId.take(8)}…", fromNodeId, fromEncPub)
                    SavedContacts.add(c); reloadContacts()
                    if (selectedContact == null) selectedContact = c
                }
                val name = contacts.firstOrNull { it.nodeId == fromNodeId }?.handle ?: fromNodeId.take(8)
                dmLog.add(0, DmEntry(from = name, text = text, mine = false))
            }
        }
        // For Android DMs (which omit the sender's enc-key in the payload), resolve it from a stored contact.
        client.resolveEncPub = { id -> SavedContacts.list().firstOrNull { it.nodeId == id }?.encPublicKeyX509B64 }
        client.onReaction = { r -> scope.launch { applyReaction(r.postId, r.reactionType, r.authorId, r.action != "remove") } }
        // Incoming handshake: add/learn the peer as a contact; for a request, auto-accept (reply USER_HANDSHAKE).
        client.onHandshake = { type, hs ->
            scope.launch {
                val contact = MeshContact(
                    handle = hs.fromUsername.ifBlank { hs.fromUserId.take(8) },
                    nodeId = NodeId.androidToHex(hs.fromUserId),
                    encPublicKeyX509B64 = hs.fromEncryptionPublicKey ?: "",
                    onionAddress = hs.fromHomeNode.takeIf { it.isNotBlank() },
                )
                if (contact.encPublicKeyX509B64.isNotBlank() && contacts.none { it.nodeId == contact.nodeId }) {
                    SavedContacts.add(contact); reloadContacts()
                }
                // Either direction of a handshake means the peer is mutually linked — stop re-sending requests.
                SavedContacts.markAccepted(contact.nodeId); reloadContacts()
                if (type == "CONNECTION_REQUEST") {
                    activeInvite?.host?.let { home -> client.sendUserHandshake(contact, home) }  // accept them back
                    status = "🤝 ${contact.handle} connected — added to contacts"
                } else {
                    status = "🤝 ${contact.handle} accepted your request ✓"
                }
            }
        }
        reloadSaved()
        reloadContacts()
        // Prefill the fields with the most recent saved hub so the UI shows what auto-connect will dial.
        savedNodes.firstOrNull()?.let { host = it.host; port = it.port.toString(); viaTor = it.tor }
    }

    private fun reloadSaved() { savedNodes.clear(); savedNodes.addAll(SavedNodes.list()) }

    /**
     * On first open, auto-reconnect to the most recent saved hub — so a paired phone never has to re-scan.
     * No-op if nothing's saved yet. Additional saved hubs are reachable by tapping them in the list.
     */
    fun autoConnectOnce() {
        if (autoTried) return
        autoTried = true
        val recent = savedNodes.firstOrNull()
        if (recent == null) { status = "No saved hub yet — scan a node QR to connect"; return }
        connect(recent, save = false)
    }

    /** Connect using whatever's in the host/port/Tor fields (the manual "Connect" button). */
    fun connectNow() {
        // Tolerate a full invite URI pasted into the host field instead of scanning.
        val pasted = MeshInvite.parse(host.trim())
        val invite = pasted ?: MeshInvite(host.trim(), port.toIntOrNull() ?: 9876, viaTor)
        connect(invite, save = true)
    }

    /** Connect to a specific saved/scanned hub. Persists it on success when [save]. */
    fun connect(invite: MeshInvite, save: Boolean) {
        activeInvite = invite
        host = invite.host; port = invite.port.toString(); viaTor = invite.tor
        startReconnectMonitor()
        connectJob?.cancel()                   // cancel any in-flight dial (e.g. to a now-stale hub) and retarget
        connecting = true
        connectJob = scope.launch {
            try {
                if (invite.tor) {
                    if (!TorService.isAvailable) { status = "Tor not available on this device"; return@launch }
                    status = "Starting Tor…"
                    TorService.start()
                    // Tor's SOCKS rejects until it's bootstrapped + the onion circuit is built, so retry the
                    // connection through SOCKS until it succeeds — no fragile control-port readiness gate.
                    var waited = 0
                    while (waited < 300_000) {
                        if (activeInvite != invite) { status = "Disconnected"; return@launch } // cancelled by disconnect / new dial
                        val sp = TorService.socksPort()
                        val prog = TorService.bootstrapProgress()
                        val phase = when { prog in 1..99 -> "bootstrapping $prog%"; prog >= 100 -> "building circuit"; else -> "starting Tor" }
                        status = "Connecting via Tor… $phase (${waited / 1000}s)"
                        if (sp > 0) {
                            val ok = runCatching { client.connect(invite.host, invite.port, SocksProxy("127.0.0.1", sp)) }
                                .map { client.linkCount > 0 }.getOrDefault(false)
                            if (ok) { onLinked(invite, save, "over Tor"); return@launch }
                        }
                        delay(4000); waited += 4000
                    }
                    status = "Couldn't reach the hub over Tor (5 min) — will keep retrying. tor: ${TorService.status()}"
                    return@launch
                }
                status = "Connecting…"
                runCatching { client.connect(invite.host, invite.port, null) }
                    .onSuccess { if (client.linkCount > 0) onLinked(invite, save, "") else { connected = false; status = "No link" } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; status = "Error: ${it.message}" }
            } finally {
                if (activeInvite == invite) connecting = false   // only the latest dial owns the flag
            }
        }
    }

    /**
     * Keep the link alive: poll for drops and auto-redial the active hub, so a paired phone stays connected
     * across Tor circuit churn / app resumes without the user touching anything. Eager — the first connect
     * starts it, then it runs for the app's lifetime.
     */
    private fun startReconnectMonitor() {
        if (monitorStarted) return
        monitorStarted = true
        scope.launch {
            while (true) {
                delay(6000)
                if (client.linkCount == 0) {
                    if (connected) connected = false      // reflect a dropped link in the UI
                    val inv = activeInvite
                    if (inv != null && !connecting) { status = "Link dropped — reconnecting…"; connect(inv, save = false) }
                }
            }
        }
    }

    private fun onLinked(invite: MeshInvite, save: Boolean, how: String) {
        connected = true
        status = "Connected ✓ ${how.ifBlank { "" }}— ${client.linkCount} link(s)".replace("  ", " ")
        if (save) { SavedNodes.add(invite.toUri()); reloadSaved() }
        // Re-handshake every Android contact through the (now-connected) bridge: dial + signed CONNECTION_REQUEST.
        contacts.filter { it.onionAddress != null }.forEach { handshakeAndroidContact(it) }
    }

    /** User-initiated disconnect: stop auto-reconnect and drop the link, keeping the client reusable. */
    fun disconnect() {
        activeInvite = null            // stop the reconnect monitor from re-dialing
        connectJob?.cancel()           // cancel any in-flight dial
        connecting = false
        scope.launch {
            client.disconnectLinks()
            connected = false
            status = "Disconnected"
        }
    }

    fun removeNode(invite: MeshInvite) { SavedNodes.remove(invite); reloadSaved() }

    /** Pin a saved hub as the launch default by moving it to the front (what autoConnectOnce dials). */
    fun setDefaultHub(invite: MeshInvite) { SavedNodes.add(invite.toUri()); reloadSaved() }

    fun onScan(scanned: String?) {
        if (scanned == null) { status = "Scan cancelled"; return }
        // A hub invite (noslop://…) connects; a contact card ({…}) is added for DMs.
        MeshInvite.parse(scanned)?.let { invite ->
            status = "Scanned ${if (invite.tor) "onion" else invite.host} — connecting…"
            connect(invite, save = true); return
        }
        MeshContact.parse(scanned)?.let { contact -> addContact(contact); return }
        status = "Not a NoSlop code"
    }

    private fun reloadContacts() { contacts.clear(); contacts.addAll(SavedContacts.list()) }

    /** My shareable contact card as a QR string — Android-compatible shape so both iOS *and* Android can scan it. */
    fun myContactQr(): String = client.contactCard().toAndroidQrJson()
    fun myHandle(): String = client.contactCard().handle

    fun addContact(contact: MeshContact) {
        SavedContacts.add(contact); reloadContacts()
        selectedContact = contacts.firstOrNull { it.nodeId == contact.nodeId }
        status = "Added contact ${contact.handle}"
        if (connected) handshakeAndroidContact(contact)
    }

    /**
     * Bring an Android contact online through the bridge: tell the bridge to dial them, then send a signed
     * CONNECTION_REQUEST (the handshake Android needs before accepting DMs). Retried a few times because the
     * bridge's dial to their onion takes a little while — the request must arrive after their link is up.
     * `fromHomeNode` is the bridge's onion (what we're connected through), so their replies route back to us.
     */
    private val handshaking = mutableSetOf<String>()   // contacts with a handshake loop in flight (no stacking)

    private fun handshakeAndroidContact(contact: MeshContact) {
        val onion = contact.onionAddress ?: return
        val homeNode = activeInvite?.host ?: return       // the bridge onion we're connected through
        // Already handshake-complete (persisted), or a loop is already running for them — don't re-spam.
        if (contact.accepted || !handshaking.add(contact.nodeId)) return
        scope.launch {
            try {
                client.requestDial(contact)
                repeat(5) {
                    // Stop as soon as they've accepted (their USER_HANDSHAKE flipped the persisted flag).
                    if (contacts.firstOrNull { it.nodeId == contact.nodeId }?.accepted == true) return@launch
                    val ok = client.sendConnectionRequest(contact, homeNode)
                    if (!ok) { status = "Can't sign handshake (identity key)"; return@launch }
                    delay(12_000)                          // give the bridge time to link their onion
                }
            } finally {
                handshaking.remove(contact.nodeId)
            }
        }
    }

    /** Give a contact a meaningful name (keyed by node id; survives relaunches). */
    fun renameContact(contact: MeshContact, newName: String) {
        val renamed = contact.copy(handle = newName.trim().ifBlank { contact.handle })
        SavedContacts.add(renamed); reloadContacts()
        if (selectedContact?.nodeId == contact.nodeId) selectedContact = renamed
    }

    fun removeContact(contact: MeshContact) {
        SavedContacts.remove(contact); reloadContacts()
        if (selectedContact?.nodeId == contact.nodeId) selectedContact = contacts.firstOrNull()
    }

    fun sendDm() {
        val to = selectedContact ?: return
        val text = dmDraft.trim()
        if (text.isEmpty()) return
        scope.launch {
            val ok = client.sendDm(to, text)
            if (ok) { dmLog.add(0, DmEntry(from = to.handle, text = text, mine = true)); dmDraft = "" }
            else status = "DM failed (no link or bad key)"
        }
    }

    fun publish() {
        scope.launch { client.publish(draft); status = "Posted ✓ — ${client.linkCount} link(s)" }
    }

    /** Fold one reaction (ours or a peer's) into the aggregated counts, deduped by (post, emoji, author). */
    private fun applyReaction(postId: String, type: String, authorId: String, add: Boolean) {
        val key = "$postId|$type"
        val dedup = "$key|$authorId"
        if (add) {
            if (reactionSeen.add(dedup)) reactionCounts[key] = (reactionCounts[key] ?: 0) + 1
        } else {
            if (reactionSeen.remove(dedup)) reactionCounts[key] = ((reactionCounts[key] ?: 1) - 1).coerceAtLeast(0)
        }
        if (authorId == client.androidId) myReactions[key] = add
    }

    /** Toggle my reaction on a post: broadcast a signed add/remove and reflect it locally right away. */
    fun react(post: PostPayload, type: String) {
        scope.launch {
            val on = !(myReactions["${post.id}|$type"] ?: false)
            if (client.sendReaction(post.id, type, add = on)) applyReaction(post.id, type, client.androidId, on)
            else status = "Reaction failed (identity key)"
        }
    }
}

/** One line of DM history for the UI. [mine] = we sent it. */
data class DmEntry(val from: String, val text: String, val mine: Boolean)

/**
 * Live mesh: dial a HUB and gossip through it. Connecting opens a real TCP link to an always-on hub
 * (ADR-002); posting broadcasts to the hub, which relays to other connected nodes, and posts from others
 * arrive here. Stateless view over [MeshUiState] so the connection persists across tab switches.
 */
@Composable
private fun MeshScreen(state: MeshUiState) {
    LaunchedEffect(Unit) { state.autoConnectOnce() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mesh — connect to a HUB", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (QrScanner.isAvailable) {
            // Always enabled — you can add/switch hubs while already connected (no need to disconnect first).
            Button(
                onClick = { focus.clearFocus(); QrScanner.scan { scanned -> state.onScan(scanned) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📷  Scan node QR to connect") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                state.host, { state.host = it }, label = { Text("Hub host / onion URI") }, singleLine = true, modifier = Modifier.weight(2f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            )
            OutlinedTextField(
                state.port, { state.port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = state.viaTor, onCheckedChange = { state.viaTor = it })
            Text("Via Tor (for .onion hubs, from anywhere)", fontSize = 13.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { focus.clearFocus(); state.connectNow() }, enabled = !state.connected && !state.connecting, modifier = Modifier.weight(1f)) {
                Text(if (state.connected) "Connected" else if (state.connecting) "Connecting…" else "Connect to hub")
            }
            if (state.connected || state.connecting) {
                Button(onClick = { focus.clearFocus(); state.disconnect() }) { Text("Disconnect") }
            }
        }
        Text(
            state.status,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.status.startsWith("Connected")) MaterialTheme.colorScheme.primary
            else if (state.status.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )

        // Saved hubs — persisted across launches. The first (★) is the launch default that autoConnectOnce
        // dials; "Default" pins another there. Any can be tapped to (re)connect. So a paired phone never re-scans.
        if (state.savedNodes.isNotEmpty()) {
            Text("Saved hubs (${state.savedNodes.size}) — ★ is the launch default", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            state.savedNodes.forEachIndexed { index, node ->
                val isDefault = index == 0
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (isDefault) "★ " else "") + (if (node.tor) "🧅 " else "🌐 ") + node.host.let { if (it.length > 20) it.take(9) + "…" + it.takeLast(7) else it },
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            )
                            Text(":${node.port}${if (node.tor) " · Tor" else " · LAN"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        if (!isDefault) Button(onClick = { state.setDefaultHub(node) }) { Text("★") }
                        Button(onClick = { state.connect(node, save = true) }) { Text("Connect") }
                        Button(onClick = { state.removeNode(node) }) { Text("✕") }
                    }
                }
            }
        }
        OutlinedTextField(state.draft, { state.draft = it }, label = { Text("Post to the mesh") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { state.publish() }, enabled = state.connected) { Text("Broadcast post") }

        Text("Received from mesh (${state.received.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.received) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.authorName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(p.content, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.reactionEmojis.forEach { emoji ->
                                val key = "${p.id}|$emoji"
                                val count = state.reactionCounts[key] ?: 0
                                val mine = state.myReactions[key] == true
                                Text(
                                    text = if (count > 0) "$emoji $count" else emoji,
                                    fontSize = 13.sp,
                                    color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clickable(enabled = state.connected) { state.react(p, emoji) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Direct messages: end-to-end encrypted leaf-to-leaf DMs relayed by the (blind) hub. Scan a peer's contact QR
 * to add them, pick a contact, and send. Incoming DMs are decrypted locally and auto-add the sender as a
 * contact so you can reply. Shares [MeshUiState] (hence the live link) with the Mesh tab.
 */
@Composable
private fun DmScreen(state: MeshUiState) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var showMyQr by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Direct messages", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                if (state.connected) "Linked — DMs route through the hub, end-to-end encrypted (the hub can't read them)."
                else "Connect to a hub on the Mesh tab first.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
            )
        }
        // My contact QR — others scan this to add me (carries my handle, node id, DM public key).
        item {
            Button(onClick = { showMyQr = !showMyQr }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showMyQr) "Hide my contact QR" else "📇  Show my contact QR")
            }
            if (showMyQr) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("I am: ${state.myHandle()}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Image(
                            painter = io.github.alexzhirkevich.qrose.rememberQrCodePainter(state.myContactQr()),
                            contentDescription = "My contact QR",
                            modifier = Modifier.size(220.dp),
                        )
                        Text("Have someone scan this to add you as a DM contact.", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        item {
            if (QrScanner.isAvailable) {
                Button(onClick = { QrScanner.scan { s -> state.onScan(s) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("📷  Scan a contact QR")
                }
            }
        }
        if (state.contacts.isNotEmpty()) {
            item { Text("Contacts (${state.contacts.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
            items(state.contacts) { c ->
                val selected = state.selectedContact?.nodeId == c.nodeId
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { state.selectedContact = c; renaming = "" }, modifier = Modifier.weight(1f)) {
                        Text((if (selected) "● " else "○ ") + c.handle + "  ·  " + c.nodeId.take(8) + "…", fontSize = 13.sp)
                    }
                    Button(onClick = { state.removeContact(c) }) { Text("✕") }
                }
            }
        }
        // Compose to + rename the selected contact.
        state.selectedContact?.let { to ->
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        renaming, { renaming = it }, label = { Text("Name for ${to.handle}") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { state.renameContact(to, renaming); renaming = ""; focus.clearFocus() }, enabled = renaming.isNotBlank()) { Text("Rename") }
                }
                Text("To: ${to.handle}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        state.dmDraft, { state.dmDraft = it }, label = { Text("Message") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { state.sendDm(); focus.clearFocus() }),
                    )
                    Button(onClick = { state.sendDm(); focus.clearFocus() }, enabled = state.connected && state.dmDraft.isNotBlank()) { Text("Send") }
                }
            }
        }
        item { Text("Messages (${state.dmLog.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        items(state.dmLog) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        if (e.mine) "me → ${e.from}" else "${e.from} → me",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (e.mine) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    )
                    Text(e.text, fontSize = 14.sp)
                }
            }
        }
    }
}
