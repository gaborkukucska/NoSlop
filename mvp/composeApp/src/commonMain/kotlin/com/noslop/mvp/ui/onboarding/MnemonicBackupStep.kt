package com.noslop.mvp.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.ui.theme.*

@Composable
fun MnemonicBackupStep(
    mnemonic: String?,
    onGenerateMnemonic: () -> Unit,
    canGenerate: Boolean
) {
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (mnemonic == null) {
            Button(
                onClick = onGenerateMnemonic,
                enabled = canGenerate,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("Generate Word Cloud", fontWeight = FontWeight.Bold)
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, AccentGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable { 
                        clipboardManager.setText(AnnotatedString(mnemonic))
                    }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Word Cloud Password (BIP39):",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = mnemonic,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextLight,
                            lineHeight = 24.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tap to Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠ Write this down! It is the ONLY way to recover your account.",
                        style = MaterialTheme.typography.labelSmall,
                        color = DestructiveRed
                    )
                }
            }
        }
    }
}
