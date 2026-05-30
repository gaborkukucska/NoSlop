// FILE: app/src/main/java/com/noslop/app/MainActivity.kt
package com.noslop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noslop.app.ui.MainScreen
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.OnboardingScreen
import com.noslop.app.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                // Instantiate the core NoSlop MVVM ViewModel
                val factory = NoSlopViewModel.Factory(application)
                val viewModel: NoSlopViewModel = viewModel(factory = factory)

                val isOnboarded by viewModel.isOnboardingComplete.collectAsState()

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
}
