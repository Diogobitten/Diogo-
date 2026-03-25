@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.collection

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.tmdb.CollectionDetail
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.ThemeSongPlayer
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun CollectionDetailScreen(
    viewModel: CollectionDetailViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        when (val state = uiState) {
            is CollectionDetailUiState.Loading -> LoadingIndicator()
            is CollectionDetailUiState.Error -> ErrorState(
                message = state.message,
                onRetry = { viewModel.retry() }
            )
            is CollectionDetailUiState.Success -> {
                CollectionContent(
                    detail = state.detail,
                    onNavigateToDetail = onNavigateToDetail
                )
                // Theme song audio player (invisible, plays in background)
                ThemeSongPlayer(
                    audioUrl = state.themeSongAudioUrl,
                    shouldPause = false
                )
            }
        }
    }
}

@Composable
private fun CollectionContent(
    detail: CollectionDetail,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop — darker gradient for readability
        if (detail.backdropUrl != null) {
            val backdropWidthPx = remember(density) { with(density) { 1920.dp.roundToPx() } }
            val backdropHeightPx = remember(density) { with(density) { 1080.dp.roundToPx() } }
            val backdropModel = remember(context, detail.backdropUrl, backdropWidthPx, backdropHeightPx) {
                ImageRequest.Builder(context)
                    .data(detail.backdropUrl)
                    .crossfade(false)
                    .allowHardware(true)
                    .size(width = backdropWidthPx, height = backdropHeightPx)
                    .memoryCacheKey("collection_backdrop_${detail.id}_${backdropWidthPx}x${backdropHeightPx}")
                    .diskCacheKey("collection_backdrop_${detail.id}_${backdropWidthPx}x${backdropHeightPx}")
                    .build()
            }
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                contentScale = ContentScale.Crop
            )
            // Darker gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to NuvioColors.Background.copy(alpha = 0.2f),
                                0.3f to NuvioColors.Background.copy(alpha = 0.5f),
                                0.6f to NuvioColors.Background.copy(alpha = 0.85f),
                                1.0f to NuvioColors.Background
                            )
                        )
                    )
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            // Header area
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (detail.backdropUrl != null) 260.dp else 48.dp)
                        .padding(horizontal = 48.dp)
                ) {
                    // Logo of the first movie (or fallback to text title)
                    if (detail.logoUrl != null) {
                        val logoWidthPx = remember(density) { with(density) { 360.dp.roundToPx() } }
                        val logoHeightPx = remember(density) { with(density) { 120.dp.roundToPx() } }
                        val logoModel = remember(context, detail.logoUrl, logoWidthPx, logoHeightPx) {
                            ImageRequest.Builder(context)
                                .data(detail.logoUrl)
                                .crossfade(false)
                                .allowHardware(true)
                                .size(width = logoWidthPx, height = logoHeightPx)
                                .memoryCacheKey("collection_logo_${detail.id}_${logoWidthPx}x${logoHeightPx}")
                                .diskCacheKey("collection_logo_${detail.id}_${logoWidthPx}x${logoHeightPx}")
                                .build()
                        }
                        AsyncImage(
                            model = logoModel,
                            contentDescription = detail.name,
                            modifier = Modifier
                                .width(360.dp)
                                .height(120.dp),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else {
                        Text(
                            text = detail.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = NuvioColors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!detail.overview.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = detail.overview,
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${detail.parts.size} ${stringResource(R.string.library_type_items)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioColors.TextTertiary
                    )
                }
            }

            // Movies row
            item(key = "movies") {
                Spacer(modifier = Modifier.height(24.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = detail.parts,
                        key = { index, item -> "${item.id}_$index" }
                    ) { _, item ->
                        GridContentCard(
                            item = item,
                            onClick = {
                                val type = when (item.type) {
                                    ContentType.SERIES -> "series"
                                    else -> "movie"
                                }
                                onNavigateToDetail(item.id, type, null)
                            }
                        )
                    }
                }
            }
        }
    }
}
