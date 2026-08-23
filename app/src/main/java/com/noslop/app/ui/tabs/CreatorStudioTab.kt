// FILE: app/src/main/java/com/noslop/app/ui/tabs/CreatorStudioTab.kt
package com.noslop.app.ui.tabs

import androidx.compose.foundation.background
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
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AccentGreen.copy(alpha = 0.2f)
                ) {
                    Text(
                        "AI Queue: ${queuedItems.size}".tr,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold
                    )
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
                            queuedItems = queuedItems.filter { it.id != publishedItem.id }
                            android.widget.Toast.makeText(context, "Published '${publishedItem.title}' to Mesh!", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDiscard = { discardedId ->
                            queuedItems = queuedItems.filter { it.id != discardedId }
                        }
                    )
                }
            }
        }
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
