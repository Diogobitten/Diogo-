package com.nuvio.tv.ui.screens.diobot

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.DiobotServer
import com.nuvio.tv.data.repository.DiobotAction
import com.nuvio.tv.data.repository.DiobotService
import com.nuvio.tv.data.repository.DiobotSuggestion
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class ChatEntry(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val suggestions: List<DiobotSuggestion> = emptyList()
)

sealed class DiobotNavEvent {
    data class NavigateToDetail(val itemId: String, val itemType: String) : DiobotNavEvent()
    data class NavigateToStream(
        val videoId: String,
        val contentType: String,
        val title: String
    ) : DiobotNavEvent()
}

data class DiobotUiState(
    val messages: List<ChatEntry> = emptyList(),
    val isProcessing: Boolean = false,
    val qrBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val serverRunning: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class DiobotViewModel @Inject constructor(
    private val application: Application,
    private val diobotService: DiobotService,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    companion object {
        private const val TAG = "DiobotViewModel"
    }

    private val _uiState = MutableStateFlow(DiobotUiState())
    val uiState: StateFlow<DiobotUiState> = _uiState.asStateFlow()

    private val _navEvents = MutableStateFlow<DiobotNavEvent?>(null)
    val navEvents: StateFlow<DiobotNavEvent?> = _navEvents.asStateFlow()

    private var server: DiobotServer? = null
    private var commandJob: Job? = null
    private val chatMutex = Mutex()

    init {
        startServer()
    }

    private fun getLibraryTitles(): List<String> {
        // Not used — use getLibraryTitlesSuspend() instead
        return emptyList()
    }

    private suspend fun getLibraryTitlesSuspend(): List<String> {
        return try {
            val items = libraryRepository.libraryItems.first()
            items.map { "${it.name} (${it.type})" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun startServer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                server?.stop()

                val newServer = DiobotServer.startOnAvailablePort() ?: run {
                    _uiState.update { it.copy(errorMessage = "Não foi possível iniciar o servidor") }
                    return@launch
                }

                newServer.chatHandler = { message -> handleChatFromPhone(message) }

                server = newServer
                val ip = DeviceIpAddress.get(application)
                val port = newServer.listeningPort
                val url = "http://$ip:$port"

                val qr = QrCodeGenerator.generate(url, 512)

                _uiState.update {
                    it.copy(
                        serverRunning = true,
                        serverUrl = url,
                        qrBitmap = qr,
                        errorMessage = null
                    )
                }

                commandJob?.cancel()
                commandJob = viewModelScope.launch {
                    newServer.commands.collect { command ->
                        when (command) {
                            is DiobotServer.TvCommand.NavigateToDetail -> {
                                _navEvents.value = DiobotNavEvent.NavigateToDetail(command.itemId, command.itemType)
                            }
                            is DiobotServer.TvCommand.PlayContent -> {
                                _navEvents.value = DiobotNavEvent.NavigateToStream(
                                    videoId = command.itemId,
                                    contentType = command.itemType,
                                    title = ""
                                )
                            }
                            is DiobotServer.TvCommand.SaveToLibrary -> {
                                val result = handleSaveToLibrary(
                                    DiobotSuggestion(
                                        title = command.title,
                                        type = command.itemType,
                                        tmdbId = null,
                                        imdbId = command.itemId.takeIf { it.startsWith("tt") }
                                    )
                                )
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages + ChatEntry(
                                            isUser = false,
                                            text = result
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "Diobot server started at $url")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                _uiState.update { it.copy(errorMessage = "Erro ao iniciar servidor: ${e.message}") }
            }
        }
    }

    private suspend fun handleChatFromPhone(message: String): DiobotServer.ChatResponse {
        return chatMutex.withLock {
            try {
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + ChatEntry(isUser = true, text = message),
                        isProcessing = true
                    )
                }

                val libraryTitles = getLibraryTitlesSuspend()
                val response = diobotService.chat(message, libraryTitles)

                // Handle actions
                val actionMessage = when (val action = response.action) {
                    is DiobotAction.SaveToLibrary -> {
                        handleSaveToLibrary(action.suggestion)
                    }
                    is DiobotAction.Play -> {
                        handlePlay(action.suggestion)
                    }
                    null -> null
                }

                val displayMessage = if (actionMessage != null) {
                    "${response.message}\n\n$actionMessage"
                } else {
                    response.message
                }

                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + ChatEntry(
                            isUser = false,
                            text = displayMessage,
                            suggestions = response.suggestions
                        ),
                        isProcessing = false
                    )
                }

                DiobotServer.ChatResponse(
                    message = displayMessage,
                    suggestions = response.suggestions.map { s ->
                        DiobotServer.SuggestionDto(
                            title = s.title,
                            type = s.type,
                            tmdbId = s.tmdbId,
                            imdbId = s.imdbId,
                            poster = s.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "handleChatFromPhone error", e)
                _uiState.update { it.copy(isProcessing = false) }
                DiobotServer.ChatResponse(
                    message = "Erro interno: ${e.message}",
                    suggestions = emptyList()
                )
            }
        }
    }

    private suspend fun handleSaveToLibrary(suggestion: DiobotSuggestion): String {
        return try {
            val itemId = suggestion.imdbId ?: "tmdb:${suggestion.tmdbId}" 
            val input = LibraryEntryInput(
                itemId = itemId,
                itemType = suggestion.type,
                title = suggestion.title,
                imdbId = suggestion.imdbId,
                tmdbId = suggestion.tmdbId
            )
            libraryRepository.toggleDefault(input)
            Log.d(TAG, "Saved to library: ${suggestion.title} ($itemId)")
            "✅ ${suggestion.title} salvo na biblioteca!"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save to library", e)
            "❌ Não consegui salvar na biblioteca: ${e.message}"
        }
    }

    private suspend fun handlePlay(suggestion: DiobotSuggestion): String {
        return try {
            val videoId = suggestion.imdbId ?: "tmdb:${suggestion.tmdbId}"
            val type = suggestion.type
            Log.d(TAG, "Play requested: ${suggestion.title} ($videoId, $type)")
            _navEvents.value = DiobotNavEvent.NavigateToStream(
                videoId = videoId,
                contentType = type,
                title = suggestion.title
            )
            "▶ Reproduzindo ${suggestion.title} na TV..."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play", e)
            "❌ Não consegui reproduzir: ${e.message}"
        }
    }

    fun consumeNavEvent() {
        _navEvents.value = null
    }

    fun clearChat() {
        diobotService.clearHistory()
        _uiState.update { it.copy(messages = emptyList(), errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        commandJob?.cancel()
        server?.stop()
        server = null
    }
}
