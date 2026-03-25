package com.nuvio.tv.ui.screens.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.DiscoverService
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverSeeAllUiState(
    val isLoading: Boolean = true,
    val items: List<MetaPreview> = emptyList()
)

@HiltViewModel
class DiscoverSeeAllViewModel @Inject constructor(
    private val discoverService: DiscoverService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val categoryKey: String = savedStateHandle["categoryKey"] ?: ""
    val categoryTitle: String = savedStateHandle["categoryTitle"] ?: ""

    private val _uiState = MutableStateFlow(DiscoverSeeAllUiState())
    val uiState: StateFlow<DiscoverSeeAllUiState> = _uiState

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val language = tmdbSettingsDataStore.settings.first().language
            val raw = when (categoryKey) {
                "upcoming" -> discoverService.getUpcoming(language)
                "best90s" -> discoverService.getAllBest90s(language)
                "best80s" -> discoverService.getAllBest80s(language)
                "werewolf" -> discoverService.getAllWerewolfMovies(language)
                "vampire" -> discoverService.getAllVampireMovies(language)
                "oscar" -> discoverService.getAllOscarWinners(language)
                else -> emptyList()
            }
            val enriched = discoverService.enrichWithImdbIds(raw, ContentType.MOVIE)
            _uiState.value = DiscoverSeeAllUiState(isLoading = false, items = enriched)
        }
    }
}
