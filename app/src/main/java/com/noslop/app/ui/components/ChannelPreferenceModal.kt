// app/src/main/java/com/noslop/app/ui/components/ChannelPreferenceModal.kt
package com.noslop.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPreferenceModal(
    channelName: String,
    isAlreadyInPreferences: Boolean,
    isBanned: Boolean = false,
    creatorItems: List<com.noslop.app.data.FeedItem> = emptyList(),
    onItemClick: ((com.noslop.app.data.FeedItem) -> Unit)? = null,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onBan: (() -> Unit)? = null,
    onUnban: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = if (isBanned) DestructiveRed else AccentGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBanned) "Channel Banned".tr else "Manage Preference".tr,
                    color = TextLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = channelName,
                color = if (isBanned) DestructiveRed else AccentGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isBanned) {
                Text(
                    text = "This channel/creator is currently Banned 🚫. All content from this channel is blacklisted and excluded from feeds and search results.".tr,
                    color = DestructiveRed,
                    fontSize = 13.sp
                )
            } else if (isAlreadyInPreferences) {
                Text(
                    text = "This channel/creator is currently in your preferences list. Removing it will stop prioritizing their content in your feed.".tr,
                    color = TextMuted,
                    fontSize = 13.sp
                )
            } else {
                Text(
                    text = "Add this channel/creator to your preferences to surface and prioritize their content across your feeds.".tr,
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }

            if (creatorItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Content ({count})".tr.replace("{count}", creatorItems.size.toString()),
                    color = TextLight,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(creatorItems, key = { it.id }) { feedItem ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PrimaryBlack.copy(alpha = 0.6f))
                                .clickable {
                                    onItemClick?.invoke(feedItem)
                                    onDismiss()
                                }
                                .padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(PrimaryBlack),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!feedItem.thumbnailUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = feedItem.thumbnailUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    val icon = if (feedItem.mediaType?.contains("video") == true) Icons.Default.PlayArrow else Icons.Default.Info
                                    Icon(icon, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feedItem.title,
                                    color = TextLight,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (feedItem.publishedAt > 0L) {
                                    val dateStr = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(feedItem.publishedAt))
                                    Text(text = dateStr, color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isBanned) {
                    if (onUnban != null) {
                        Button(
                            onClick = {
                                onUnban()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentGreen,
                                contentColor = PrimaryBlack
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unban Channel".tr, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    if (isAlreadyInPreferences) {
                        Button(
                            onClick = {
                                onRemove()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SurfaceDark,
                                contentColor = TextLight
                            ),
                            border = BorderStroke(1.dp, BorderSubtle),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Remove Preference".tr, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                onAdd()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentGreen,
                                contentColor = PrimaryBlack
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add to Preferences".tr, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (onBan != null) {
                        Button(
                            onClick = {
                                onBan()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DestructiveRed,
                                contentColor = TextLight
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Ban Channel 🚫".tr, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onDismiss,
                    border = BorderStroke(1.dp, BorderSubtle),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close".tr, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
