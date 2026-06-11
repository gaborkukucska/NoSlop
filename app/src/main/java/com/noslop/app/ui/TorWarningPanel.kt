package com.noslop.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.theme.*
import com.noslop.app.tor.TorState
import com.noslop.app.tor.TorService

@Composable
fun TorWarningPanel(viewModel: NoSlopViewModel) {
    val daemonState by TorService.torState.collectAsState()
    val torState by viewModel.torReadyState.collectAsState()
    val isTorChecking by viewModel.isTorChecking.collectAsState()
    val context = LocalContext.current

    if (daemonState == TorState.READY && torState.first) {
        return // Hidden entirely when healthy
    }

    // Show spinner while starting up — IDLE means startTor() hasn't fired yet (brief window on cold launch)
    if (daemonState == TorState.IDLE || daemonState == TorState.STARTING || daemonState == TorState.PROXY_READY) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .testTag("tor_warning_panel"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (daemonState) {
                        TorState.IDLE -> "Preparing Tor..."
                        TorState.PROXY_READY -> "Building Circuits..."
                        else -> "Starting Tor..."
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp
                )
                if (daemonState == TorState.PROXY_READY) {
                    Text(
                        text = "Proxy ready, waiting for consensus.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        return
    }

    if (daemonState == TorState.FAILED || !torState.first) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .testTag("tor_warning_panel"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Tor Offline Warn",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tor SOCKS5 Proxy Offline",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 16.sp
                    )
                }

                Text(
                    text = "NoSlop requires the Tor daemon for mesh gossip and secure DMs. Embedded daemon failed to start or connect.",
                    color = TextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Grid layout for CTA buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { viewModel.startTor() },
                        // FIX: also disable during IDLE (briefly) to avoid double-tap before first launch
                        enabled = daemonState != TorState.STARTING && daemonState != TorState.IDLE,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            contentColor = PrimaryBlack
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("retry_tor_btn")
                    ) {
                        Text("Tor failed to start — Retry", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { viewModel.refreshTorStatus() },
                        enabled = !isTorChecking,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = TextLight
                        ),
                        border = BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("retry_socks_btn")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry Proxy", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                }
            }
        }
    }
}
