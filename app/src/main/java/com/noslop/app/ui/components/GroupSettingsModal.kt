package com.noslop.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.GroupChat
import com.noslop.app.data.Peer
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

val PRESET_GROUP_AVATARS = listOf("👥", "🚀", "💬", "🔒", "⚡", "🎨", "🌐", "🔥", "🛡️", "💎")

fun uriToCompressedBase64(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream) ?: return null
        inputStream.close()

        val maxDim = 256
        val width = originalBitmap.width
        val height = originalBitmap.height
        var newWidth = width
        var newHeight = height
        if (width > maxDim || height > maxDim) {
            val ratio = Math.min(maxDim.toFloat() / width, maxDim.toFloat() / height)
            newWidth = (width * ratio).toInt()
            newHeight = (height * ratio).toInt()
        }
        val scaled = if (newWidth != width || newHeight != height) {
            android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
        } else originalBitmap

        val outputStream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun GroupAvatarDisplay(avatarB64: String?, size: Int = 48) {
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
    myHandle: String? = null,
    onUpdateGroup: (title: String, description: String?, avatarB64: String?, allowInvites: Boolean, allowSelfRemove: Boolean, members: List<String>) -> Unit,
    onLeaveGroup: () -> Unit,
    onDeleteGroup: () -> Unit,
    onResendInvites: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(group.title) }
    var description by remember { mutableStateOf(group.description ?: "") }
    var avatarB64 by remember { mutableStateOf(group.avatarB64 ?: "") }
    var allowInvites by remember { mutableStateOf(group.allowMemberInvites) }
    var allowSelfRemove by remember { mutableStateOf(group.allowMemberSelfRemove) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val converted = uriToCompressedBase64(context, it)
            if (converted != null) avatarB64 = converted
        }
    }

    val currentMembers: List<String> = remember(group.membersJson) {
        try {
            com.google.gson.Gson().fromJson(group.membersJson, Array<String>::class.java).toList()
        } catch (e: Exception) { emptyList() }
    }
    var membersList by remember { mutableStateOf(currentMembers) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var showLeaveConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                // Group Avatar Selection Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GroupAvatarDisplay(avatarB64 = avatarB64, size = 56)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        if (isAdmin) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { photoPickerLauncher.launch("image/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Upload Photo".tr, fontSize = 11.sp)
                                }
                            }
                        } else {
                            Text(group.title, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                // Preset Emoji Avatar Picker Row (Admin only)
                if (isAdmin) {
                    Text("Avatar Emoji Presets".tr, color = TextMuted, style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        items(PRESET_GROUP_AVATARS) { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (avatarB64 == emoji) AccentGreen.copy(alpha = 0.3f) else PrimaryBlack)
                                    .clickable { avatarB64 = emoji },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, fontSize = 18.sp)
                            }
                        }
                    }
                }

                // Title Input (Admin editable)
                if (isAdmin) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Group Title".tr) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedLabelColor = AccentGreen,
                            unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (Optional)".tr) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedLabelColor = AccentGreen,
                            unfocusedLabelColor = TextMuted,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Permissions Toggles (Admin editable)
                if (isAdmin) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Allow Members to Invite Peers".tr, color = TextLight, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = allowInvites,
                            onCheckedChange = { allowInvites = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlack, checkedTrackColor = AccentGreen)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Allow Members to Self-Leave".tr, color = TextLight, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = allowSelfRemove,
                            onCheckedChange = { allowSelfRemove = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlack, checkedTrackColor = AccentGreen)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Members List Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Members (${membersList.size})".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isAdmin && onResendInvites != null) {
                            IconButton(onClick = {
                                onResendInvites()
                                android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Invites re-sent to all members"), android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Resend Invites".tr, tint = AccentGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (isAdmin || allowInvites) {
                            IconButton(onClick = { showAddMemberDialog = true }) {
                                Icon(Icons.Default.PersonAdd, contentDescription = "Add Member".tr, tint = AccentGreen)
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    membersList.forEach { memberPub ->
                        val isMe = memberPub == myPubKey
                        val peer = allPeers.find { it.publicKeyB64 == memberPub }
                        val name = when {
                            isMe -> (myHandle?.takeIf { it.isNotBlank() } ?: "You") + " (You)"
                            peer != null -> peer.handle
                            else -> memberPub.take(8) + "..."
                        }
                        val isMemberAdmin = memberPub == group.adminPublicKeyB64

                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(PrimaryBlack.copy(alpha = 0.5f)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (isMemberAdmin) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("(Admin)".tr, color = AccentGreen, fontSize = 10.sp)
                                }
                            }
                            if (isAdmin && !isMemberAdmin) {
                                IconButton(onClick = { membersList = membersList - memberPub }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove".tr, tint = DestructiveRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                // Leave / Delete Group Button inside body
                Spacer(modifier = Modifier.height(16.dp))
                if (isAdmin) {
                    Button(
                        onClick = { showDeleteConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Group Chat".tr, fontWeight = FontWeight.Bold)
                    }
                } else if (allowSelfRemove) {
                    Button(
                        onClick = { showLeaveConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = DestructiveRed),
                        border = BorderStroke(1.dp, DestructiveRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Leave Group Chat".tr, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdateGroup(title, description.ifBlank { null }, avatarB64.ifBlank { null }, allowInvites, allowSelfRemove, membersList)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) {
                Text("Save Changes".tr, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel".tr, color = TextMuted)
            }
        }
    )

    if (showLeaveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirmDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Leave Group Chat?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to leave this group chat? You will no longer receive messages from this group.".tr, color = TextLight) },
            confirmButton = {
                Button(
                    onClick = {
                        showLeaveConfirmDialog = false
                        onLeaveGroup()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                ) { Text("Leave".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirmDialog = false }) { Text("Cancel".tr, color = TextMuted) }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Delete Group Chat?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete this group chat? This action will remove the group for all members.".tr, color = TextLight) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteGroup()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                ) { Text("Delete".tr, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel".tr, color = TextMuted) }
            }
        )
    }

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
