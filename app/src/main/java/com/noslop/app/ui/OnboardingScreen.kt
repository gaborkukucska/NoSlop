// FILE: app/src/main/java/com/noslop/app/ui/OnboardingScreen.kt
package com.noslop.app.ui

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
    val selectedSources = remember { mutableStateListOf<BuiltInSource>() }

    // State derived from step 1 identity generation
    val localKeys by viewModel.localKeys.collectAsState()

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

                // 3-dot step indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(3) { index ->
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
                    1 -> Step1Identity(
                        handle = handleText,
                        onHandleChange = { handleText = it },
                        localKeys = localKeys,
                        onGenerate = {
                            viewModel.completeOnboarding(handleText, selectedSources)
                        }
                    )
                    2 -> Step2Feeds(
                        selectedSources = selectedSources,
                        onToggleSource = { src ->
                            if (selectedSources.contains(src)) selectedSources.remove(src)
                            else selectedSources.add(src)
                        }
                    )
                    3 -> Step3Connection(viewModel)
                }
            }

            // Bottom Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 1) {
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
                    1 -> localKeys != null
                    2 -> selectedSources.isNotEmpty()
                    3 -> true
                    else -> false
                }

                Button(
                    onClick = {
                        if (currentStep < 3) {
                            currentStep++
                        } else {
                            viewModel.completeOnboarding(handleText, selectedSources)
                            onComplete()
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
                        text = if (currentStep == 3) "Enter App" else "Continue",
                        fontWeight = FontWeight.Bold,
                        color = if (canProceed) PrimaryBlack else TextMuted
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = "Next")
                }
            }
        }
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
