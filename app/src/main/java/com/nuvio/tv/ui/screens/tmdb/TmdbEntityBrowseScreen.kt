@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.tmdb

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nuvio.tv.core.tmdb.TmdbEntityBrowseData
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun TmdbEntityBrowseScreen(
    viewModel: TmdbEntityBrowseViewModel = hiltViewModel(),
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
            is TmdbEntityBrowseUiState.Loading -> {
                LoadingIndicator()
            }
            is TmdbEntityBrowseUiState.Error -> {
                ErrorState(
                    message = state.message,
                    onRetry = { viewModel.retry() }
                )
            }
            is TmdbEntityBrowseUiState.Success -> {
                EntityBrowseContent(
                    data = state.data,
                    entityName = viewModel.entityName,
                    onNavigateToDetail = onNavigateToDetail,
                    onLoadMore = { rail -> viewModel.loadMoreRail(rail) }
                )
            }
        }
    }
}

@Composable
private fun EntityBrowseContent(
    data: TmdbEntityBrowseData,
    entityName: String,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onLoadMore: (TmdbEntityRail) -> Unit
) {
    val header = data.header

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        // Header with company/network logo and name
        item(key = "header") {
            EntityHeader(
                name = header?.name ?: entityName,
                description = header?.description,
                logoUrl = header?.logoUrl
            )
        }

        // Rails
        data.rails.forEach { rail ->
            item(key = "${rail.mediaType.name}_${rail.railType.name}") {
                EntityRailRow(
                    rail = rail,
                    onNavigateToDetail = onNavigateToDetail,
                    onLoadMore = { onLoadMore(rail) }
                )
            }
        }
    }
}

@Composable
private fun EntityHeader(
    name: String,
    description: String?,
    logoUrl: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (logoUrl != null) {
                val context = LocalContext.current
                val density = LocalDensity.current
                val logoWidthPx = remember(density) { with(density) { 120.dp.roundToPx() } }
                val logoHeightPx = remember(density) { with(density) { 48.dp.roundToPx() } }
                val logoModel = remember(context, logoUrl, logoWidthPx, logoHeightPx) {
                    ImageRequest.Builder(context)
                        .data(logoUrl)
                        .crossfade(false)
                        .allowHardware(true)
                        .size(width = logoWidthPx, height = logoHeightPx)
                        .memoryCacheKey("entity_logo_${logoUrl}_${logoWidthPx}x${logoHeightPx}")
                        .diskCacheKey("entity_logo_${logoUrl}_${logoWidthPx}x${logoHeightPx}")
                        .build()
                }
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EntityRailRow(
    rail: TmdbEntityRail,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit,
    onLoadMore: () -> Unit
) {
    val title = railTitle(rail.mediaType, rail.railType)
    val listState = rememberLazyListState()

    // Trigger load more when near end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            rail.hasMore && lastVisible >= totalItems - 5
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp)
        )

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = rail.items,
                key = { index, item -> "${rail.mediaType.name}_${rail.railType.name}_${item.id}_$index" }
            ) { _, item ->
                GridContentCard(
                    item = item,
                    onClick = {
                        val id = if (item.imdbId != null) {
                            item.imdbId
                        } else {
                            item.id.removePrefix("tmdb:")
                        }
                        val type = when (item.type) {
                            ContentType.SERIES -> "series"
                            else -> "movie"
                        }
                        onNavigateToDetail(id, type, null)
                    }
                )
            }
        }
    }
}

@Composable
private fun railTitle(mediaType: TmdbEntityMediaType, railType: TmdbEntityRailType): String {
    return when (railType) {
        TmdbEntityRailType.POPULAR -> when (mediaType) {
            TmdbEntityMediaType.MOVIE -> stringResource(R.string.tmdb_entity_rail_popular_movies)
            TmdbEntityMediaType.TV -> stringResource(R.string.tmdb_entity_rail_popular_tv)
        }
        TmdbEntityRailType.TOP_RATED -> when (mediaType) {
            TmdbEntityMediaType.MOVIE -> stringResource(R.string.tmdb_entity_rail_top_rated_movies)
            TmdbEntityMediaType.TV -> stringResource(R.string.tmdb_entity_rail_top_rated_tv)
        }
        TmdbEntityRailType.RECENT -> when (mediaType) {
            TmdbEntityMediaType.MOVIE -> stringResource(R.string.tmdb_entity_rail_recent_movies)
            TmdbEntityMediaType.TV -> stringResource(R.string.tmdb_entity_rail_recent_tv)
        }
    }
}
