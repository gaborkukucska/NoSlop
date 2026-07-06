// FILE: app/src/main/java/com/noslop/app/ui/OnboardingScreen.kt
package com.noslop.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.crypto.CryptoService
import com.noslop.app.feeds.BuiltInSource
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr // Added translation extension

@Composable
fun OnboardingScreen(
    viewModel: NoSlopViewModel,
    onComplete: () -> Unit
) {
    var currentStep by remember { mutableStateOf(1) }
    var handleText by remember { mutableStateOf("") }
    val selectedInterests = remember { mutableStateListOf<String>() }
    val selectedMusicGenres = remember { mutableStateListOf<String>() }
    val selectedVideoGenres = remember { mutableStateListOf<String>() }
    val selectedSources = remember { mutableStateListOf<BuiltInSource>() }
    var creatorKeywordsText by remember { mutableStateOf("") }
    
    val mnemonic by viewModel.mnemonic.collectAsState()
    val localKeys by viewModel.localKeys.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("onboarding_scaffold"),
        containerColor = PrimaryBlack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: App logo & Step Indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "NO_SLOP".tr,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                )

                Text(
                    text = "SERVERLESS HAI-NET NODE".tr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        letterSpacing = 2.sp
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                )

                // 7-dot step indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(7) { index ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (currentStep == index + 1) 12.dp else 8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (currentStep == index + 1) AccentGreen else TextMuted)
                        )
                    }
                }
            }

            // Middle Content Section
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                when (currentStep) {
                    1 -> Step1Welcome()
                    2 -> Step2Language(
                        currentLanguage = appLanguage,
                        onLanguageSelect = { code -> viewModel.updateAppLanguage(code) }
                    )
                    3 -> Step3Identity(
                        handle = handleText,
                        onHandleChange = { handleText = it },
                        mnemonic = mnemonic,
                        onGenerateMnemonic = { viewModel.generateMnemonic() },
                        onCopyMnemonic = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(com.noslop.app.util.LanguageManager.translate("NoSlop Mnemonic"), it)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Mnemonic copied to clipboard"), Toast.LENGTH_SHORT).show()
                        }
                    )
                    4 -> Step4Interests(
                        selectedInterests = selectedInterests,
                        onToggleInterest = { interest ->
                            if (selectedInterests.contains(interest)) selectedInterests.remove(interest)
                            else selectedInterests.add(interest)
                        }
                    )
                    5 -> Step5Creators(
                        selectedInterests = selectedInterests,
                        creatorKeywords = creatorKeywordsText,
                        onCreatorKeywordsChange = { creatorKeywordsText = it }
                    )
                    6 -> Step6Genres(
                        interests = selectedInterests,
                        selectedMusicGenres = selectedMusicGenres,
                        selectedVideoGenres = selectedVideoGenres,
                        onToggleMusicGenre = { genre ->
                            if (selectedMusicGenres.contains(genre)) selectedMusicGenres.remove(genre)
                            else selectedMusicGenres.add(genre)
                        },
                        onToggleVideoGenre = { genre ->
                            if (selectedVideoGenres.contains(genre)) selectedVideoGenres.remove(genre)
                            else selectedVideoGenres.add(genre)
                        }
                    )
                    7 -> Step7Feeds(
                        interests = selectedInterests,
                        selectedSources = selectedSources,
                        onToggleSource = { src ->
                            if (selectedSources.contains(src)) selectedSources.remove(src)
                            else selectedSources.add(src)
                        }
                    )
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1 && currentStep < 7) {
                    Button(
                        onClick = { currentStep-- },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = TextLight
                        ),
                        border = BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("onboarding_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back".tr)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back".tr, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                val canProceed = when (currentStep) {
                    1 -> true
                    2 -> true // Language selection always has a valid default
                    3 -> handleText.isNotBlank() && mnemonic != null // Identity
                    4 -> selectedInterests.isNotEmpty() // Interests
                    5 -> true // Creator keywords are optional
                    6 -> true // Optional genre selection
                    7 -> selectedSources.isNotEmpty() // Feeds
                    else -> false
                }

                if (currentStep < 7) {
                    Button(
                        onClick = {
                            if (currentStep == 5 && creatorKeywordsText.isNotBlank()) {
                                // Trigger background fetch for creators while user finishes onboarding
                                viewModel.triggerBackgroundCreatorPreFetch(creatorKeywordsText)
                            }
                            currentStep++
                        },
                        enabled = canProceed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            contentColor = PrimaryBlack,
                            disabledContainerColor = SurfaceDark,
                            disabledContentColor = TextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("onboarding_next_button")
                    ) {
                        Text(
                            text = "Continue".tr,
                            fontWeight = FontWeight.Bold,
                            color = if (canProceed) PrimaryBlack else TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = "Next".tr)
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.completeOnboarding(
                                handleText, 
                                selectedSources, 
                                selectedInterests, 
                                selectedMusicGenres,
                                selectedVideoGenres,
                                mnemonic!!,
                                creatorKeywordsText
                            )
                            onComplete()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            contentColor = PrimaryBlack
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("onboarding_finish_button")
                    ) {
                        Text("Enter NoSlop".tr, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Check, contentDescription = "Finish".tr)
                    }
                }
            }
        }
    }
}

