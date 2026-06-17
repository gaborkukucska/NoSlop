package com.noslop.mvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.launch

/** Root MVP UI: three tabs — Identity, Feed, and Mesh (live hub connection). */
@Composable
fun App() {
    MaterialTheme {
        var tab by remember { mutableStateOf(0) }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Identity") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Feed") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Mesh") })
                }
                when (tab) {
                    0 -> IdentityScreen()
                    1 -> FeedScreen()
                    else -> MeshScreen()
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
    val repo = remember { FeedRepository() }
    var stories by remember { mutableStateOf<List<FeedStory>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { load() }, enabled = !loading) { Text(if (loading) "Loading…" else "Refresh feed") }
        when {
            loading && stories.isEmpty() -> CircularProgressIndicator()
            error != null && stories.isEmpty() -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stories) { s ->
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
@Composable
private fun MeshScreen() {
    val scope = rememberCoroutineScope()
    val identity = remember { loadIdentity(HandleStore.load()) }
    val store = remember { MeshStoreProvider.get() }
    val client = remember { MeshClient(identity, store, scope) }
    val received = remember { mutableStateListOf<PostPayload>() }

    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("9876") }
    var status by remember { mutableStateOf("Not connected") }
    var connected by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("Hello from my iPhone 👋") }

    DisposableEffect(Unit) {
        // Sink runs on a background coroutine; bounce UI updates onto the (main) composition scope.
        client.onPost = { post -> scope.launch { if (received.none { it.id == post.id }) received.add(0, post) } }
        onDispose { client.close() }
    }

    // Auto-connect to the default hub on open — the "just install the app" path (a real product ships a
    // default/public hub address here; the user does nothing). Manual reconnect via the button below.
    LaunchedEffect(Unit) {
        if (!connected) {
            status = "Connecting…"
            runCatching { client.connect(host.trim(), port.toIntOrNull() ?: 9876) }
                .onSuccess { connected = client.linkCount > 0; status = if (connected) "Connected ✓ — ${client.linkCount} link(s)" else "No link" }
                .onFailure { status = "Error: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mesh — connect to a HUB", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("Hub host") }, singleLine = true, modifier = Modifier.weight(2f))
            OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(
            onClick = {
                status = "Connecting…"
                scope.launch {
                    runCatching { client.connect(host.trim(), port.toIntOrNull() ?: 9876) }
                        .onSuccess { connected = client.linkCount > 0; status = if (connected) "Connected ✓ — ${client.linkCount} link(s)" else "No link" }
                        .onFailure { status = "Error: ${it.message}" }
                }
            },
            enabled = !connected,
        ) { Text(if (connected) "Connected" else "Connect to hub") }
        Text(
            status,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (status.startsWith("Connected")) MaterialTheme.colorScheme.primary
            else if (status.startsWith("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(draft, { draft = it }, label = { Text("Post to the mesh") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { scope.launch { client.publish(draft); status = "Posted ✓ — ${client.linkCount} link(s)" } },
            enabled = connected,
        ) { Text("Broadcast post") }

        Text("Received from mesh (${received.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(received) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(p.authorName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(p.content, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
