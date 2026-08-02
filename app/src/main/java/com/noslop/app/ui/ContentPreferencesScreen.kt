package com.noslop.app.ui

import com.noslop.app.util.tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noslop.app.data.UserProfile
import com.noslop.app.data.FeedSource
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.ui.theme.*
import androidx.compose.ui.graphics.asImageBitmap

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContentPreferencesScreen(
    viewModel: NoSlopViewModel,
    onBack: () -> Unit,
    onNavigateToMeshFilters: () -> Unit
) {
    val currentNegativeKeywords by viewModel.negativeKeywords.collectAsState()
    val currentLanguage by viewModel.languagePreference.collectAsState()
    val currentCreatorKeywords by viewModel.creatorKeywords.collectAsState()
    val isUsingInsecureStorage by viewModel.isUsingInsecureStorage.collectAsState()

    var negativeKeywords by remember { mutableStateOf(currentNegativeKeywords) }
    var language by remember { mutableStateOf(currentLanguage) }
    var creatorKeywords by remember { mutableStateOf(currentCreatorKeywords) }

    // Categories & genres state
    val interests by viewModel.selectedInterests.collectAsState()
    val musicGenres by viewModel.selectedMusicGenres.collectAsState()
    val videoGenres by viewModel.selectedVideoGenres.collectAsState()

    val localInterests = remember { mutableStateListOf<String>().apply { addAll(interests) } }
    val localMusicGenres = remember { mutableStateListOf<String>().apply { addAll(musicGenres) } }
    val localVideoGenres = remember { mutableStateListOf<String>().apply { addAll(videoGenres) } }

    val allMusicGenres = listOf(
        "Electronic", "Ambient", "Rock", "Lo-Fi", "Classical", "Hip-Hop", "Jazz", "Pop",
        "Metal", "R&B", "Country", "Reggae", "Blues", "Indie", "Soul", "Punk"
    )
    val allVideoGenres = listOf(
        "Education", "Tech", "Gaming", "Science", "Entertainment", "News", "Documentary",
        "Comedy", "Music Videos", "Sports", "Travel", "DIY & How-To", "Animation", "Film & Cinema"
    )

    // Sources state
    val sources by viewModel.allSources.collectAsState(initial = emptyList())
    var showSourceManager by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back".tr, tint = AccentGreen)
            }
            Text(
                text = "Settings".tr,
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (isUsingInsecureStorage) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = DestructiveRed.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, DestructiveRed)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Warning".tr, tint = DestructiveRed, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("SECURITY WARNING".tr, color = DestructiveRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Hardware-backed Keystore is unavailable on this device. Your identity and private keys are currently stored in PLAINTEXT. Do not use this device for sensitive communication.".tr, color = TextLight, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ────────────────── CONTENT MIX RATIO ──────────────────
            item {
                com.noslop.app.ui.tabs.FeedMixSettingsSection(viewModel = viewModel)
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onNavigateToMeshFilters,
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FilterAlt, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mesh Filters".tr, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ────────────────── CONTENT SOURCES ──────────────────
            item {
                Text("CONTENT SOURCES".tr, style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                val activeSources = sources.filter { it.isActive }
                if (activeSources.isEmpty()) {
                    Text("No active sources.".tr, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text = activeSources.joinToString(", ") { it.title },
                        color = TextLight,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showSourceManager = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
                    border = BorderStroke(1.dp, BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage Sources".tr, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ────────────────── NEGATIVE KEYWORDS ──────────────────
            item {
                Text("FILTERING".tr, style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = negativeKeywords,
                    onValueChange = { negativeKeywords = it },
                    label = { Text("Negative Keywords (comma separated)".tr) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ────────────────── CATEGORIES ──────────────────
            item {
                Text("CATEGORIES".tr, style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }

            items(SourceLibrary.selectableCategories) { category ->
                val isSelected = localInterests.contains(category)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        if (isSelected) localInterests.remove(category) else localInterests.add(category)
                    },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceDark else PrimaryBlack),
                    border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(category, color = TextLight)
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (it) localInterests.add(category) else localInterests.remove(category)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                        )
                    }
                }
            }

            // ────────────────── MUSIC GENRES ──────────────────
            if (localInterests.contains("Music")) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("MUSIC GENRES".tr, style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(allMusicGenres) { genre ->
                    val isSelected = localMusicGenres.contains(genre)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            if (isSelected) localMusicGenres.remove(genre) else localMusicGenres.add(genre)
                        },
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceDark else PrimaryBlack),
                        border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(genre, color = TextLight)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) localMusicGenres.add(genre) else localMusicGenres.remove(genre)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                            )
                        }
                    }
                }
            }

            // ────────────────── VIDEO GENRES ──────────────────
            // Video Platforms is always included — always show video genre options
            run {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("VIDEO GENRES".tr, style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(allVideoGenres) { genre ->
                    val isSelected = localVideoGenres.contains(genre)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            if (isSelected) localVideoGenres.remove(genre) else localVideoGenres.add(genre)
                        },
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceDark else PrimaryBlack),
                        border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(genre, color = TextLight)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) localVideoGenres.add(genre) else localVideoGenres.remove(genre)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                            )
                        }
                    }
            }
            }



            // Creator word-cloud suggestion chips (derived from current selected interests)
            // Shown ABOVE the text field so users pick from suggestions first
            item {
                var channelSearchQuery by remember { mutableStateOf("") }
                var searchedChannels by remember { mutableStateOf<List<String>>(emptyList()) }
                var isSearchingChannels by remember { mutableStateOf(false) }

                // Pre-warm the Invidious instance cache so the first search is fast
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try { com.noslop.app.feeds.api.InvidiousApiClient.preWarmInstances() } catch (_: Exception) {}
                    }
                }

                LaunchedEffect(channelSearchQuery) {
                    if (channelSearchQuery.isBlank()) {
                        searchedChannels = emptyList()
                        isSearchingChannels = false
                        return@LaunchedEffect
                    }
                    isSearchingChannels = true
                    kotlinx.coroutines.delay(300) // Debounce typing
                    try {
                        val ytChannels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.noslop.app.feeds.api.YouTubeInternalClient.searchChannels(channelSearchQuery).take(3) }
                        if (ytChannels.isNotEmpty()) {
                            searchedChannels = ytChannels
                        } else {
                            throw Exception("YouTube returned empty")
                        }
                    } catch (e: Exception) {
                        com.noslop.app.debug.Logger.error("CONTENT_PREFS", "Channel search failed: ${e.message}, trying fallback")
                        try {
                            val fallback = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.noslop.app.feeds.api.RedditApiClient.searchReddit(channelSearchQuery, "relevance")
                                    .mapNotNull { it.author }
                                    .distinct()
                                    .take(3)
                            }
                            searchedChannels = fallback
                        } catch (e2: Exception) {
                            com.noslop.app.debug.Logger.error("CONTENT_PREFS", "Fallback search failed: ${e2.message}")
                            searchedChannels = emptyList()
                        }
                    } finally {
                        isSearchingChannels = false
                    }
                }
                
                val suggestions = SourceLibrary.getSuggestedCreatorsForCategories(localInterests)
                val combinedSuggestions = (searchedChannels + suggestions).distinct()

                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = channelSearchQuery,
                        onValueChange = { channelSearchQuery = it },
                        label = { Text("Search channel names...".tr) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
                        trailingIcon = {
                            if (isSearchingChannels) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
                            } else if (channelSearchQuery.isNotBlank()) {
                                IconButton(onClick = { channelSearchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear".tr, tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight,
                            focusedLabelColor = AccentGreen,
                            unfocusedLabelColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    if (combinedSuggestions.isNotEmpty()) {
                        Text(
                            "SUGGESTED CHANNELS & CREATORS".tr,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            combinedSuggestions.forEach { creator ->
                                val currentSet = creatorKeywords.split(",")
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .toSet()
                                val isSelected = currentSet.contains(creator)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val updated = if (isSelected) currentSet - creator else currentSet + creator
                                        creatorKeywords = updated.joinToString(", ")
                                    },
                                    label = {
                                        Text(
                                            text = creator,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = PrimaryBlack,
                                        labelColor = TextLight,
                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f),
                                        selectedLabelColor = AccentGreen
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = if (isSelected) AccentGreen else BorderSubtle,
                                        selectedBorderColor = AccentGreen
                                    )
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // ────────────────── CREATOR TEXT FIELD ──────────────────
            item {
                OutlinedTextField(
                    value = creatorKeywords,
                    onValueChange = { creatorKeywords = it },
                    label = { Text("Creators / channels (comma separated)".tr) },
                    placeholder = { Text("e.g. Linus Tech Tips, Veritasium, Krebs...".tr) },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ────────────────── LANGUAGE ──────────────────
            item {
                var expanded by remember { mutableStateOf(false) }
                val languages = listOf(
                    "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
                    "it" to "Italiano", "pt" to "Português", "ru" to "Русский", "zh" to "中文",
                    "ja" to "日本語", "ko" to "한국어", "ar" to "العربية", "hi" to "हिन्दी",
                    "nl" to "Nederlands", "tr" to "Türkçe", "pl" to "Polski", "sv" to "Svenska",
                    "id" to "Bahasa Indonesia", "vi" to "Tiếng Việt", "th" to "ไทย", "el" to "Ελληνικά",
                    "hu" to "Magyar"
                )
                val selectedLangs = language.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val displayLanguage = if (selectedLangs.isEmpty()) "Any Language" else selectedLangs.mapNotNull { code -> languages.find { it.first == code }?.second }.joinToString(", ")

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = displayLanguage,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Content Languages".tr) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextLight, unfocusedTextColor = TextLight
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        languages.forEach { lang ->
                            val isSelected = selectedLangs.contains(lang.first)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null,
                                            colors = CheckboxDefaults.colors(checkedColor = AccentGreen, uncheckedColor = TextMuted)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(lang.second, color = if (isSelected) AccentGreen else TextLight)
                                    }
                                },
                                onClick = {
                                    val newSelected = if (isSelected) {
                                        selectedLangs - lang.first
                                    } else {
                                        selectedLangs + lang.first
                                    }
                                    language = newSelected.joinToString(",")
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        // Manage Sources dialog
        if (showSourceManager) {
            ManageSourcesDialog(
                savedSources = sources,
                onDismiss = { showSourceManager = false },
                onToggleSource = { sourceId, isBuiltIn ->
                    if (isBuiltIn) {
                        val builtIn = SourceLibrary.sources.find { it.id == sourceId }
                        if (builtIn != null) {
                            val existing = sources.find { it.id == sourceId }
                            if (existing != null) {
                                viewModel.toggleSource(existing)
                            } else {
                                viewModel.toggleSource(
                                    FeedSource(
                                        id = builtIn.id,
                                        url = builtIn.url,
                                        title = builtIn.title,
                                        feedType = builtIn.feedType,
                                        category = builtIn.category,
                                        isActive = false
                                    )
                                )
                            }
                        }
                    } else {
                        val existing = sources.find { it.id == sourceId }
                        if (existing != null) viewModel.toggleSource(existing)
                    }
                }
            )
        }

        // Save button
        Button(
            onClick = {
                viewModel.updateContentPreferences(
                    selectedCategories = localInterests.toList(),
                    selectedMusicGenres = localMusicGenres.toList(),
                    selectedVideoGenres = localVideoGenres.toList(),
                    negativeKeywords = negativeKeywords,
                    languagePreference = language,
                    creatorKeywords = creatorKeywords
                )
                onBack()
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
        ) {
            Text("Save Settings".tr, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ManageSourcesDialog(
    savedSources: List<FeedSource>,
    onDismiss: () -> Unit,
    onToggleSource: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val apiKeyRepository = remember { com.noslop.app.data.ApiKeyRepository(context) }
    var showApiWarningFor by remember { mutableStateOf<String?>(null) }
    val allLibrarySources = SourceLibrary.sources
    val activeSourceIds = savedSources.filter { it.isActive }.map { it.id }.toSet()

    // Group sources by category for clean presentation
    val groupedSources = allLibrarySources
        .groupBy { it.category }
        .toSortedMap()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrimaryBlack,
        title = {
            Text("Manage Sources".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groupedSources.forEach { (category, sourcesInCategory) ->
                        item {
                            Text(
                                text = category.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }
                        items(sourcesInCategory) { src ->
                            val isActive = activeSourceIds.contains(src.id)
                            
                            val isApiSource = src.feedType == "api"
                            val serviceId = if (isApiSource) src.url.split(":").first() else null
                            val requiresKey = serviceId != null && com.noslop.app.data.ApiKeyRepository.SERVICES.find { it.id == serviceId }?.requiresUserKey == true
                            val hasKey = if (requiresKey) apiKeyRepository.hasKey(serviceId!!) else true
                            val alpha = if (!isActive && requiresKey && !hasKey) 0.5f else 1f

                            Card(
                                modifier = Modifier.fillMaxWidth().alpha(alpha),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) SurfaceDark else PrimaryBlack
                                ),
                                border = BorderStroke(1.dp, if (isActive) AccentGreen else BorderSubtle),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = src.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextLight,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = isActive,
                                        onCheckedChange = { 
                                            if (!isActive && requiresKey && !hasKey) {
                                                showApiWarningFor = src.title
                                            } else {
                                                onToggleSource(src.id, true) 
                                            }
                                        },
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done".tr, color = AccentGreen)
            }
        }
    )

    if (showApiWarningFor != null) {
        AlertDialog(
            onDismissRequest = { showApiWarningFor = null },
            title = { Text("API Key Required".tr, color = AccentGreen) },
            text = { Text("To enable ${showApiWarningFor}, you must first configure its API key in Settings -> API Keys.", color = TextLight) },
            confirmButton = {
                TextButton(onClick = { showApiWarningFor = null }) {
                    Text("OK".tr, color = AccentGreen)
                }
            },
            containerColor = SurfaceDark
        )
    }
}