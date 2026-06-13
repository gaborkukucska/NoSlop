// FILE: app/src/main/java/com/noslop/app/MainActivity.kt
package com.noslop.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
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

                LaunchedEffect(isOnboarded) {
                    viewModel.startTor()
                }

                if (isOnboarded) {
                    MainScreen(viewModel = viewModel, initialRoute = targetRoute)
                } else {
                    OnboardingScreen(
                        viewModel = viewModel,
                        onComplete = {
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
