package com.noslop.mvp.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.HandleStore
import com.noslop.mvp.MnemonicGenerator
import com.noslop.mvp.feeds.BuiltInSource
import com.noslop.mvp.loadIdentity
import com.noslop.mvp.regenerateIdentity
import com.noslop.mvp.ui.theme.*

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentStep by remember { mutableStateOf(1) }
    var handleText by remember { mutableStateOf("") }
    val selectedInterests = remember { mutableStateListOf<String>() }
    val selectedSources = remember { mutableStateListOf<BuiltInSource>() }
    var mnemonic by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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

                // 5-dot step indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(5) { index ->
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
                    1 -> WelcomeStep()
                    2 -> IdentityStep(
                        handle = handleText,
                        onHandleChange = { handleText = it }
                    )
                    3 -> MnemonicBackupStep(
                        mnemonic = mnemonic,
                        onGenerateMnemonic = { mnemonic = MnemonicGenerator.generateMnemonic() },
                        canGenerate = handleText.isNotBlank()
                    )
                    4 -> InterestSelectionStep(
                        selectedInterests = selectedInterests,
                        onToggleInterest = { interest ->
                            if (selectedInterests.contains(interest)) selectedInterests.remove(interest)
                            else selectedInterests.add(interest)
                        }
                    )
                    5 -> FeedPreloadStep(
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
                if (currentStep > 1 && currentStep < 5) {
                    Button(
                        onClick = { currentStep-- },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = TextLight
                        ),
                        border = BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(50.dp)
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
                    2 -> handleText.isNotBlank()
                    3 -> mnemonic != null
                    4 -> selectedInterests.isNotEmpty()
                    5 -> selectedSources.isNotEmpty()
                    else -> false
                }

                if (currentStep < 5) {
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
                        modifier = Modifier.height(50.dp)
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
                            // Save handle to finalize onboarding
                            HandleStore.save(handleText)
                            // Initialize identity
                            regenerateIdentity(handleText)
                            onComplete()
                        },
                        enabled = canProceed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            contentColor = PrimaryBlack,
                            disabledContainerColor = SurfaceDark,
                            disabledContentColor = TextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Text(
                            text = "Enter NoSlop",
                            fontWeight = FontWeight.Bold,
                            color = if (canProceed) PrimaryBlack else TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Check, contentDescription = "Finish")
                    }
                }
            }
        }
    }
}
