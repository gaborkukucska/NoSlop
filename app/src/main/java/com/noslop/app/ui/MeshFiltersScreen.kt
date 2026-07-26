package com.noslop.app.ui

import com.noslop.app.util.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noslop.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshFiltersScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val settings by viewModel.meshFilterSettings.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
            }
            Text(
                text = "Mesh Filters".tr,
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
                Text(
                    text = "Control what gets sent and received over the mesh. This helps save bandwidth and reduce noise.".tr,
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            item {
                FilterCategorySingle(
                    title = "TEXT BROADCASTS",
                    description = "Standard text-only user broadcasts without attachments.",
                    incomingChecked = settings.allowIncomingTextPosts,
                    onIncomingChange = { viewModel.updateMeshFilterSettings(settings.copy(allowIncomingTextPosts = it)) }
                )
            }

            item {
                FilterCategorySingle(
                    title = "IMAGE BROADCASTS",
                    description = "Broadcasts containing user-generated images.",
                    incomingChecked = settings.allowIncomingImagePosts,
                    onIncomingChange = { viewModel.updateMeshFilterSettings(settings.copy(allowIncomingImagePosts = it)) }
                )
            }

            item {
                FilterCategorySingle(
                    title = "VIDEO BROADCASTS",
                    description = "Broadcasts containing user-generated videos.",
                    incomingChecked = settings.allowIncomingVideoPosts,
                    onIncomingChange = { viewModel.updateMeshFilterSettings(settings.copy(allowIncomingVideoPosts = it)) }
                )
            }

            item {
                FilterCategory(
                    title = "CLEARNET SHARES",
                    description = "Broadcasts sharing URLs from the regular internet.",
                    incomingChecked = settings.allowIncomingClearnetShares,
                    outgoingChecked = settings.allowOutgoingClearnetShares,
                    onIncomingChange = { viewModel.updateMeshFilterSettings(settings.copy(allowIncomingClearnetShares = it)) },
                    onOutgoingChange = { viewModel.updateMeshFilterSettings(settings.copy(allowOutgoingClearnetShares = it)) }
                )
            }
        }
    }
}

@Composable
fun FilterCategorySingle(
    title: String,
    description: String,
    incomingChecked: Boolean,
    onIncomingChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AccentGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Allow Incoming".tr, color = TextLight, style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = incomingChecked,
                    onCheckedChange = onIncomingChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PrimaryBlack,
                        checkedTrackColor = AccentGreen,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SurfaceDark,
                        uncheckedBorderColor = BorderSubtle
                    )
                )
            }
        }
    }
}

@Composable
fun FilterCategory(
    title: String,
    description: String,
    incomingChecked: Boolean,
    outgoingChecked: Boolean,
    onIncomingChange: (Boolean) -> Unit,
    onOutgoingChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AccentGreen,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Allow Incoming".tr, color = TextLight, style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = incomingChecked,
                    onCheckedChange = onIncomingChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PrimaryBlack,
                        checkedTrackColor = AccentGreen,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SurfaceDark,
                        uncheckedBorderColor = BorderSubtle
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Allow Outgoing".tr, color = TextLight, style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = outgoingChecked,
                    onCheckedChange = onOutgoingChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PrimaryBlack,
                        checkedTrackColor = AccentGreen,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = SurfaceDark,
                        uncheckedBorderColor = BorderSubtle
                    )
                )
            }
        }
    }
}
