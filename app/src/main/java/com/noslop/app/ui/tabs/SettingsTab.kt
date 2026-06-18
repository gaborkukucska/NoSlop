package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import androidx.compose.ui.graphics.Color
import com.noslop.app.ui.*
import com.noslop.app.ui.components.*

@Composable
fun SettingsTab(viewModel: NoSlopViewModel) {
    val torState by viewModel.torReadyState.collectAsState()
    val isTorChecking by viewModel.isTorChecking.collectAsState()
    val mediaSettings by viewModel.mediaSettings.collectAsState()
    val isEncryptionActive by viewModel.isEncryptionActive.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val context = LocalContext.current

    var selectedSettingsScreen by remember { mutableStateOf(0) }

    if (selectedSettingsScreen == 1) {
        LogsViewerScreen(viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 3) {
        ApiKeysScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 5) {
        ContentPreferencesScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "System Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (updateInfo != null) {
                    item {
                        val info = updateInfo!!
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, DestructiveRed)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = DestructiveRed)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Update Available", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Version ${info.latestVersion} is out (you have ${info.currentVersion}). Tap to download the new APK.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextLight
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(info.downloadUrl)
                                        )
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed)
                                ) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }

                if (!isEncryptionActive) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, DestructiveRed)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = DestructiveRed)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text("Security Warning", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text("Hardware-backed encryption is unavailable. Your keys are stored in plaintext.", style = MaterialTheme.typography.bodySmall, color = TextLight)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "TOR ROUTING STATUS",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = TextMuted,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (torState.first) AccentGreen else DestructiveRed)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (torState.first) "Active Tor Proxy" else "Tor Disconnected",
                                            fontWeight = FontWeight.Bold,
                                            color = TextLight
                                        )
                                    }
                                    Text(
                                        text = torState.second,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }

                                Button(
                                    onClick = { viewModel.refreshTorStatus() },
                                    enabled = !isTorChecking,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text("Test Tor", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "ACCOUNT & PREFERENCES",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 5 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Face, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Profile & Preferences", fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "MEDIA & PRIVACY",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable Media", color = TextLight, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = mediaSettings.enabled,
                                    onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(enabled = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            if (mediaSettings.enabled) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Max File Size: ${mediaSettings.maxFileSizeMB} MB",
                                    color = TextLight,
                                    fontSize = 14.sp
                                )
                                Slider(
                                    value = mediaSettings.maxFileSizeMB.toFloat(),
                                    onValueChange = { viewModel.updateMediaSettings(mediaSettings.copy(maxFileSizeMB = it.toInt())) },
                                    valueRange = 1f..100f,
                                    colors = SliderDefaults.colors(thumbColor = AccentGreen, activeTrackColor = AccentGreen)
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Auto-download Friends", color = TextMuted, fontSize = 14.sp)
                                    Switch(
                                        checked = mediaSettings.autoDownloadFriends,
                                        onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadFriends = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Auto-download Private", color = TextMuted, fontSize = 14.sp)
                                    Switch(
                                        checked = mediaSettings.autoDownloadPrivate,
                                        onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadPrivate = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                            }

                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

                            // Content Transparency Toggle
                            val isContentTransparencyEnabled by viewModel.isContentTransparencyEnabled.collectAsState()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text("Opt-in Transparency", fontWeight = FontWeight.Bold, color = TextLight)
                                        Text(
                                            "When enabled, community-flagged content shows a warning badge instead of a blocking overlay, letting you decide what to view.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                    Switch(
                                        checked = isContentTransparencyEnabled,
                                        onCheckedChange = { viewModel.toggleContentTransparency() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = PrimaryBlack,
                                            checkedTrackColor = Color.Yellow,
                                            uncheckedThumbColor = TextMuted,
                                            uncheckedTrackColor = SurfaceDark
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "CONTENT AGGREGATOR",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val isAggregatorEnabled by viewModel.isAggregatorEnabled.collectAsState()
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Clearnet Aggregator", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Automatically fetch content from RSS feeds and public APIs to mix with your mesh timeline.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                Switch(
                                    checked = isAggregatorEnabled,
                                    onCheckedChange = { viewModel.toggleAggregator() },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PrimaryBlack,
                                        checkedTrackColor = AccentGreen,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = SurfaceDark
                                    )
                                )
                            }
                            
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 3 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("API Keys", fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "SYSTEM & NOTIFICATIONS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            val isForegroundServiceEnabled by viewModel.isForegroundServiceEnabled.collectAsState()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Foreground Service", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Keep NoSlop running in the background for uninterrupted mesh sync and media playback.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                Switch(
                                    checked = isForegroundServiceEnabled,
                                    onCheckedChange = { viewModel.setForegroundServiceEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PrimaryBlack,
                                        checkedTrackColor = AccentGreen,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = SurfaceDark
                                    )
                                )
                            }
                            
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                            val notificationSettings by viewModel.notificationSettings.collectAsState()
                            
                            Text("Notifications", color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Direct Messages", color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.dms,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(dms = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Comments & Replies", color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.comments,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(comments = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Mentions", color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.mentions,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(mentions = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("System Alerts", color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.system,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(system = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "DEVELOPER",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedSettingsScreen = 1 },
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Structured Debug Logs", fontWeight = FontWeight.Bold, color = TextLight)
                                    Text("Examine packet drops, network, parser info.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted)
                        }
                    }
                }

                item {
                    Text(
                        text = "DATA & BACKUP",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = TextMuted,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            var showFactoryResetConfirm by remember { mutableStateOf(false) }

                            Button(
                                onClick = { /* Export Trigger */ },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Export Profile (Zip)", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { /* Import Trigger */ },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import Profile (Zip)", fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = { showFactoryResetConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = TextLight)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("FACTORY RESET", fontWeight = FontWeight.Bold)
                            }

                            if (showFactoryResetConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showFactoryResetConfirm = false },
                                    title = { Text("Nuclear Option") },
                                    text = { Text("This will wipe all your keys, contacts, settings, and feed data. It cannot be undone without a backup mnemonic. Are you sure?", color = TextMuted) },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showFactoryResetConfirm = false
                                                viewModel.factoryReset()
                                            }
                                        ) {
                                            Text("WIPE EVERYTHING", color = DestructiveRed, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showFactoryResetConfirm = false }) {
                                            Text("Cancel", color = AccentGreen)
                                        }
                                    },
                                    containerColor = SurfaceDark,
                                    titleContentColor = TextLight
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}