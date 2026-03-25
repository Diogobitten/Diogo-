package com.nuvio.tv.ui.screens.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.tmdb.CollectionDetail
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.themesong.ThemeSongService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CollectionDetailUiState {
    data object Loading : CollectionDetailUiState
    data class Error(val message: String) : CollectionDetailUiState
    data class Success(
        val detail: CollectionDetail,
        val themeSongAudioUrl: String? = null
    ) : CollectionDetailUiState
}

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    private val tmdbMetadataService: TmdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val themeSongService: ThemeSongService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val collectionId: Int = savedStateHandle.get<String>("collectionId")?.toIntOrNull() ?: 0
    val collectionName: String = savedStateHandle["collectionName"] ?: ""

    private val _uiState = MutableStateFlow<CollectionDetailUiState>(CollectionDetailUiState.Loading)
    val uiState: StateFlow<CollectionDetailUiState> = _uiState

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = CollectionDetailUiState.Loading
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val detail = tmdbMetadataService.fetchCollectionDetail(collectionId, language)
                if (detail != null && detail.parts.isNotEmpty()) {
                    _uiState.value = CollectionDetailUiState.Success(detail)
                    // Load theme song for the first movie in the collection
                    loadThemeSong(detail)
                } else {
                    _uiState.value = CollectionDetailUiState.Error("No content found")
                }
            } catch (e: Exception) {
                _uiState.value = CollectionDetailUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun loadThemeSong(detail: CollectionDetail) {
        val tmdbId = detail.firstMovieTmdbId ?: return
        viewModelScope.launch {
            try {
                val audioUrl = themeSongService.getThemeSongAudioUrl(
                    itemId = tmdbId.toString(),
                    itemType = "movie"
                )
                val current = _uiState.value
                if (current is CollectionDetailUiState.Success) {
                    _uiState.value = current.copy(themeSongAudioUrl = audioUrl)
                }
            } catch (_: Exception) { }
        }
    }

    fun retry() {
        loadData()
    }
}
