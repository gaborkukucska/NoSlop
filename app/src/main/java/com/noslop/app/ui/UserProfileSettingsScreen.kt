package com.noslop.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.noslop.app.data.UserProfile
import com.noslop.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileSettingsScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val currentProfile by viewModel.userProfile.collectAsState()
    val currentNegativeKeywords by viewModel.negativeKeywords.collectAsState()
    val currentLanguage by viewModel.languagePreference.collectAsState()
    
    var displayName by remember { mutableStateOf(currentProfile.displayName) }
    var bio by remember { mutableStateOf(currentProfile.bio) }
    var avatarUrl by remember { mutableStateOf(currentProfile.avatarUrl) }
    var negativeKeywords by remember { mutableStateOf(currentNegativeKeywords) }
    var language by remember { mutableStateOf(currentLanguage) }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
        }

        Column(modifier = Modifier.padding(24.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceDark)
                    .align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Face, contentDescription = null, tint = TextMuted, modifier = Modifier.size(50.dp))
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text("Profile", color = AccentGreen, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display Name") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("Bio") },
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = avatarUrl,
                onValueChange = { avatarUrl = it },
                label = { Text("Avatar URL (or local path)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
            Text("Content Preferences", color = AccentGreen, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = negativeKeywords,
                onValueChange = { negativeKeywords = it },
                label = { Text("Negative Keywords (comma separated)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            var expanded by remember { mutableStateOf(false) }
            val languages = listOf(
                "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
                "it" to "Italiano", "pt" to "Português", "ru" to "Русский", "zh" to "中文",
                "ja" to "日本語", "ko" to "한국어", "ar" to "العربية", "hi" to "हिन्दी",
                "nl" to "Nederlands", "tr" to "Türkçe", "pl" to "Polski", "sv" to "Svenska",
                "id" to "Bahasa Indonesia", "vi" to "Tiếng Việt", "th" to "ไทย", "el" to "Ελληνικά"
            )
            
            val selectedLangs = language.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val displayLanguage = if (selectedLangs.isEmpty()) "Any Language" else selectedLangs.mapNotNull { code -> languages.find { it.first == code }?.second }.joinToString(", ")
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = displayLanguage,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Content Languages") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight,
                        unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    languages.forEach { lang ->
                        val isSelected = selectedLangs.contains(lang.first)
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = null,
                                        colors = CheckboxDefaults.colors(checkedColor = AccentGreen, uncheckedColor = TextMuted)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(lang.second, color = if (isSelected) AccentGreen else TextLight)
                                }
                            },
                            onClick = {
                                val newSelected = if (isSelected) {
                                    selectedLangs - lang.first
                                } else {
                                    selectedLangs + lang.first
                                }
                                language = newSelected.joinToString(",")
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        Box(modifier = Modifier.padding(24.dp)) {
            Button(
                onClick = {
                    viewModel.updateUserProfile(
                        UserProfile(displayName = displayName, bio = bio, avatarUrl = avatarUrl)
                    )
                    viewModel.updateContentPreferences(
                        selectedCategories = viewModel.selectedInterests.value,
                        selectedMusicGenres = viewModel.selectedMusicGenres.value,
                        selectedVideoGenres = viewModel.selectedVideoGenres.value,
                        negativeKeywords = negativeKeywords,
                        languagePreference = language
                    )
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Save Settings", fontWeight = FontWeight.Bold)
            }
        }
    }
}
