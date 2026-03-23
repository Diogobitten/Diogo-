package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest

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

private fun getLogoUrl(serviceName: String): String? {
    val key = serviceName.trim().lowercase()
    return SERVICE_LOGO_URLS[key]
}

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
    val context = LocalContext.current

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(160.dp)
            .height(56.dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF2A2A2E),
            focusedContainerColor = Color(0xFF3A3A40)
        ),
        scale = CardDefaults.scale(focusedScale = 1.08f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center
        ) {
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
                // Fallback to text for unknown services
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
        }
    }
}
