package com.noslop.app.ui.components

import com.noslop.app.util.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.noslop.app.data.ChatMessage
import com.noslop.app.data.Peer
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerItem(
    peer: Peer, 
    lastMsg: ChatMessage?, 
    viewModel: NoSlopViewModel,
    onLongPress: (() -> Unit)? = null
) {
    var showContactCard by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { viewModel.selectChatPeer(peer.publicKeyB64) },
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, when {
            peer.isTrusted && peer.isTemporary -> TemporaryAmber.copy(alpha = 0.3f)
            peer.isTrusted -> AccentGreen.copy(alpha = 0.3f)
            else -> DestructiveRed.copy(alpha = 0.3f)
        })
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
            ) {
                PeerAvatar(
                    peer = peer,
                    size = 44,
                    modifier = Modifier.fillMaxSize()
                )

                if (peer.isTrusted && peer.isOnline) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(AccentGreen)
                            .border(2.dp, SurfaceDark, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = peer.handle,
                        fontWeight = FontWeight.Bold,
                        color = TextLight,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                    if (peer.isTrusted && peer.isTemporary) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.AccessTime, contentDescription = "Temporary Contact".tr, tint = TemporaryAmber, modifier = Modifier.size(14.dp))
                    } else if (peer.isTrusted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.CheckCircle, contentDescription = "Trusted".tr, tint = AccentGreen, modifier = Modifier.size(14.dp))
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
                IconButton(onClick = { showContactCard = true }) {
                    Icon(Icons.Default.Info, contentDescription = "Contact Info".tr, tint = TextMuted)
                }
            }
        }
    }

    // Contact Card Dialog
    if (showContactCard) {
        ContactCardDialog(
            peer = peer,
            onDismiss = { showContactCard = false },
            onDelete = { viewModel.removePeer(peer.publicKeyB64) },
            onConnect = { viewModel.acceptHandshake(peer) }
        )
    }
}

@Composable
private fun PeerAvatar(peer: Peer, size: Int, modifier: Modifier = Modifier) {
    val bitmap = remember(peer.authorAvatarB64) {
        peer.authorAvatarB64?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "${peer.handle}'s avatar",
            modifier = modifier
                .clip(RoundedCornerShape(8.dp)),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(PrimaryBlack),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peer.handle.take(1).uppercase(),
                color = when {
                    peer.isTrusted && peer.isTemporary -> TemporaryAmber
                    peer.isTrusted -> AccentGreen
                    else -> TextMuted
                },
                fontWeight = FontWeight.Bold,
                fontSize = (size / 2.2).sp
            )
        }
    }
}

@Composable
private fun ContactCardDialog(
    peer: Peer,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit = {}
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Avatar
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            2.dp,
                            when {
                                peer.isTrusted && peer.isTemporary -> TemporaryAmber.copy(alpha = 0.5f)
                                peer.isTrusted -> AccentGreen.copy(alpha = 0.5f)
                                else -> BorderSubtle
                            },
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    PeerAvatar(
                        peer = peer,
                        size = 96,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Online indicator
                    if (peer.isTrusted && peer.isOnline) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 4.dp, y = 4.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(AccentGreen)
                                .border(3.dp, SurfaceDark, CircleShape)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Handle + Tripcode
                Text(
                    text = peer.handle,
                    fontWeight = FontWeight.Bold,
                    color = TextLight,
                    fontSize = 22.sp
                )

                Text(
                    text = ".${peer.tripcode}",
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Trust badge
                if (peer.isTrusted && peer.isTemporary) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(TemporaryAmber.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = TemporaryAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Temporary Contact".tr,
                            color = TemporaryAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (peer.isTrusted) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentGreen.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Trusted Contact".tr,
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(DestructiveRed.copy(alpha = 0.1f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = DestructiveRed,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pending Trust".tr,
                            color = DestructiveRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Onion address (truncated)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(PrimaryBlack)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "ONION ADDRESS".tr,
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = peer.onionAddress.take(24) + "...",
                        color = TextLight.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!peer.isTrusted) {
                        Button(
                            onClick = {
                                onConnect()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Connect".tr, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Close button
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, BorderSubtle),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
                    ) {
                        Text("Close".tr, fontWeight = FontWeight.Bold)
                    }

                    // Delete button
                    if (peer.isTrusted) {
                        Button(
                            onClick = { showDeleteConfirmation = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DestructiveRed.copy(alpha = 0.15f),
                                contentColor = DestructiveRed
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remove".tr, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // "Are you sure?" Confirmation Dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(16.dp),
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = DestructiveRed,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    "Are you sure?".tr,
                    fontWeight = FontWeight.Bold,
                    color = TextLight,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    "You are about to remove ${peer.handle} from your contacts. " +
                            "All messages with this peer will be lost. This cannot be undone.",
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                        // onDismiss for the contact card is implicit since the peer gets removed
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DestructiveRed,
                        contentColor = TextLight
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Yes, Remove".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirmation = false },
                    border = BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight)
                ) {
                    Text("Cancel".tr, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