@Composable
fun Step1Welcome() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Welcome to NoSlop".tr,
            style = MaterialTheme.typography.headlineMedium,
            color = TextLight,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "The serverless, unfilterable feed reader and social mesh node.\n\n" +
                   "• No algorithms\n" +
                   "• No central servers\n" +
                   "• End-to-end encrypted DMs\n" +
                   "• P2P Gossip Mesh",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun Step2Language(
    currentLanguage: String,
    onLanguageSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "App Language".tr,
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Change the interface language.".tr,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )

        val languages = listOf("en" to "English", "hu" to "Magyar")
        
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            items(languages) { (code, name) ->
                val isSelected = currentLanguage == code
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onLanguageSelect(code) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) SurfaceDark else PrimaryBlack
                    ),
                    border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(name, color = if (isSelected) AccentGreen else TextLight, fontWeight = FontWeight.Bold)
                        if (isSelected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = AccentGreen)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step3Identity(
    handle: String,
    onHandleChange: (String) -> Unit,
    mnemonic: String?,
    onGenerateMnemonic: () -> Unit,
    onCopyMnemonic: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Your Identity Card".tr,
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Choose a handle and generate your 'Word Cloud' password.".tr,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )

        OutlinedTextField(
            value = handle,
            onValueChange = { if (it.length <= 20) onHandleChange(it) },
            label = { Text("Handle (e.g., satoshi)".tr) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = BorderSubtle,
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (mnemonic == null) {
            Button(
                onClick = onGenerateMnemonic,
                enabled = handle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("Generate Word Cloud".tr, fontWeight = FontWeight.Bold)
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, AccentGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { onCopyMnemonic(mnemonic) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Word Cloud Password (BIP39):".tr,
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = mnemonic,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextLight,
                            lineHeight = 24.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Tap to Copy".tr,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠ Write this down! It is the ONLY way to recover your account.".tr,
                        style = MaterialTheme.typography.labelSmall,
                        color = DestructiveRed
                    )
                }
            }
        }
    }
}

