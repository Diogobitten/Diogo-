package com.nuvio.tv.ui.screens.home

import android.view.KeyEvent as AndroidKeyEvent
import com.nuvio.tv.LocalContentFocusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import androidx.tv.material3.Border
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.DailyTipSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.NewReleasesSection
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.StreamingServicesRow
import com.nuvio.tv.ui.theme.NuvioColors

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.nuvio.tv.ui.util.rememberDominantColor

/** Minimum interval between processed key repeat events to prevent HWUI overload. */
private const val KEY_REPEAT_THROTTLE_MS = 120L

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GridHomeContent(
    uiState: HomeUiState,
    gridFocusState: HomeScreenFocusState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = {},
    showContinueWatchingManualPlayOption: Boolean = false,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    posterCardStyle: PosterCardStyle = PosterCardDefaults.Style,
    onItemFocus: (com.nuvio.tv.domain.model.MetaPreview) -> Unit = {},
    onNewReleaseClick: (com.nuvio.tv.domain.model.CalendarItem) -> Unit = {},
    onStreamingServiceClick: (String) -> Unit = {},
    onDailyTipClick: (com.nuvio.tv.domain.model.MetaPreview) -> Unit = {},
    onSaveGridFocusState: (Int, Int) -> Unit
) {
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = gridFocusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = gridFocusState.verticalScrollOffset
    )

    // Save scroll state when leaving
    DisposableEffect(Unit) {
        onDispose {
            onSaveGridFocusState(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset
            )
        }
    }

    // Offset for section indices when continue watching is present
    val gridItems = uiState.gridItems
    val continueWatchingItems = uiState.continueWatchingItems
    val continueWatchingOffset = if (continueWatchingItems.isNotEmpty()) 1 else 0

    // Build index-to-section mapping for sticky header
    val sectionMapping = remember(gridItems, continueWatchingOffset) {
        buildSectionMapping(gridItems, continueWatchingOffset)
    }

    val currentSectionName by remember(gridState, sectionMapping) {
        derivedStateOf {
            sectionMapping.findSectionForIndex(gridState.firstVisibleItemIndex)?.catalogName
        }
    }

    // Pre-compute whether hero exists to avoid repeated list scan in derivedStateOf
    val hasHero = remember(gridItems) {
        gridItems.firstOrNull() is GridItem.Hero
    }
    val topPadding = if (hasHero) 0.dp else 24.dp

    // Determine if hero is scrolled past
    val isScrolledPastHero by remember(hasHero) {
        derivedStateOf {
            if (hasHero) {
                gridState.firstVisibleItemIndex > 0
            } else {
                true
            }
        }
    }
    val shouldRequestInitialFocus = remember(gridFocusState.verticalScrollIndex, gridFocusState.verticalScrollOffset) {
        gridFocusState.verticalScrollIndex == 0 && gridFocusState.verticalScrollOffset == 0
    }
    val heroFocusRequester = remember { FocusRequester() }
    val firstGridItemFocusRequester = remember { FocusRequester() }
    val hasContinueWatching = continueWatchingItems.isNotEmpty()
    val hasStandaloneFocusableGridItem = remember(gridItems) {
        gridItems.any { it is GridItem.Content || it is GridItem.SeeAll }
    }

    LaunchedEffect(
        shouldRequestInitialFocus,
        hasHero,
        hasContinueWatching,
        hasStandaloneFocusableGridItem,
            gridItems.size
    ) {
        if (!shouldRequestInitialFocus) return@LaunchedEffect
        if (hasContinueWatching && !hasHero) return@LaunchedEffect
        val targetRequester = when {
            hasHero -> heroFocusRequester
            hasStandaloneFocusableGridItem -> firstGridItemFocusRequester
            else -> null
        } ?: return@LaunchedEffect

        repeat(2) { withFrameNanos { } }
        try {
            targetRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
    }

    // Throttle D-pad key repeats to prevent HWUI overload when a key is held down.
    val lastKeyRepeatTime = remember { longArrayOf(0L) }

    Box(modifier = Modifier.fillMaxSize()) {
        val contentFocusRequester = LocalContentFocusRequester.current
        val context = LocalContext.current

        // Track focused hero item for dominant color extraction
        var focusedHeroBackdrop by remember { mutableStateOf<String?>(null) }

        // Initialize with first hero item backdrop
        LaunchedEffect(gridItems) {
            if (focusedHeroBackdrop == null) {
                val heroItem = gridItems.filterIsInstance<GridItem.Hero>().firstOrNull()
                focusedHeroBackdrop = heroItem?.items?.firstOrNull()?.backdropUrl
            }
        }

        val dominantColor = rememberDominantColor(
            imageUrl = focusedHeroBackdrop,
            context = context
        )
        val animatedDominantColor by animateColorAsState(
            targetValue = dominantColor,
            animationSpec = tween(durationMillis = 800),
            label = "gridDominantColorAnim"
        )

        // Fundo radial completo - simplified gradient for performance (fewer color stops = less GPU work)
        val gradientBrush = remember(animatedDominantColor) {
            Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to animatedDominantColor.copy(alpha = 0.50f),
                    0.20f to animatedDominantColor.copy(alpha = 0.30f),
                    0.45f to animatedDominantColor.copy(alpha = 0.10f),
                    0.70f to animatedDominantColor.copy(alpha = 0.02f),
                    1.00f to Color.Transparent
                ),
                center = Offset(0f, 0f),
                radius = 2400f
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush)
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridWidth = maxWidth
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = posterCardStyle.width),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocusRequester)
                .focusRestorer()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastKeyRepeatTime[0] < KEY_REPEAT_THROTTLE_MS) {
                            return@onPreviewKeyEvent true
                        }
                        lastKeyRepeatTime[0] = now
                    }
                    false
                },
            contentPadding = PaddingValues(
                start = 120.dp,
                end = 24.dp,
                top = topPadding,
                bottom = 32.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var continueWatchingInserted = false
            var newReleasesInserted = false
            var dailyTipsInserted = false
            var streamingServicesInserted = false
            var firstGridFocusableAssigned = false
            val contentOccurrencesByCatalogAndId = mutableMapOf<String, Int>()

            gridItems.forEach { gridItem ->
                when (gridItem) {
                    is GridItem.Hero -> {
                        item(
                            key = "hero",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "hero"
                        ) {
                            // Hero extends full screen width: offset left by grid start padding
                            val heroExtraStart = 120.dp // matches grid contentPadding.start
                            HeroCarousel(
                                items = gridItem.items,
                                focusRequester = if (shouldRequestInitialFocus) heroFocusRequester else null,
                                onItemClick = { item ->
                                    onNavigateToDetail(
                                        item.id,
                                        item.apiType,
                                        ""
                                    )
                                },
                                onItemFocus = { item ->
                                    focusedHeroBackdrop = item.backdropUrl
                                },
                                fullWidth = gridWidth + heroExtraStart + 24.dp,
                                contentStartPadding = heroExtraStart,
                                // Corrigido para não empurrar a largura para fora do ecrã
                                modifier = Modifier.offset(x = -heroExtraStart)
                            )
                        }
                    }

                    is GridItem.SectionDivider -> {
                        // Insert streaming services before continue watching
                        if (!streamingServicesInserted && uiState.streamingServiceNames.isNotEmpty()) {
                            streamingServicesInserted = true
                            item(
                                key = "streaming_services",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "streaming_services"
                            ) {
                                StreamingServicesRow(
                                    serviceNames = uiState.streamingServiceNames,
                                    onServiceClick = onStreamingServiceClick
                                )
                            }
                        }

                        // Insert new releases
                        if (!newReleasesInserted && uiState.newReleases.isNotEmpty()) {
                            newReleasesInserted = true
                            item(
                                key = "new_releases",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "new_releases"
                            ) {
                                NewReleasesSection(
                                    items = uiState.newReleases,
                                    onItemClick = onNewReleaseClick
                                )
                            }
                        }

                        // Insert daily tips
                        if (!dailyTipsInserted && uiState.dailyTips.isNotEmpty()) {
                            dailyTipsInserted = true
                            item(
                                key = "daily_tips",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "daily_tips"
                            ) {
                                DailyTipSection(
                                    items = uiState.dailyTips,
                                    onItemClick = onDailyTipClick
                                )
                            }
                        }

                        // Insert continue watching after streaming services and new releases
                        if (!continueWatchingInserted && continueWatchingItems.isNotEmpty()) {
                            continueWatchingInserted = true
                            item(
                                key = "continue_watching",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "continue_watching"
                            ) {
                                GridContinueWatchingSection(
                                    modifier = Modifier.fillMaxWidth(),
                                    fullWidth = gridWidth,
                                    items = continueWatchingItems,
                                    focusedItemIndex = if (shouldRequestInitialFocus && !hasHero) 0 else -1,
                                    onItemClick = { item ->
                                        onContinueWatchingClick(item)
                                    },
                                    onStartFromBeginning = onContinueWatchingStartFromBeginning,
                                    showManualPlayOption = showContinueWatchingManualPlayOption,
                                    onPlayManually = onContinueWatchingPlayManually,
                                    onDetailsClick = { item ->
                                        onNavigateToDetail(
                                            when (item) {
                                                is ContinueWatchingItem.InProgress -> item.progress.contentId
                                                is ContinueWatchingItem.NextUp -> item.info.contentId
                                            },
                                            when (item) {
                                                is ContinueWatchingItem.InProgress -> item.progress.contentType
                                                is ContinueWatchingItem.NextUp -> item.info.contentType
                                            },
                                            ""
                                        )
                                    },
                                    onRemoveItem = { item ->
                                        val contentId = when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.contentId
                                            is ContinueWatchingItem.NextUp -> item.info.contentId
                                        }
                                        val season = when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.season
                                            is ContinueWatchingItem.NextUp -> item.info.season
                                        }
                                        val episode = when (item) {
                                            is ContinueWatchingItem.InProgress -> item.progress.episode
                                            is ContinueWatchingItem.NextUp -> item.info.episode
                                        }
                                        val isNextUp = item is ContinueWatchingItem.NextUp
                                        onRemoveContinueWatching(contentId, season, episode, isNextUp)
                                    }
                                )
                            }
                        }

                        item(
                            key = "divider_${gridItem.catalogId}_${gridItem.addonId}_${gridItem.type}",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "divider"
                        ) {
                            val strTypeMovie = stringResource(R.string.type_movie)
                            val strTypeSeries = stringResource(R.string.type_series)
                            val displayName = remember(gridItem.catalogName, gridItem.type, uiState.catalogTypeSuffixEnabled, strTypeMovie, strTypeSeries) {
                                val typeLabel = when (gridItem.type.lowercase()) {
                                    "movie" -> strTypeMovie
                                    "series" -> strTypeSeries
                                    else -> gridItem.type.replaceFirstChar { it.uppercase() }
                                }
                                if (uiState.catalogTypeSuffixEnabled && typeLabel.isNotBlank()) {
                                    "${gridItem.catalogName.replaceFirstChar { it.uppercase() }} - $typeLabel"
                                } else {
                                    gridItem.catalogName.replaceFirstChar { it.uppercase() }
                                }
                            }
                            SectionDivider(
                                catalogName = displayName
                            )
                        }
                    }

                    is GridItem.Content -> {
                        val focusRequester = if (
                            shouldRequestInitialFocus &&
                            !hasHero &&
                            !hasContinueWatching &&
                            !firstGridFocusableAssigned
                        ) {
                            firstGridFocusableAssigned = true
                            firstGridItemFocusRequester
                        } else {
                            null
                        }
                        val occurrenceBaseKey = "${gridItem.catalogId}|${gridItem.item.id}"
                        val occurrence = contentOccurrencesByCatalogAndId.getOrDefault(occurrenceBaseKey, 0)
                        contentOccurrencesByCatalogAndId[occurrenceBaseKey] = occurrence + 1
                        item(
                            key = "content_${gridItem.catalogId}_${gridItem.item.id}_$occurrence",
                            span = { GridItemSpan(1) },
                            contentType = "content"
                        ) {
                            GridContentCard(
                                item = gridItem.item,
                                focusRequester = focusRequester,
                                posterCardStyle = posterCardStyle,
                                showLabel = uiState.posterLabelsEnabled,
                                isWatched = isCatalogItemWatched(gridItem.item),
                                onFocused = { onItemFocus(gridItem.item) },
                                onClick = {
                                    onNavigateToDetail(
                                        gridItem.item.id,
                                        gridItem.item.apiType,
                                        gridItem.addonBaseUrl
                                    )
                                },
                                onLongPress = {
                                    onCatalogItemLongPress(gridItem.item, gridItem.addonBaseUrl)
                                }
                            )
                        }
                    }

                    is GridItem.SeeAll -> {
                        val focusRequester = if (
                            shouldRequestInitialFocus &&
                            !hasHero &&
                            !hasContinueWatching &&
                            !firstGridFocusableAssigned
                        ) {
                            firstGridFocusableAssigned = true
                            firstGridItemFocusRequester
                        } else {
                            null
                        }
                        item(
                            key = "see_all_${gridItem.catalogId}_${gridItem.addonId}_${gridItem.type}",
                            span = { GridItemSpan(1) },
                            contentType = "see_all"
                        ) {
                            SeeAllGridCard(
                                posterCardStyle = posterCardStyle,
                                focusRequester = focusRequester,
                                onClick = {
                                    onNavigateToCatalogSeeAll(
                                        gridItem.catalogId,
                                        gridItem.addonId,
                                        gridItem.type
                                    )
                                }
                            )
                        }
                    }
                }
            }

            if (!continueWatchingInserted && continueWatchingItems.isNotEmpty()) {
                item(
                    key = "continue_watching_fallback",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "continue_watching"
                ) {
                    GridContinueWatchingSection(
                        modifier = Modifier.fillMaxWidth(),
                        fullWidth = gridWidth,
                        items = continueWatchingItems,
                        focusedItemIndex = if (shouldRequestInitialFocus && !hasHero) 0 else -1,
                        onItemClick = { item ->
                            onContinueWatchingClick(item)
                        },
                        onStartFromBeginning = onContinueWatchingStartFromBeginning,
                        showManualPlayOption = showContinueWatchingManualPlayOption,
                        onPlayManually = onContinueWatchingPlayManually,
                        onDetailsClick = { item ->
                            onNavigateToDetail(
                                when (item) {
                                    is ContinueWatchingItem.InProgress -> item.progress.contentId
                                    is ContinueWatchingItem.NextUp -> item.info.contentId
                                },
                                when (item) {
                                    is ContinueWatchingItem.InProgress -> item.progress.contentType
                                    is ContinueWatchingItem.NextUp -> item.info.contentType
                                },
                                ""
                            )
                        },
                        onRemoveItem = { item ->
                            val contentId = when (item) {
                                    is ContinueWatchingItem.InProgress -> item.progress.contentId
                                    is ContinueWatchingItem.NextUp -> item.info.contentId
                            }
                            val season = when (item) {
                                    is ContinueWatchingItem.InProgress -> item.progress.season
                                    is ContinueWatchingItem.NextUp -> item.info.season
                            }
                            val episode = when (item) {
                                    is ContinueWatchingItem.InProgress -> item.progress.episode
                                    is ContinueWatchingItem.NextUp -> item.info.episode
                            }
                            val isNextUp = item is ContinueWatchingItem.NextUp
                            onRemoveContinueWatching(contentId, season, episode, isNextUp)
                        }
                    )
                }
            }
        } // end LazyVerticalGrid
        } // end BoxWithConstraints

        // Sticky header overlay
        AnimatedVisibility(
            visible = isScrolledPastHero && currentSectionName != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            StickyCategoryHeader(
                sectionName = currentSectionName ?: ""
            )
        }
    }
}

