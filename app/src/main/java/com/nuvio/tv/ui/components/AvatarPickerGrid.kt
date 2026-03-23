package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.ui.theme.NuvioColors

private val PinnedAvatarCategories = listOf(
    "actors", "marvel", "dc comics", "star wars", "heroes",
    "rick and morty", "anime", "saint seiya", "pokémon", "cartoon network",
    "adventurer", "big ears", "cartoon", "emoji", "lorelei", "personas"
)

@Composable
fun AvatarPickerGrid(
    avatars: List<AvatarCatalogItem>,
    selectedAvatarId: String?,
    onAvatarSelected: (AvatarCatalogItem) -> Unit,
    onAvatarFocused: ((AvatarCatalogItem?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val categories = remember(avatars) {
        buildList {
            add("all")

            val normalizedCategories = avatars
                .mapNotNull { avatar -> avatar.category.trim().takeIf { it.isNotEmpty() } }
                .distinct()

            PinnedAvatarCategories.forEach { category ->
                if (normalizedCategories.any { it.equals(category, ignoreCase = true) }) {
                    add(category)
                }
            }

            normalizedCategories
                .filterNot { category ->
                    PinnedAvatarCategories.any { it.equals(category, ignoreCase = true) }
                }
                .sortedBy { it.lowercase() }
                .forEach(::add)
        }
    }
    var selectedCategory by remember { mutableStateOf("all") }

    LaunchedEffect(categories) {
        if (selectedCategory !in categories) {
            selectedCategory = "all"
        }
    }

    val filteredAvatars = remember(avatars, selectedCategory) {
        if (selectedCategory == "all") avatars
        else avatars.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }

    Column(modifier = modifier) {
        // Category tabs
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { category ->
                CategoryTab(
                    label = categoryLabel(category),
                    isSelected = selectedCategory == category,
                    onClick = { selectedCategory = category }
                )
                if (category != categories.last()) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }

        // Avatar grid using LazyColumn+Row (lazy rendering for large catalogs)
        val columns = 5
        val rows = remember(filteredAvatars) { filteredAvatars.chunked(columns) }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(rows, key = { row -> row.first().id }) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    rowItems.forEach { avatar ->
                        AvatarGridItem(
                            avatar = avatar,
                            isSelected = avatar.id == selectedAvatarId,
                            onFocused = { focused -> if (focused) onAvatarFocused?.invoke(avatar) },
                            onClick = { onAvatarSelected(avatar) }
                        )
                    }
                    // Fill remaining slots with invisible spacers to keep alignment
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.requiredSize(80.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected && isFocused -> NuvioColors.FocusBackground
            isSelected -> NuvioColors.Secondary.copy(alpha = 0.22f)
            isFocused -> NuvioColors.FocusBackground
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(150),
        label = "categoryBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected && isFocused -> NuvioColors.FocusRing
            isFocused -> NuvioColors.FocusRing
            isSelected -> NuvioColors.Secondary
            else -> NuvioColors.Border
        },
        animationSpec = tween(150),
        label = "categoryBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            isSelected && isFocused -> 2.dp
            isFocused -> 2.dp
            isSelected -> 1.dp
            else -> 1.dp
        },
        animationSpec = tween(150),
        label = "categoryBorderWidth"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isFocused) Color.White else NuvioColors.TextSecondary,
        animationSpec = tween(150),
        label = "categoryText"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun AvatarGridItem(
    avatar: AvatarCatalogItem,
    isSelected: Boolean,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(150),
        label = "avatarScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            isSelected -> 3.dp
            isFocused -> 2.dp
            else -> 0.dp
        },
        animationSpec = tween(120),
        label = "avatarBorder"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected || isFocused -> NuvioColors.FocusRing
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "avatarBorderColor"
    )

    val isSvg = remember(avatar.imageUrl) { avatar.imageUrl.contains("/svg") }

    Box(
        modifier = Modifier
            .requiredSize(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                isFocused = it.isFocused
                onFocused(it.isFocused)
            }
            .clip(CircleShape)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatar.imageUrl)
                    .crossfade(true)
                    .apply {
                        if (isSvg) decoderFactory(SvgDecoder.Factory())
                    }
                    .build(),
                contentDescription = avatar.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun categoryLabel(category: String): String {
    return when (category) {
        "all" -> stringResource(R.string.profile_avatar_category_all)
        "adventurer" -> "Adventurer"
        "big ears" -> "Big Ears"
        "cartoon" -> "Cartoon"
        "emoji" -> "Emoji"
        "lorelei" -> "Lorelei"
        "personas" -> "Personas"
        "actors" -> "Actors"
        "marvel" -> "Marvel"
        "dc comics" -> "DC Comics"
        "star wars" -> "Star Wars"
        "heroes" -> "Heroes"
        "rick and morty" -> "Rick and Morty"
        "anime" -> "Anime"
        "saint seiya" -> "Cavaleiros do Zodíaco"
        "pokémon" -> "Pokémon"
        "cartoon network" -> "Cartoon Network"
        "animation" -> stringResource(R.string.profile_avatar_category_animation)
        "movie" -> stringResource(R.string.profile_avatar_category_movie)
        "tv" -> stringResource(R.string.profile_avatar_category_tv)
        "gaming" -> stringResource(R.string.profile_avatar_category_gaming)
        else -> category.replaceFirstChar { it.uppercase() }
    }
}
