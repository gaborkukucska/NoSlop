package com.noslop.mvp.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.feeds.ApiKeyStore

/**
 * API keys for the keyed feed sources. The keyless sources (Invidious/YouTube, Reddit, Jamendo, Wikimedia,
 * Internet Archive, Hacker News, NASA via DEMO_KEY) work without anything here; entering a key just unlocks
 * the extra ones. Persisted via [ApiKeyStore] under the exact service names the API clients resolve.
 */
private val API_SERVICES = listOf(
    Triple("guardian", "The Guardian", "Open Platform API key"),
    Triple("newsapi", "NewsAPI", "newsapi.org key"),
    Triple("pexels", "Pexels", "Photos + videos key"),
    Triple("nasa", "NASA", "api.nasa.gov key (optional — DEMO_KEY otherwise)"),
    Triple("vimeo", "Vimeo", "Access token"),
    Triple("podcastindex", "Podcast Index", "Format: key:secret"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(onBack: () -> Unit) {
    val fields = remember { API_SERVICES.associate { it.first to mutableStateOf(ApiKeyStore.get(it.first) ?: "") } }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "Optional — unlock extra feed sources. The feed already works without any keys (YouTube via Invidious, Reddit, Jamendo, Internet Archive, NASA, Wikimedia).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(API_SERVICES) { (service, title, hint) ->
                val state = fields.getValue(service)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { state.value = it; saved = false },
                        label = { Text(title) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
            item {
                Button(
                    onClick = {
                        fields.forEach { (service, state) -> ApiKeyStore.set(service, state.value.trim()) }
                        saved = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saved) "Saved ✓" else "Save Keys") }
            }
        }
    }
}
