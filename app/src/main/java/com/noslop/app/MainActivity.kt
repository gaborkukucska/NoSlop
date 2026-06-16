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
                            kotlinx.coroutines.delay(3000)
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("target_route")?.let { _routeFlow.value = it }
    }
}