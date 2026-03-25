package com.nuvio.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.TraktListPrivacy
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.nuvio.tv.R
import java.util.Locale
import javax.inject.Inject

data class LibraryTypeTab(
    val key: String,
    val label: String
) {
    companion object {
        const val ALL_KEY = "__all__"
        const val COLLECTION_KEY = "__collection__"
        val All = LibraryTypeTab(key = ALL_KEY, label = "All")
        val Collection = LibraryTypeTab(key = COLLECTION_KEY, label = "Coleção")
    }
}

enum class LibrarySortOption(
    val key: String,
    val labelResId: Int
) {
    DEFAULT("default", R.string.library_sort_trakt_order),
    ADDED_DESC("added_desc", R.string.library_sort_added_desc),
    ADDED_ASC("added_asc", R.string.library_sort_added_asc),
    TITLE_ASC("title_asc", R.string.library_sort_title_asc),
    TITLE_DESC("title_desc", R.string.library_sort_title_desc);

    companion object {
        val TraktOptions = listOf(DEFAULT, ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
        val LocalOptions = listOf(ADDED_DESC, ADDED_ASC, TITLE_ASC, TITLE_DESC)
    }
}

data class LibraryListEditorState(
    val mode: Mode,
    val listId: String? = null,
    val name: String = "",
    val description: String = "",
    val privacy: TraktListPrivacy = TraktListPrivacy.PRIVATE
) {
    enum class Mode {
        CREATE,
        EDIT
    }
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val allItems: List<LibraryEntry> = emptyList(),
    val visibleItems: List<LibraryEntry> = emptyList(),
    val collectionItems: List<LibraryCollectionItem> = emptyList(),
    val isCollectionView: Boolean = false,
    val isCollectionsLoading: Boolean = false,
    val listTabs: List<LibraryListTab> = emptyList(),
    val availableTypeTabs: List<LibraryTypeTab> = emptyList(),
    val availableSortOptions: List<LibrarySortOption> = emptyList(),
    val selectedListKey: String? = null,
    val selectedTypeTab: LibraryTypeTab? = null,
    val selectedSortOption: LibrarySortOption = LibrarySortOption.DEFAULT,
    val sortSelectionVersion: Long = 0L,
    val posterCardWidthDp: Int = 126,
    val posterCardCornerRadiusDp: Int = 12,
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
    val showManageDialog: Boolean = false,
    val manageSelectedListKey: String? = null,
    val listEditorState: LibraryListEditorState? = null,
    val pendingOperation: Boolean = false
)

data class LibraryCollectionItem(
    val collectionId: Int,
    val collectionName: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val movieCount: Int
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbMetadataService: com.nuvio.tv.core.tmdb.TmdbMetadataService,
    private val tmdbService: com.nuvio.tv.core.tmdb.TmdbService,
    private val tmdbSettingsDataStore: com.nuvio.tv.data.local.TmdbSettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var messageClearJob: Job? = null

    init {
        observeLayoutPreferences()
        observeLibraryData()
    }

    fun onSelectTypeTab(tab: LibraryTypeTab) {
        _uiState.update { current ->
            val isCollection = tab.key == LibraryTypeTab.COLLECTION_KEY
            val updated = current.copy(selectedTypeTab = tab, isCollectionView = isCollection)
            if (isCollection) updated else updated.withVisibleItems()
        }
        if (tab.key == LibraryTypeTab.COLLECTION_KEY) {
            loadCollections()
        }
    }

    fun onSelectListTab(listKey: String) {
        _uiState.update { current ->
            val updated = current.copy(selectedListKey = listKey)
            updated.withVisibleItems()
        }
    }

    fun onSelectSortOption(option: LibrarySortOption) {
        _uiState.update { current ->
            val nextVersion = if (current.selectedSortOption != option) {
                current.sortSelectionVersion + 1L
            } else {
                current.sortSelectionVersion
            }
            val updated = current.copy(
                selectedSortOption = option,
                sortSelectionVersion = nextVersion
            )
            updated.withVisibleItems()
        }
    }

    private var collectionsJob: Job? = null

    private fun loadCollections() {
        collectionsJob?.cancel()
        _uiState.update { it.copy(isCollectionsLoading = true, collectionItems = emptyList()) }
        collectionsJob = viewModelScope.launch {
            try {
                val language = tmdbSettingsDataStore.settings.first().language
                val movieItems = _uiState.value.allItems.filter {
                    it.type.trim().lowercase(Locale.ROOT) == "movie"
                }
                val semaphore = Semaphore(6)
                val collectionMap = linkedMapOf<Int, LibraryCollectionItem>()

                val results = coroutineScope {
                    movieItems.map { entry ->
                        async {
                            semaphore.withPermit {
                                try {
                                    val tmdbId = tmdbService.ensureTmdbId(entry.id, entry.type)
                                        ?: return@withPermit null
                                    val enrichment = tmdbMetadataService.fetchEnrichment(
                                        tmdbId = tmdbId,
                                        contentType = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                        language = language
                                    ) ?: return@withPermit null
                                    val collId = enrichment.collectionId ?: return@withPermit null
                                    val collName = enrichment.collectionName ?: return@withPermit null
                                    Triple(collId, collName, entry.id)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                for ((collId, collName, _) in results) {
                    val existing = collectionMap[collId]
                    if (existing != null) {
                        collectionMap[collId] = existing.copy(movieCount = existing.movieCount + 1)
                    } else {
                        val collResp = runCatching {
                            tmdbMetadataService.fetchCollectionDetail(collId, language)
                        }.getOrNull()
                        collectionMap[collId] = LibraryCollectionItem(
                            collectionId = collId,
                            collectionName = collName,
                            posterUrl = collResp?.posterUrl,
                            backdropUrl = collResp?.backdropUrl,
                            movieCount = 1
                        )
                    }
                }

                val sorted = collectionMap.values.sortedByDescending { it.movieCount }
                _uiState.update { it.copy(collectionItems = sorted, isCollectionsLoading = false) }
            } catch (e: Exception) {
                android.util.Log.w("LibraryViewModel", "Failed to load collections: ${e.message}", e)
                _uiState.update { it.copy(isCollectionsLoading = false) }
            }
        }
    }

    fun onRefresh() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            setTransientMessage("Syncing Trakt library...")
            runCatching {
                libraryRepository.refreshNow()
                setTransientMessage("Library synced")
            }.onFailure { error ->
                setError(error.message ?: "Failed to refresh library")
            }
        }
    }

    fun onOpenManageLists() {
        _uiState.update { current ->
            if (current.sourceMode != LibrarySourceMode.TRAKT) {
                return@update current
            }
            current.copy(
                showManageDialog = true,
                manageSelectedListKey = current.manageSelectedListKey
                    ?: current.listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key
            )
        }
    }

    fun onCloseManageLists() {
        _uiState.update { current ->
            current.copy(
                showManageDialog = false,
                listEditorState = null,
                errorMessage = null
            )
        }
    }

    fun onSelectManageList(listKey: String) {
        _uiState.update { it.copy(manageSelectedListKey = listKey) }
    }

    fun onStartCreateList() {
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(mode = LibraryListEditorState.Mode.CREATE),
                errorMessage = null
            )
        }
    }

    fun onStartEditList() {
        val selected = selectedManagePersonalList() ?: return
        _uiState.update {
            it.copy(
                listEditorState = LibraryListEditorState(
                    mode = LibraryListEditorState.Mode.EDIT,
                    listId = selected.traktListId?.toString(),
                    name = selected.title,
                    description = selected.description.orEmpty(),
                    privacy = selected.privacy ?: TraktListPrivacy.PRIVATE
                ),
                errorMessage = null
            )
        }
    }

    fun onUpdateEditorName(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(name = value))
        }
    }

    fun onUpdateEditorDescription(value: String) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(description = value))
        }
    }

    fun onUpdateEditorPrivacy(value: TraktListPrivacy) {
        _uiState.update { current ->
            val editor = current.listEditorState ?: return@update current
            current.copy(listEditorState = editor.copy(privacy = value))
        }
    }

    fun onCancelEditor() {
        _uiState.update { it.copy(listEditorState = null, errorMessage = null) }
    }

    fun onSubmitEditor() {
        val editor = _uiState.value.listEditorState ?: return
        val name = editor.name.trim()
        if (name.isBlank()) {
            setError("List name is required")
            return
        }
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                when (editor.mode) {
                    LibraryListEditorState.Mode.CREATE -> {
                        libraryRepository.createPersonalList(
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List created")
                    }
                    LibraryListEditorState.Mode.EDIT -> {
                        val listId = editor.listId
                            ?: throw IllegalStateException("Invalid list")
                        libraryRepository.updatePersonalList(
                            listId = listId,
                            name = name,
                            description = editor.description.trim().ifBlank { null },
                            privacy = editor.privacy
                        )
                        setTransientMessage("List updated")
                    }
                }
            }.onSuccess {
                _uiState.update { it.copy(listEditorState = null, pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to save list")
            }
        }
    }

    fun onDeleteSelectedList() {
        val selected = selectedManagePersonalList() ?: return
        val listId = selected.traktListId?.toString() ?: return
        if (_uiState.value.pendingOperation) return

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.deletePersonalList(listId)
                setTransientMessage("List deleted")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to delete list")
            }
        }
    }

    fun onMoveSelectedListUp() {
        reorderSelectedList(moveUp = true)
    }

    fun onMoveSelectedListDown() {
        reorderSelectedList(moveUp = false)
    }

    fun onClearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private fun observeLibraryData() {
        viewModelScope.launch {
            combine(
                libraryRepository.sourceMode,
                libraryRepository.isSyncing,
                libraryRepository.libraryItems,
                libraryRepository.listTabs
            ) { sourceMode, isSyncing, items, listTabs ->
                DataBundle(
                    sourceMode = sourceMode,
                    isSyncing = isSyncing,
                    items = items,
                    listTabs = listTabs
                )
            }.collectLatest { (sourceMode, isSyncing, items, listTabs) ->
                _uiState.update { current ->
                    val nextSelectedList = when {
                        sourceMode == LibrarySourceMode.TRAKT -> {
                            current.selectedListKey
                                ?.takeIf { key -> listTabs.any { it.key == key } }
                                ?: listTabs.firstOrNull()?.key
                        }
                        else -> null
                    }

                    val nextManageSelected = current.manageSelectedListKey
                        ?.takeIf { key ->
                            listTabs.any { tab ->
                                tab.key == key && tab.type == LibraryListTab.Type.PERSONAL
                            }
                        }
                        ?: listTabs.firstOrNull { it.type == LibraryListTab.Type.PERSONAL }?.key

                    val itemsForTypeTabs = if (sourceMode == LibrarySourceMode.TRAKT) {
                        val listKey = nextSelectedList
                        if (listKey.isNullOrBlank()) items else items.filter { it.listKeys.contains(listKey) }
                    } else {
                        items
                    }
                    val typeTabs = buildTypeTabs(itemsForTypeTabs)
                    val nextSelectedType = current.selectedTypeTab
                        ?.takeIf { selected -> typeTabs.any { it.key == selected.key } }
                        ?: LibraryTypeTab.All
                    val sortOptions = if (sourceMode == LibrarySourceMode.TRAKT) {
                        LibrarySortOption.TraktOptions
                    } else {
                        LibrarySortOption.LocalOptions
                    }
                    val nextSelectedSort = current.selectedSortOption
                        .takeIf { it in sortOptions }
                        ?: if (sourceMode == LibrarySourceMode.TRAKT) LibrarySortOption.DEFAULT else LibrarySortOption.ADDED_DESC

                    val updated = current.copy(
                        sourceMode = sourceMode,
                        allItems = items,
                        listTabs = listTabs,
                        availableTypeTabs = typeTabs,
                        availableSortOptions = sortOptions,
                        selectedTypeTab = nextSelectedType,
                        selectedListKey = nextSelectedList,
                        selectedSortOption = nextSelectedSort,
                        manageSelectedListKey = nextManageSelected,
                        isSyncing = sourceMode == LibrarySourceMode.TRAKT && isSyncing,
                        isLoading = sourceMode == LibrarySourceMode.TRAKT &&
                            isSyncing &&
                            items.isEmpty() &&
                            listTabs.isEmpty()
                    )
                    updated.withVisibleItems()
                }
            }
        }
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, cornerRadiusDp ->
                widthDp to cornerRadiusDp
            }.collectLatest { (widthDp, cornerRadiusDp) ->
                _uiState.update { current ->
                    if (current.posterCardWidthDp == widthDp &&
                        current.posterCardCornerRadiusDp == cornerRadiusDp
                    ) {
                        current
                    } else {
                        current.copy(
                            posterCardWidthDp = widthDp,
                            posterCardCornerRadiusDp = cornerRadiusDp
                        )
                    }
                }
            }
        }
    }

    private data class DataBundle(
        val sourceMode: LibrarySourceMode,
        val isSyncing: Boolean,
        val items: List<LibraryEntry>,
        val listTabs: List<LibraryListTab>
    )

    private fun reorderSelectedList(moveUp: Boolean) {
        val state = _uiState.value
        if (state.pendingOperation) return

        val personalTabs = state.listTabs.filter { it.type == LibraryListTab.Type.PERSONAL }
        val selectedKey = state.manageSelectedListKey ?: return
        val selectedIndex = personalTabs.indexOfFirst { it.key == selectedKey }
        if (selectedIndex < 0) return

        val targetIndex = if (moveUp) selectedIndex - 1 else selectedIndex + 1
        if (targetIndex !in personalTabs.indices) return

        val reordered = personalTabs.toMutableList().apply {
            add(targetIndex, removeAt(selectedIndex))
        }
        val orderedIds = reordered.mapNotNull { tab ->
            tab.traktListId?.toString() ?: tab.key.removePrefix(TraktLibraryService.PERSONAL_KEY_PREFIX)
        }

        viewModelScope.launch {
            _uiState.update { it.copy(pendingOperation = true, errorMessage = null) }
            runCatching {
                libraryRepository.reorderPersonalLists(orderedIds)
                setTransientMessage("List order updated")
            }.onSuccess {
                _uiState.update { it.copy(pendingOperation = false) }
            }.onFailure { error ->
                _uiState.update { it.copy(pendingOperation = false) }
                setError(error.message ?: "Failed to reorder lists")
            }
        }
    }

    private fun selectedManagePersonalList(): LibraryListTab? {
        val state = _uiState.value
        val selectedKey = state.manageSelectedListKey ?: return null
        return state.listTabs.firstOrNull { it.key == selectedKey && it.type == LibraryListTab.Type.PERSONAL }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, transientMessage = message) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2800)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun setTransientMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message, errorMessage = null) }
        messageClearJob?.cancel()
        messageClearJob = viewModelScope.launch {
            delay(2200)
            _uiState.update { it.copy(transientMessage = null) }
        }
    }

    private fun buildTypeTabs(items: List<LibraryEntry>): List<LibraryTypeTab> {
        val byKey = linkedMapOf<String, LibraryTypeTab>()
        items.forEach { entry ->
            val key = entry.type.trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)
            if (byKey.containsKey(key)) return@forEach
            byKey[key] = LibraryTypeTab(
                key = key,
                label = prettifyTypeLabel(key)
            )
        }
        val hasMovies = items.any { it.type.trim().lowercase(Locale.ROOT) == "movie" }
        val tabs = mutableListOf(LibraryTypeTab.All)
        tabs.addAll(byKey.values)
        if (hasMovies) {
            tabs.add(LibraryTypeTab.Collection)
        }
        return tabs
    }

    private fun prettifyTypeLabel(key: String): String {
        return key
            .replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
            .ifBlank { "Unknown" }
    }

    private fun LibraryUiState.withVisibleItems(): LibraryUiState {
        val selectedTypeKey = selectedTypeTab?.key
        val typeFiltered = allItems.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                entry.type.trim().lowercase(Locale.ROOT) == selectedTypeKey
        }

        val listFiltered = if (sourceMode == LibrarySourceMode.TRAKT) {
            val listKey = selectedListKey ?: ""
            typeFiltered.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            typeFiltered
        }

        val sorted = when (selectedSortOption) {
            LibrarySortOption.DEFAULT -> if (sourceMode == LibrarySourceMode.TRAKT) {
                listFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
            } else {
                listFiltered
            }
            LibrarySortOption.ADDED_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> listFiltered.sortedWith(
                compareBy<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> listFiltered.sortedWith(
                compareByDescending<LibraryEntry> { it.name.ifBlank { it.id }.lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
        }

        return copy(visibleItems = sorted)
    }
}
