package com.noslop.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(viewModel: NoSlopViewModel, onNavigateToRoute: (String) -> Unit) {
    val notifications by viewModel.allNotifications.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleLarge,
                color = AccentGreen,
                fontWeight = FontWeight.Bold
            )
            
            if (notifications.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearAllNotifications() }) {
                    Text("Clear All", color = TextMuted)
                }
            }
        }

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(64.dp), tint = TextMuted)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No new notifications", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notifications, key = { it.id }) { notif ->
                    NotificationCard(
                        notif = notif,
                        onClick = {
                            viewModel.markNotificationAsRead(notif.id)
                            if (notif.targetRoute != null) {
                                onNavigateToRoute(notif.targetRoute)
                            }
                        },
                        onAccept = { notifId, senderPub -> viewModel.acceptConnectionFromNotification(notifId, senderPub) },
                        onDecline = { notifId, senderPub -> viewModel.rejectConnectionFromNotification(notifId, senderPub) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationCard(
    notif: com.noslop.app.data.NotificationItem, 
    onClick: () -> Unit,
    onAccept: (String, String) -> Unit,
    onDecline: (String, String) -> Unit
) {
    val icon = when (notif.iconType) {
        "dm" -> Icons.Default.Email
        "comment" -> Icons.Default.ChatBubble
        "reaction" -> Icons.Default.Favorite
        "handshake" -> Icons.Default.Person
        else -> Icons.Default.Notifications
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (notif.isRead) SurfaceDark else SurfaceDark.copy(alpha = 0.8f))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PrimaryBlack),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AccentGreen)
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = notif.title,
                        fontWeight = if (notif.isRead) FontWeight.Normal else FontWeight.Bold,
                        color = TextLight,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(notif.timestamp)),
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = notif.body,
                    color = if (notif.isRead) TextMuted else TextLight,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            if (!notif.isRead && notif.type != "CONNECTION_REQUEST") {
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AccentGreen))
            }
        }

        if (notif.type == "CONNECTION_REQUEST") {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 64.dp), // align with main text
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { notif.senderPub?.let { onAccept(notif.id, it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Accept", color = PrimaryBlack, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { notif.senderPub?.let { onDecline(notif.id, it) } },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decline")
                }
            }
        }
    }
}