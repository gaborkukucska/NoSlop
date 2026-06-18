package com.noslop.mvp

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point consumed by the SwiftUI host (iosApp) — wraps the shared Compose [App] in a UIViewController. */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
