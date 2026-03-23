package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarItemType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Dot colors for calendar indicators
private val EpisodeDotColor = Color(0xFF4FC3F7)  // Light blue for episodes
private val MovieDotColor = Color(0xFFFFA726)     // Orange for movies
private val TodayColor = Color(0xFF66BB6A)        // Green for today
private val SelectedBorderColor = Color.White
private val DayCellBg = Color(0xFF2A2A2A)
private val DayCellFocusedBg = Color(0xFF3A3A3A)
private val HeaderBg = Color(0xFF1A1A1A)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
    ) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.calendar_loading),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 48.dp,
                            end = 32.dp,
                            top = if (showBuiltInHeader) 24.dp else 8.dp,
                            bottom = 24.dp
                        )
                ) {
                    // Left side: Calendar grid
                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .padding(end = 24.dp)
                    ) {
                        CalendarMonthGrid(
                            currentMonth = uiState.currentMonth,
                            selectedDate = uiState.selectedDate,
                            itemsByDate = uiState.itemsByDate,
                            onDateSelected = { viewModel.selectDate(it) },
                            onMonthChanged = { viewModel.changeMonth(it) }
                        )
                    }

                    // Right side: Selected day items
                    Column(
                        modifier = Modifier.weight(0.45f)
                    ) {
                        SelectedDayPanel(
                            selectedDate = uiState.selectedDate,
                            items = uiState.selectedDateItems,
                            onItemClick = { calendarItem ->
                                val itemId = calendarItem.imdbId
                                    ?: calendarItem.tmdbId?.let { "tmdb:$it" }
                                    ?: return@SelectedDayPanel
                                val itemType = when (calendarItem.type) {
                                    CalendarItemType.EPISODE -> "series"
                                    CalendarItemType.MOVIE -> "movie"
                                }
                                onNavigateToDetail(itemId, itemType, null)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarMonthGrid(
    currentMonth: LocalDate,
    selectedDate: LocalDate,
    itemsByDate: Map<String, List<CalendarItem>>,
    onDateSelected: (LocalDate) -> Unit,
    onMonthChanged: (LocalDate) -> Unit
) {
    val today = remember { LocalDate.now() }
    val monthName = remember(currentMonth) {
        currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            .replaceFirstChar { it.uppercase() }
    }
    val year = currentMonth.year

    Column {
        // Month header with navigation arrows
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = {
                onMonthChanged(currentMonth.minusMonths(1))
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous month",
                    tint = Color.White
                )
            }

            Text(
                text = "$monthName $year",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            IconButton(onClick = {
                onMonthChanged(currentMonth.plusMonths(1))
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next month",
                    tint = Color.White
                )
            }
        }

        // Day of week headers
        val daysOfWeek = remember {
            listOf(
                DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            daysOfWeek.forEach { day ->
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        .take(3).uppercase(),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Calendar grid
        val firstDayOfMonth = currentMonth.withDayOfMonth(1)
        val lastDayOfMonth = currentMonth.with(TemporalAdjusters.lastDayOfMonth())
        val startOffset = (firstDayOfMonth.dayOfWeek.value % 7) // Sunday = 0
        val totalDays = lastDayOfMonth.dayOfMonth

        val weeks = remember(currentMonth) {
            val cells = mutableListOf<LocalDate?>()
            repeat(startOffset) { cells.add(null) }
            for (d in 1..totalDays) {
                cells.add(currentMonth.withDayOfMonth(d))
            }
            while (cells.size % 7 != 0) { cells.add(null) }
            cells.chunked(7)
        }

        weeks.forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                week.forEach { date ->
                    if (date != null) {
                        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                        val dayItems = itemsByDate[dateStr].orEmpty()
                        val hasEpisodes = dayItems.any { it.type == CalendarItemType.EPISODE }
                        val hasMovies = dayItems.any { it.type == CalendarItemType.MOVIE }
                        val isToday = date == today
                        val isSelected = date == selectedDate

                        DayCell(
                            day = date.dayOfMonth,
                            isToday = isToday,
                            isSelected = isSelected,
                            hasEpisodes = hasEpisodes,
                            hasMovies = hasMovies,
                            onClick = { onDateSelected(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    hasEpisodes: Boolean,
    hasMovies: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isToday -> TodayColor.copy(alpha = 0.25f)
        else -> DayCellBg
    }
    val borderMod = if (isSelected) {
        Modifier.border(1.5.dp, SelectedBorderColor, RoundedCornerShape(8.dp))
    } else {
        Modifier
    }

    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(52.dp)
            .then(borderMod),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = bgColor,
            focusedContainerColor = DayCellFocusedBg
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            focusedScale = 1.08f
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = day.toString(),
                color = if (isToday) TodayColor else Color.White,
                fontSize = 14.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
            )

            if (hasEpisodes || hasMovies) {
                Spacer(Modifier.height(3.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasEpisodes) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(EpisodeDotColor, CircleShape)
                        )
                    }
                    if (hasMovies) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(MovieDotColor, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SelectedDayPanel(
    selectedDate: LocalDate,
    items: List<CalendarItem>,
    onItemClick: (CalendarItem) -> Unit
) {
    val today = remember { LocalDate.now() }
    val tomorrow = remember { today.plusDays(1) }

    val dateLabel = remember(selectedDate) {
        when (selectedDate) {
            today -> "Hoje"
            tomorrow -> "Amanhã"
            else -> {
                val dow = selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    .replaceFirstChar { it.uppercase() }
                val month = selectedDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "$dow, ${selectedDate.dayOfMonth} $month"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HeaderBg, RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Text(
            text = dateLabel,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = if (items.isEmpty()) stringResource(R.string.calendar_empty)
            else "${items.size} ${if (items.size == 1) "item" else "items"}",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(16.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nenhum lançamento neste dia",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    CalendarListItem(item = item, onClick = { onItemClick(item) })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarListItem(
    item: CalendarItem,
    onClick: () -> Unit
) {
    val dotColor = when (item.type) {
        CalendarItemType.EPISODE -> EpisodeDotColor
        CalendarItemType.MOVIE -> MovieDotColor
    }

    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(10.dp)
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF222222),
            focusedContainerColor = Color(0xFF333333)
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(
            focusedScale = 1.02f
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Poster thumbnail
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF333333))
            ) {
                if (item.poster != null) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                // Type dot + title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (item.type == CalendarItemType.EPISODE) item.showName ?: item.title else item.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Subtitle line
                val subtitle = when (item.type) {
                    CalendarItemType.EPISODE -> {
                        val epLabel = if (item.season != null && item.episode != null) {
                            "S${item.season}E${item.episode}"
                        } else ""
                        val epName = item.episodeName ?: item.title
                        if (epLabel.isNotEmpty()) "$epLabel · $epName" else epName
                    }
                    CalendarItemType.MOVIE -> {
                        stringResource(R.string.calendar_digital_release) +
                                if (item.year != null) " · ${item.year}" else ""
                    }
                }

                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
