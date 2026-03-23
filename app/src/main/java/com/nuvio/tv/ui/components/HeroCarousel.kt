package com.nuvio.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_INTERVAL_MS = 10000L
private val YEAR_REGEX = Regex("""\b\d{4}\b""")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HeroCarousel(
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {},
    focusRequester: FocusRequester? = null,
    fullWidth: Dp = Dp.Unspecified,
    contentStartPadding: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var activeIndex by remember { mutableIntStateOf(0) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(activeIndex, isFocused) {
        items.getOrNull(activeIndex)?.let { onItemFocus(it) }
    }

    // Auto-advance when not focused — delay first advance to 20s so initial GPU load settles
    LaunchedEffect(isFocused, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        delay(AUTO_ADVANCE_INTERVAL_MS * 2) // 20s before first advance
        while (true) {
            delay(AUTO_ADVANCE_INTERVAL_MS)
            if (!isFocused) {
                activeIndex = (activeIndex + 1) % items.size
            }
        }
    }

    Box(
        modifier = modifier
            .then(
                if (fullWidth != Dp.Unspecified)
                    Modifier.requiredWidth(fullWidth)
                else
                    Modifier.fillMaxWidth()
            )
            .height(400.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()
            .onFocusChanged { isFocused = it.hasFocus || it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (activeIndex > 0) {
                                activeIndex--
                                true
                            } else false
                        }
                        Key.DirectionRight -> {
                            if (activeIndex < items.size - 1) {
                                activeIndex++
                                true
                            } else false
                        }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onItemClick(items[activeIndex])
                    true
                } else {
                    false
                }
            }
    ) {
        // Crossfade between slides — key on item ID for stability during enrichment updates
        val currentItem = items.getOrNull(activeIndex)
        Crossfade(
            targetState = currentItem?.id ?: activeIndex.toString(),
            animationSpec = tween(300),
            label = "heroSlide"
        ) { targetId ->
            val item = items.firstOrNull { it.id == targetId }
                ?: items.getOrNull(activeIndex)
                ?: return@Crossfade
            HeroCarouselSlide(item = item, contentStartPadding = contentStartPadding)
        }

        // Indicator dots — pre-compute colors + shape to avoid reallocation per dot
        val focusRing = NuvioColors.FocusRing
        val dotColorFocusedInactive = remember(focusRing) { focusRing.copy(alpha = 0.4f) }
        val dotColorUnfocusedInactive = remember { Color.White.copy(alpha = 0.3f) }
        val dotShape = remember { RoundedCornerShape(3.dp) }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = contentStartPadding + 140.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { index, _ ->
                val isActive = index == activeIndex
                val dotWidth = when {
                    isFocused && isActive -> 32.dp
                    isActive -> 24.dp
                    else -> 12.dp
                }
                val dotHeight = if (isFocused && isActive) 6.dp else 4.dp
                Box(
                    modifier = Modifier
                        .width(dotWidth)
                        .height(dotHeight)
                        .clip(dotShape)
                        .background(
                            when {
                                isFocused && isActive -> focusRing
                                isFocused -> dotColorFocusedInactive
                                isActive -> focusRing
                                else -> dotColorUnfocusedInactive
                            }
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroCarouselSlide(
    item: MetaPreview,
    contentStartPadding: Dp = 48.dp
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val requestWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val requestHeightPx = remember(density) { with(density) { 400.dp.roundToPx() } }
    val logoRequestHeightPx = remember(density) { with(density) { 80.dp.roundToPx() } }
    val backdropUrl = item.backdropUrl
    val backgroundModel = remember(backdropUrl, requestWidthPx, requestHeightPx) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .crossfade(false)
            .allowHardware(true)
            .memoryCacheKey("hero_bg_${backdropUrl}_${requestWidthPx}x${requestHeightPx}")
            .size(width = requestWidthPx, height = requestHeightPx)
            .build()
    }
    val logoModel = remember(item.logo, requestWidthPx, logoRequestHeightPx) {
        item.logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .crossfade(false)
                .allowHardware(true)
                .memoryCacheKey("hero_logo_${it}_${requestWidthPx}x${logoRequestHeightPx}")
                .size(width = requestWidthPx, height = logoRequestHeightPx)
                .build()
        }
    }
    var logoLoadFailed by remember(item.logo) { mutableStateOf(false) }
    val showLogo = !item.logo.isNullOrBlank() && !logoLoadFailed

    val bgColor = NuvioColors.Background

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // ── LAYER 1: Background image with alpha-faded edges ──
        // Uses Offscreen compositing + DstIn blend to make edges transparent
        val zoomTransition = rememberInfiniteTransition(label = "heroCarouselZoom")
        val zoomScale by zoomTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 10_000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "heroCarouselZoomScale"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Left edge fade: solid → transparent
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = size.width * 0.35f
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    // Bottom edge fade: solid → transparent
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height * 0.55f,
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
                .clipToBounds()
        ) {
            AsyncImage(
                model = backgroundModel,
                contentDescription = item.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                    },
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
        }

        // ── LAYER 2: Content (text, metadata) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = contentStartPadding + 140.dp, bottom = 48.dp, end = 120.dp)
                .fillMaxWidth(0.45f)
        ) {
                // Title logo or text title
                if (showLogo) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = item.name,
                        onError = { logoLoadFailed = true },
                        modifier = Modifier
                            .height(56.dp)
                            .fillMaxWidth(0.55f),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart
                    )
                } else {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Meta info row: IMDB rating + year
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item.imdbRating?.let { rating ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val imdbModel = remember(context) {
                                ImageRequest.Builder(context)
                                    .data(com.nuvio.tv.R.raw.imdb_logo_2016)
                                    .decoderFactory(SvgDecoder.Factory())
                                    .build()
                            }
                            AsyncImage(
                                model = imdbModel,
                                contentDescription = "IMDB",
                                modifier = Modifier.size(24.dp),
                                contentScale = ContentScale.Fit
                            )
                            val ratingText = remember(rating) { String.format("%.1f", rating) }
                            Text(
                                text = ratingText,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    val releaseYear = remember(item.releaseInfo) {
                        item.releaseInfo?.let { releaseInfo ->
                            YEAR_REGEX.find(releaseInfo)?.value ?: releaseInfo.split("-").firstOrNull()
                        }?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    releaseYear?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                if (item.genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item.genres.take(3).forEach { genre ->
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                item.description?.let { desc ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
        }
    }
}