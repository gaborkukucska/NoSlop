// FILE: app/src/main/java/com/noslop/app/ui/components/SourceStatusBanner.kt
package com.noslop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.feeds.SourceHealth
import com.noslop.app.feeds.SourceHealthRules
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted
import com.noslop.app.util.tr

/**
 * NOSLOP_SOURCE_STATUS_V1 — a line on the feed naming the API services not responding.
 * Tapping hides it until the set of services changes.
 */
@Composable
fun SourceStatusBanner(modifier: Modifier = Modifier) {
    val health by SourceHealth.state.collectAsState()
    val names = remember(health) { SourceHealthRules.failingServiceNames(SourceHealthRules.failing(health)) }
    val key = names.joinToString(",")
    var hidden by rememberSaveable { mutableStateOf("") }
    if (names.isEmpty() || hidden == key) return
    val text = (if (names.size == 1) "{sources} not responding right now. Its items may be missing for a while.".tr
        else "{sources} not responding right now. Their items may be missing for a while.".tr)
        .replace("{sources}", names.joinToString(", "))
    Column(
        modifier = modifier
            .background(PrimaryBlack.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .clickable { hidden = key }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = Color(0xFFFFB74D), fontSize = 12.sp)
        Text("Tap to hide".tr, color = TextMuted, fontSize = 10.sp)
    }
}
