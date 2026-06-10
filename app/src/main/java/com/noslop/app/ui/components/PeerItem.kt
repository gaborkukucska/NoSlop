package com.noslop.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.Peer
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*

@Composable
fun PeerItem(peer: Peer, lastMsg: ChatMessage?, viewModel: NoSlopViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.selectChatPeer(peer.publicKeyB64) },
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, if (peer.isTrusted) AccentGreen.copy(alpha = 0.3f) else DestructiveRed.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Placeholder
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PrimaryBlack),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.handle.take(1).uppercase(),
                    color = if (peer.isTrusted) AccentGreen else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${peer.handle}.${peer.tripcode}",
                        fontWeight = FontWeight.Bold,
                        color = TextLight,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    if (peer.isTrusted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = "Trusted", tint = AccentGreen, modifier = Modifier.size(14.dp))
                    }
                }

                Text(
                    text = lastMsg?.ciphertext?.take(32)?.plus("...") ?: "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Actions
            Row {
                if (!peer.isTrusted) {
                    IconButton(onClick = { viewModel.togglePeerTrust(peer) }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Trust Peer", tint = AccentGreen)
                    }
                }
                IconButton(onClick = { viewModel.removePeer(peer.publicKeyB64) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove Peer", tint = TextMuted)
                }
            }
        }
    }
}
