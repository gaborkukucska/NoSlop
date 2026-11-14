// FILE: app/src/main/java/com/noslop/app/ui/components/DbEncryptionUi.kt
package com.noslop.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.DbEncryption
import com.noslop.app.data.DbEncryptionPolicy.PlainReason
import com.noslop.app.debug.Logger
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.AccentGreen
import com.noslop.app.ui.theme.BorderSubtle
import com.noslop.app.ui.theme.PrimaryBlack
import com.noslop.app.ui.theme.SurfaceDark
import com.noslop.app.ui.theme.TemporaryAmber
import com.noslop.app.ui.theme.TextLight
import com.noslop.app.ui.theme.TextMuted
import com.noslop.app.util.LanguageManager
import com.noslop.app.util.tr

/**
 * NOSLOP_DB_ENCRYPTION_V1 (docs/TECHNICAL_REFERENCE.md §16.51): what the user sees of database encryption.
 *
 *  * [DbEncryptionSetting]: one line in Settings saying whether the database on this phone is encrypted,
 *    and if not, why.
 *  * [LockedDatabaseScreen]: shown in place of the whole app when the database can't be opened. The app
 *    then runs on an empty stand-in database (so nothing crashes and nothing touches the real file), Tor
 *    and the mesh stay off, and this screen offers the ways out.
 */

/** The line in Settings. Says nothing while the database is still being opened. */
@Composable
fun DbEncryptionSetting() {
    val status by DbEncryption.status.collectAsState()
    val encrypted = status.state == DbEncryption.State.ENCRYPTED
    if (!encrypted && status.state != DbEncryption.State.PLAINTEXT) return

    val text = if (encrypted) {
        "The database on this phone is encrypted. Its key is kept by the Android Keystore and never leaves this phone.".tr
    } else {
        val reason = when (status.reason) {
            PlainReason.KEY_UNAVAILABLE -> "this phone's Keystore is not available".tr
            PlainReason.LIBRARY_UNAVAILABLE -> "the encryption library did not load".tr
            PlainReason.NOT_ENOUGH_SPACE -> "there is not enough free space to convert it. Free some space and restart NoSlop".tr
            PlainReason.CONVERSION_FAILED -> "converting it failed. NoSlop will try again".tr
            null -> "the reason is not known".tr
        }
        LanguageManager.translate("The database on this phone is not encrypted: {reason}.").replace("{reason}", reason)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Database encryption".tr, fontWeight = FontWeight.Bold, color = TextLight)
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (encrypted) TextMuted else TemporaryAmber)
    }
}

/**
 * The whole screen when the database can't be opened ([DbEncryption.State.LOCKED]).
 *
 * Nothing here deletes anything without being asked. "Start Over" sets the unreadable file aside under
 * another name; a restore replaces it only after the backup has been read and checked.
 */
