// FILE: app/src/main/java/com/noslop/app/ui/tabs/CreatorStudioTab.kt
package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

data class StudioItem(
    val id: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val mediaType: String = "video",
    val thumbnailUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatorStudioTab(
    viewModel: NoSlopViewModel,
    onBack: (() -> Unit)? = null
) {
    var queuedItems by remember {
        mutableStateOf(
            listOf(
                StudioItem(
                    id = "studio_1",
                    title = "Decentralized P2P Mesh Architecture Overview",
                    description = "AI Auto-Tagged: Deep dive into sovereign network topology and Tor v3 routing.",
                    tags = listOf("Privacy", "Mesh", "Architecture"),
                    mediaType = "video"
                ),
                StudioItem(
                    id = "studio_2",
                    title = "Building Serverless Encryption Pipelines",
                    description = "AI Auto-Tagged: Discussion on X25519 key exchange and ChaCha20-Poly1305 DMs.",
                    tags = listOf("Cryptography", "Security", "OpenSource"),
                    mediaType = "audio"
                )
            )
        )
    }

    var isRefreshing by remember { mutableStateOf(false) }
    var showCreatorIdSheet by remember { mutableStateOf(false) }
    var showBurnConfirmDialog by remember { mutableStateOf(false) }
    var burnableIdentity by remember { mutableStateOf<com.noslop.app.crypto.CryptoService.IdentityKeys?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val handle by viewModel.localHandle.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
                    }
                }
                Text("Creator Studio 🎬".tr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextLight)
            }

            IconButton(onClick = { isRefreshing = true }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Queue".tr, tint = AccentGreen)
            }
        }

        // Creator Node Status Badge
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Podcasts, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Creator Node Status".tr, fontSize = 12.sp, color = TextMuted)
                        Text("Active & Publishing Ready".tr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextLight)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                burnableIdentity = viewModel.ensureBurnableIdentity()
                                showCreatorIdSheet = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Creator ID 🪪".tr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { showBurnConfirmDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DestructiveRed),
                        border = BorderStroke(1.dp, DestructiveRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(14.dp), tint = DestructiveRed)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("Burn ID 🔥".tr, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AccentGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            "AI Queue: {count}".tr.replace("{count}", queuedItems.size.toString()),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Text(
            "AI Approval Queue".tr,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextLight,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (queuedItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Approval Queue Clear!".tr, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextLight)
                    Text("No pending media from Home Hub AI auto-tagging.".tr, fontSize = 12.sp, color = TextMuted)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(queuedItems, key = { it.id }) { item ->
                    StudioItemCard(
                        item = item,
                        onPublish = { publishedItem ->
                            val tagsStr = if (publishedItem.tags.isNotEmpty()) "\n\n" + publishedItem.tags.joinToString(" ") { "#$it" } else ""
                            val fullPostContent = (publishedItem.title + "\n\n" + publishedItem.description + tagsStr).trim()
                            viewModel.composeAndBroadcastPost(
                                content = fullPostContent,
                                mediaMetadata = null,
                                privacy = "public"
                            )
                            queuedItems = queuedItems.filter { it.id != publishedItem.id }
                            android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Published '{title}' to Mesh!").replace("{title}", publishedItem.title), android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDiscard = { discardedId ->
                            queuedItems = queuedItems.filter { it.id != discardedId }
                        }
                    )
                }
            }
        }
    }

    if (showBurnConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBurnConfirmDialog = false },
            containerColor = SurfaceDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = DestructiveRed, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Burn Creator Identity?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "Burning your Creator Identity will permanently purge your secondary keypair from this device, unregister its Tor hidden service, broadcast a USER_EXIT to followers, and generate a brand-new Creator ID. ".tr +
                        "All current followers connected via your old Creator ID will permanently lose connectivity. Are you sure?".tr,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBurnConfirmDialog = false
                        viewModel.burnCreatorIdentity()
                        android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Creator Identity burned. New identity generated."), android.widget.Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                ) {
                    Text("Burn & Regenerate".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBurnConfirmDialog = false }) {
                    Text("Cancel".tr, color = AccentGreen)
                }
            }
        )
    }

    if (showCreatorIdSheet && burnableIdentity != null) {
        com.noslop.app.ui.QRShareSheet(
            handle = handle,
            localKeys = burnableIdentity!!,
            title = "Creator Contact Card".tr,
            subtitle = "Fans and peers can scan this QR code to connect with your Creator Node. This connection uses your severable secondary Tor address, keeping your personal identity private.".tr,
            isCreator = true,
            onDismiss = { showCreatorIdSheet = false }
        )
    }
}

@Composable
private fun StudioItemCard(
    item: StudioItem,
    onPublish: (StudioItem) -> Unit,
    onDiscard: (String) -> Unit
) {
    var editableTitle by remember(item.id) { mutableStateOf(item.title) }
    var editableDescription by remember(item.id) { mutableStateOf(item.description) }
    var editableTags by remember(item.id) { mutableStateOf(item.tags.joinToString(", ")) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (item.mediaType == "video") Icons.Default.Videocam else Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(item.mediaType.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentGreen)
                }
                IconButton(onClick = { onDiscard(item.id) }) {
                    Icon(Icons.Default.Close, contentDescription = "Discard".tr, tint = DestructiveRed, modifier = Modifier.size(18.dp))
                }
            }

            OutlinedTextField(
                value = editableTitle,
                onValueChange = { editableTitle = it },
                label = { Text("Title".tr) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            OutlinedTextField(
                value = editableDescription,
                onValueChange = { editableDescription = it },
                label = { Text("Description".tr) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            OutlinedTextField(
                value = editableTags,
                onValueChange = { editableTags = it },
                label = { Text("Tags (comma separated)".tr) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val updatedTags = editableTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    onPublish(item.copy(title = editableTitle, description = editableDescription, tags = updatedTags))
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Publish to Mesh 🚀".tr, fontWeight = FontWeight.Bold)
            }
        }
    }
}