@Composable
private fun SectionDivider(
    catalogName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 12.dp)
    ) {
        Text(
            text = catalogName,
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioColors.TextPrimary
        )
    }
}

@Composable
private fun StickyCategoryHeader(
    sectionName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 12.dp)
    ) {
        Text(
            text = sectionName,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioColors.TextPrimary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeeAllGridCard(
    onClick: () -> Unit,
    posterCardStyle: PosterCardStyle,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val seeAllCardShape = RoundedCornerShape(posterCardStyle.cornerRadius)
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(posterCardStyle.aspectRatio)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = CardDefaults.shape(
            shape = seeAllCardShape
        ),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(posterCardStyle.focusedBorderWidth, NuvioColors.FocusRing),
                shape = seeAllCardShape
            )
        ),
        scale = CardDefaults.scale(
            focusedScale = posterCardStyle.focusedScale
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.action_see_all),
                    modifier = Modifier.size(32.dp),
                    tint = NuvioColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.action_see_all),
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Section mapping utilities

private data class SectionInfo(
    val catalogName: String,
    val catalogId: String,
    val addonId: String,
    val type: String
)

private class SectionMapping(
    private val indexToSection: Map<Int, SectionInfo>
) {
    private val sortedSectionStarts = indexToSection.keys.sorted()

    fun findSectionForIndex(index: Int): SectionInfo? {
        if (sortedSectionStarts.isEmpty()) return null
        val insertionPoint = sortedSectionStarts.binarySearch(index)
        val targetIdx = if (insertionPoint >= 0) insertionPoint else (-insertionPoint - 2)
        if (targetIdx < 0) return null
        return indexToSection[sortedSectionStarts[targetIdx]]
    }
}

private fun buildSectionMapping(gridItems: List<GridItem>, indexOffset: Int = 0): SectionMapping {
    val mapping = mutableMapOf<Int, SectionInfo>()
    gridItems.forEachIndexed { index, item ->
        if (item is GridItem.SectionDivider) {
            mapping[index + indexOffset] = SectionInfo(
                catalogName = item.catalogName,
                catalogId = item.catalogId,
                addonId = item.addonId,
                type = item.type
            )
        }
    }
    return SectionMapping(mapping)
}