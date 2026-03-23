package com.nuvio.tv.ui.components

import android.net.Uri
import androidx.annotation.RawRes
import com.nuvio.tv.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * Maps known streaming service names (case-insensitive) to SVG logo URLs.
 * Sources: Simple Icons CDN (white SVGs) and SVGL (colored, tinted white via ColorFilter).
 */
private val SERVICE_LOGO_URLS = mapOf(
    "netflix" to "https://cdn.simpleicons.org/netflix/white",
    "disney+" to "https://svgl.app/library/disneyplus.svg",
    "disney plus" to "https://svgl.app/library/disneyplus.svg",
    "hbo max" to "https://cdn.simpleicons.org/hbomax/white",
    "hbo" to "https://cdn.simpleicons.org/hbo/white",
    "max" to "https://cdn.simpleicons.org/hbo/white",
    "paramount+" to "https://cdn.simpleicons.org/paramountplus/white",
    "paramount plus" to "https://cdn.simpleicons.org/paramountplus/white",
    "paramount" to "https://cdn.simpleicons.org/paramountplus/white",
    "mubi" to "https://cdn.simpleicons.org/mubi/white",
    "crunchyroll" to "https://cdn.simpleicons.org/crunchyroll/white",
    "amazon prime" to "https://svgl.app/library/prime-video.svg",
    "prime video" to "https://svgl.app/library/prime-video.svg",
    "apple tv+" to "https://cdn.simpleicons.org/appletv/white",
    "apple tv" to "https://cdn.simpleicons.org/appletv/white",
    "youtube" to "https://cdn.simpleicons.org/youtube/white"
)

/**
 * Maps streaming service names to local intro video resources in res/raw/.
 * Add entries here as you place video files. File naming: intro_<service>.mp4
 * Services without a mapping will show only the static logo.
 */
private val SERVICE_INTRO_VIDEOS: Map<String, Int> = buildMap {
    put("netflix", R.raw.intro_netflix)
    put("disney+", R.raw.intro_disney_plus)
    put("disney plus", R.raw.intro_disney_plus)
    put("hbo max", R.raw.intro_hbo_max)
    put("hbo", R.raw.intro_hbo_max)
    put("max", R.raw.intro_hbo_max)
    put("paramount+", R.raw.intro_paramount)
    put("paramount plus", R.raw.intro_paramount)
    put("paramount", R.raw.intro_paramount)
    put("mubi", R.raw.intro_mubi)
    put("crunchyroll", R.raw.intro_crunchyroll)
    put("amazon prime", R.raw.intro_prime_video)
    put("prime video", R.raw.intro_prime_video)
    put("apple tv+", R.raw.intro_apple_tv)
    put("apple tv", R.raw.intro_apple_tv)
    put("globoplay", R.raw.intro_globoplay)
    // put("youtube", R.raw.intro_youtube)
}

@RawRes
private fun getIntroVideoRes(serviceName: String): Int? {
    return SERVICE_INTRO_VIDEOS[serviceName.trim().lowercase()]
}

private fun getLogoUrl(serviceName: String): String? {
    val key = serviceName.trim().lowercase()
    return SERVICE_LOGO_URLS[key]
}

private const val INTRO_DELAY_MS = 2000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamingServicesRow(
    serviceNames: List<String>,
    onServiceClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (serviceNames.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 8.dp, end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(
            items = serviceNames,
            key = { _, name -> "streaming_$name" }
        ) { _, name ->
            StreamingServiceCard(
                name = name,
                onClick = { onServiceClick(name) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamingServiceCard(
    name: String,
    onClick: () -> Unit
) {
    val logoUrl = remember(name) { getLogoUrl(name) }
    val introVideoRes = remember(name) { getIntroVideoRes(name) }
    val context = LocalContext.current

    var isFocused by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(false) }

    // Start video after 2s of sustained focus
    LaunchedEffect(isFocused) {
        if (isFocused && introVideoRes != null) {
            delay(INTRO_DELAY_MS)
            showVideo = true
        } else {
            showVideo = false
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .height(56.dp)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
            },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF2A2A2E),
            focusedContainerColor = Color(0xFF3A3A40)
        ),
        scale = CardDefaults.scale(focusedScale = 1.08f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Static logo (always rendered as base layer)
            if (logoUrl != null) {
                AsyncImage(
                    model = remember(logoUrl) {
                        ImageRequest.Builder(context)
                            .data(logoUrl)
                            .decoderFactory(SvgDecoder.Factory())
                            .crossfade(false)
                            .memoryCacheKey("streaming_logo_$name")
                            .diskCacheKey("streaming_logo_$name")
                            .build()
                    },
                    contentDescription = name,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .height(32.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(Color.White)
                )
            } else {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            // Intro video overlay (fades in after 2s focus)
            if (showVideo && introVideoRes != null) {
                val videoAlpha by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500),
                    label = "introVideoFadeIn"
                )
                StreamingIntroPlayer(
                    rawRes = introVideoRes,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .graphicsLayer { alpha = videoAlpha }
                )
            }
        }
    }
}

/**
 * Minimal ExoPlayer composable that plays a local res/raw video muted and looping.
 * Released automatically when removed from composition.
 */
@Composable
private fun StreamingIntroPlayer(
    @RawRes rawRes: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(rawRes) {
        ExoPlayer.Builder(context).build().apply {
            val uri = Uri.parse("android.resource://${context.packageName}/$rawRes")
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(rawRes) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        modifier = modifier
    )
}