@Composable
fun LockedDatabaseScreen(viewModel: NoSlopViewModel, status: DbEncryption.Status) {
    val context = LocalContext.current
    var showRestore by remember { mutableStateOf(false) }
    var showStartOver by remember { mutableStateOf(false) }
    var confirmLegacy by remember { mutableStateOf(false) }
    var mnemonic by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var startOverFailed by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickedUri = uri
    }

    fun startRestore(allowLegacy: Boolean) {
        val uri = pickedUri ?: return
        busy = true
        error = null
        // On success the view model restarts the app, so there is nothing to do here in that case.
        viewModel.importBackupFromUri(
            context, mnemonic.trim(), uri,
            allowLegacyUnauthenticated = allowLegacy,
            onLegacyDetected = {
                busy = false
                confirmLegacy = true
            },
            onFailed = { reason -> error = reason },
            onResult = { ok ->
                if (!ok) {
                    busy = false
                    if (error == null) error = ""
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = PrimaryBlack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = TemporaryAmber, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Your data can't be opened".tr,
                color = TextLight, fontWeight = FontWeight.Bold, fontSize = 22.sp, textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                if (status.reason == PlainReason.KEY_UNAVAILABLE) {
                    "NoSlop keeps its database encrypted with a key held by this phone. That key can't be read right now, so the database can't be opened. Nothing has been deleted.".tr
                } else {
                    "The database on this phone could not be opened. Nothing has been deleted.".tr
                },
                color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Closing NoSlop and opening it again, or restarting the phone, sometimes fixes this.".tr,
                color = TextMuted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            val detail = status.detail
            if (!detail.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    LanguageManager.translate("Details: {detail}").replace("{detail}", detail),
                    color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = { closeApp(context) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) {
                Text("Close NoSlop".tr, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = { showRestore = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Restore from Backup".tr, color = AccentGreen)
            }
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = { showStartOver = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Start Over".tr, color = TextMuted)
            }
            if (startOverFailed) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "That did not work. Close NoSlop and try again.".tr,
                    color = TemporaryAmber, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
        }
    }

    if (showRestore) {
        AlertDialog(
            onDismissRequest = { if (!busy) showRestore = false },
            containerColor = SurfaceDark,
            title = { Text("Restore from Backup".tr, color = TextLight, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Please enter your Word Cloud password to decrypt the backup archive:".tr,
                        color = TextMuted, fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = mnemonic,
                        onValueChange = { mnemonic = it },
                        enabled = !busy,
                        label = { Text("Enter Word Cloud (Mnemonic)".tr) },
                        placeholder = { Text("12 recovery words...".tr) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextLight,
                            unfocusedTextColor = TextLight
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (pickedUri == null) "Select Backup File".tr else "Backup file selected".tr,
                            color = AccentGreen
                        )
                    }
                    if (busy) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Importing...".tr, color = TextMuted, fontSize = 13.sp)
                    }
                    val failure = error
                    if (failure != null && !busy) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            if (failure.isBlank()) {
                                "Import failed. Check password and file.".tr
                            } else {
                                LanguageManager.translate("Restore failed: {reason}").replace("{reason}", failure)
                            },
                            color = TemporaryAmber, fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { startRestore(allowLegacy = false) },
                    enabled = !busy && pickedUri != null && mnemonic.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("Restore".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestore = false }, enabled = !busy) {
                    Text("Cancel".tr, color = TextMuted)
                }
            }
        )
    }

    if (confirmLegacy) {
        AlertDialog(
            onDismissRequest = { confirmLegacy = false },
            containerColor = SurfaceDark,
            title = { Text("Older Backup Format".tr, color = TemporaryAmber, fontWeight = FontWeight.Bold) },
            text = { Text("Do you want to proceed with importing this legacy archive?".tr, color = TextMuted) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLegacy = false
                        startRestore(allowLegacy = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TemporaryAmber, contentColor = PrimaryBlack)
                ) {
                    Text("Proceed with Import".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLegacy = false }) {
                    Text("Cancel".tr, color = TextMuted)
                }
            }
        )
    }

    if (showStartOver) {
        AlertDialog(
            onDismissRequest = { showStartOver = false },
            containerColor = SurfaceDark,
            title = { Text("Start over?".tr, color = TemporaryAmber, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The database that can't be opened is set aside and NoSlop starts empty. The contacts, messages and feed history on this phone will be gone unless you restore a backup. Try closing and reopening NoSlop first.".tr,
                    color = TextMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStartOver = false
                        if (DbEncryption.setAsideUnreadableDatabase(context)) {
                            restartApp(context)
                        } else {
                            startOverFailed = true
                            Logger.error("DB_ENCRYPTION", "Start over: the unreadable database could not be set aside")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TemporaryAmber, contentColor = PrimaryBlack)
                ) {
                    Text("Start Over".tr, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStartOver = false }) {
                    Text("Cancel".tr, color = TextMuted)
                }
            }
        )
    }
}

private fun closeApp(context: android.content.Context) {
    (context as? android.app.Activity)?.finishAndRemoveTask()
    android.os.Process.killProcess(android.os.Process.myPid())
}

private fun restartApp(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
}
