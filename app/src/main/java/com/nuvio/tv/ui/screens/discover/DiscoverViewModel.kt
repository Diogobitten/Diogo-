package com.nuvio.tv.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.DiscoverCategory
import com.nuvio.tv.data.repository.DiscoverService
import com.nuvio.tv.data.repository.TrailerItem
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.ContentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val isLoading: Boolean = true,
    val trailers: List<TrailerItem> = emptyList(),
    val categories: List<DiscoverCategory> = emptyList()
)

sealed interface DiscoverNavEvent {
    data class PlayTrailer(
        val streamUrl: String,
        val title: String,
        val audioUrl: String? = null
    ) : DiscoverNavEvent
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discoverService: DiscoverService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val trailerService: TrailerService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState

    private val _navEvents = MutableSharedFlow<DiscoverNavEvent>()
    val navEvents: SharedFlow<DiscoverNavEvent> = _navEvents

    init {
        loadAll()
    }

    fun onTrailerClick(trailer: TrailerItem) {
        val ytKey = trailer.youtubeKey ?: return
        viewModelScope.launch {
            try {
                val source = trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                    youtubeUrl = "https://www.youtube.com/watch?v=$ytKey",
                    title = trailer.title,
                    year = trailer.releaseInfo
                )
                val videoUrl = source?.videoUrl
                if (videoUrl != null) {
                    _navEvents.emit(
                        DiscoverNavEvent.PlayTrailer(
                            streamUrl = videoUrl,
                            title = "${trailer.title} — Trailer",
                            audioUrl = source.audioUrl
                        )
                    )
                }
            } catch (_: Exception) { }
        }
    }

    private fun loadAll() {
        viewModelScope.launch {
            val language = tmdbSettingsDataStore.settings.first().language

            val trailersDeferred = async { discoverService.getLatestTrailers(language) }
            val trailers = trailersDeferred.await()
            _uiState.value = _uiState.value.copy(trailers = trailers)

            val upcomingDeferred = async { discoverService.getUpcoming(language) }
            val best90sDeferred = async { discoverService.getBest90s(language) }
            val best80sDeferred = async { discoverService.getBest80s(language) }
            val werewolfDeferred = async { discoverService.getWerewolfMovies(language) }
            val vampireDeferred = async { discoverService.getVampireMovies(language) }
            val oscarDeferred = async { discoverService.getOscarWinners(language) }

            val upcoming = discoverService.enrichWithImdbIds(upcomingDeferred.await(), ContentType.MOVIE)
            val best90s = discoverService.enrichWithImdbIds(best90sDeferred.await(), ContentType.MOVIE)
            val best80s = discoverService.enrichWithImdbIds(best80sDeferred.await(), ContentType.MOVIE)
            val werewolf = discoverService.enrichWithImdbIds(werewolfDeferred.await(), ContentType.MOVIE)
            val vampire = discoverService.enrichWithImdbIds(vampireDeferred.await(), ContentType.MOVIE)
            val oscar = discoverService.enrichWithImdbIds(oscarDeferred.await(), ContentType.MOVIE)

            val categories = listOfNotNull(
                upcoming.takeIf { it.isNotEmpty() }?.let { DiscoverCategory("upcoming", "Em Breve", it) },
                best90s.takeIf { it.isNotEmpty() }?.let { DiscoverCategory("best90s", "Melhores dos Anos 90", it) },
                best80s.takeIf { it.isNotEmpty() }?.let { DiscoverCategory("best80s", "Melhores dos Anos 80", it) },
                werewolf.takeIf { it.isNotEmpty() }?.let { DiscoverCategory("werewolf", "Filmes de Lobisomem", it) },
                vampire.takeIf { it.isNotEmpty() }?.let { DiscoverCategory("vampire", "Filmes de Vampiro", it) },
                oscar.takeIf { it.isNotEmpty() }?.let { DiscoverCategory("oscar", "Oscar — Melhor Filme", it) }
            )

            _uiState.value = _uiState.value.copy(isLoading = false, categories = categories)
        }
    }
}