@Composable
fun Step4Interests(
    selectedInterests: List<String>,
    onToggleInterest: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "What interests you?".tr,
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Select your favorite categories to help us suggest initial feeds.".tr,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            gridItems(SourceLibrary.selectableCategories) { category ->
                val isSelected = selectedInterests.contains(category)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clickable { onToggleInterest(category) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) SurfaceDark else PrimaryBlack
                    ),
                    border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isSelected) AccentGreen else TextLight,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Step5Creators(
    selectedInterests: List<String>,
    creatorKeywords: String,
    onCreatorKeywordsChange: (String) -> Unit
) {
    // Derive suggestions from selected categories using the SourceLibrary map
    val suggestions = remember(selectedInterests) {
        com.noslop.app.feeds.SourceLibrary.getSuggestedCreatorsForCategories(selectedInterests)
    }

    // Parse the current keyword text into a set for chip highlighting
    val currentKeywords = remember(creatorKeywords) {
        creatorKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

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
            searchedChannels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.noslop.app.feeds.api.InvidiousApiClient.searchChannels(channelSearchQuery).take(3) }
        } catch (e: Exception) {
            com.noslop.app.debug.Logger.error("ONBOARDING", "Channel search failed: ${e.message}")
        } finally {
            isSearchingChannels = false
        }
    }
    
    val combinedSuggestions = remember(suggestions, searchedChannels) {
        (searchedChannels + suggestions).distinct()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Who do you follow?".tr,
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Add creators, channels, or outlets you love. NoSlop will surface their content across all your feeds. Tap suggestions or type your own.".tr,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Word-cloud: show suggestions above the text field so users pick from the cloud first
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (combinedSuggestions.isNotEmpty()) {
            Text(
                text = "SUGGESTED CHANNELS & CREATORS".tr,
                style = MaterialTheme.typography.labelSmall,
                color = AccentGreen,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, bottom = 6.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        combinedSuggestions.forEach { creator ->
                            val isSelected = currentKeywords.contains(creator)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    val updated = if (isSelected) {
                                        currentKeywords - creator
                                    } else {
                                        currentKeywords + creator
                                    }
                                    onCreatorKeywordsChange(updated.joinToString(", "))
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
            }
        }

        OutlinedTextField(
            value = creatorKeywords,
            onValueChange = onCreatorKeywordsChange,
            label = { Text("Creators, channels, outlets (comma separated)".tr) },
            placeholder = { Text("e.g. Linus Tech Tips, Veritasium, Krebs...".tr) },
            minLines = 2,
            maxLines = 4,
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun Step6Genres(
    interests: List<String>,
    selectedMusicGenres: MutableList<String>,
    selectedVideoGenres: MutableList<String>,
    onToggleMusicGenre: (String) -> Unit,
    onToggleVideoGenre: (String) -> Unit
) {
    val showMusic = interests.contains("Music")
    // Video is always included (not a selectable category) so always show video genres
    val showVideo = true
    
    val musicGenres = listOf(
        "Electronic", "Ambient", "Rock", "Lo-Fi", "Classical", "Hip-Hop", "Jazz", "Pop",
        "Metal", "R&B", "Country", "Reggae", "Blues", "Indie", "Soul", "Punk"
    )
    val videoGenres = listOf(
        "Education", "Tech", "Gaming", "Science", "Entertainment", "News", "Documentary",
        "Comedy", "Music Videos", "Sports", "Travel", "DIY & How-To", "Animation", "Film & Cinema"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!showMusic && !showVideo) {
            Text(
                text = "No genres to select based on your interests.".tr,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        Text(
            text = "Refine your taste".tr,
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Choose specific genres for your dynamic media streams.".tr,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp)
        ) {
            if (showMusic) {
                item {
                    Text("Music Genres".tr, style = MaterialTheme.typography.titleMedium, color = AccentGreen, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(musicGenres) { genre ->
                    val isSelected = selectedMusicGenres.contains(genre)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onToggleMusicGenre(genre) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) SurfaceDark else PrimaryBlack
                        ),
                        border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(genre, color = TextLight, fontWeight = FontWeight.Bold)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleMusicGenre(genre) },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack, uncheckedColor = TextMuted)
                            )
                        }
                    }
                }
            }
            if (showVideo) {
                item {
                    Text("Video Genres".tr, style = MaterialTheme.typography.titleMedium, color = AccentGreen, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                }
                items(videoGenres) { genre ->
                    val isSelected = selectedVideoGenres.contains(genre)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onToggleVideoGenre(genre) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) SurfaceDark else PrimaryBlack
                        ),
                        border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(genre, color = TextLight, fontWeight = FontWeight.Bold)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleVideoGenre(genre) },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack, uncheckedColor = TextMuted)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Step7Feeds(
    interests: List<String>,
    selectedSources: List<BuiltInSource>,
    onToggleSource: (BuiltInSource) -> Unit
) {
    val context = LocalContext.current
    val apiKeyRepository = remember { com.noslop.app.data.ApiKeyRepository(context) }
    var showApiWarningFor by remember { mutableStateOf<String?>(null) }
    
    // Include always-included categories (Video Platforms, Social Clearnet) alongside user interests
    val effectiveInterests = remember(interests) {
        (interests + SourceLibrary.alwaysIncludedCategories).distinct()
    }
    
    val suggestedSources = remember(effectiveInterests) {
        SourceLibrary.sources.filter { effectiveInterests.contains(it.category) }
    }

    var hasPreselected by remember { mutableStateOf(false) }

    LaunchedEffect(suggestedSources) {
        if (!hasPreselected && suggestedSources.isNotEmpty()) {
            suggestedSources.forEach { src ->
                val isApiSource = src.feedType == "api"
                val serviceId = if (isApiSource) src.url.split(":").first() else null
                val requiresKey = serviceId != null && com.noslop.app.data.ApiKeyRepository.SERVICES.find { it.id == serviceId }?.requiresUserKey == true
                val hasKey = if (requiresKey) apiKeyRepository.hasKey(serviceId!!) else true
                
                if (!selectedSources.contains(src) && (!requiresKey || hasKey)) {
                    onToggleSource(src)
                }
            }
            hasPreselected = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Suggested Clearnet Feeds".tr,
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Based on your interests, we recommend these sources.".tr,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp)
        ) {
            gridItems(suggestedSources) { src: BuiltInSource ->
                val isSelected = selectedSources.contains(src)
                
                val isApiSource = src.feedType == "api"
                val serviceId = if (isApiSource) src.url.split(":").first() else null
                val requiresKey = serviceId != null && com.noslop.app.data.ApiKeyRepository.SERVICES.find { it.id == serviceId }?.requiresUserKey == true
                val hasKey = if (requiresKey) apiKeyRepository.hasKey(serviceId!!) else true
                val alpha = if (!isSelected && requiresKey && !hasKey) 0.5f else 1f

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha)
                        .clickable { 
                            if (!isSelected && requiresKey && !hasKey) {
                                showApiWarningFor = src.title
                            } else {
                                onToggleSource(src) 
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) SurfaceDark else PrimaryBlack
                    ),
                    border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextLight,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = src.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentGreen
                            )
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { 
                                if (!isSelected && requiresKey && !hasKey) {
                                    showApiWarningFor = src.title
                                } else {
                                    onToggleSource(src) 
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentGreen,
                                checkmarkColor = PrimaryBlack,
                                uncheckedColor = TextMuted
                            )
                        )
                    }
                }
            }
        }
    }

    if (showApiWarningFor != null) {
        AlertDialog(
            onDismissRequest = { showApiWarningFor = null },
            title = { Text("API Key Required".tr, color = AccentGreen) },
            text = { Text("To enable ${showApiWarningFor}, you must first configure its API key in Settings -> API Keys. Please skip it for now and come back later.", color = TextLight) },
            confirmButton = {
                TextButton(onClick = { showApiWarningFor = null }) {
                    Text("OK".tr, color = AccentGreen)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
