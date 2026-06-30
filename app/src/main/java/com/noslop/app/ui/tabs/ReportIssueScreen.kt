package com.noslop.app.ui.tabs

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.noslop.app.net.HttpClientProvider
import com.noslop.app.ui.theme.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportIssueScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var preferredName by remember { mutableStateOf("") }
    var includeDeviceInfo by remember { mutableStateOf(true) }
    var includeLogs by remember { mutableStateOf(true) }

    val issueTypes = listOf("Bug", "Feature Request", "Question")
    var selectedIssueType by remember { mutableStateOf(issueTypes[0]) }
    var expandedTypeDropdown by remember { mutableStateOf(false) }

    var isSubmitting by remember { mutableStateOf(false) }
    var submitSuccess by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    
    val hasGithubPat = BuildConfig.GITHUB_PAT.isNotBlank()

    if (submitSuccess) {
        AlertDialog(
            onDismissRequest = { 
                submitSuccess = false
                onBack()
            },
            title = { Text("Success", color = TextLight, fontWeight = FontWeight.Bold) },
            text = { Text("Your issue has been submitted successfully to GitHub! Thank you for your feedback.", color = TextMuted) },
            confirmButton = {
                Button(
                    onClick = { 
                        submitSuccess = false
                        onBack() 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SurfaceDark
        )
    }

    if (submitError != null) {
        AlertDialog(
            onDismissRequest = { submitError = null },
            title = { Text("Error", color = DestructiveRed, fontWeight = FontWeight.Bold) },
            text = { Text(submitError ?: "Unknown error occurred.", color = TextMuted) },
            confirmButton = {
                Button(
                    onClick = { submitError = null },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SurfaceDark
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextLight)
            }
            Text(
                text = "File a Report",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextLight,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                text = if (hasGithubPat) {
                    "Help us improve NoSlop. Your report will be sent directly to our GitHub repository."
                } else {
                    "Help us improve NoSlop. Since no GitHub Token is configured, this will open a pre-filled issue in your browser."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = preferredName,
                onValueChange = { preferredName = it },
                label = { Text("Your Name / Handle (Optional)") },
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

            ExposedDropdownMenuBox(
                expanded = expandedTypeDropdown,
                onExpandedChange = { expandedTypeDropdown = !expandedTypeDropdown },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                OutlinedTextField(
                    value = selectedIssueType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Issue Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTypeDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    )
                )
                ExposedDropdownMenu(
                    expanded = expandedTypeDropdown,
                    onDismissRequest = { expandedTypeDropdown = false },
                    modifier = Modifier.background(SurfaceDark)
                ) {
                    issueTypes.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption, color = TextLight) },
                            onClick = {
                                selectedIssueType = selectionOption
                                expandedTypeDropdown = false
                            }
                        )
                    }
                }
            }

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
                    val issueTitle = "[$selectedIssueType] ${title.takeIf { it.isNotBlank() } ?: "User Report"}"
                    val bodyBuilder = java.lang.StringBuilder()
                    bodyBuilder.append("### Description\n")
                    bodyBuilder.append(description.takeIf { it.isNotBlank() } ?: "No description provided.")
                    bodyBuilder.append("\n\n")

                    if (preferredName.isNotBlank()) {
                        bodyBuilder.append("- **Reported by:** $preferredName\n\n")
                    }

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

                    if (hasGithubPat) {
                        isSubmitting = true
                        coroutineScope.launch {
                            try {
                                val label = when (selectedIssueType) {
                                    "Bug" -> "bug"
                                    "Feature Request" -> "enhancement"
                                    "Question" -> "question"
                                    else -> "bug"
                                }

                                val payload = mapOf(
                                    "title" to issueTitle,
                                    "body" to bodyBuilder.toString(),
                                    "labels" to listOf(label),
                                    "assignees" to listOf("gaborkukucska")
                                )

                                val json = Gson().toJson(payload)
                                val requestBody = json.toRequestBody("application/json".toMediaType())

                                val request = Request.Builder()
                                    .url("https://api.github.com/repos/gaborkukucska/NoSlop/issues")
                                    .header("Authorization", "Bearer ${BuildConfig.GITHUB_PAT}")
                                    .header("Accept", "application/vnd.github.v3+json")
                                    .post(requestBody)
                                    .build()

                                val response = withContext(Dispatchers.IO) {
                                    HttpClientProvider.clearnetClient.newCall(request).execute()
                                }

                                if (response.isSuccessful) {
                                    submitSuccess = true
                                } else {
                                    val errorBody = response.body?.string()
                                    submitError = "Failed to submit issue: HTTP ${response.code}\n$errorBody"
                                    Logger.error("GITHUB", "Submit issue failed: HTTP ${response.code}", errorBody)
                                }
                            } catch (e: Exception) {
                                submitError = "An error occurred while submitting the issue: ${e.message}"
                                Logger.error("GITHUB", "Submit issue exception", e.stackTraceToString())
                            } finally {
                                isSubmitting = false
                            }
                        }
                    } else {
                        val encodedTitle = URLEncoder.encode(issueTitle, "UTF-8")
                        val encodedBody = URLEncoder.encode(bodyBuilder.toString(), "UTF-8")
                        // When using intent, pass the un-interpolated variables correctly to Uri.parse
                        val url = "https://github.com/gaborkukucska/NoSlop/issues/new?title=$encodedTitle&body=$encodedBody"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                enabled = !isSubmitting && (title.isNotBlank() || description.isNotBlank())
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryBlack, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Submitting...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.BugReport, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (hasGithubPat) "Submit via GitHub API" else "Submit via Browser", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
