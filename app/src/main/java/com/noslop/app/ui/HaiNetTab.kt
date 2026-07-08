package com.noslop.app.ui

import androidx.compose.runtime.Composable
import com.noslop.app.ui.tabs.HubSetupScreen

@Composable
fun HaiNetTab(viewModel: NoSlopViewModel) {
    HubSetupScreen(viewModel = viewModel)
}
