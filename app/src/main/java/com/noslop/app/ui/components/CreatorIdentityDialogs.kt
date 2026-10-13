package com.noslop.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.CreatorIdentityArchive
import com.noslop.app.data.CreatorRestoreResult
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr

/**
 * D10d: asks for the passphrase of a creator identity file. For a backup ([confirmTwice]) it is typed
 * twice and must have CreatorIdentityArchive.MIN_PASSPHRASE_LENGTH characters; for a restore, once.
 * Spaces at either end are ignored, as they are when the file is written and read.
 */
@Composable
fun CreatorPassphraseDialog(
    title: String,
    message: String,
    confirmTwice: Boolean,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val trimmed = passphrase.trim()
    val longEnough = trimmed.length >= CreatorIdentityArchive.MIN_PASSPHRASE_LENGTH
    val matches = trimmed == repeated.trim()
    val canConfirm = if (confirmTwice) longEnough && matches else trimmed.isNotEmpty()
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
        focusedTextColor = TextLight, unfocusedTextColor = TextLight
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text(title, color = TextLight, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(message, color = TextMuted, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase".tr) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirmTwice) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it },
                        label = { Text("Repeat Passphrase".tr) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val hint = when {
                        !longEnough -> "At least {count} characters.".tr.replace("{count}", CreatorIdentityArchive.MIN_PASSPHRASE_LENGTH.toString())
                        !matches -> "The two passphrases differ.".tr
                        else -> "Keep this passphrase safe. Without it the file cannot be opened.".tr
                    }
                    Text(hint, color = if (longEnough && matches) TextMuted else DestructiveRed, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel".tr, color = TextMuted) }
        }
    )
}

/** D10d: this device already has a different secondary identity; restoring replaces it. */
@Composable
fun CreatorReplaceConfirmDialog(onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Replace Secondary Identity?".tr, color = DestructiveRed, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "This device already has a different secondary identity. Restoring replaces it: the creators it follows and the contacts that know it lose it for good.".tr,
                color = TextMuted
            )
        },
        confirmButton = {
            Button(
                onClick = onReplace,
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveRed, contentColor = Color.White)
            ) { Text("Replace It".tr, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel".tr, color = AccentGreen) }
        }
    )
}

/** D10d: what a creator identity restore brought in, and the old device to switch off. */
@Composable
fun CreatorRestoreResultDialog(result: CreatorRestoreResult.Restored, onDismiss: () -> Unit) {
    val summary = result.summary
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Creator Identity Restored".tr, color = AccentGreen, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "{handle} is now this device's creator identity: {contacts} contacts, {posts} posts and {media} media files.".tr
                        .replace("{handle}", summary.handle)
                        .replace("{contacts}", summary.contacts.toString())
                        .replace("{posts}", summary.posts.toString())
                        .replace("{media}", summary.mediaFiles.toString()),
                    color = TextLight
                )
                if (result.skippedContacts > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "{count} contacts were left out because this device already knows them by your personal identity.".tr
                            .replace("{count}", result.skippedContacts.toString()),
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
                if (summary.missingMedia > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "{count} posts had no media file on the old device, so their media is not included.".tr
                            .replace("{count}", summary.missingMedia.toString()),
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "If this identity still runs on another device, turn creator mode off there now. Two devices running the same creator identity compete for one address, and followers reach only one of them.".tr,
                    color = DestructiveRed,
                    fontSize = 13.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
            ) { Text("OK".tr, fontWeight = FontWeight.Bold) }
        }
    )
}
