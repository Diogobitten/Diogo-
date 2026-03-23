package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CalendarItem
import java.time.LocalDate

@Immutable
data class CalendarUiState(
    val isLoading: Boolean = true,
    val allItems: List<CalendarItem> = emptyList(),
    val itemsByDate: Map<String, List<CalendarItem>> = emptyMap(),
    val currentMonth: LocalDate = LocalDate.now().withDayOfMonth(1),
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedDateItems: List<CalendarItem> = emptyList(),
    val error: String? = null
)
