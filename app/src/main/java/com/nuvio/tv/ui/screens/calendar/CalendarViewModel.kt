package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        loadCalendar()
    }

    fun refresh() {
        loadCalendar()
    }

    fun selectDate(date: LocalDate) {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val items = _uiState.value.itemsByDate[dateStr].orEmpty()
        _uiState.update { it.copy(selectedDate = date, selectedDateItems = items) }
    }

    fun changeMonth(month: LocalDate) {
        _uiState.update { it.copy(currentMonth = month.withDayOfMonth(1)) }
        // Reload with extended range if needed
        loadCalendar()
    }

    private fun loadCalendar() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Fetch 90 days to cover current + next months
                val items = calendarRepository.getCalendarItems(days = 90)
                val byDate = items.groupBy { it.date }

                val today = LocalDate.now()
                val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val todayItems = byDate[todayStr].orEmpty()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        allItems = items,
                        itemsByDate = byDate,
                        selectedDate = today,
                        selectedDateItems = todayItems
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message)
                }
            }
        }
    }
}
