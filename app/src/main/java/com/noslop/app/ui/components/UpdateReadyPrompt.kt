// FILE: app/src/main/java/com/noslop/app/ui/components/UpdateReadyPrompt.kt
package com.noslop.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noslop.app.NoSlopApp
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted
import com.noslop.app.util.SavedUpdate
import com.noslop.app.util.UpdateManager
import com.noslop.app.util.UpdatePolicy
import com.noslop.app.util.tr

/** The green shield. Shown only for a file that was compared against its published checksum. */
@Composable
internal fun UpdateChecksumBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.VerifiedUser,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text("Checksum verified".tr, color = AccentGreen, fontWeight = FontWeight.Bold)
            Text(
                "The file matches its published SHA-256 checksum.".tr,
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** How to finish an update. It stays until dismissed, because a toast is easy to miss. */
@Composable
internal fun UpdateReadyMessage(saved: SavedUpdate) {
    Text(
        "{file} is in your Download folder. Open it from your Files app to install.".tr
            .replace("{file}", saved.fileName),
        color = TextLight
    )
}

/**
 * --- NOSLOP_UPDATE_READY_V1 ---
 * Sits beside MainScreen, so it appears on whichever screen the user reached while the download ran.
 */
@Composable
fun UpdateReadyPrompt() {
    val context = LocalContext.current
    val checker = NoSlopApp.updateChecker
    val showPrompt by checker.showReadyPrompt.collectAsState()
    val savedUpdate by checker.savedUpdate.collectAsState()
    val updateInfo by checker.updateInfo.collectAsState()
    val saved = savedUpdate ?: return
    if (!showPrompt || !UpdatePolicy.isReadyToInstall(saved.version, updateInfo?.latestVersion)) return

    AlertDialog(
        onDismissRequest = { checker.dismissReadyPrompt() },
        containerColor = SurfaceDark,
        title = { Text("Update Ready to Install".tr, color = TextLight, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (saved.checksumVerified) {
                    UpdateChecksumBadge()
                    Spacer(modifier = Modifier.height(16.dp))
                }
                UpdateReadyMessage(saved)
            }
        },
        confirmButton = {
            if (UpdateManager.canOpenDownloads) {
                Button(
                    onClick = {
                        checker.dismissReadyPrompt()
                        UpdateManager.openDownloads(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) { Text("Open Downloads".tr, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            TextButton(onClick = { checker.dismissReadyPrompt() }) { Text("Later".tr, color = TextMuted) }
        }
    )
}
