package com.noslop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.MeshComment
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    postId: String,
    viewModel: NoSlopViewModel,
    onDismiss: () -> Unit
) {
    val comments by viewModel.getCommentsForPost(postId).collectAsState(initial = emptyList())
    val localKeys by viewModel.localKeys.collectAsState()
    var commentText by remember { mutableStateOf("") }
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
                .fillMaxHeight(0.9f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Text(
                "Mesh Comments",
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (comments.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No comments yet. Be the first to gossip!", color = TextMuted)
                        }
                    }
                }
                items(comments) { comment ->
                    CommentItem(comment, viewModel, localKeys)
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Add a comment...") },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    )
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = {
                        viewModel.composeAndBroadcastComment(postId, commentText)
                        commentText = ""
                    },
                    enabled = commentText.isNotBlank(),
                    modifier = Modifier.background(AccentGreen, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post", tint = PrimaryBlack)
                }
            }
        }
    }
}

@Composable
fun CommentItem(comment: MeshComment, viewModel: NoSlopViewModel, localKeys: com.noslop.app.crypto.CryptoService.IdentityKeys?) {
    val reactions by viewModel.getReactionsForComment(comment.id).collectAsState(initial = emptyList())
    val votes by viewModel.getVotesForComment(comment.id).collectAsState(initial = emptyList())
    var showReactionPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { showReactionPicker = true })
            }
            .background(PrimaryBlack.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (comment.authorAvatarB64 != null) {
                val bitmap = remember(comment.authorAvatarB64) {
                    try {
                        val bytes = android.util.Base64.decode(comment.authorAvatarB64, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (e: Exception) { null }
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = "Avatar",
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            Text(
                comment.authorHandle,
                style = MaterialTheme.typography.bodyMedium,
                color = AccentGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(comment.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            comment.content,
            style = MaterialTheme.typography.bodyMedium,
            color = TextLight
        )

        if (reactions.isNotEmpty() || votes.isNotEmpty()) {
            val allReactions = reactions.map { it.reactionType to it.authorPublicKeyB64 } + 
                               votes.map { it.voteType to it.authorPublicKeyB64 }
            val grouped = allReactions.groupBy { it.first }
            
            val emojiMap = mapOf(
                "like" to "❤️", "upvote" to "👍", "downvote" to "👎",
                "laugh" to "😂", "wow" to "😮", "sad" to "😢",
                "fire" to "🔥", "angry" to "😡"
            )

            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                grouped.forEach { (type, reacts) ->
                    val displayEmoji = emojiMap[type] ?: type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .clickable { viewModel.reactToComment(comment.id, type) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$displayEmoji ${reacts.size}",
                            fontSize = 12.sp,
                            color = if (reacts.any { it.second == localKeys?.publicKeyB64 }) AccentGreen else TextMuted
                        )
                    }
                }
            }
        }

        if (showReactionPicker) {
            val emojis = listOf("upvote", "downvote", "🔥", "😂", "👀")
            val emojiMap = mapOf(
                "like" to "❤️", "upvote" to "👍", "downvote" to "👎",
                "laugh" to "😂", "wow" to "😮", "sad" to "😢",
                "fire" to "🔥", "angry" to "😡"
            )
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                emojis.forEach { emoji ->
                    val displayEmoji = emojiMap[emoji] ?: emoji
                    Text(
                        text = displayEmoji,
                        fontSize = 20.sp,
                        modifier = Modifier.clickable {
                            viewModel.reactToComment(comment.id, emoji)
                            showReactionPicker = false
                        }
                    )
                }
                Icon(
                    Icons.Default.Close, 
                    contentDescription = "Close", 
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp).clickable { showReactionPicker = false }
                )
            }
        }
    }
}
