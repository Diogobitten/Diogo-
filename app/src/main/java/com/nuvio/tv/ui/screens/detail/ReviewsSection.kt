package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.TmdbReview

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReviewsSection(
    reviews: List<TmdbReview>,
    modifier: Modifier = Modifier
) {
    if (reviews.isEmpty()) return

    val firstItemFocusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.detail_section_reviews),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 10.dp)
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer { firstItemFocusRequester },
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(
                items = reviews,
                key = { _, review -> review.id }
            ) { index, review ->
                ReviewCard(
                    review = review,
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ReviewCard(
    review: TmdbReview,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val truncatedContent = remember(review.content) {
        if (review.content.length > 300) review.content.take(300) + "…"
        else review.content
    }

    val cardModifier = if (focusRequester != null) {
        Modifier.focusRequester(focusRequester)
    } else {
        Modifier
    }

    Card(
        onClick = {},
        modifier = cardModifier
            .width(340.dp)
            .height(180.dp)
            .onFocusChanged { state -> isFocused = state.isFocused },
        colors = CardDefaults.colors(
            containerColor = Color(0xFF1E1E22).copy(alpha = 0.2f),
            focusedContainerColor = Color(0xFF2A2A2E).copy(alpha = 0.2f)
        ),
        shape = CardDefaults.shape(
            shape = RoundedCornerShape(12.dp),
            focusedShape = RoundedCornerShape(12.dp)
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Author row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (review.avatarUrl != null) {
                    AsyncImage(
                        model = review.avatarUrl,
                        contentDescription = review.author,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Text(
                    text = review.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (review.rating != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "★ ${review.rating.toInt()}/10",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD700),
                        fontSize = 12.sp
                    )
                }
            }

            if (review.createdAt != null) {
                Text(
                    text = review.createdAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Review content
            Text(
                text = truncatedContent,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
