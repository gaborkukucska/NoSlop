package com.noslop.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.GroupChat
import com.noslop.app.data.Peer
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

val PRESET_GROUP_AVATARS = listOf("👥", "🚀", "💬", "🔒", "⚡", "🎨", "🌐", "🔥", "🛡️", "💎")

@Composable
fun GroupAvatarDisplay(avatarB64: String?, size: Int = 40) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarB64.isNullOrBlank() && avatarB64.length <= 4) {
            // Preset Emoji Avatar
            Text(text = avatarB64, fontSize = (size * 0.5).sp)
        } else if (!avatarB64.isNullOrBlank() && avatarB64.startsWith("data:image")) {
            // Render decoded Base64 image
            val bitmap = remember(avatarB64) {
                try {
                    val pureB64 = avatarB64.substringAfter(",")
                    val bytes = android.util.Base64.decode(pureB64, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Group Avatar",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else {
                Icon(Icons.Outlined.People, contentDescription = null, tint = AccentGreen, modifier = Modifier.size((size * 0.6).dp))
            }
        } else {
            Icon(Icons.Outlined.People, contentDescription = null, tint = AccentGreen, modifier = Modifier.size((size * 0.6).dp))
        }
    }
}

@Composable
fun GroupSettingsModal(
    group: GroupChat,
    allPeers: List<Peer>,
    myPubKey: String?,
    onUpdateGroup: (title: String, description: String?, avatarB64: String?, allowInvites: Boolean, allowSelfRemove: Boolean, members: List<String>) -> Unit,
    onDeleteGroup: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(group.title) }
    var description by remember { mutableStateOf(group.description ?: "") }
    var avatarB64 by remember { mutableStateOf(group.avatarB64 ?: "") }
    var allowInvites by remember { mutableStateOf(group.allowMemberInvites) }
    var allowSelfRemove by remember { mutableStateOf(group.allowMemberSelfRemove) }
    
    val currentMembers: List<String> = remember(group.membersJson) {
        try {
            com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }
    }
    var membersList by remember { mutableStateOf(currentMembers) }
    var showAddMemberDialog by remember { mutableStateOf(false) }

    val isAdmin = myPubKey == group.adminPublicKeyB64

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Group Settings".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // Group Avatar Selection Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupAvatarDisplay(avatarB64 = avatarB64, size = 52)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Group Avatar".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Tap preset icon below".tr, color = TextMuted, fontSize = 11.sp)
                    }
                }

                if (isAdmin) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        items(PRESET_GROUP_AVATARS) { preset ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (avatarB64 == preset) AccentGreen.copy(alpha = 0.3f) else SurfaceDark)
                                    .clickable { avatarB64 = preset },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(preset, fontSize = 18.sp)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Group Name".tr, color = TextMuted) },
                    enabled = isAdmin,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description".tr, color = TextMuted) },
                    enabled = isAdmin,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight,
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isAdmin) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Allow members to invite".tr, color = TextLight, fontSize = 13.sp)
                        Switch(
                            checked = allowInvites,
                            onCheckedChange = { allowInvites = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlack, checkedTrackColor = AccentGreen)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Allow members to leave".tr, color = TextLight, fontSize = 13.sp)
                        Switch(
                            checked = allowSelfRemove,
                            onCheckedChange = { allowSelfRemove = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlack, checkedTrackColor = AccentGreen)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Members (${membersList.size}):".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (isAdmin || allowInvites) {
                        IconButton(onClick = { showAddMemberDialog = true }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = "Add Member".tr, tint = AccentGreen)
                        }
                    }
                }

                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(membersList) { memberPub ->
                        val peer = allPeers.find { it.publicKeyB64 == memberPub }
                        val displayName = peer?.handle ?: (memberPub.take(8) + "...")
                        val isMemberAdmin = memberPub == group.adminPublicKeyB64
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName + (if (isMemberAdmin) " (Admin)".tr else ""),
                                color = if (isMemberAdmin) AccentGreen else TextLight,
                                fontSize = 13.sp
                            )
                            if (isAdmin && !isMemberAdmin) {
                                IconButton(onClick = { membersList = membersList - memberPub }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove".tr, tint = DestructiveRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onUpdateGroup(title, description.ifBlank { null }, avatarB64.ifBlank { null }, allowInvites, allowSelfRemove, membersList)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Changes".tr, fontWeight = FontWeight.Bold)
                }

                if (isAdmin) {
                    Button(
                        onClick = {
                            onDeleteGroup()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Group Chat".tr, fontWeight = FontWeight.Bold)
                    }
                } else if (allowSelfRemove && myPubKey != null) {
                    Button(
                        onClick = {
                            onUpdateGroup(title, description.ifBlank { null }, avatarB64.ifBlank { null }, allowInvites, allowSelfRemove, membersList - myPubKey)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = DestructiveRed),
                        border = BorderStroke(1.dp, DestructiveRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Leave Group Chat".tr, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel".tr, color = TextMuted)
            }
        }
    )

    if (showAddMemberDialog) {
        val availablePeers = allPeers.filter { it.isTrusted && !membersList.contains(it.publicKeyB64) }
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Invite Member".tr, color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                if (availablePeers.isEmpty()) {
                    Text("No additional contacts to invite.".tr, color = TextMuted)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(availablePeers) { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        membersList = membersList + p.publicKeyB64
                                        showAddMemberDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.handle, color = TextLight, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false }) { Text("Close".tr, color = TextMuted) }
            }
        )
    }
}
