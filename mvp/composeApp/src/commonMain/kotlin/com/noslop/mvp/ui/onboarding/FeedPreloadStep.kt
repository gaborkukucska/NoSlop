package com.noslop.mvp.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noslop.mvp.feeds.BuiltInSource
import com.noslop.mvp.feeds.SourceLibrary
import com.noslop.mvp.ui.theme.*

@Composable
fun FeedPreloadStep(
    interests: List<String>,
    selectedSources: List<BuiltInSource>,
    onToggleSource: (BuiltInSource) -> Unit
) {
    val effectiveInterests = remember(interests) {
        (interests + SourceLibrary.alwaysIncludedCategories).distinct()
    }
    
    val suggestedSources = remember(effectiveInterests) {
        SourceLibrary.sources.filter { effectiveInterests.contains(it.category) }
    }

    var hasPreselected by remember { mutableStateOf(false) }

    LaunchedEffect(suggestedSources) {
        if (!hasPreselected && suggestedSources.isNotEmpty()) {
            suggestedSources.forEach { src ->
                if (!selectedSources.contains(src)) {
                    onToggleSource(src)
                }
            }
            hasPreselected = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Suggested Clearnet Feeds",
            style = MaterialTheme.typography.titleLarge,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Based on your interests, we recommend these sources.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp)
        ) {
            items(suggestedSources) { src: BuiltInSource ->
                val isSelected = selectedSources.contains(src)
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSource(src) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) SurfaceDark else PrimaryBlack
                    ),
                    border = BorderStroke(1.dp, if (isSelected) AccentGreen else BorderSubtle),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = src.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextLight,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = src.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentGreen
                            )
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleSource(src) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AccentGreen,
                                checkmarkColor = PrimaryBlack,
                                uncheckedColor = TextMuted
                            )
                        )
                    }
                }
            }
        }
    }
}
