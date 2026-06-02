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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val selectedSources = remember { mutableStateListOf<BuiltInSource>() }
    
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

                // 6-dot step indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(6) { index ->
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
                    4 -> Step4Feeds(
                        interests = selectedInterests,
                        selectedSources = selectedSources,
                        onToggleSource = { src ->
                            if (selectedSources.contains(src)) selectedSources.remove(src)
                            else selectedSources.add(src)
                        }
                    )
                    5 -> Step5Connection(viewModel)
                    6 -> Step6Finalize(viewModel, handleText, selectedSources, selectedInterests, mnemonic)
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1 && currentStep < 6) {
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
                    4 -> selectedSources.isNotEmpty()
                    5 -> true
                    6 -> true
                    else -> false
                }

                if (currentStep < 6) {
                    Button(
                        onClick = { currentStep++ },
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
                            viewModel.completeOnboarding(handleText, selectedSources, selectedInterests, mnemonic!!)
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
            items(SourceLibrary.categories) { category ->
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
fun Step4Feeds(
    interests: List<String>,
    selectedSources: List<BuiltInSource>,
    onToggleSource: (BuiltInSource) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val suggestedSources = remember(interests, searchQuery) {
        SourceLibrary.sources.filter { 
            (interests.contains(it.category) || searchQuery.isNotBlank()) &&
            (it.title.contains(searchQuery, ignoreCase = true) || 
             it.category.contains(searchQuery, ignoreCase = true))
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

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search creators, channels, names...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AccentGreen) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentGreen,
                unfocusedBorderColor = BorderSubtle,
                focusedTextColor = TextLight,
                unfocusedTextColor = TextLight
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )

        Text(
            text = if (searchQuery.isBlank()) "Based on your interests, we recommend these sources." else "Search results:",
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
            items(suggestedSources) { src: BuiltInSource ->
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
fun Step5Connection(viewModel: NoSlopViewModel) {
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
fun Step6Finalize(
    viewModel: NoSlopViewModel,
    handle: String,
    selectedSources: List<BuiltInSource>,
    selectedInterests: List<String>,
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
            items(SourceLibrary.sources) { src ->
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
