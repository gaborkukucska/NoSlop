package com.noslop.mvp.ui.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.ui.theme.*

@Composable
fun OverlayInteractions(
    modifier: Modifier = Modifier,
    isMesh: Boolean = false,
    showLike: Boolean = true,
    showComment: Boolean = true,
    onLike: () -> Unit,
    onReaction: (String) -> Unit = {},
    onShare: () -> Unit,
    onComment: (() -> Unit)? = null,
    reactionSummary: Map<String, Int> = emptyMap(),
    commentCount: Int = 0,
    netScore: Int? = null,
    isFlagged: Boolean = false,
    isBlocked: Boolean = false
) {
    var showReactionPicker by remember { mutableStateOf(false) }

    val emojiMap = mapOf(
        "like" to "❤️", "upvote" to "👍", "downvote" to "👎",
        "laugh" to "😂", "wow" to "😮", "sad" to "😢",
        "fire" to "🔥", "angry" to "😡"
    )

    Column(
        modifier = modifier.padding(end = 12.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isBlocked) {
            // Reaction Pills
            if (reactionSummary.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    reactionSummary.entries
                        .filter { it.value > 0 }
                        .sortedByDescending { it.value }
                        .forEach { (type, count) ->
                            val emoji = emojiMap[type] ?: type
                            Surface(
                                onClick = { onReaction(type) },
                                color = SurfaceDark.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, BorderSubtle)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(emoji, fontSize = 14.sp)
                                    Text(
                                        count.toString(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentGreen
                                    )
                                }
                            }
                        }
                }
            }

            // Main Action Buttons
            if (showLike) {
                Box {
                    InteractionButton(
                        icon = Icons.Default.AddReaction,
                        label = "React",
                        onClick = { showReactionPicker = !showReactionPicker }
                    )

                    if (showReactionPicker) {
                        ReactionPicker(
                            currentReactions = reactionSummary,
                            onReactionSelect = {
                                onReaction(it)
                                showReactionPicker = false
                            },
                            onDismiss = { showReactionPicker = false }
                        )
                    }
                }
            }

            InteractionButton(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = onShare
            )

            if (showComment && onComment != null) {
                InteractionButton(
                    icon = Icons.Default.Chat,
                    label = if (commentCount > 0) commentCount.toString() else "Chat",
                    onClick = onComment
                )
            }
        }
    }
}

@Composable
fun ReactionPicker(
    currentReactions: Map<String, Int> = emptyMap(),
    onReactionSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val reactions = listOf(
        "like" to "❤️",
        "upvote" to "👍",
        "downvote" to "👎",
        "laugh" to "😂",
        "wow" to "😮",
        "sad" to "😢",
        "fire" to "🔥",
        "angry" to "😡"
    )

    androidx.compose.ui.window.Popup(
        alignment = Alignment.CenterEnd,
        onDismissRequest = onDismiss
    ) {
        Surface(
            color = SurfaceDark.copy(alpha = 0.95f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, BorderSubtle),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(end = 56.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    reactions.take(4).forEach { (type, emoji) ->
                        val count = currentReactions[type] ?: 0
                        ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    reactions.drop(4).forEach { (type, emoji) ->
                        val count = currentReactions[type] ?: 0
                        ReactionPickerItem(emoji, count) { onReactionSelect(type) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionPickerItem(emoji: String, count: Int, onClick: () -> Unit) {
    val hasCount = count > 0
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (hasCount) AccentGreen.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 24.sp)
            if (hasCount) {
                Text(
                    count.toString(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen
                )
            }
        }
    }
}

@Composable
private fun InteractionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(SurfaceDark.copy(alpha = 0.6f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = AccentGreen,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextLight,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun ContentHealthOverlay(
    isBlocked: Boolean,
    isSoftBlocked: Boolean,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isBlocked && !isSoftBlocked) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = if (isBlocked) Icons.Default.Shield else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isBlocked) DestructiveRed else Color.Yellow,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isBlocked) "Content Blocked" else "Community Flagged",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isBlocked)
                    "This broadcast has been community hidden (>95% negative feedback)."
                else "This content has received significantly negative feedback (2/3+).",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )

            if (isSoftBlocked && !isBlocked) {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onReveal,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Text("Temporarily View")
                }
            }
        }
    }
}
