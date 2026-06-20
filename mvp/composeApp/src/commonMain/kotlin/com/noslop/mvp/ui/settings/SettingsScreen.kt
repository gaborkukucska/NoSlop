package com.noslop.mvp.ui.settings

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.data.SettingsRepository
import com.noslop.mvp.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onNavigateToPreferences: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    val mediaSettings by repository.mediaSettingsFlow.collectAsState()
    val isSendOnEnterEnabled by repository.isSendOnEnterEnabled.collectAsState()
    val isContentTransparencyEnabled by repository.isContentTransparencyEnabled.collectAsState()
    val isAggregatorEnabled by repository.isAggregatorEnabled.collectAsState()
    val isForegroundServiceEnabled by repository.isForegroundServiceEnabled.collectAsState()
    val notificationSettings by repository.notificationSettingsFlow.collectAsState()

    LaunchedEffect(Unit) {
        repository.getMediaSettings()
        repository.getNotificationSettings()
        repository.initForegroundServiceSetting()
        repository.initSendOnEnterSetting()
        repository.initContentTransparencySetting()
        repository.initAggregatorSetting()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "System Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextLight,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
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
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToPreferences() }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Face, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("Profile & Content Preferences", fontWeight = FontWeight.Bold, color = TextLight)
                            }
                        }
                        
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Text("Send Chat on Enter", fontWeight = FontWeight.Bold, color = TextLight)
                                Text(
                                    "Pressing enter on the on-screen keyboard sends the message immediately.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            Switch(
                                checked = isSendOnEnterEnabled,
                                onCheckedChange = { scope.launch { repository.setSendOnEnterEnabled(it) } },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PrimaryBlack,
                                    checkedTrackColor = AccentGreen,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = SurfaceDark
                                )
                            )
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
                                onCheckedChange = { scope.launch { repository.updateMediaSettings(mediaSettings.copy(enabled = it)) } },
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
                                onValueChange = { scope.launch { repository.updateMediaSettings(mediaSettings.copy(maxFileSizeMB = it.toInt())) } },
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
                                    onCheckedChange = { scope.launch { repository.updateMediaSettings(mediaSettings.copy(autoDownloadFriends = it)) } },
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
                                    onCheckedChange = { scope.launch { repository.updateMediaSettings(mediaSettings.copy(autoDownloadPrivate = it)) } },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                        }

                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

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
                                    onCheckedChange = { scope.launch { repository.setContentTransparencyEnabled(it) } },
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
                                onCheckedChange = { scope.launch { repository.setAggregatorEnabled(it) } },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PrimaryBlack,
                                    checkedTrackColor = AccentGreen,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = SurfaceDark
                                )
                            )
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
                                onCheckedChange = { scope.launch { repository.setForegroundServiceEnabled(it) } },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PrimaryBlack,
                                    checkedTrackColor = AccentGreen,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = SurfaceDark
                                )
                            )
                        }
                        
                        HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                        Text("Notifications", color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Direct Messages", color = TextLight, fontSize = 14.sp)
                            Switch(
                                checked = notificationSettings.dms,
                                onCheckedChange = { scope.launch { repository.updateNotificationSettings(notificationSettings.copy(dms = it)) } },
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
                                onCheckedChange = { scope.launch { repository.updateNotificationSettings(notificationSettings.copy(comments = it)) } },
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
                                onCheckedChange = { scope.launch { repository.updateNotificationSettings(notificationSettings.copy(mentions = it)) } },
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
                                onCheckedChange = { scope.launch { repository.updateNotificationSettings(notificationSettings.copy(system = it)) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                            )
                        }
                    }
                }
            }
        }
    }
}
