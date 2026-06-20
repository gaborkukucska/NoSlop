package com.noslop.mvp.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.noslop.mvp.data.PreferencesRepository
import com.noslop.mvp.data.UserProfile
import com.noslop.mvp.feeds.SourceLibrary
import com.noslop.mvp.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContentPreferencesScreen(
    repository: PreferencesRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    var currentProfile by remember { mutableStateOf(UserProfile()) }
    var negativeKeywords by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var creatorKeywords by remember { mutableStateOf("") }
    
    val localInterests = remember { mutableStateListOf<String>() }
    val localMusicGenres = remember { mutableStateListOf<String>() }
    val localVideoGenres = remember { mutableStateListOf<String>() }

    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }

    val allMusicGenres = listOf(
        "Electronic", "Ambient", "Rock", "Lo-Fi", "Classical", "Hip-Hop", "Jazz", "Pop",
        "Metal", "R&B", "Country", "Reggae", "Blues", "Indie", "Soul", "Punk"
    )
    val allVideoGenres = listOf(
        "Education", "Tech", "Gaming", "Science", "Entertainment", "News", "Documentary",
        "Comedy", "Music Videos", "Sports", "Travel", "DIY & How-To", "Animation", "Film & Cinema"
    )

    LaunchedEffect(Unit) {
        val profile = repository.getUserProfile()
        currentProfile = profile
        displayName = profile.displayName
        bio = profile.bio
        
        negativeKeywords = repository.getUserNegativeKeywords().joinToString(", ")
        language = repository.getLanguagePreference()
        creatorKeywords = repository.getCreatorKeywords().joinToString(", ")
        
        localInterests.addAll(repository.getUserSelectedCategories())
        localMusicGenres.addAll(repository.getSelectedMusicGenres())
        localVideoGenres.addAll(repository.getSelectedVideoGenres())
    }

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
            }
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ────────────────── PROFILE ──────────────────
            item {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Face, contentDescription = null, tint = TextMuted, modifier = Modifier.size(40.dp))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                Text("PROFILE", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("Bio") },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // ────────────────── CATEGORIES ──────────────────
            item {
                Text("CATEGORIES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            }

            items(SourceLibrary.selectableCategories) { category ->
                val isSelected = localInterests.contains(category)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        if (isSelected) localInterests.remove(category) else localInterests.add(category)
                    },
                    colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceDark else PrimaryBlack),
                    border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(category, color = TextLight)
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = {
                                if (it) localInterests.add(category) else localInterests.remove(category)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                        )
                    }
                }
            }

            // ────────────────── MUSIC GENRES ──────────────────
            if (localInterests.contains("Music")) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("MUSIC GENRES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(allMusicGenres) { genre ->
                    val isSelected = localMusicGenres.contains(genre)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            if (isSelected) localMusicGenres.remove(genre) else localMusicGenres.add(genre)
                        },
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceDark else PrimaryBlack),
                        border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(genre, color = TextLight)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) localMusicGenres.add(genre) else localMusicGenres.remove(genre)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                            )
                        }
                    }
                }
            }

            // ────────────────── VIDEO GENRES ──────────────────
            run {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("VIDEO GENRES", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(allVideoGenres) { genre ->
                    val isSelected = localVideoGenres.contains(genre)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            if (isSelected) localVideoGenres.remove(genre) else localVideoGenres.add(genre)
                        },
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) SurfaceDark else PrimaryBlack),
                        border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(genre, color = TextLight)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) localVideoGenres.add(genre) else localVideoGenres.remove(genre)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, checkmarkColor = PrimaryBlack)
                            )
                        }
                    }
                }
            }

            // ────────────────── FILTERING ──────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text("FILTERING", style = MaterialTheme.typography.labelMedium, color = AccentGreen, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = negativeKeywords,
                    onValueChange = { negativeKeywords = it },
                    label = { Text("Negative Keywords (comma separated)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ────────────────── CREATOR TEXT FIELD ──────────────────
            item {
                OutlinedTextField(
                    value = creatorKeywords,
                    onValueChange = { creatorKeywords = it },
                    label = { Text("Creators / channels (comma separated)") },
                    placeholder = { Text("e.g. Linus Tech Tips, Veritasium, Krebs...") },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentGreen, unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextLight, unfocusedTextColor = TextLight
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Save button
        Button(
            onClick = {
                scope.launch {
                    repository.saveUserProfile(
                        UserProfile(displayName = displayName, bio = bio, avatarB64 = currentProfile.avatarB64)
                    )
                    repository.saveSelectedCategories(localInterests.toList())
                    repository.saveSelectedMusicGenres(localMusicGenres.toList())
                    repository.saveSelectedVideoGenres(localVideoGenres.toList())
                    repository.saveUserNegativeKeywords(negativeKeywords)
                    repository.saveLanguagePreference(language)
                    repository.saveCreatorKeywords(creatorKeywords)
                    onBack()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
        ) {
            Text("Save Settings", fontWeight = FontWeight.Bold)
        }
    }
}
