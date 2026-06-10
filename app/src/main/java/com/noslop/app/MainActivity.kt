// FILE: app/src/main/java/com/noslop/app/MainActivity.kt
package com.noslop.app

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = NoSlopViewModel.Factory(application)
        viewModel = ViewModelProvider(this, factory).get(NoSlopViewModel::class.java)

        setContent {
            MyApplicationTheme {
                val isOnboarded by viewModel.isOnboardingComplete.collectAsState()

                LaunchedEffect(isOnboarded) {
                    viewModel.startTor()
                }

                if (isOnboarded) {
                    MainScreen(viewModel = viewModel)
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
}
