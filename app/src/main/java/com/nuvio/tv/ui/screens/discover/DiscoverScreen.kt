@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.discover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.DiscoverCategory
import com.nuvio.tv.data.repository.TrailerItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.theme.NuvioColors

private const val CONTENT_START_PADDING = 120

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onNavigateToSeeAll: (categoryKey: String, categoryTitle: String) -> Unit = { _, _ -> },
    onPlayTrailer: (streamUrl: String, title: String, audioUrl: String?) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Collect nav events for trailer playback
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is DiscoverNavEvent.PlayTrailer -> onPlayTrailer(event.streamUrl, event.title, event.audioUrl)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        if (uiState.isLoading && uiState.trailers.isEmpty()) {
            LoadingIndicator()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp)
            ) {
                if (showBuiltInHeader) {
                    item(key = "header") {
                        Text(
                            text = stringResource(R.string.discover_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = NuvioColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(start = CONTENT_START_PADDING.dp, top = 24.dp, bottom = 16.dp)
                        )
                    }
                }

                // Trailers section
                if (uiState.trailers.isNotEmpty()) {
                    item(key = "trailers_title") {
                        Text(
                            text = stringResource(R.string.discover_latest_trailers),
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioColors.TextPrimary,
                            modifier = Modifier.padding(start = CONTENT_START_PADDING.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                    item(key = "trailers_row") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(start = CONTENT_START_PADDING.dp, end = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(uiState.trailers, key = { "${it.id}_${it.youtubeKey}" }) { trailer ->
                                TrailerCard(
                                    trailer = trailer,
                                    onClick = {
                                        viewModel.onTrailerClick(trailer)
                                    }
                                )
                            }
                        }
                    }
                }

                // Category rows with See All
                uiState.categories.forEach { category ->
                    item(key = "cat_title_${category.key}") {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = category.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = NuvioColors.TextPrimary,
                            modifier = Modifier.padding(start = CONTENT_START_PADDING.dp, top = 0.dp, bottom = 8.dp)
                        )
                    }
                    item(key = "cat_row_${category.key}") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(start = CONTENT_START_PADDING.dp, end = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(
                                items = category.items,
                                key = { index, item -> "${category.key}_${item.id}_$index" }
                            ) { _, item ->
                                GridContentCard(
                                    item = item,
                                    onClick = {
                                        val type = if (item.type == ContentType.SERIES) "series" else "movie"
                                        onNavigateToDetail(item.id, type, null)
                                    }
                                )
                            }
                            // See All card at the end
                            item(key = "see_all_${category.key}") {
                                SeeAllCard(
                                    onClick = { onNavigateToSeeAll(category.key, category.title) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    val posterCardStyle = PosterCardDefaults.Style
    val cardShape = remember { RoundedCornerShape(posterCardStyle.cornerRadius) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(posterCardStyle.width)
            .height(posterCardStyle.height),
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = posterCardStyle.focusedScale)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.discover_see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun TrailerCard(
    trailer: TrailerItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardWidth = 320.dp
    val cardHeight = 180.dp
    val cardShape = remember { RoundedCornerShape(12.dp) }
    val widthPx = remember(density) { with(density) { cardWidth.roundToPx() } }
    val heightPx = remember(density) { with(density) { cardHeight.roundToPx() } }

    Card(
        onClick = onClick,
        modifier = Modifier.width(cardWidth).height(cardHeight),
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, NuvioColors.FocusRing), shape = cardShape)
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f)
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(cardShape)) {
            val imageUrl = trailer.backdropUrl ?: trailer.posterUrl
            if (imageUrl != null) {
                val imageModel = remember(imageUrl, widthPx, heightPx) {
                    ImageRequest.Builder(context)
                        .data(imageUrl).crossfade(false).allowHardware(true)
                        .size(width = widthPx, height = heightPx)
                        .memoryCacheKey("${imageUrl}_${widthPx}x${heightPx}")
                        .diskCacheKey("${imageUrl}_${widthPx}x${heightPx}")
                        .build()
                }
                AsyncImage(
                    model = imageModel, contentDescription = trailer.title,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            )
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(text = trailer.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val typeLabel = if (trailer.type == ContentType.SERIES) "Série" else "Filme"
                val yearLabel = trailer.releaseInfo?.let { " · $it" } ?: ""
                Text(text = "$typeLabel$yearLabel", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
            }
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(text = "▶ Trailer", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}
