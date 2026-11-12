// FILE: app/src/main/java/com/noslop/app/ui/components/ScreenSecurity.kt
package com.noslop.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted
import com.noslop.app.util.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * --- NOSLOP_SCREEN_SECURITY_V1 ---
 * Messages are kept out of screenshots, screen recordings and the recent-apps preview (Android's
 * FLAG_SECURE) while they are on screen, unless the user turns it off. On by default.
 */
object ScreenSecurity {
    private const val PREFS = "noslop_screen_security"
    private const val KEY_HIDE_MESSAGES = "hide_messages"

    private val hideMessages = MutableStateFlow(true)
    @Volatile
    private var loaded = false

    fun hideMessages(context: Context): StateFlow<Boolean> {
        load(context)
        return hideMessages.asStateFlow()
    }

    fun setHideMessages(context: Context, hide: Boolean) {
        load(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_MESSAGES, hide).apply()
        hideMessages.value = hide
    }

    private fun load(context: Context) {
        if (loaded) return
        hideMessages.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HIDE_MESSAGES, true)
        loaded = true
    }

    /** Tests only: forget what was read. */
    internal fun resetForTest() {
        loaded = false
        hideMessages.value = true
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** While this is in the composition, and the setting is on, the window can't be captured. */
@Composable
fun SecureWhileShown() {
    val context = LocalContext.current
    val hide by remember(context) { ScreenSecurity.hideMessages(context) }.collectAsState()
    DisposableEffect(context, hide) {
        val window = if (hide) context.findActivity()?.window else null
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** The switch in Settings. */
@Composable
fun ScreenSecuritySetting() {
    val context = LocalContext.current
    val hide by remember(context) { ScreenSecurity.hideMessages(context) }.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Hide messages from screenshots".tr, fontWeight = FontWeight.Bold, color = TextLight)
            Text(
                "Messages can't be captured in screenshots or screen recordings, and the recent-apps preview stays blank while they are open.".tr,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }
        Switch(
            checked = hide,
            onCheckedChange = { ScreenSecurity.setHideMessages(context, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = PrimaryBlack,
                checkedTrackColor = AccentGreen,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceDark
            )
        )
    }
}
