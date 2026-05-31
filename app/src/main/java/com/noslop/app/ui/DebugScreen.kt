package com.noslop.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.debug.Logger
import com.noslop.app.tor.TorService
import com.noslop.app.tor.TorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val torState by TorService.torState.collectAsState()
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val localKeys by viewModel.localKeys.collectAsState()
    val peers by viewModel.peers.collectAsState()
    
    // Polling for live states
    var isListening by remember { mutableStateOf(viewModel.isMeshListening()) }
    var recentLogs by remember { mutableStateOf(Logger.getRecentLogs(200)) }

    LaunchedEffect(Unit) {
        while (true) {
            isListening = viewModel.isMeshListening()
            recentLogs = Logger.getLogs().takeLast(200).map { it.toString() }
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug & Test", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Tor Status
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tor Status", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = when(torState) {
                            TorState.STARTING -> androidx.compose.ui.graphics.Color(0xFFFFA000) // Amber
                            TorState.READY -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
                            TorState.FAILED -> androidx.compose.ui.graphics.Color(0xFFF44336) // Red
                        }
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(color, RoundedCornerShape(50))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(torState.name)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val onion = localKeys?.onionAddress ?: "Unknown"
                    val displayOnion = if (onion.length > 20) onion.take(20) + "..." else onion
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Onion: $displayOnion", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = { clipboardManager.setText(AnnotatedString(onion)) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Copy", fontSize = 10.sp)
                        }
                    }
                }
            }

            // Mesh Listener Status
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mesh Listener (Port 9999)", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (isListening) "Bound and accepting connections" else "Not listening")
                }
            }

            // Send Test Gossip Action
            Button(
                onClick = { viewModel.sendTestPost() },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text("Send Test Gossip Post")
            }

            // Peers List
            Text("Peers (${peers.size})", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(modifier = Modifier.weight(0.3f).fillMaxWidth()) {
                items(peers) { peer ->
                    Text(
                        "${peer.handle}#${peer.tripcode} — ${peer.onionAddress.take(12)}... (Trusted: ${peer.isTrusted})",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Logs
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Last 200 Logs", fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(recentLogs.joinToString("\n")))
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(0.5f).fillMaxWidth().background(MaterialTheme.colorScheme.onSurface.copy(alpha=0.05f))
            ) {
                items(recentLogs) { logLine ->
                    Text(
                        logLine,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}
