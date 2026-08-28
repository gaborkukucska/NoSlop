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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

            // ────────────────── CONTENT CATEGORIES & GENRES (COLLAPSIBLE BY GROUPS) ──────────────────
            item {
                var isCategorySectionExpanded by remember { mutableStateOf(false) }
                var expandedSubGroup by remember { mutableStateOf<String?>(null) }

                val totalActiveCategoriesCount = localInterests.size + localVideoGenres.size + localMusicGenres.size

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, if (isCategorySectionExpanded) AccentGreen else BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { isCategorySectionExpanded = !isCategorySectionExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Content Categories & Genres 🏷️".tr, style = MaterialTheme.typography.titleMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = AccentGreen.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "{count} active".tr.replace("{count}", totalActiveCategoriesCount.toString()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentGreen,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isCategorySectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Categories",
                                tint = AccentGreen
                            )
                        }

                        if (isCategorySectionExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Customize topic interests and genre preferences for feed generation.".tr,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Sub-group 1: Main Topics
                            val isMainExpanded = expandedSubGroup == "main"
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    expandedSubGroup = if (isMainExpanded) null else "main"
                                },
                                colors = CardDefaults.cardColors(containerColor = PrimaryBlack),
                                border = BorderStroke(1.dp, if (isMainExpanded) AccentGreen.copy(alpha = 0.5f) else BorderSubtle)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Main Topics".tr, style = MaterialTheme.typography.bodyMedium, color = TextLight, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("({count} selected)".tr.replace("{count}", localInterests.size.toString()), style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                                        }
                                        Icon(
                                            imageVector = if (isMainExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Expand",
                                            tint = TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (isMainExpanded) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            SourceLibrary.selectableCategories.forEach { category ->
                                                val isSelected = localInterests.contains(category)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        if (isSelected) localInterests.remove(category) else localInterests.add(category)
                                                    },
                                                    label = { Text(category.tr, style = MaterialTheme.typography.labelSmall) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        containerColor = SurfaceDark, labelColor = TextLight,
                                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f), selectedLabelColor = AccentGreen
                                                    ),
                                                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected, borderColor = BorderSubtle, selectedBorderColor = AccentGreen)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Sub-group 2: Video Genres
                            val isVideoExpanded = expandedSubGroup == "video"
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    expandedSubGroup = if (isVideoExpanded) null else "video"
                                },
                                colors = CardDefaults.cardColors(containerColor = PrimaryBlack),
                                border = BorderStroke(1.dp, if (isVideoExpanded) AccentGreen.copy(alpha = 0.5f) else BorderSubtle)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Video Genres".tr, style = MaterialTheme.typography.bodyMedium, color = TextLight, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("({count} selected)".tr.replace("{count}", localVideoGenres.size.toString()), style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                                        }
                                        Icon(
                                            imageVector = if (isVideoExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Expand",
                                            tint = TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (isVideoExpanded) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            allVideoGenres.forEach { genre ->
                                                val isSelected = localVideoGenres.contains(genre)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        if (isSelected) localVideoGenres.remove(genre) else localVideoGenres.add(genre)
                                                    },
                                                    label = { Text(genre.tr, style = MaterialTheme.typography.labelSmall) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        containerColor = SurfaceDark, labelColor = TextLight,
                                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f), selectedLabelColor = AccentGreen
                                                    ),
                                                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected, borderColor = BorderSubtle, selectedBorderColor = AccentGreen)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Sub-group 3: Music Genres
                            val isMusicExpanded = expandedSubGroup == "music"
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    expandedSubGroup = if (isMusicExpanded) null else "music"
                                },
                                colors = CardDefaults.cardColors(containerColor = PrimaryBlack),
                                border = BorderStroke(1.dp, if (isMusicExpanded) AccentGreen.copy(alpha = 0.5f) else BorderSubtle)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("Music Genres".tr, style = MaterialTheme.typography.bodyMedium, color = TextLight, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("({count} selected)".tr.replace("{count}", localMusicGenres.size.toString()), style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                                        }
                                        Icon(
                                            imageVector = if (isMusicExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Expand",
                                            tint = TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (isMusicExpanded) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            allMusicGenres.forEach { genre ->
                                                val isSelected = localMusicGenres.contains(genre)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        if (isSelected) localMusicGenres.remove(genre) else localMusicGenres.add(genre)
                                                    },
                                                    label = { Text(genre.tr, style = MaterialTheme.typography.labelSmall) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        containerColor = SurfaceDark, labelColor = TextLight,
                                                        selectedContainerColor = AccentGreen.copy(alpha = 0.15f), selectedLabelColor = AccentGreen
                                                    ),
                                                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected, borderColor = BorderSubtle, selectedBorderColor = AccentGreen)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }



            // ────────────────── CREATOR & CHANNEL PREFERENCES (COLLAPSIBLE BY CATEGORY) ──────────────────
            item {
                var isCreatorSectionExpanded by remember { mutableStateOf(false) }
                var expandedCategory by remember { mutableStateOf<String?>(null) }
                var channelSearchQuery by remember { mutableStateOf("") }
                var searchedChannels by remember { mutableStateOf<List<String>>(emptyList()) }
                var isSearchingChannels by remember { mutableStateOf(false) }

                val currentSelectedSet = creatorKeywords.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()

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
                    kotlinx.coroutines.delay(500)
                    try {
                        val ytChannels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { 
                            com.noslop.app.feeds.api.YouTubeInternalClient.searchChannels(channelSearchQuery).take(10) 
                        }
                        if (ytChannels.isNotEmpty()) {
                            searchedChannels = ytChannels
                        } else {
                            throw Exception("YouTube returned empty")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.noslop.app.debug.Logger.error("CONTENT_PREFS", "Channel search failed: ${e.message}, trying fallback")
                        try {
                            val fallback = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.noslop.app.feeds.api.RedditApiClient.searchReddit(channelSearchQuery, "relevance")
                                    .mapNotNull { it.author }
                                    .distinct()
                                    .take(10)
                            }
                            searchedChannels = fallback
                        } catch (e2: kotlinx.coroutines.CancellationException) {
                            throw e2
                        } catch (e2: Exception) {
                            com.noslop.app.debug.Logger.error("CONTENT_PREFS", "Fallback search failed: ${e2.message}")
                            searchedChannels = emptyList()
                        }
                    }
                    isSearchingChannels = false
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, if (isCreatorSectionExpanded) AccentGreen else BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { isCreatorSectionExpanded = !isCreatorSectionExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Creator Preferences 👤".tr, style = MaterialTheme.typography.titleMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = AccentGreen.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "{count} selected".tr.replace("{count}", currentSelectedSet.size.toString()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentGreen,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isCreatorSectionExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle",
                                tint = AccentGreen
                            )
                        }

                        if (isCreatorSectionExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Select creator channels grouped by category or search across YouTube & Reddit.".tr,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Global Live Channel Search Box
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
                                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (searchedChannels.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("LIVE SEARCH RESULTS".tr, style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    searchedChannels.forEach { creator ->
                                        val isSelected = currentSelectedSet.contains(creator)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                val updated = if (isSelected) currentSelectedSet - creator else currentSelectedSet + creator
                                                creatorKeywords = updated.joinToString(", ")
                                            },
                                            label = { Text(creator, style = MaterialTheme.typography.labelSmall) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = PrimaryBlack, labelColor = TextLight,
                                                selectedContainerColor = AccentGreen.copy(alpha = 0.15f), selectedLabelColor = AccentGreen
                                            ),
                                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected, borderColor = BorderSubtle, selectedBorderColor = AccentGreen)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Category Accordions
                            val availableCategoryMap = SourceLibrary.creatorSuggestionsByCategory
                            availableCategoryMap.forEach { (catName, suggestions) ->
                                val catCount = suggestions.count { currentSelectedSet.contains(it) }
                                val isCatExpanded = expandedCategory == catName

                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        expandedCategory = if (isCatExpanded) null else catName
                                    },
                                    colors = CardDefaults.cardColors(containerColor = PrimaryBlack),
                                    border = BorderStroke(1.dp, if (isCatExpanded) AccentGreen.copy(alpha = 0.5f) else BorderSubtle)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(catName.tr, style = MaterialTheme.typography.bodyMedium, color = TextLight, fontWeight = FontWeight.Bold)
                                                if (catCount > 0) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("($catCount)", style = MaterialTheme.typography.labelSmall, color = AccentGreen)
                                                }
                                            }
                                            Icon(
                                                imageVector = if (isCatExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Expand",
                                                tint = TextMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        if (isCatExpanded) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                suggestions.forEach { creator ->
                                                    val isSelected = currentSelectedSet.contains(creator)
                                                    FilterChip(
                                                        selected = isSelected,
                                                        onClick = {
                                                            val updated = if (isSelected) currentSelectedSet - creator else currentSelectedSet + creator
                                                            creatorKeywords = updated.joinToString(", ")
                                                        },
                                                        label = { Text(creator, style = MaterialTheme.typography.labelSmall) },
                                                        colors = FilterChipDefaults.filterChipColors(
                                                            containerColor = SurfaceDark, labelColor = TextLight,
                                                            selectedContainerColor = AccentGreen.copy(alpha = 0.15f), selectedLabelColor = AccentGreen
                                                        ),
                                                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isSelected, borderColor = BorderSubtle, selectedBorderColor = AccentGreen)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Manual Comma Separated Input
                            OutlinedTextField(
                                value = creatorKeywords,
                                onValueChange = { creatorKeywords = it },
                                label = { Text("All Selected Creators (Comma Separated)".tr) },
                                placeholder = { Text("e.g. Linus Tech Tips, Veritasium, Krebs...".tr) },
                                minLines = 2,
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
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

            // ────────────────── BANNED CHANNELS / CREATORS (COLLAPSIBLE BUTTON) ──────────────────
            item {
                val bannedChannels by viewModel.bannedChannels.collectAsState()
                var isBannedExpanded by remember { mutableStateOf(false) }
                var newBanInput by remember { mutableStateOf("") }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = BorderStroke(1.dp, if (isBannedExpanded) DestructiveRed else BorderSubtle)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { isBannedExpanded = !isBannedExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Banned Channels / Creators 🚫".tr, style = MaterialTheme.typography.titleMedium, color = DestructiveRed, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = DestructiveRed.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "{count} banned".tr.replace("{count}", bannedChannels.size.toString()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DestructiveRed,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isBannedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Banned",
                                tint = DestructiveRed
                            )
                        }

                        if (isBannedExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Channels blacklisted via the 🚫 reaction or added below will be completely excluded from live feeds and search results.".tr,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (bannedChannels.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    bannedChannels.forEach { channel ->
                                        FilterChip(
                                            selected = true,
                                            onClick = { viewModel.unbanChannel(channel) },
                                            label = { Text(channel, style = MaterialTheme.typography.labelSmall) },
                                            trailingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Unban",
                                                    tint = DestructiveRed,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = DestructiveRed.copy(alpha = 0.15f),
                                                selectedLabelColor = DestructiveRed
                                            ),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = true,
                                                borderColor = DestructiveRed,
                                                selectedBorderColor = DestructiveRed
                                            )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newBanInput,
                                    onValueChange = { newBanInput = it },
                                    label = { Text("Ban channel by name".tr) },
                                    placeholder = { Text("e.g. SlopFactory".tr) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = DestructiveRed, unfocusedBorderColor = BorderSubtle,
                                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (newBanInput.isNotBlank()) {
                                            viewModel.banChannel(newBanInput.trim())
                                            newBanInput = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = TextLight),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(54.dp)
                                ) {
                                    Text("Ban".tr, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ────────────────── CHANNEL CREATION CUT-OFF DATE ──────────────────
            item {
                val cutoffSettings by viewModel.channelCutoffSettings.collectAsState()
                var cutoffEnabled by remember(cutoffSettings) { mutableStateOf(cutoffSettings.first) }
                var selectedYear by remember(cutoffSettings) { mutableStateOf(cutoffSettings.second) }
                var selectedMonth by remember(cutoffSettings) { mutableStateOf(cutoffSettings.third) }

                val months = listOf(
                    1 to "Jan", 2 to "Feb", 3 to "Mar", 4 to "Apr",
                    5 to "May", 6 to "Jun", 7 to "Jul", 8 to "Aug",
                    9 to "Sep", 10 to "Oct", 11 to "Nov", 12 to "Dec"
                )

                Text(
                    text = "Channel Creation Cut-Off Date 📅".tr,
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Filter out channels and creators created after a specific date to drop recent automated content farms. Creator search remains unaffected.".tr,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Enable Cut-Off Date Filter".tr,
                        color = TextLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = cutoffEnabled,
                        onCheckedChange = {
                            cutoffEnabled = it
                            viewModel.saveChannelCutoffSettings(it, selectedYear, selectedMonth)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PrimaryBlack,
                            checkedTrackColor = AccentGreen
                        )
                    )
                }

                if (cutoffEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Year Dropdown
                        var yearExpanded by remember { mutableStateOf(false) }
                        val years = (2005..2026).toList().reversed()
                        ExposedDropdownMenuBox(
                            expanded = yearExpanded,
                            onExpandedChange = { yearExpanded = !yearExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = selectedYear.toString(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Year Cut-Off".tr) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = yearExpanded,
                                onDismissRequest = { yearExpanded = false }
                            ) {
                                years.forEach { yr ->
                                    DropdownMenuItem(
                                        text = { Text(yr.toString(), color = if (yr == selectedYear) AccentGreen else TextLight) },
                                        onClick = {
                                            selectedYear = yr
                                            yearExpanded = false
                                            viewModel.saveChannelCutoffSettings(true, yr, selectedMonth)
                                        }
                                    )
                                }
                            }
                        }

                        // Month Dropdown
                        var monthExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = monthExpanded,
                            onExpandedChange = { monthExpanded = !monthExpanded },
                            modifier = Modifier.weight(1f)
                        ) {
                            val monthLabel = months.find { it.first == selectedMonth }?.second ?: "Jan"
                            OutlinedTextField(
                                value = monthLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Month Cut-Off".tr) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                                    focusedTextColor = TextLight, unfocusedTextColor = TextLight
                                ),
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = monthExpanded,
                                onDismissRequest = { monthExpanded = false }
                            ) {
                                months.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text("${m.first} - ${m.second}", color = if (m.first == selectedMonth) AccentGreen else TextLight) },
                                        onClick = {
                                            selectedMonth = m.first
                                            monthExpanded = false
                                            viewModel.saveChannelCutoffSettings(true, selectedYear, m.first)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Excludes channels created after {month} {year}".tr
                            .replace("{month}", months.find { it.first == selectedMonth }?.second ?: "")
                            .replace("{year}", selectedYear.toString()),
                        color = AccentGreen,
                        fontSize = 12.sp
                    )
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