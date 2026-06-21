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
                    text = "NO_SLOP",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                )

                Text(
                    text = "SERVERLESS HAI-NET NODE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextMuted,
                        letterSpacing = 2.sp
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                )

                // 8-dot step indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(8) { index ->
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
                    2 -> Step2Identity(
                        handle = handleText,
                        onHandleChange = { handleText = it },
                        mnemonic = mnemonic,
                        onGenerateMnemonic = { viewModel.generateMnemonic() },
                        onCopyMnemonic = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("NoSlop Mnemonic", it)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Mnemonic copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                    3 -> Step3Interests(
                        selectedInterests = selectedInterests,
                        onToggleInterest = { interest ->
                            if (selectedInterests.contains(interest)) selectedInterests.remove(interest)
                            else selectedInterests.add(interest)
                        }
                    )
                    4 -> Step4Genres(
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
                    5 -> Step5Feeds(
                        interests = selectedInterests,
                        selectedSources = selectedSources,
                        onToggleSource = { src ->
                            if (selectedSources.contains(src)) selectedSources.remove(src)
                            else selectedSources.add(src)
                        }
                    )
                    6 -> Step6Creators(
                        selectedInterests = selectedInterests,
                        creatorKeywords = creatorKeywordsText,
                        onCreatorKeywordsChange = { creatorKeywordsText = it }
                    )
                    7 -> Step7Connection(viewModel)
                    8 -> Step8Finalize(viewModel, handleText, selectedSources, selectedInterests, selectedMusicGenres, selectedVideoGenres, mnemonic)
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1 && currentStep < 8) {
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                val canProceed = when (currentStep) {
                    1 -> true
                    2 -> handleText.isNotBlank() && mnemonic != null
                    3 -> selectedInterests.isNotEmpty()
                    4 -> true // Optional genre selection
                    5 -> selectedSources.isNotEmpty()
                    6 -> true // Creator keywords are optional
                    7 -> true
                    8 -> true
                    else -> false
                }

                if (currentStep < 8) {
                    Button(
                        onClick = {
                            currentStep++
                            if (currentStep == 7) {
                                viewModel.preloadFeedsDuringOnboarding(
                                    selectedSources,
                                    selectedInterests,
                                    selectedMusicGenres,
                                    selectedVideoGenres,
                                    creatorKeywordsText
                                )
                            }
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
                            text = "Continue",
                            fontWeight = FontWeight.Bold,
                            color = if (canProceed) PrimaryBlack else TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = "Next")
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
                        Text("Enter NoSlop", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Check, contentDescription = "Finish")
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
            text = "Welcome to NoSlop",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step2Identity(
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
            text = "Your Identity Card",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Choose a handle and generate your 'Word Cloud' password.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )

        OutlinedTextField(
            value = handle,
            onValueChange = { if (it.length <= 20) onHandleChange(it) },
            label = { Text("Handle (e.g., satoshi)") },
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
                Text("Generate Word Cloud", fontWeight = FontWeight.Bold)
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
                        text = "Word Cloud Password (BIP39):",
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
                            text = "Tap to Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠ Write this down! It is the ONLY way to recover your account.",
                        style = MaterialTheme.typography.labelSmall,
                        color = DestructiveRed
                    )
                }
            }
        }
    }
}

