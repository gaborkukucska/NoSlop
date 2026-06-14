package com.noslop.app.ui

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentPreferencesScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    // Profile state
    val currentProfile by viewModel.userProfile.collectAsState()
    val currentNegativeKeywords by viewModel.negativeKeywords.collectAsState()
    val currentLanguage by viewModel.languagePreference.collectAsState()
    val currentCreatorKeywords by viewModel.creatorKeywords.collectAsState()
    val isUsingInsecureStorage by viewModel.isUsingInsecureStorage.collectAsState()

    var displayName by remember { mutableStateOf(currentProfile.displayName) }
    var bio by remember { mutableStateOf(currentProfile.bio) }
    var avatarB64 by remember { mutableStateOf(currentProfile.avatarB64) }
    var negativeKeywords by remember { mutableStateOf(currentNegativeKeywords) }
    var language by remember { mutableStateOf(currentLanguage) }
    var creatorKeywords by remember { mutableStateOf(currentCreatorKeywords) }
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

    // Categories & genres state
    val interests by viewModel.selectedInterests.collectAsState()
    val musicGenres by viewModel.selectedMusicGenres.collectAsState()
    val videoGenres by viewModel.selectedVideoGenres.collectAsState()

    val localInterests = remember { mutableStateListOf<String>().apply { addAll(interests) } }
    val localMusicGenres = remember { mutableStateListOf<String>().apply { addAll(musicGenres) } }
    val localVideoGenres = remember { mutableStateListOf<String>().apply { addAll(videoGenres) } }

    val allMusicGenres = listOf("Electronic", "Ambient", "Rock", "Lo-Fi", "Classical", "Hip-Hop", "Jazz", "Pop")
    val allVideoGenres = listOf("Education", "Tech", "Gaming", "Science", "Entertainment", "News", "Documentary")

    // Sources state
    val sources by viewModel.allSources.collectAsState(initial = emptyList())
    var showSourceManager by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
            }
            Text(
                text = "Settings",
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
                            Icon(Icons.Default.Warning, contentDescription = "Warning", tint = DestructiveRed, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("SECURITY WARNING", color = DestructiveRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Hardware-backed Keystore is unavailable on this device. Your identity and private keys are currently stored in PLAINTEXT. Do not use this device for sensitive communication.", color = TextLight, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ────────────────── PROFILE ──────────────────
            item {
                val context = LocalContext.current
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
                                contentDescription = "Profile Avatar",
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
                Text("PROFILE", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
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
                    label = { Text("Bio") },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ────────────────── CONTENT SOURCES ──────────────────
            item {
                Text("CONTENT SOURCES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                val activeSources = sources.filter { it.isActive }
                if (activeSources.isEmpty()) {
                    Text("No active sources.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
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
                    Text("Manage Sources", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ────────────────── CATEGORIES ──────────────────
            item {
                Text("CATEGORIES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }

            items(SourceLibrary.categories) { category ->
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
                    Text("MUSIC GENRES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
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
            if (localInterests.contains("Video Platforms")) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("VIDEO GENRES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
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

            // ────────────────── NEGATIVE KEYWORDS ──────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text("FILTERING", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = negativeKeywords,
                    onValueChange = { negativeKeywords = it },
                    label = { Text("Negative Keywords (comma separated)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ────────────────── CREATOR FILTERS ──────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "CREATOR & CHANNEL FILTERS",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Creators, channels, or outlets whose content you want surfaced in your feed. Used as search keywords across all active API sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = creatorKeywords,
                    onValueChange = { creatorKeywords = it },
                    label = { Text("Creators / channels (comma separated)") },
                    placeholder = { Text("e.g. Linus Tech Tips, Veritasium, Krebs...") },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Creator word-cloud suggestion chips (derived from current selected interests)
            run {
                val suggestions = SourceLibrary.getSuggestedCreatorsForCategories(localInterests)
                if (suggestions.isNotEmpty()) {
                    item {
                        Text(
                            "SUGGESTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    val chunked = suggestions.chunked(3)
                    items(chunked) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { creator ->
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
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
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
                                    ),
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
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
                        label = { Text("Content Languages") },
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
                viewModel.updateUserProfile(
                    UserProfile(displayName = displayName, bio = bio, avatarB64 = avatarB64)
                )
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
            Text("Save Settings", fontWeight = FontWeight.Bold)
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
            Text("Manage Sources", color = AccentGreen, fontWeight = FontWeight.Bold)
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
                Text("Done", color = AccentGreen)
            }
        }
    )

    if (showApiWarningFor != null) {
        AlertDialog(
            onDismissRequest = { showApiWarningFor = null },
            title = { Text("API Key Required", color = AccentGreen) },
            text = { Text("To enable ${showApiWarningFor}, you must first configure its API key in Settings -> API Keys.", color = TextLight) },
            confirmButton = {
                TextButton(onClick = { showApiWarningFor = null }) {
                    Text("OK", color = AccentGreen)
                }
            },
            containerColor = SurfaceDark
        )
    }
}