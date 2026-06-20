package com.noslop.mvp.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noslop.mvp.ui.theme.*

/**
 * Article reader with horizontal pagination.
 * Each segment fits within the viewport, and sideways swiping navigates between pages.
 */
@Composable
fun SegmentedArticleReader(
    content: String,
    title: String,
    author: String?,
    sourceLabel: String,
    thumbnailUrl: String?,
    articleUrl: String?,
    modifier: Modifier = Modifier,
    onOpenArticle: ((String) -> Unit)? = null,
    imageContent: @Composable (url: String, contentScale: ContentScale, modifier: Modifier) -> Unit = { _, _, _ -> }
) {
    val rawContent = content.ifBlank { "" }
    val segments: List<String> = remember(rawContent) { splitIntoSegments(rawContent, 800) }
    val effectiveSegments = if (segments.isEmpty()) listOf("") else segments
    val pagerState = rememberPagerState(pageCount = { effectiveSegments.size + 1 })
    val fallbackImage = "https://images.unsplash.com/photo-1450101499163-c8848c66ca85?q=80&w=2070&auto=format&fit=crop"

    Column(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            if (page == 0) {
                // Page 0: Hero Layout (Magazine Style)
                Box(modifier = Modifier.fillMaxSize()) {
                    imageContent(
                        thumbnailUrl ?: fallbackImage,
                        ContentScale.Crop,
                        Modifier.fillMaxSize()
                    )

                    // Gradient Scrim
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent),
                                    startY = Float.POSITIVE_INFINITY,
                                    endY = 0f
                                )
                            )
                    )

                    // Title Overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 32.dp, vertical = 64.dp)
                    ) {
                        Surface(
                            color = AccentGreen,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = sourceLabel.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                lineHeight = 38.sp
                            ),
                            color = Color.White
                        )
                        if (author != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "By $author",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted
                            )
                        }
                    }
                }
            } else {
                // Pages 1 to N: Text Content
                val segmentIndex = page - 1
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (effectiveSegments[segmentIndex].isNotBlank()) {
                            Text(
                                text = effectiveSegments[segmentIndex],
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    lineHeight = 30.sp,
                                    letterSpacing = 0.4.sp,
                                    fontSize = 17.sp
                                ),
                                color = TextLight,
                                textAlign = TextAlign.Start,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "Preview not available.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // "Read Full Article" Button on the last segment
                        if (segmentIndex == effectiveSegments.size - 1 && articleUrl != null && onOpenArticle != null) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { onOpenArticle(articleUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Read Full Article", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Page Indicator
        if (effectiveSegments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(effectiveSegments.size + 1) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) AccentGreen else TextMuted.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

private fun splitIntoSegments(text: String, chunkSize: Int): List<String> {
    if (text.isBlank()) return emptyList()
    if (text.length <= chunkSize) return listOf(text)

    val pages = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + chunkSize).coerceAtMost(text.length)
        if (end < text.length) {
            val searchRange = text.substring(start, end)
            var breakPoint = searchRange.lastIndexOf("\n\n")
            if (breakPoint < chunkSize / 2) {
                breakPoint = searchRange.lastIndexOf(". ")
            }
            if (breakPoint < chunkSize / 2) {
                breakPoint = searchRange.lastIndexOf(" ")
            }
            if (breakPoint > 0) {
                end = start + breakPoint + 1
            }
        }
        val segment = text.substring(start, end).trim()
        if (segment.isNotEmpty()) {
            pages.add(segment)
        }
        start = end
    }
    return if (pages.isEmpty()) listOf(text) else pages
}
