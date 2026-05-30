// FILE: app/src/main/java/com/noslop/app/ui/theme/Theme.kt
package com.noslop.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = PrimaryBlack,
    secondary = TextMuted,
    onSecondary = TextLight,
    tertiary = DestructiveRed,
    onTertiary = TextLight,
    background = PrimaryBlack,
    onBackground = TextLight,
    surface = SurfaceDark,
    onSurface = TextLight,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextMuted,
    outline = BorderSubtle
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00B360),
    onPrimary = Color.White,
    secondary = Color(0xFF4A4A4A),
    onSecondary = Color.White,
    tertiary = Color(0xFFD32F2F),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    onBackground = Color(0xFF222222),
    onSurface = Color(0xFF222222)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force Dark theme for premium cyberpunk feel consistent with build plan
    dynamicColor: Boolean = false, // Disable dynamic colors to preserve premium branding
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
