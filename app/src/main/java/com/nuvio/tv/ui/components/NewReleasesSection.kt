package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarItemType

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NewReleasesSection(
    items: List<CalendarItem>,
    onItemClick: (CalendarItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.new_releases),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id }
            ) { _, item ->
                NewReleaseCard(
                    item = item,
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NewReleaseCard(
    item: CalendarItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val cardWidth = 220.dp
    val cardHeight = 124.dp
    val cardShape = RoundedCornerShape(10.dp)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val requestWidthPx = remember(density) { with(density) { cardWidth.roundToPx() } }
    val requestHeightPx = remember(density) { with(density) { cardHeight.roundToPx() } }
    val imageUrl = item.backdrop ?: item.poster
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

    Column(
        modifier = Modifier.width(cardWidth)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight),
            shape = CardDefaults.shape(shape = cardShape)
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(cardHeight)) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(cardHeight)
                )

                // Badge overlay
                val badgeText: String
                val badgeColor: Color
                when (item.type) {
                    CalendarItemType.EPISODE -> {
                        if (item.season != null && item.episode == 1) {
                            badgeText = stringResource(R.string.new_releases_new_season)
                            badgeColor = Color(0xFF66BB6A) // green for new season
                        } else {
                            badgeText = stringResource(R.string.new_releases_new_episode)
                            badgeColor = Color(0xFF4FC3F7) // blue for new episode
                        }
                    }
                    CalendarItemType.MOVIE -> {
                        badgeText = stringResource(R.string.new_releases_new)
                        badgeColor = Color(0xFFFFA726) // orange for movie
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(
                            color = badgeColor,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.Black
                    )
                }

                // Episode label at bottom right
                if (item.type == CalendarItemType.EPISODE && item.season != null && item.episode != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "S${item.season}E${item.episode}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = item.showName ?: item.title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
