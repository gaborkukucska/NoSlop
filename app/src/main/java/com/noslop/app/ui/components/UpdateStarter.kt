// FILE: app/src/main/java/com/noslop/app/ui/components/UpdateStarter.kt
package com.noslop.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.noslop.app.util.UpdateInfo
import com.noslop.app.util.UpdateManager

/**
 * --- NOSLOP_SAVE_AS_PICKER_V1 ---
 * Returns the function the update buttons call. On Android 10+ it first opens the system "Save as"
 * picker and starts the download once a place is chosen, so the system, not NoSlop, creates the APK
 * file. A file NoSlop creates itself can't be opened from the Files app (see UpdateManager). Cancelling
 * the picker cancels the update. Android 9 and older start the download straight away.
 */
@Composable
fun rememberUpdateStarter(): (UpdateInfo) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<UpdateInfo?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(UpdateManager.SAVE_MIME)
    ) { uri ->
        val info = pending
        pending = null
        if (info != null && uri != null) UpdateManager.startDownload(context, info, destination = uri)
    }
    return { info ->
        if (UpdateManager.needsSavePicker) {
            pending = info
            launcher.launch(UpdateManager.suggestedFileName(info.latestVersion))
        } else {
            UpdateManager.startDownload(context, info)
        }
    }
}
