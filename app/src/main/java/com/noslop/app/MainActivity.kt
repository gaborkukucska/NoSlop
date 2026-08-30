// FILE: app/src/main/java/com/noslop/app/MainActivity.kt
package com.noslop.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.noslop.app.ui.MainScreen
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.OnboardingScreen
import com.noslop.app.ui.theme.MyApplicationTheme
import com.noslop.app.util.tr

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: NoSlopViewModel
    private val _routeFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = NoSlopViewModel.Factory(application)
        viewModel = ViewModelProvider(this, factory).get(NoSlopViewModel::class.java)

        intent?.getStringExtra("target_route")?.let { _routeFlow.value = it + "-" + System.currentTimeMillis() }

        setContent {
            MyApplicationTheme {
                val isOnboarded by viewModel.isOnboardingComplete.collectAsState()
                val targetRoute by _routeFlow.collectAsState()
                
                // Track if onboarding was shown during this app session, using rememberSaveable 
                // to survive any unavoidable Activity recreation (like uiMode/Dark Mode toggles).
                var didJustFinishOnboarding by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(isOnboarded) {
                    viewModel.startTor()
                }

                if (isOnboarded) {
                    // Remember the splash screen state across configuration changes
                    var showSplash by rememberSaveable { mutableStateOf(!didJustFinishOnboarding) }
                    var splashStatusMessage by remember { mutableStateOf(com.noslop.app.util.LanguageManager.translate("Initializing NoSlop...")) }

                    LaunchedEffect(Unit) {
                        if (showSplash) {
                            val startTime = System.currentTimeMillis()
                            
                            // 1. Wait for Tor readiness if clearnet over Tor is enabled (up to 10s, non-blocking once ready)
                            splashStatusMessage = com.noslop.app.util.LanguageManager.translate("Connecting to Tor network...")
                            try {
                                com.noslop.app.net.HttpClientProvider.awaitNetworkReady(10000L)
                            } catch (_: Exception) {}

                            var firstPreloadUrl: String? = null
                            var secondPreloadUrl: String? = null
                            
                            // 2. Brief check for initial feed items (up to 3s)
                            splashStatusMessage = com.noslop.app.util.LanguageManager.translate("Loading feed items...")
                            try {
                                kotlinx.coroutines.withTimeout(3000L) {
                                    viewModel.unifiedFeed.collect { items ->
                                        if (items.isNotEmpty()) {
                                            val nonTutItems = items.filter { it !is com.noslop.app.ui.UnifiedItem.Tutorial }
                                            if (nonTutItems.isNotEmpty()) {
                                                val savedActiveId = com.noslop.app.NoSlopApp.repository.getAppSetting("saved_feed_active_id")
                                                val targetItem = nonTutItems.find { it.id == savedActiveId } ?: nonTutItems.firstOrNull()
                                                val targetIndex = nonTutItems.indexOf(targetItem)
                                                val secondItem = nonTutItems.getOrNull(if (targetIndex >= 0) targetIndex + 1 else 1)

                                                fun extractUrl(item: com.noslop.app.ui.UnifiedItem?): String? {
                                                    val (rawUrl, isPlayable) = when(item) {
                                                        is com.noslop.app.ui.UnifiedItem.Feed -> {
                                                            val isMedia = item.item.mediaType == "video" || item.item.mediaType == "audio" || (item.item.mediaUrl?.contains("youtube") == true || item.item.url?.contains("youtube") == true)
                                                            Pair(item.item.mediaUrl ?: item.item.url, isMedia)
                                                        }
                                                        is com.noslop.app.ui.UnifiedItem.Mesh -> {
                                                            val isMedia = item.post.mediaType == "video" || item.post.mediaType == "audio" || item.post.clearnetMediaType == "video" || item.post.clearnetMediaType == "audio"
                                                            Pair(item.post.mediaUrl ?: item.post.clearnetUrl, isMedia)
                                                        }
                                                        else -> Pair(null, false)
                                                    }
                                                    if (!isPlayable || rawUrl.isNullOrBlank()) return null
                                                    return if (rawUrl.startsWith("noslop://")) {
                                                        val onion = rawUrl.substringAfter("noslop://").substringBefore("/")
                                                        val id = rawUrl.substringAfterLast("/")
                                                        "http://127.0.0.1:8080/stream?onion=${onion}&id=${id}"
                                                    } else {
                                                        rawUrl
                                                    }
                                                }

                                                firstPreloadUrl = extractUrl(targetItem)
                                                secondPreloadUrl = extractUrl(secondItem)
                                                throw java.util.concurrent.CancellationException("Feed Loaded")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Caught timeout or deliberate success cancellation
                            }
                            
                            splashStatusMessage = com.noslop.app.util.LanguageManager.translate("Starting NoSlop...")
                            
                            // 3. Keep splash smooth and snappy (1.2s min delay)
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed < 1200L) {
                                kotlinx.coroutines.delay(1200L - elapsed)
                            }
                            
                            showSplash = false

                            // 4. Pre-warm Slide 1 and Slide 2 asynchronously in the background so UI renders immediately
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                firstPreloadUrl?.let { url ->
                                    try { com.noslop.app.ui.PreloadManager.preWarm(this@MainActivity, url) } catch (_: Exception) {}
                                }
                                secondPreloadUrl?.let { url ->
                                    try {
                                        kotlinx.coroutines.delay(1000L)
                                        com.noslop.app.ui.PreloadManager.preWarm(this@MainActivity, url)
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                    if (showSplash) {
                        val buildStatus by viewModel.feedBuildStatus.collectAsState()
                        val statusMsg = when {
                            buildStatus.isNotBlank() -> buildStatus
                            splashStatusMessage.isNotBlank() -> splashStatusMessage
                            else -> "Initializing NoSlop...".tr
                        }
                        com.noslop.app.ui.SplashScreen(statusMessage = statusMsg)
                    } else {
                        val prefs = applicationContext.getSharedPreferences("noslop_system", android.content.Context.MODE_PRIVATE)
                        var showRestoreHubPrompt by rememberSaveable { mutableStateOf(prefs.getBoolean("prompt_hub_after_restore", false)) }
                        val hubStatus by viewModel.hubDeploymentStatus.collectAsState()

                        if (showRestoreHubPrompt && hubStatus.isNullOrBlank()) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { 
                                    prefs.edit().putBoolean("prompt_hub_after_restore", false).apply()
                                    showRestoreHubPrompt = false 
                                },
                                containerColor = com.noslop.app.ui.theme.SurfaceDark,
                                title = { androidx.compose.material3.Text("Connect Home Hub?".tr, color = com.noslop.app.ui.theme.TextLight, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                                text = { androidx.compose.material3.Text("Your profile was restored, but you don't have a Home Hub connected. Would you like to set one up now?".tr, color = com.noslop.app.ui.theme.TextMuted) },
                                confirmButton = {
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            prefs.edit().putBoolean("prompt_hub_after_restore", false).apply()
                                            showRestoreHubPrompt = false
                                            _routeFlow.value = "hubs"
                                        },
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = com.noslop.app.ui.theme.AccentGreen, contentColor = com.noslop.app.ui.theme.PrimaryBlack)
                                    ) {
                                        androidx.compose.material3.Text("Setup Hub".tr)
                                    }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { 
                                        prefs.edit().putBoolean("prompt_hub_after_restore", false).apply()
                                        showRestoreHubPrompt = false 
                                    }) {
                                        androidx.compose.material3.Text("Not Now".tr, color = com.noslop.app.ui.theme.TextMuted)
                                    }
                                }
                            )
                        }

                        MainScreen(viewModel = viewModel, initialRoute = targetRoute)
                    }
                } else {
                    OnboardingScreen(
                        viewModel = viewModel,
                        onComplete = {
                            didJustFinishOnboarding = true
                            // On completion, state automatically triggers recomposition to MainScreen
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::viewModel.isInitialized) {
            viewModel.refreshTorStatus()
            viewModel.checkForUpdateNow()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("target_route")?.let { _routeFlow.value = it }
    }
}