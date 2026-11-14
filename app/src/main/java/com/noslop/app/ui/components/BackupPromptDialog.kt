// FILE: app/src/main/java/com/noslop/app/ui/components/BackupPromptDialog.kt
package com.noslop.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.ui.BackupPromptPolicy
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.BorderSubtle
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TemporaryAmber
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted
import com.noslop.app.util.tr
import kotlinx.coroutines.launch

/**
 * NOSLOP_BACKUP_PROMPT_AFTER_UPDATE_V1.
 *
 * The "Protect Your Sovereign Identity" popup. It lived inside the Settings tab, so it only appeared
 * once the user opened Settings. It is now placed once in the main screen and shows on any tab.
 *
 * It also asks, once, whether an app update makes a backup due ([NoSlopViewModel.checkBackupPromptAfterUpdate]).
 * The onboarding and the after-update popup wait until the feed tutorial is finished
 * ([BackupPromptPolicy.mayShowNow]). The dialog itself is the one from the Settings tab, unchanged.
 */
@Composable
fun BackupPromptHost(viewModel: NoSlopViewModel) {
    // Runs when the main screen first appears, so after the splash screen and never during onboarding.
    LaunchedEffect(Unit) { viewModel.checkBackupPromptAfterUpdate() }

    val backupPromptReason by viewModel.backupPromptReason.collectAsState()
    val feedTutorialStep by viewModel.feedTutorialStep.collectAsState()
    val reason = backupPromptReason ?: return
    val waitsForFeedTutorial = reason == NoSlopViewModel.BackupPromptReason.ONBOARDING_COMPLETED ||
        reason == NoSlopViewModel.BackupPromptReason.APP_UPDATED
    if (!BackupPromptPolicy.mayShowNow(waitsForFeedTutorial, feedTutorialStep)) return

    val context = LocalContext.current
    val reasonExplanation = when (reason) {
        NoSlopViewModel.BackupPromptReason.ONBOARDING_COMPLETED ->
            "Node setup is complete! Because NoSlop is a completely serverless, peer-to-peer network, there are no central servers or email logins. Your cryptographic keys and contacts live only on this device.".tr
        NoSlopViewModel.BackupPromptReason.DISCOVERABILITY_CHANGED ->
            "Discoverability mode has been toggled. Your node has configured an ephemeral burnable onion address. Back up your identity now to retain your reachability settings.".tr
        NoSlopViewModel.BackupPromptReason.CREATOR_MODE_CHANGED ->
            "Creator Mode is now active! A secondary Creator ID keypair and onion address have been generated. Back up your keys now to preserve your creator broadcasts and followers.".tr
        NoSlopViewModel.BackupPromptReason.CREATOR_IDENTITY_BURNED ->
            "Creator ID burned and re-generated! You have minted a fresh cryptographic creator address. Back up now to preserve your new identity.".tr
        NoSlopViewModel.BackupPromptReason.APP_UPDATED ->
            "NoSlop was updated. Your keys and contacts live only on this device, with no server copy. Make a fresh backup now, so your latest contacts, groups and settings can be restored if this device is lost.".tr
    }

    var advisoryOption by remember { mutableStateOf(com.noslop.app.data.BackupMediaOption.NONE) }
    var advisoryMnemonicInput by remember { mutableStateOf("") }
    var showAdvisoryMnemonicPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(showAdvisoryMnemonicPrompt) {
        if (com.noslop.app.BuildConfig.AUTOFILL_BACKUP_MNEMONIC && showAdvisoryMnemonicPrompt && advisoryMnemonicInput.isBlank()) {
            val stored = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.noslop.app.NoSlopApp.repository.getWordCloudMnemonic()
            }
            if (stored.isNotBlank()) advisoryMnemonicInput = stored
        }
    }
    // R5b: set once the typed words were checked (matching, or the user accepted a difference).
    var advisoryWordsAccepted by remember { mutableStateOf(false) }
    var advisoryMismatch by remember { mutableStateOf<com.noslop.app.data.WordCloudCheck.Differs?>(null) }
    val advisoryScope = rememberCoroutineScope()
    val advisoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && advisoryMnemonicInput.isNotBlank()) {
            val mediaOpt = advisoryOption
            val mnemonic = advisoryMnemonicInput.trim()
            advisoryScope.launch {
                if (advisoryWordsAccepted) {
                    advisoryWordsAccepted = false
                    viewModel.exportBackupToUri(context, mnemonic, uri, mediaOpt) { success, _ ->
                        if (success) {
                            android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Backup exported successfully!"), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    advisoryMnemonicInput = ""
                    showAdvisoryMnemonicPrompt = false
                    viewModel.dismissBackupPrompt()
                } else {
                    android.widget.Toast.makeText(context, com.noslop.app.util.LanguageManager.translate("Mnemonic does not match your active Word Cloud!"), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { viewModel.dismissBackupPrompt() },
        containerColor = SurfaceDark,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = AccentGreen, modifier = Modifier.size(26.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Protect Your Sovereign Identity".tr, color = TextLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column {
                Text(reasonExplanation, color = TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(modifier = Modifier.height(10.dp))
                Text("Store your encrypted backup archive in multiple safe locations (e.g. USB flash drive, password manager, or offline storage):".tr, color = TextLight, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(12.dp))

                if (showAdvisoryMnemonicPrompt) {
                    OutlinedTextField(
                        value = advisoryMnemonicInput,
                        onValueChange = { advisoryMnemonicInput = it },
                        label = { Text("Enter Word Cloud (Mnemonic)".tr) },
                        placeholder = { Text("12 recovery words...".tr) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advisoryOption = com.noslop.app.data.BackupMediaOption.NONE },
                    colors = CardDefaults.cardColors(
                        containerColor = if (advisoryOption == com.noslop.app.data.BackupMediaOption.NONE) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack
                    ),
                    border = BorderStroke(1.dp, if (advisoryOption == com.noslop.app.data.BackupMediaOption.NONE) AccentGreen else BorderSubtle)
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = advisoryOption == com.noslop.app.data.BackupMediaOption.NONE,
                            onClick = { advisoryOption = com.noslop.app.data.BackupMediaOption.NONE },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("IDs & Keys Only (Lightweight)".tr, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 13.sp)
                            Text("All keys, contacts, groups & settings (~100 KB). Fast & reliable.".tr, color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advisoryOption = com.noslop.app.data.BackupMediaOption.OWNED_ONLY },
                    colors = CardDefaults.cardColors(
                        containerColor = if (advisoryOption == com.noslop.app.data.BackupMediaOption.OWNED_ONLY) AccentGreen.copy(alpha = 0.15f) else PrimaryBlack
                    ),
                    border = BorderStroke(1.dp, if (advisoryOption == com.noslop.app.data.BackupMediaOption.OWNED_ONLY) AccentGreen else BorderSubtle)
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = advisoryOption == com.noslop.app.data.BackupMediaOption.OWNED_ONLY,
                            onClick = { advisoryOption = com.noslop.app.data.BackupMediaOption.OWNED_ONLY },
                            colors = RadioButtonDefaults.colors(selectedColor = AccentGreen)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("Full Backup (Owned Media Included)".tr, fontWeight = FontWeight.Bold, color = TextLight, fontSize = 13.sp)
                            Text("Database and all media created by you.".tr, color = TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!showAdvisoryMnemonicPrompt) {
                        showAdvisoryMnemonicPrompt = true
                    } else if (advisoryMnemonicInput.isNotBlank()) {
                        val mnemonicToVerify = advisoryMnemonicInput.trim()
                        advisoryScope.launch {
                            val check = viewModel.checkWordCloud(mnemonicToVerify)
                            if (check is com.noslop.app.data.WordCloudCheck.Differs) {
                                advisoryMismatch = check
                            } else {
                                advisoryWordsAccepted = true
                                val prefix = if (advisoryOption == com.noslop.app.data.BackupMediaOption.NONE) "noslop_keys_" else "noslop_full_"
                                val exportTimestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                                advisoryLauncher.launch("$prefix$exportTimestamp.zip")
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) {
                Text(if (showAdvisoryMnemonicPrompt) "Choose File Location".tr else "Export Backup Now".tr, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissBackupPrompt() }) {
                Text("Remind Me Later".tr, color = TextMuted)
            }
        }
    )

    advisoryMismatch?.let { mismatch ->
        AlertDialog(
            onDismissRequest = { advisoryMismatch = null },
            title = { Text("Word Cloud Differs".tr, color = TemporaryAmber, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    com.noslop.app.util.LanguageManager.translate("The words you typed differ from the Word Cloud saved on this device in {count} of {total} places. The backup is encrypted with the words you typed and will open only with exactly those words. Continue only if they are the words you have written down.")
                        .replace("{count}", mismatch.differingWords.toString())
                        .replace("{total}", maxOf(mismatch.storedWords, mismatch.typedWords).toString()),
                    color = TextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        advisoryMismatch = null
                        advisoryWordsAccepted = true
                        val prefix = if (advisoryOption == com.noslop.app.data.BackupMediaOption.NONE) "noslop_keys_" else "noslop_full_"
                        val exportTimestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                        advisoryLauncher.launch("$prefix$exportTimestamp.zip")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TemporaryAmber, contentColor = PrimaryBlack)
                ) {
                    Text("Back Up With These Words".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { advisoryMismatch = null }) {
                    Text("Re-enter Words".tr, color = AccentGreen)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
