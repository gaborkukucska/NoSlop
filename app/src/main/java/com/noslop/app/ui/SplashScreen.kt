package com.noslop.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.TextMuted
import kotlinx.coroutines.delay

@Composable
fun SplashScreen() {
    var startAnimation by remember { mutableStateOf(false) }

    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
    )

    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    )

    LaunchedEffect(Unit) {
        delay(300) // Small delay before starting animation
        startAnimation = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim)
        ) {
            Text(
                text = "NO_SLOP",
                style = MaterialTheme.typography.headlineLarge.copy(
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Welcome Back",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = TextMuted,
                    letterSpacing = 2.sp
                )
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Sleek pulsing indicator
            val infiniteTransition = rememberInfiniteTransition()
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(pulseAlpha)
                    .background(AccentGreen, shape = androidx.compose.foundation.shape.CircleShape)
            )
        }
    }
}
