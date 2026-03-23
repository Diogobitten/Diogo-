package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.domain.model.MetaPreview

@Composable
fun DailyTipSection(
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Dica do Dia ✦",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items.take(3).forEach { item ->
                DailyTipCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DailyTipCard(
    item: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardHeight = 100.dp
    val cardShape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    val imageUrl = item.background ?: item.poster

    Card(
        onClick = onClick,
        modifier = modifier.height(cardHeight),
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF1E1E22),
            focusedContainerColor = Color(0xFF2A2A30)
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (imageUrl != null) {
                val requestWidthPx = remember(density) { with(density) { 300.dp.roundToPx() } }
                val requestHeightPx = remember(density) { with(density) { cardHeight.roundToPx() } }
                val imageModel = remember(imageUrl, requestWidthPx, requestHeightPx) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(false)
                        .allowHardware(true)
                        .size(width = requestWidthPx, height = requestHeightPx)
                        .memoryCacheKey("${imageUrl}_${requestWidthPx}x${requestHeightPx}")
                        .diskCacheKey("${imageUrl}_${requestWidthPx}x${requestHeightPx}")
                        .build()
                }
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                )
            }

            // Gradient overlay + title at bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(cardShape)
                    .padding(0.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f)
                                ),
                                startY = 30f
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.releaseInfo != null || item.imdbRating != null) {
                        val info = buildString {
                            item.releaseInfo?.let { append(it) }
                            item.imdbRating?.let {
                                if (isNotEmpty()) append(" · ")
                                append("⭐ %.1f".format(it))
                            }
                        }
                        Text(
                            text = info,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
