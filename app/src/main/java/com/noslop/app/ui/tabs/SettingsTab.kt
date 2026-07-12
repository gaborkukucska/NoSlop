// app/src/main/java/com/noslop/app/ui/tabs/SettingsTab.kt
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
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import com.noslop.app.util.tr // Added translation extension
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun SettingsTab(viewModel: NoSlopViewModel) {
    val torState by viewModel.torReadyState.collectAsState()
    val isTorChecking by viewModel.isTorChecking.collectAsState()
    val mediaSettings by viewModel.mediaSettings.collectAsState()
    val isEncryptionActive by viewModel.isEncryptionActive.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val hubDeploymentStatus by viewModel.hubDeploymentStatus.collectAsState()
    val context = LocalContext.current

    var selectedSettingsScreen by remember { mutableStateOf(0) }
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabTitles = listOf("General".tr, "Network".tr, "Content".tr, "System".tr)
    var showDonationModal by remember { mutableStateOf(false) }
    var showAboutModal by remember { mutableStateOf(false) }

    if (selectedSettingsScreen == 1) {
        LogsViewerScreen(viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 3) {
        ApiKeysScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 5) {
        ContentPreferencesScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 6) {
        ReportIssueScreen(onBack = { selectedSettingsScreen = 0 })
    } else if (selectedSettingsScreen == 7) {
        MeshFiltersScreen(viewModel = viewModel, onBack = { selectedSettingsScreen = 0 })
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "System Settings".tr,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = SurfaceDark,
                contentColor = AccentGreen,
                edgePadding = 8.dp,
                modifier = Modifier.padding(bottom = 16.dp),
                divider = { HorizontalDivider(color = BorderSubtle) },
                indicator = { tabPositions ->
                    if (selectedTabIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = AccentGreen
                        )
                    }
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontWeight = FontWeight.Bold) },
                        selectedContentColor = AccentGreen,
                        unselectedContentColor = TextMuted
                    )
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (selectedTabIndex == 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).clickable { showDonationModal = true },
                        colors = CardDefaults.cardColors(containerColor = AccentGreen.copy(alpha = 0.1f)),
                        border = BorderStroke(1.dp, AccentGreen)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = AccentGreen)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Help Development".tr, fontWeight = FontWeight.Bold, color = AccentGreen)
                                Text("Support Gabby's work with a small donation".tr, style = MaterialTheme.typography.bodySmall, color = TextLight)
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
                                    Text("Security Warning".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text("Hardware-backed encryption is unavailable. Your keys are stored in plaintext.".tr, style = MaterialTheme.typography.bodySmall, color = TextLight)
                                }
                            }
                        }
                    }
                }

                }
                if (selectedTabIndex == 1) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "TOR ROUTING STATUS".tr,
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
                                            text = if (torState.first) "Active Tor Proxy".tr else "Tor Disconnected".tr,
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
                                    Text("Test Tor".tr, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                }
                if (selectedTabIndex == 0) {
                item {
                    Text(
                        text = "ACCOUNT & PREFERENCES".tr,
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

                            // APP LANGUAGE SELECTOR
                            val appLanguage by viewModel.appLanguage.collectAsState()
                            val availableLanguages = listOf("en" to "English", "hu" to "Magyar")
                            var expandedLang by remember { mutableStateOf(false) }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("App Language".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Change the interface language.".tr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                
                                Box {
                                    OutlinedButton(
                                        onClick = { expandedLang = true },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                                        border = BorderStroke(1.dp, BorderSubtle),
                                        contentPadding = PaddingValues(horizontal = 12.dp)
                                    ) {
                                        Text(availableLanguages.find { it.first == appLanguage }?.second ?: appLanguage)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = expandedLang,
                                        onDismissRequest = { expandedLang = false },
                                        modifier = Modifier.background(SurfaceDark)
                                    ) {
                                        availableLanguages.forEach { (code, name) ->
                                            DropdownMenuItem(
                                                text = { Text(name, color = TextLight) },
                                                onClick = {
                                                    viewModel.updateAppLanguage(code)
                                                    expandedLang = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 5 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Face, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Profile & Preferences".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                            
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { selectedSettingsScreen = 7 }.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FilterAlt, contentDescription = null, tint = AccentGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Mesh Filters".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                            
                            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                            
                            val isSendOnEnterEnabled by viewModel.isSendOnEnterEnabled.collectAsState()
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Send Chat on Enter".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Pressing enter on the on-screen keyboard sends the message immediately.".tr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                Switch(
                                    checked = isSendOnEnterEnabled,
                                    onCheckedChange = { viewModel.setSendOnEnterEnabled(it) },
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

                }
                if (selectedTabIndex == 2) {
                item {
                    Text(
                        text = "MEDIA & PRIVACY".tr,
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
                                Text("Automatic Media Download".tr, color = TextLight, fontWeight = FontWeight.Bold)
                                Switch(
                                    checked = mediaSettings.enabled,
                                    onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(enabled = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            if (mediaSettings.enabled) {
                                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))
                                
                                Text(
                                    text = "Max File Size: ".tr + "${mediaSettings.maxFileSizeMB} MB",
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
                                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text("Friends".tr, color = TextLight, fontSize = 14.sp)
                                        Text("only auto-download media from contacts".tr, color = TextMuted, fontSize = 12.sp)
                                    }
                                    Switch(
                                        checked = mediaSettings.autoDownloadFriends,
                                        onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadFriends = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                                
                                var showPublicMediaWarning by remember { mutableStateOf(false) }
                                
                                if (showPublicMediaWarning) {
                                    AlertDialog(
                                        onDismissRequest = { showPublicMediaWarning = false },
                                        containerColor = SurfaceDark,
                                        title = { Text("Enable Public Media?".tr, color = TextLight, fontWeight = FontWeight.Bold) },
                                        text = { Text("You'll download 3rd party media!".tr, color = TextMuted) },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    showPublicMediaWarning = false
                                                    viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadPublic = true))
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack)
                                            ) {
                                                Text("Accept".tr, color = AccentGreen)
                                            }
                                        },
                                        dismissButton = {
                                            Button(
                                                onClick = { showPublicMediaWarning = false },
                                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                                            ) {
                                                Text("Cancel".tr, color = TextMuted)
                                            }
                                        }
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text("Public".tr, color = TextLight, fontSize = 14.sp)
                                        Text("Also auto-download media from public broadcasts".tr, color = TextMuted, fontSize = 12.sp)
                                    }
                                    Switch(
                                        checked = mediaSettings.autoDownloadPublic,
                                        onCheckedChange = { 
                                            if (it) {
                                                showPublicMediaWarning = true
                                            } else {
                                                viewModel.updateMediaSettings(mediaSettings.copy(autoDownloadPublic = false))
                                            }
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                            }
                        }
                    }

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
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Background Playback".tr, color = TextLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text("Keep audio/video playing while browsing other tabs.".tr, color = TextMuted, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = mediaSettings.backgroundPlayEnabled,
                                    onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(backgroundPlayEnabled = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            if (mediaSettings.backgroundPlayEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text("Play Outside App".tr, color = TextLight, fontSize = 14.sp)
                                        Text("Continue playing when NoSlop is minimized.".tr, color = TextMuted, fontSize = 12.sp)
                                    }
                                    Switch(
                                        checked = mediaSettings.backgroundPlayOutsideApp,
                                        onCheckedChange = { viewModel.updateMediaSettings(mediaSettings.copy(backgroundPlayOutsideApp = it)) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                    )
                                }
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Content Transparency Toggle
                            val isContentTransparencyEnabled by viewModel.isContentTransparencyEnabled.collectAsState()
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text("Opt-in Transparency".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                        Text(
                                            "When enabled, community-flagged content shows a warning badge instead of a blocking overlay, letting you decide what to view.".tr,
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

                }
                if (selectedTabIndex == 1) {
                item {
                    Text(
                        text = "MESH DISCOVERABILITY".tr,
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
                            val isDiscoverableEnabled by viewModel.isDiscoverableEnabled.collectAsState()
                            
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("Discoverable Mode".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Broadcast your ephemeral identity to the mesh so others can find you without a direct connection code.".tr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                                Switch(
                                    checked = isDiscoverableEnabled,
                                    onCheckedChange = { viewModel.setDiscoverableEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = PrimaryBlack,
                                        checkedTrackColor = AccentGreen,
                                        uncheckedThumbColor = TextMuted,
                                        uncheckedTrackColor = SurfaceDark
                                    )
                                )
                            }
                            
                            if (isDiscoverableEnabled) {
                                HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
                                
                                val isCreatorEnabled by viewModel.isCreatorEnabled.collectAsState()
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                        Text("Creator Node".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                        Text(
                                            "Automatically accept connections and lock your media from being purged by auto-cleanup.".tr,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                    Switch(
                                        checked = isCreatorEnabled,
                                        onCheckedChange = { viewModel.setCreatorEnabled(it) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = PrimaryBlack,
                                            checkedTrackColor = AccentGreen,
                                            uncheckedThumbColor = TextMuted,
                                            uncheckedTrackColor = SurfaceDark
                                        )
                                    )
                                }

                                if (isCreatorEnabled) {
                                    val creatorFundMeLink by viewModel.creatorFundMeLink.collectAsState()
                                    OutlinedTextField(
                                        value = creatorFundMeLink,
                                        onValueChange = { viewModel.setCreatorFundMeLink(it) },
                                        label = { Text("Donation Link (Optional)".tr) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = AccentGreen,
                                            unfocusedBorderColor = BorderSubtle,
                                            focusedTextColor = TextLight,
                                            unfocusedTextColor = TextLight
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                }
                if (selectedTabIndex == 2) {
                item {
                    Text(
                        text = "CONTENT AGGREGATOR".tr,
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
                                    Text("Clearnet Aggregator".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Automatically fetch content from RSS feeds and public APIs to mix with your mesh timeline.".tr,
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
                                    Text("API Keys".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }

                }
                if (selectedTabIndex == 0) {
                item {
                    Text(
                        text = "SYSTEM & NOTIFICATIONS".tr,
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
                                    Text("Foreground Service".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Keep NoSlop running in the background for uninterrupted mesh sync and media auto-downloads.".tr,
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
                            
                            Text("Notifications".tr, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Direct Messages".tr, color = TextLight, fontSize = 14.sp)
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
                                Text("Comments & Replies".tr, color = TextLight, fontSize = 14.sp)
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
                                Text("Mentions".tr, color = TextLight, fontSize = 14.sp)
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
                                Text("Reactions".tr, color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.reactions,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(reactions = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Connection Requests".tr, color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.connectionRequests,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(connectionRequests = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("System Alerts".tr, color = TextLight, fontSize = 14.sp)
                                Switch(
                                    checked = notificationSettings.system,
                                    onCheckedChange = { viewModel.updateNotificationSettings(notificationSettings.copy(system = it)) },
                                    colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                                )
                            }
                        }
                    }
                }

                }
                if (selectedTabIndex == 1) {
                if (!hubDeploymentStatus.isNullOrBlank()) {
                    item {
                        Text(
                            text = "HAI-NET HUB".tr,
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
                            var showUnlinkConfirm by remember { mutableStateOf(false) }

                            if (showUnlinkConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showUnlinkConfirm = false },
                                    containerColor = SurfaceDark,
                                    title = { Text("Disconnect Hub?".tr, color = TextLight) },
                                    text = { Text("This will unlink your HAI-Net Hub from this app. You will need to re-deploy or configure it manually to reconnect.".tr, color = TextMuted) },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                showUnlinkConfirm = false
                                                viewModel.setHubDeploymentStatus("")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                                        ) {
                                            Text("Disconnect".tr, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showUnlinkConfirm = false }) {
                                            Text("Cancel".tr, color = AccentGreen)
                                        }
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showUnlinkConfirm = true }
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LinkOff, contentDescription = null, tint = DestructiveRed)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Disconnect HAI-Net Hub".tr, fontWeight = FontWeight.Bold, color = DestructiveRed)
                                        Text("Unlink this device from your active Hub".tr, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                    }
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
                            }
                        }
                    }
                }

                }
                if (selectedTabIndex == 3) {
                item {
                    Text(
                        text = "DEVELOPER".tr,
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
                                    Text("Structured Debug Logs".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text("Examine packet drops, network, parser info.".tr, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .clickable { selectedSettingsScreen = 6 },
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BugReport, contentDescription = null, tint = AccentGreen)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("File a Bug Report".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text("Report issues or request features via GitHub.".tr, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TextMuted)
                        }
                    }
                }

                item {
                    Text(
                        text = "DATA & BACKUP".tr,
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
                            var showMnemonicDialog by remember { mutableStateOf(false) }
                            var isExporting by remember { mutableStateOf(false) }
                            var mnemonicInput by remember { mutableStateOf("") }
                            var showImportWarning by remember { mutableStateOf(false) }
                            var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
                            var importStatus by remember { mutableStateOf<String?>(null) }

                            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                                if (uri != null && mnemonicInput.isNotBlank()) {
                                    viewModel.exportBackupToUri(context, mnemonicInput, uri)
                                    mnemonicInput = ""
                                }
                            }

                            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                                if (uri != null && mnemonicInput.isNotBlank()) {
                                    pendingImportUri = uri
                                    showImportWarning = true
                                }
                            }

                            Button(
                                onClick = { 
                                    isExporting = true
                                    showMnemonicDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Export Profile (Zip)".tr, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { 
                                    isExporting = false
                                    showMnemonicDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlack, contentColor = AccentGreen),
                                border = BorderStroke(1.dp, AccentGreen)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import Profile (Zip)".tr, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            HorizontalDivider(color = BorderSubtle)
                            Spacer(modifier = Modifier.height(24.dp))

                            if (showMnemonicDialog) {
                                AlertDialog(
                                    onDismissRequest = { showMnemonicDialog = false },
                                    title = { Text(if (isExporting) "Export Backup".tr else "Import Backup".tr) },
                                    text = {
                                        Column {
                                            Text("Please enter your Word Cloud password to encrypt/decrypt the backup.".tr, color = TextMuted)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = mnemonicInput,
                                                onValueChange = { mnemonicInput = it },
                                                label = { Text("Word Cloud (BIP39 Mnemonic)".tr) },
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = AccentGreen,
                                                    unfocusedBorderColor = BorderSubtle,
                                                    focusedTextColor = TextLight,
                                                    unfocusedTextColor = TextLight
                                                )
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showMnemonicDialog = false
                                                if (isExporting) {
                                                    exportLauncher.launch("noslop_backup.zip")
                                                } else {
                                                    importLauncher.launch(arrayOf("application/zip"))
                                                }
                                            },
                                            enabled = mnemonicInput.isNotBlank()
                                        ) {
                                            Text(if (isExporting) "Save File".tr else "Select File".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showMnemonicDialog = false }) {
                                            Text("Cancel".tr, color = TextMuted)
                                        }
                                    },
                                    containerColor = SurfaceDark,
                                    titleContentColor = TextLight
                                )
                            }

                            // ─── Import Destructive Warning ───
                            if (showImportWarning && pendingImportUri != null) {
                                AlertDialog(
                                    onDismissRequest = { showImportWarning = false; pendingImportUri = null },
                                    title = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Warning, contentDescription = null, tint = DestructiveRed, modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Destructive Import".tr, color = DestructiveRed, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    text = {
                                        Column {
                                            Text(
                                                "This will permanently wipe your current identity, keys, contacts, and all data. ".tr +
                                                "The selected backup will be imported in its place.".tr,
                                                color = TextMuted
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                "The app will restart automatically after import.".tr,
                                                color = TextLight,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                showImportWarning = false
                                                importStatus = "Importing..."
                                                viewModel.importBackupFromUri(context, mnemonicInput, pendingImportUri!!) { success ->
                                                    if (!success) {
                                                        importStatus = "Import failed. Check password and file."
                                                    }
                                                }
                                                mnemonicInput = ""
                                                pendingImportUri = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
                                        ) {
                                            Text("Wipe & Import".tr, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showImportWarning = false; pendingImportUri = null }) {
                                            Text("Cancel".tr, color = AccentGreen)
                                        }
                                    },
                                    containerColor = SurfaceDark
                                )
                            }

                            if (importStatus != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(importStatus!!.tr, color = if (importStatus!!.contains("failed")) DestructiveRed else AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { showFactoryResetConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = TextLight)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("FACTORY RESET".tr, fontWeight = FontWeight.Bold)
                            }

                            if (showFactoryResetConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showFactoryResetConfirm = false },
                                    title = { Text("Nuclear Option".tr) },
                                    text = { Text("This will wipe all your keys, contacts, settings, and feed data. It cannot be undone without a backup mnemonic. Are you sure?".tr, color = TextMuted) },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showFactoryResetConfirm = false
                                                viewModel.factoryReset()
                                            }
                                        ) {
                                            Text("WIPE EVERYTHING".tr, color = DestructiveRed, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showFactoryResetConfirm = false }) {
                                            Text("Cancel".tr, color = AccentGreen)
                                        }
                                    },
                                    containerColor = SurfaceDark,
                                    titleContentColor = TextLight
                                )
                            }
                        }
                    }
                }
                item {
                    TextButton(
                        onClick = { showAboutModal = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp)
                    ) {
                        Text("About NoSlop".tr, color = TextMuted)
                    }
                }
                } // End Tab 3
            }
        }
    }

    if (showDonationModal) {
        AlertDialog(
            onDismissRequest = { showDonationModal = false },
            containerColor = SurfaceDark,
            title = {
                Text("Support NoSlop".tr, color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("NoSlop is entirely free and open-source. If you enjoy using it, consider buying Gabby (the founder) a coffee! Your support helps cover development time.".tr, color = TextMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Even \$1 makes a difference!".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://donate.stripe.com/dRmfZae1F0jNfPNfFC9fW00")))
                            showDonationModal = false
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buy Gabby a Coffee".tr, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDonationModal = false }) {
                    Text("Close".tr, color = TextMuted)
                }
            }
        )
    }

    if (showAboutModal) {
        val versionName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "Unknown".tr }
        AlertDialog(
            onDismissRequest = { showAboutModal = false },
            containerColor = SurfaceDark,
            title = {
                Text("About NoSlop".tr, color = TextLight, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Version ".tr + versionName, color = AccentGreen, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (updateInfo != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, DestructiveRed)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = DestructiveRed)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Update Available".tr, fontWeight = FontWeight.Bold, color = TextLight)
                                    Text(
                                        "Version ".tr + "${updateInfo!!.latestVersion} " + "is out (you have ".tr + "${updateInfo!!.currentVersion}). " + "Tap to download the new APK.".tr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextLight
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(updateInfo!!.downloadUrl)
                                        )
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed)
                                ) {
                                    Text("Download".tr)
                                }
                            }
                        }
                    }

                    Text("NoSlop is a privacy-first, serverless mesh network and content aggregator. It routes all communication over Tor by default and keeps your identity cryptographically secure on your device.".tr, color = TextMuted, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Resources".tr, color = TextLight, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "GitHub Repository".tr, 
                        color = AccentGreen, 
                        modifier = Modifier.clickable { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/gaborkukucska/NoSlop"))) }.padding(vertical = 4.dp)
                    )
                    Text(
                        "Privacy Policy".tr, 
                        color = AccentGreen, 
                        modifier = Modifier.clickable { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/gaborkukucska/NoSlop/blob/main/docs/PRIVACY_POLICY.md"))) }.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = BorderSubtle)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Imagined by Gabor Kukucska".tr, 
                        color = TextLight, 
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://gaborkukucska.com"))) }.padding(vertical = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutModal = false }) {
                    Text("Close".tr, color = TextMuted)
                }
            }
        )
    }
}
