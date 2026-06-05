package com.noslop.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noslop.app.feeds.SourceLibrary
import com.noslop.app.ui.theme.*

@Composable
fun ContentPreferencesScreen(viewModel: NoSlopViewModel, onBack: () -> Unit) {
    val interests by viewModel.selectedInterests.collectAsState()
    val musicGenres by viewModel.selectedMusicGenres.collectAsState()
    val videoGenres by viewModel.selectedVideoGenres.collectAsState()

    val localInterests = remember { mutableStateListOf<String>().apply { addAll(interests) } }
    val localMusicGenres = remember { mutableStateListOf<String>().apply { addAll(musicGenres) } }
    val localVideoGenres = remember { mutableStateListOf<String>().apply { addAll(videoGenres) } }

    val allMusicGenres = listOf("Electronic", "Ambient", "Rock", "Lo-Fi", "Classical", "Hip-Hop", "Jazz", "Pop")
    val allVideoGenres = listOf("Education", "Tech", "Gaming", "Science", "Entertainment", "News", "Documentary")

    Column(modifier = Modifier.fillMaxSize().background(PrimaryBlack)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentGreen)
            }
            Text(
                text = "Content Preferences",
                style = MaterialTheme.typography.titleLarge,
                color = TextLight,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = "CATEGORIES",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(SourceLibrary.categories) { category ->
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

            if (localInterests.contains("Music")) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "MUSIC GENRES",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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

            if (localInterests.contains("Video Platforms")) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "VIDEO GENRES",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentGreen,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
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
        }

        Button(
            onClick = {
                viewModel.updateContentPreferences(
                    localInterests.toList(),
                    localMusicGenres.toList(),
                    localVideoGenres.toList()
                )
                onBack()
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = PrimaryBlack),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp)
        ) {
            Text("Save Preferences", fontWeight = FontWeight.Bold)
        }
    }
}
