package com.nuvio.tv.ui.screens.tmdb

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.TmdbEntityKind
import com.nuvio.tv.core.tmdb.TmdbEntityMediaType
import com.nuvio.tv.core.tmdb.TmdbEntityRail
import com.nuvio.tv.core.tmdb.TmdbEntityRailType
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TmdbEntityBrowseViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val entityKindArg: String = savedStateHandle["entityKind"] ?: "COMPANY"
    private val entityIdArg: Int = (savedStateHandle.get<String>("entityId"))?.toIntOrNull() ?: 0
    private val entityNameArg: String = savedStateHandle["entityName"] ?: ""

    val entityKind: TmdbEntityKind = runCatching {
        TmdbEntityKind.valueOf(entityKindArg.uppercase())
    }.getOrDefault(TmdbEntityKind.COMPANY)
    val entityId: Int = entityIdArg
    val entityName: String = entityNameArg

    private val _uiState = MutableStateFlow<TmdbEntityBrowseUiState>(TmdbEntityBrowseUiState.Loading)
    val uiState: StateFlow<TmdbEntityBrowseUiState> = _uiState

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = TmdbEntityBrowseUiState.Loading
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val data = tmdbMetadataService.fetchEntityBrowse(entityKind, entityId, language)
                if (data != null && data.rails.isNotEmpty()) {
                    _uiState.value = TmdbEntityBrowseUiState.Success(data)
                } else {
                    _uiState.value = TmdbEntityBrowseUiState.Error("No content found")
                }
            } catch (e: Exception) {
                _uiState.value = TmdbEntityBrowseUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun loadMoreRail(rail: TmdbEntityRail) {
        if (!rail.hasMore) return
        val currentState = _uiState.value
        if (currentState !is TmdbEntityBrowseUiState.Success) return

        viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val result = tmdbMetadataService.fetchEntityRailPage(
                    entityKind = entityKind,
                    entityId = entityId,
                    mediaType = rail.mediaType,
                    railType = rail.railType,
                    page = rail.currentPage + 1,
                    language = language
                )
                val updatedRails = currentState.data.rails.map { existingRail ->
                    if (existingRail.mediaType == rail.mediaType && existingRail.railType == rail.railType) {
                        existingRail.copy(
                            items = existingRail.items + result.items,
                            currentPage = rail.currentPage + 1,
                            hasMore = result.hasMore
                        )
                    } else {
                        existingRail
                    }
                }
                _uiState.value = TmdbEntityBrowseUiState.Success(
                    currentState.data.copy(rails = updatedRails)
                )
            } catch (_: Exception) {
                // Silently fail pagination
            }
        }
    }

    fun retry() {
        loadData()
    }
}
