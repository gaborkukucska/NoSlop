package com.noslop.app.ui.tabs

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.BuildConfig
import com.noslop.app.debug.Logger
import com.noslop.app.ui.theme.*
import java.net.URLEncoder

@Composable
fun ReportIssueScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var includeDeviceInfo by remember { mutableStateOf(true) }
    var includeLogs by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
            }
            Text(
                text = "File a Bug Report",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = "Help us improve NoSlop by reporting bugs or suggesting features. This will open a pre-filled GitHub issue in your browser.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (e.g., 'Video player crashes on pause')") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    cursorColor = AccentGreen
                ),
                singleLine = true
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (Steps to reproduce, expected behavior, etc.)") },
                modifier = Modifier.fillMaxWidth().height(150.dp).padding(bottom = 16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    cursorColor = AccentGreen
                ),
                maxLines = 10
            )

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Attachments", fontWeight = FontWeight.Bold, color = TextLight, modifier = Modifier.padding(bottom = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Include Device Information", color = TextLight, fontSize = 14.sp)
                            Text("OS Version, Device Model, App Version", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = includeDeviceInfo,
                            onCheckedChange = { includeDeviceInfo = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                        )
                    }

                    HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text("Include Recent Error Logs", color = TextLight, fontSize = 14.sp)
                            Text("The last 25 warnings/errors from the local Logger", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = includeLogs,
                            onCheckedChange = { includeLogs = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = AccentGreen)
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val issueTitle = "[Bug] ${title.takeIf { it.isNotBlank() } ?: "User Report"}"
                    val bodyBuilder = java.lang.StringBuilder()
                    bodyBuilder.append("### Description\n")
                    bodyBuilder.append(description.takeIf { it.isNotBlank() } ?: "No description provided.")
                    bodyBuilder.append("\n\n")

                    if (includeDeviceInfo) {
                        bodyBuilder.append("### Device Information\n")
                        bodyBuilder.append("- **Device:** ${Build.MANUFACTURER} ${Build.MODEL}\n")
                        bodyBuilder.append("- **Android Version:** ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                        bodyBuilder.append("- **App Version:** ${BuildConfig.VERSION_NAME}\n\n")
                    }

                    if (includeLogs) {
                        bodyBuilder.append("### Recent Error Logs\n")
                        val logs = Logger.getLogs()
                            .filter { it.level == Logger.Level.ERROR || it.level == Logger.Level.WARN }
                            .takeLast(25)
                        if (logs.isNotEmpty()) {
                            bodyBuilder.append("```\n")
                            logs.forEach { bodyBuilder.append(it.toString()).append("\n") }
                            bodyBuilder.append("```\n")
                        } else {
                            bodyBuilder.append("No recent errors or warnings found.\n")
                        }
                    }

                    val encodedTitle = URLEncoder.encode(issueTitle, "UTF-8")
                    val encodedBody = URLEncoder.encode(bodyBuilder.toString(), "UTF-8")
                    val url = "https://github.com/gaborkukucska/NoSlop/issues/new?title=\$encodedTitle&body=\$encodedBody"

                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                enabled = title.isNotBlank() || description.isNotBlank()
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submit via GitHub", fontWeight = FontWeight.Bold)
            }
        }
    }
}
