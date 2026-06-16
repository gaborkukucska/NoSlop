package com.noslop.mvp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/** Root MVP UI: two tabs — Identity and Feed. */
@Composable
fun App() {
    MaterialTheme {
        var tab by remember { mutableStateOf(0) }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Identity") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Feed") })
                }
                when (tab) {
                    0 -> IdentityScreen()
                    else -> FeedScreen()
                }
            }
        }
    }
}

@Composable
private fun IdentityScreen() {
    var identity by remember { mutableStateOf<Identity?>(null) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("NoSlop Identity", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Button(onClick = { identity = generateIdentity("anon") }) {
            Text(if (identity == null) "Generate identity" else "Regenerate")
        }
        identity?.let { id ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("Handle", "${id.handle}.${id.tripcode}")
                    Field("Tripcode", id.tripcode)
                    Field("Onion", id.onionAddress)
                    Field("Public key", id.publicKeyHex)
                    if (!id.isRealKeypair) {
                        Text(
                            "⚠︎ iOS MVP demo key — real CryptoKit Ed25519 is the next step.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
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
            runCatching { repo.topStories() }
                .onSuccess { stories = it }
                .onFailure { error = it.message ?: "Failed to load feed" }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { load() }, enabled = !loading) { Text("Load feed") }
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text("Error: $error", color = MaterialTheme.colorScheme.error)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(stories) { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("▲ ${s.score} · ${s.by}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            s.url?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            }
        }
    }
}
