// FILE: app/src/main/java/com/noslop/app/ui/components/PhoneNotifications.kt
package com.noslop.app.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.noslop.app.debug.Logger
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.TemporaryAmber
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted
import com.noslop.app.util.tr

/**
 * --- NOSLOP_PHONE_NOTIFICATIONS_V1 ---
 * Alerts in the phone's notification shade. On Android 13 and later posting a notification needs the
 * POST_NOTIFICATIONS permission at run time; without it Android drops every notification silently.
 * The user's own switch is NotificationSettings.phone (on by default).
 */
private const val TAG = "NOTIFY"
private const val PREFS = "noslop_phone_notifications"
private const val ASKED_KEY = "post_notifications_asked"

/** Whether Android lets NoSlop post notifications: the permission, and the app's switch in Android settings. */
fun phoneNotificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

/** Android's notification settings page for NoSlop. */
fun openAppNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Logger.warn(TAG, "Could not open the notification settings: ${e.message}")
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

private fun wasAsked(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_KEY, false)

private fun markAsked(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED_KEY, true).apply()
}

/** Whether Android allows NoSlop's notifications, read again each time the app comes back to the front. */
@Composable
private fun rememberPhoneNotificationsAllowed(): Boolean {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(phoneNotificationsAllowed(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = phoneNotificationsAllowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return allowed
}

/**
 * Asks Android to allow notifications. Where Android will not show its dialog (denied twice, or the
 * permission is there but NoSlop is switched off in Android settings, or Android 12 and older), the
 * settings page opens instead, when [openSettingsIfRefused].
 */
@Composable
private fun rememberNotificationPermissionRequest(openSettingsIfRefused: Boolean, onDone: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Logger.info(TAG, "Notification permission ${if (granted) "granted" else "declined"}")
        if (openSettingsIfRefused && !phoneNotificationsAllowed(context)) {
            val activity = context.findActivity()
            val androidWillAskAgain = !granted && activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            if (!androidWillAskAgain) openAppNotificationSettings(context)
        }
        onDone()
    }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                markAsked(context)
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (openSettingsIfRefused) {
                openAppNotificationSettings(context)
            }
        }
    }
}

/** Asks once per install, on the main screen, while phone notifications are switched on. */
@Composable
fun PhoneNotificationPermissionPrompt(wanted: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val request = rememberNotificationPermissionRequest(openSettingsIfRefused = false)
    LaunchedEffect(wanted) {
        if (wanted && !phoneNotificationsAllowed(context) && !wasAsked(context)) request()
    }
}

/** Settings > Notifications: the phone notifications switch, above the in-app choices. */
@Composable
fun PhoneNotificationsSetting(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val allowed = rememberPhoneNotificationsAllowed()
    val request = rememberNotificationPermissionRequest(openSettingsIfRefused = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Phone notifications".tr, color = TextLight, fontSize = 14.sp)
            Text("Show alerts in the phone's notifications, not only in the app.".tr, color = TextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                onEnabledChange(on)
                if (on && !allowed) request()
            },
            colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
        )
    }
    if (enabled && !allowed) {
        Text(
            "Android is not letting NoSlop show notifications. Tap to allow.".tr,
            color = TemporaryAmber,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clickable { request() }.padding(vertical = 4.dp)
        )
    }
}
