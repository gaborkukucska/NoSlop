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
import com.noslop.app.ui.MainScreen
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.OnboardingScreen
import com.noslop.app.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: NoSlopViewModel
    private val _routeFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = NoSlopViewModel.Factory(application)
        viewModel = ViewModelProvider(this, factory).get(NoSlopViewModel::class.java)

        intent?.getStringExtra("target_route")?.let { _routeFlow.value = it }

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

                    LaunchedEffect(Unit) {
                        if (showSplash) {
                            val startTime = System.currentTimeMillis()
                            var firstPreloadUrl: String? = null
                            
                            // 1. Wait up to 4 seconds for the feed to populate
                            try {
                                kotlinx.coroutines.withTimeout(4000) {
                                    viewModel.unifiedFeed.collect { items ->
                                        if (items.isNotEmpty()) {
                                            val firstItem = items.first()
                                            val rawUrl = when(firstItem) {
                                                is com.noslop.app.ui.UnifiedItem.Feed -> {
                                                    val type = firstItem.item.mediaType
                                                    if (type == "video" || type == "audio") firstItem.item.mediaUrl else null
                                                }
                                                is com.noslop.app.ui.UnifiedItem.Mesh -> {
                                                    val type = firstItem.post.mediaType ?: firstItem.post.clearnetMediaType
                                                    if (type == "video" || type == "audio") firstItem.post.mediaUrl ?: firstItem.post.clearnetUrl else null
                                                }
                                            }
                                            
                                            // Safely resolve local mesh media proxies natively to avoid import issues
                                            firstPreloadUrl = if (rawUrl?.startsWith("noslop://") == true) {
                                                val onion = rawUrl.substringAfter("noslop://").substringBefore("/")
                                                val id = rawUrl.substringAfterLast("/")
                                                "http://127.0.0.1:8080/stream?onion=${onion}&id=${id}"
                                            } else {
                                                rawUrl
                                            }
                                            throw java.util.concurrent.CancellationException("Feed Loaded")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Caught timeout or our deliberate success cancellation
                            }
                            
                            // 2. If the first item is media, aggressively pre-warm it before dropping the splash screen! (up to 4s)
                            if (firstPreloadUrl != null) {
                                try {
                                    kotlinx.coroutines.withTimeout(4000) {
                                        com.noslop.app.ui.PreloadManager.preWarm(this@MainActivity, firstPreloadUrl!!)
                                    }
                                } catch (e: Exception) {
                                    // Timeout on preload
                                }
                            }
                            
                            // 3. Ensure we've shown the splash for at least 1.8s for the aesthetic
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed < 1800) {
                                kotlinx.coroutines.delay(1800 - elapsed)
                            }
                            
                            showSplash = false
                        }
                    }

                    if (showSplash) {
                        com.noslop.app.ui.SplashScreen()
                    } else {
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