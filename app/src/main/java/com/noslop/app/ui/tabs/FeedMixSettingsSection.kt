package com.noslop.app.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.app.data.FeedMixSettings
import com.noslop.app.ui.NoSlopViewModel
import com.noslop.app.ui.theme.*
import com.noslop.app.util.tr
import kotlinx.coroutines.launch

@Composable
fun FeedMixSettingsSection(viewModel: NoSlopViewModel) {
    val mixSettings by viewModel.feedMixSettingsFlow.collectAsState()
    val scope = rememberCoroutineScope()

    Text(
        text = "MAIN CONTENT FEED".tr,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = TextMuted,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Content Ratio Mix".tr, fontWeight = FontWeight.Bold, color = TextLight, modifier = Modifier.padding(bottom = 8.dp))
            Text(
                "Adjust what types of content appear in your Live Feed. The sliders dynamically balance so they always total 100%.".tr,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Function to handle the auto-balancing of sliders
            fun onValueChange(index: Int, newValue: Float) {
                val values = floatArrayOf(
                    if (mixSettings.videoEnabled) mixSettings.videoPercent.toFloat() else 0f,
                    if (mixSettings.audioEnabled) mixSettings.audioPercent.toFloat() else 0f,
                    if (mixSettings.imageEnabled) mixSettings.imagePercent.toFloat() else 0f,
                    if (mixSettings.articleEnabled) mixSettings.articlePercent.toFloat() else 0f,
                    if (mixSettings.meshEnabled) mixSettings.meshPercent.toFloat() else 0f
                )
                
                val oldVal = values[index]
                if (oldVal == newValue) return
                
                values[index] = newValue
                
                val diff = newValue - oldVal
                val others = values.indices.filter { it != index && values[it] > 0 }
                
                if (others.isNotEmpty()) {
                    var remainingToDistribute = diff
                    // Try to subtract equally
                    while (remainingToDistribute > 0.5f || remainingToDistribute < -0.5f) {
                        val activeOthers = others.filter { values[it] > 0 || remainingToDistribute < 0 }
                        if (activeOthers.isEmpty()) break
                        val step = remainingToDistribute / activeOthers.size
                        var distributedThisPass = 0f
                        for (i in activeOthers) {
                            val pre = values[i]
                            values[i] = (values[i] - step).coerceIn(0f, 100f)
                            distributedThisPass += (pre - values[i])
                        }
                        if (Math.abs(distributedThisPass) < 0.1f) break // prevents infinite loops if constrained
                        remainingToDistribute -= distributedThisPass
                    }
                }
                
                // Normalize to exactly 100
                val total = values.sum()
                if (total > 0) {
                    for (i in values.indices) {
                        values[i] = (values[i] / total) * 100f
                    }
                }
                
                val newSettings = mixSettings.copy(
                    videoPercent = if (mixSettings.videoEnabled) Math.round(values[0]) else 20,
                    audioPercent = if (mixSettings.audioEnabled) Math.round(values[1]) else 20,
                    imagePercent = if (mixSettings.imageEnabled) Math.round(values[2]) else 20,
                    articlePercent = if (mixSettings.articleEnabled) Math.round(values[3]) else 20,
                    meshPercent = if (mixSettings.meshEnabled) Math.round(values[4]) else 20
                )
                scope.launch { viewModel.updateFeedMixSettings(newSettings) }
            }

            fun toggleItem(index: Int, enabled: Boolean) {
                var newSettings = mixSettings
                when (index) {
                    0 -> newSettings = mixSettings.copy(videoEnabled = enabled)
                    1 -> newSettings = mixSettings.copy(audioEnabled = enabled)
                    2 -> newSettings = mixSettings.copy(imageEnabled = enabled)
                    3 -> newSettings = mixSettings.copy(articleEnabled = enabled)
                    4 -> newSettings = mixSettings.copy(meshEnabled = enabled)
                }
                scope.launch { viewModel.updateFeedMixSettings(newSettings) }
                // Re-balance remaining active sliders
                onValueChange(index, if (enabled) 20f else 0f)
            }

            @Composable
            fun MixRow(title: String, index: Int, enabled: Boolean, value: Int) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = enabled,
                                onCheckedChange = { toggleItem(index, it) },
                                colors = CheckboxDefaults.colors(checkedColor = AccentGreen, uncheckedColor = TextMuted)
                            )
                            Text(title.tr, color = if (enabled) TextLight else TextMuted, fontSize = 14.sp)
                        }
                        if (enabled) {
                            Text("${value}%", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (enabled) {
                        Slider(
                            value = value.toFloat(),
                            onValueChange = { onValueChange(index, it) },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentGreen,
                                activeTrackColor = AccentGreen,
                                inactiveTrackColor = SurfaceDark
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp).height(24.dp)
                        )
                    }
                }
            }

            MixRow("Clearnet Videos", 0, mixSettings.videoEnabled, mixSettings.videoPercent)
            MixRow("Clearnet Audio", 1, mixSettings.audioEnabled, mixSettings.audioPercent)
            MixRow("Clearnet Images", 2, mixSettings.imageEnabled, mixSettings.imagePercent)
            MixRow("Clearnet Articles", 3, mixSettings.articleEnabled, mixSettings.articlePercent)
            HorizontalDivider(color = BorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
            MixRow("Mesh Posts (Peers)", 4, mixSettings.meshEnabled, mixSettings.meshPercent)
        }
    }
}
