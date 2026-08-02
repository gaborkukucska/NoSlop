package com.noslop.app.ui

import com.noslop.app.util.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noslop.app.data.UserProfile
import com.noslop.app.ui.theme.*
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val currentProfile by viewModel.userProfile.collectAsState()
    
    var displayName by remember { mutableStateOf(currentProfile.displayName) }
    var bio by remember { mutableStateOf(currentProfile.bio) }
    var avatarB64 by remember { mutableStateOf(currentProfile.avatarB64) }
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }

    if (cropUri != null) {
        com.noslop.app.ui.components.AvatarCropper(
            uri = cropUri!!,
            onCropSuccess = { b64 ->
                avatarB64 = b64
                cropUri = null
            },
            onCancel = { cropUri = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
            }
            Text(
                text = "Edit Profile".tr,
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri: android.net.Uri? ->
                    cropUri = uri
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceDark)
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarB64 != null) {
                        val bitmap = remember(avatarB64) {
                            try {
                                val bytes = android.util.Base64.decode(avatarB64, android.util.Base64.DEFAULT)
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (e: Exception) { null }
                        }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap,
                                contentDescription = "Profile Avatar".tr,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Face, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                        }
                    } else {
                        Icon(Icons.Default.Face, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name".tr) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Bio".tr) },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Save button
        Button(
            onClick = {
                val profileChanged = displayName != currentProfile.displayName || bio != currentProfile.bio || avatarB64 != currentProfile.avatarB64
                if (profileChanged) {
                    viewModel.updateUserProfile(
                        UserProfile(displayName = displayName, bio = bio, avatarB64 = avatarB64)
                    )
                }
                onBack()
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
        ) {
            Text("Save Profile".tr, fontWeight = FontWeight.Bold)
        }
    }
}