@Composable
fun Step3Interests(
    selectedInterests: List<String>,
    onToggleInterest: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "What interests you?",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Select your favorite categories to help us suggest initial feeds.",
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

@Composable
fun Step4Genres(
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
                text = "No genres to select based on your interests.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        Text(
            text = "Refine your taste",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Choose specific genres for your dynamic media streams.",
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
                    Text("Music Genres", style = MaterialTheme.typography.titleMedium, color = AccentGreen, modifier = Modifier.padding(vertical = 8.dp))
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
                    Text("Video Genres", style = MaterialTheme.typography.titleMedium, color = AccentGreen, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
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
fun Step5Feeds(
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
            text = "Suggested Clearnet Feeds",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Based on your interests, we recommend these sources.",
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
            title = { Text("API Key Required", color = AccentGreen) },
            text = { Text("To enable ${showApiWarningFor}, you must first configure its API key in Settings -> API Keys. Please skip it for now and come back later.", color = TextLight) },
            confirmButton = {
                TextButton(onClick = { showApiWarningFor = null }) {
                    Text("OK", color = AccentGreen)
                }
            },
            containerColor = SurfaceDark
        )
    }
}

/**
 * Onboarding Step 6 — Creator & Channel Filters
 *
 * Shows a free-text entry area for creator/channel names and a "word cloud" of
 * curated suggestions derived from the user's selected interest categories and
 * chosen feeds. Tapping a suggestion chip toggles it into the text field.
 * The collected names are later passed as extra search keywords into the API
 * aggregation pipeline so content from those creators surfaces in the feed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Step6Creators(
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

    LaunchedEffect(channelSearchQuery) {
        if (channelSearchQuery.isBlank()) {
            searchedChannels = emptyList()
            isSearchingChannels = false
            return@LaunchedEffect
        }
        isSearchingChannels = true
        kotlinx.coroutines.delay(600) // Debounce typing
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
            text = "Who do you follow?",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Add creators, channels, or outlets you love. NoSlop will surface their content across all your feeds. Tap suggestions or type your own.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Word-cloud: show suggestions above the text field so users pick from the cloud first
        OutlinedTextField(
            value = channelSearchQuery,
            onValueChange = { channelSearchQuery = it },
            label = { Text("Search channel names...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
            trailingIcon = {
                if (isSearchingChannels) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentGreen, strokeWidth = 2.dp)
                } else if (channelSearchQuery.isNotBlank()) {
                    IconButton(onClick = { channelSearchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
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
                text = "SUGGESTED CHANNELS & CREATORS",
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
            label = { Text("Creators, channels, outlets (comma separated)") },
            placeholder = { Text("e.g. Linus Tech Tips, Veritasium, Krebs...") },
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
fun Step7Connection(viewModel: NoSlopViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TorWarningPanel(viewModel)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Mesh Network Connectivity",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "NoSlop routes all traffic through Tor. Scanning a QR code connects you directly to a peer.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
        
        var showScan by remember { mutableStateOf(false) }
        Button(
            onClick = { showScan = true },
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark, contentColor = AccentGreen),
            border = BorderStroke(1.dp, AccentGreen)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan Friend's QR")
        }

        if (showScan) {
            QRScanScreen(
                onPeerScannedAndAccepted = { h, p, o, e ->
                    viewModel.requestConnection(h, p, o, e)
                    showScan = false
                },
                onDismiss = { showScan = false }
            )
        }
    }
}

@Composable
fun Step8Finalize(
    viewModel: NoSlopViewModel,
    handle: String,
    selectedSources: List<BuiltInSource>,
    selectedInterests: List<String>,
    selectedMusicGenres: List<String>,
    selectedVideoGenres: List<String>,
    mnemonic: String?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(80.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Ready to Launch",
            style = MaterialTheme.typography.headlineMedium,
            color = TextLight,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "We're setting up your identity and pre-loading 50+ pieces of content from your chosen feeds.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(32.dp))
        LinearProgressIndicator(color = AccentGreen, trackColor = SurfaceDark, modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step1Identity(
    handle: String,
    onHandleChange: (String) -> Unit,
    localKeys: CryptoService.IdentityKeys?,
    onGenerate: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Generate Cryptographic Identity",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Your identity is self-generated and belongs only to you. No centralized accounts or algorithms.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )

        OutlinedTextField(
            value = handle,
            onValueChange = { if (it.length <= 20) onHandleChange(it) },
            label = { Text("Choose Handle (e.g., alice)") },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = BorderSubtle,
                focusedLabelColor = AccentGreen,
                unfocusedLabelColor = TextMuted,
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("handle_input")
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (localKeys == null) {
            Button(
                onClick = onGenerate,
                enabled = handle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = PrimaryBlack,
                    disabledContainerColor = SurfaceDark,
                    disabledContentColor = TextMuted
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp)
                    .testTag("generate_keys_button")
            ) {
                Icon(Icons.Default.Lock, contentDescription = "Generate Identity")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generate Keypair", fontWeight = FontWeight.Bold)
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Identity Registered!",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextLight,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Display Handle:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                    Text(
                        text = "${handle}.${localKeys.tripcode}",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Derived Onion Address:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                    Text(
                        text = localKeys.onionAddress,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextLight
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun Step2Feeds(
    selectedSources: List<BuiltInSource>,
    onToggleSource: (BuiltInSource) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "What do you want to read?",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Select from NoSlop's built-in operational feeds library. Free of corporate feeds.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp)
        ) {
            gridItems(SourceLibrary.sources) { src ->
                val isSelected = selectedSources.contains(src)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSource(src) },
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
                            onCheckedChange = { onToggleSource(src) },
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
}

@Composable
fun Step3Connection(viewModel: NoSlopViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        TorWarningPanel(viewModel)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Connect Securely over Tor",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "NoSlop functions as a gossip node on the HAI-Net mesh network. Handshake directly to build your web of trust.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(1.dp, BorderSubtle),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGreen)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("No Algorithms or Central Servers", fontWeight = FontWeight.Bold, color = TextLight)
                        Text("Your posts route strictly peer-to-peer using gossip encryption.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("E2EE Messenger", fontWeight = FontWeight.Bold, color = TextLight)
                        Text("Direct messages are signed and encrypted natively using Elliptic Curve exchange.", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }
    }
}