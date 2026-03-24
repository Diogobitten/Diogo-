package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.AiRecommendationService
import com.nuvio.tv.data.repository.CalendarRepository
import com.nuvio.tv.data.repository.DailyTipService
import com.nuvio.tv.data.repository.TmdbDiscoveryService
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.TraktDiscoveryService
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import javax.inject.Inject

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    internal val addonRepository: AddonRepository,
    internal val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val libraryRepository: LibraryRepository,
    internal val metaRepository: MetaRepository,
    internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    internal val playerSettingsDataStore: PlayerSettingsDataStore,
    internal val tmdbSettingsDataStore: TmdbSettingsDataStore,
    internal val traktSettingsDataStore: TraktSettingsDataStore,
    internal val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val trailerService: TrailerService,
    internal val watchedItemsPreferences: WatchedItemsPreferences,
    internal val calendarRepository: CalendarRepository,
    internal val traktDiscoveryService: TraktDiscoveryService,
    internal val traktAuthService: TraktAuthService,
    internal val traktAuthDataStore: TraktAuthDataStore,
    internal val aiRecommendationService: AiRecommendationService,
    internal val dailyTipService: DailyTipService,
    internal val tmdbDiscoveryService: TmdbDiscoveryService
) : ViewModel() {
    companion object {
        internal const val TAG = "HomeViewModel"
        private const val CONTINUE_WATCHING_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_RECENT_PROGRESS_ITEMS = 300
        private const val MAX_NEXT_UP_LOOKUPS = 24
        private const val MAX_NEXT_UP_CONCURRENCY = 4
        private const val MAX_CATALOG_LOAD_CONCURRENCY = 4
        internal const val EXTERNAL_META_PREFETCH_FOCUS_DEBOUNCE_MS = 220L
        internal const val EXTERNAL_META_PREFETCH_ADJACENT_DEBOUNCE_MS = 120L
        internal const val MAX_POSTER_STATUS_OBSERVERS = 24
    }

    internal val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    val effectiveAutoplayEnabled = playerSettingsDataStore.playerSettings
        .map(StreamAutoPlayPolicy::isEffectivelyEnabled)
        .distinctUntilChanged()
    internal val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    internal val _contentTypeFilter = MutableStateFlow<String?>(null)
    val contentTypeFilter: StateFlow<String?> = _contentTypeFilter.asStateFlow()

    fun setContentTypeFilter(filter: String?) {
        if (_contentTypeFilter.value != filter) {
            _contentTypeFilter.value = filter
            scheduleUpdateCatalogRows()
        }
    }

    private val _focusState = MutableStateFlow(HomeScreenFocusState())
    val focusState: StateFlow<HomeScreenFocusState> = _focusState.asStateFlow()

    private val _gridFocusState = MutableStateFlow(HomeScreenFocusState())
    val gridFocusState: StateFlow<HomeScreenFocusState> = _gridFocusState.asStateFlow()

    internal val _loadingCatalogs = MutableStateFlow<Set<String>>(emptySet())
    val loadingCatalogs: StateFlow<Set<String>> = _loadingCatalogs.asStateFlow()

    internal val _enrichingItemId = MutableStateFlow<String?>(null)
    val enrichingItemId: StateFlow<String?> = _enrichingItemId.asStateFlow()
    internal fun setEnrichingItemId(id: String?) { _enrichingItemId.value = id }

    internal val catalogsMap = linkedMapOf<String, CatalogRow>()
    internal val catalogOrder = mutableListOf<String>()
    internal var addonsCache: List<Addon> = emptyList()
    internal var homeCatalogOrderKeys: List<String> = emptyList()
    internal var disabledHomeCatalogKeys: Set<String> = emptySet()
    internal var currentHeroCatalogKeys: List<String> = emptyList()
    internal var catalogUpdateJob: Job? = null
    internal var hasRenderedFirstCatalog = false
    internal val catalogLoadSemaphore = Semaphore(MAX_CATALOG_LOAD_CONCURRENCY)
    internal var pendingCatalogLoads = 0
    internal val activeCatalogLoadJobs = mutableSetOf<Job>()
    internal var activeCatalogLoadSignature: String? = null
    internal var catalogLoadGeneration: Long = 0L
    internal var catalogsLoadInProgress: Boolean = false
    internal data class TruncatedRowCacheEntry(
        val sourceRow: CatalogRow,
        val truncatedRow: CatalogRow
    )
    internal val truncatedRowCache = mutableMapOf<String, TruncatedRowCacheEntry>()
    internal val trailerPreviewLoadingIds = mutableSetOf<String>()
    internal val trailerPreviewNegativeCache = mutableSetOf<String>()
    internal val trailerPreviewUrlsState = mutableStateMapOf<String, String>()
    internal val trailerPreviewAudioUrlsState = mutableStateMapOf<String, String>()
    internal var activeTrailerPreviewItemId: String? = null
    internal var trailerPreviewRequestVersion: Long = 0L
    internal var currentTmdbSettings: TmdbSettings = TmdbSettings()
    internal var traktDiscoveryRows: List<CatalogRow> = emptyList()
    internal var aiRecommendationRow: CatalogRow? = null
    internal var tmdbRecentlyReleasedRow: CatalogRow? = null
    internal var heroEnrichmentJob: Job? = null
    internal var lastHeroEnrichmentSignature: String? = null
    internal var lastHeroEnrichedItems: List<MetaPreview> = emptyList()
    internal var heroItemOrder: List<String> = emptyList()
    internal val prefetchedExternalMetaIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal val externalMetaPrefetchInFlightIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var externalMetaPrefetchJob: Job? = null
    internal var pendingExternalMetaPrefetchItemId: String? = null
    internal val prefetchedTmdbIds = Collections.synchronizedSet(mutableSetOf<String>())
    internal var tmdbEnrichFocusJob: Job? = null
    internal var pendingTmdbEnrichItemId: String? = null
    internal var adjacentItemPrefetchJob: Job? = null
    internal var pendingAdjacentPrefetchItemId: String? = null
    internal val posterLibraryObserverJobs = mutableMapOf<String, Job>()
    internal val movieWatchedObserverJobs = mutableMapOf<String, Job>()
    internal var movieWatchedBatchJob: Job? = null
    internal var lastMovieWatchedItemKeys: Set<String> = emptySet()
    internal var activePosterListPickerInput: LibraryEntryInput? = null
    @Volatile
    internal var externalMetaPrefetchEnabled: Boolean = false
    @Volatile
    internal var startupGracePeriodActive: Boolean = true
    internal var startupAuthNoticeJob: Job? = null
    val trailerPreviewUrls: Map<String, String>
        get() = trailerPreviewUrlsState
    val trailerPreviewAudioUrls: Map<String, String>
        get() = trailerPreviewAudioUrlsState

    init {
        observeLayoutPreferences()
        observeExternalMetaPrefetchPreference()
        loadHomeCatalogOrderPreference()
        loadDisabledHomeCatalogPreference()
        observeLibraryState()
        observeTmdbSettings()
        observeStartupAuthNotice()
        loadContinueWatching()
        // Catalogs are the critical path — load them first, everything else deferred
        observeInstalledAddons()
        // Stagger secondary data loads so they don't compete with catalog network calls
        viewModelScope.launch {
            delay(1500)
            startupGracePeriodActive = false
        }
        viewModelScope.launch {
            // Wait until first catalogs have arrived before loading secondary content
            delay(2000)
            loadNewReleases()
            loadDailyTips()
            loadTmdbDiscovery()
            loadTraktDiscovery()
        }
        viewModelScope.launch {
            // AI recommendations are the least critical — load last
            delay(4000)
            loadAiRecommendations()
        }
    }

    private fun observeLayoutPreferences() = observeLayoutPreferencesPipeline()

    private fun observeExternalMetaPrefetchPreference() = observeExternalMetaPrefetchPreferencePipeline()

    fun requestTrailerPreview(item: MetaPreview) = requestTrailerPreviewPipeline(item)

    fun requestTrailerPreview(
        itemId: String,
        title: String,
        releaseInfo: String?,
        apiType: String
    ) = requestTrailerPreviewPipeline(
        itemId = itemId,
        title = title,
        releaseInfo = releaseInfo,
        apiType = apiType
    )

    fun onItemFocus(item: MetaPreview) = onItemFocusPipeline(item)

    fun preloadAdjacentItem(item: MetaPreview) = preloadAdjacentItemPipeline(item)

    private fun loadHomeCatalogOrderPreference() = loadHomeCatalogOrderPreferencePipeline()

    private fun loadDisabledHomeCatalogPreference() = loadDisabledHomeCatalogPreferencePipeline()

    private fun observeTmdbSettings() = observeTmdbSettingsPipeline()

    private fun observeStartupAuthNotice() {
        viewModelScope.launch {
            authSessionNoticeDataStore.pendingNotice.collect { notice ->
                if (notice == null) return@collect
                _uiState.update { state ->
                    if (state.startupAuthNotice == notice) state else state.copy(startupAuthNotice = notice)
                }
                startupAuthNoticeJob?.cancel()
                startupAuthNoticeJob = viewModelScope.launch {
                    delay(3200)
                    clearStartupAuthNotice(notice)
                }
                authSessionNoticeDataStore.consumeNotice(notice)
            }
        }
    }

    private fun clearStartupAuthNotice(notice: StartupAuthNotice) {
        _uiState.update { state ->
            if (state.startupAuthNotice == notice) {
                state.copy(startupAuthNotice = null)
            } else {
                state
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnItemClick -> navigateToDetail(event.itemId, event.itemType)
            is HomeEvent.OnLoadMoreCatalog -> loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            is HomeEvent.OnRemoveContinueWatching -> removeContinueWatching(
                contentId = event.contentId,
                season = event.season,
                episode = event.episode,
                isNextUp = event.isNextUp
            )
            HomeEvent.OnRetry -> viewModelScope.launch { loadAllCatalogs(addonsCache, forceReload = true) }
        }
    }

    private fun loadContinueWatching() = loadContinueWatchingPipeline()

    private fun loadNewReleases() {
        viewModelScope.launch {
            // Wait for library to actually have items before fetching calendar data.
            // Use a reactive approach: observe libraryItems and re-fetch when library changes.
            // Timeout after 5s if library never loads (e.g. empty library).
            val hasLibrary = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                libraryRepository.libraryItems.first { it.isNotEmpty() }
            }
            if (hasLibrary == null) {
                android.util.Log.d(TAG, "New releases: library empty after timeout, skipping")
                return@launch
            }

            // Small settle delay to let any remaining library sync finish
            delay(300)

            try {
                val items = calendarRepository.getCalendarItems(days = 14, pastDays = 7)
                val today = java.time.LocalDate.now()
                val filtered = items.filter { item ->
                    val date = try { java.time.LocalDate.parse(item.date) } catch (_: Exception) { return@filter false }
                    date == today
                }.sortedByDescending { it.date }
                android.util.Log.d(TAG, "New releases: ${filtered.size} items (from ${items.size} calendar items)")
                _uiState.update { it.copy(newReleases = filtered) }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to load new releases: ${e.message}", e)
            }
        }
    }

    private fun loadDailyTips() {
        viewModelScope.launch {
            try {
                val tips = dailyTipService.getDailyTips()
                if (tips.isNotEmpty()) {
                    android.util.Log.d(TAG, "Daily tips: ${tips.size} items")
                    _uiState.update { it.copy(dailyTips = tips) }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to load daily tips: ${e.message}", e)
            }
        }
    }

    private fun loadAiRecommendations() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(aiRecommendationsLoading = true) }
                val row = aiRecommendationService.getAiRecommendationRow()
                if (row != null) {
                    aiRecommendationRow = row
                    val key = catalogKey(
                        addonId = row.addonId,
                        type = row.apiType,
                        catalogId = row.catalogId
                    )
                    catalogsMap[key] = row
                    if (key !in catalogOrder) {
                        // Insert at the beginning so it appears first among catalog rows
                        catalogOrder.add(0, key)
                    }
                    android.util.Log.d(TAG, "AI recommendations loaded: ${row.items.size} items")
                    scheduleUpdateCatalogRows()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to load AI recommendations: ${e.message}", e)
            } finally {
                _uiState.update { it.copy(aiRecommendationsLoading = false) }
            }
        }
    }

    private fun loadTmdbDiscovery() {
        viewModelScope.launch {
            try {
                val row = tmdbDiscoveryService.getRecentlyReleasedRow()
                if (row != null) {
                    tmdbRecentlyReleasedRow = row
                    val key = catalogKey(
                        addonId = row.addonId,
                        type = row.apiType,
                        catalogId = row.catalogId
                    )
                    catalogsMap[key] = row
                    if (key !in catalogOrder) {
                        catalogOrder.add(key)
                    }
                    android.util.Log.d(TAG, "TMDB recently released loaded: ${row.items.size} items")
                    scheduleUpdateCatalogRows()
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to load TMDB discovery: ${e.message}", e)
            }
        }
    }

    private fun loadTraktDiscovery() {
        viewModelScope.launch {
            traktAuthDataStore.state
                .map { it.isAuthenticated }
                .distinctUntilChanged()
                .collectLatest { isAuthenticated ->
                    if (!isAuthenticated) {
                        if (traktDiscoveryRows.isNotEmpty()) {
                            traktDiscoveryRows.forEach { row ->
                                val key = catalogKey(
                                    addonId = row.addonId,
                                    type = row.apiType,
                                    catalogId = row.catalogId
                                )
                                catalogsMap.remove(key)
                                catalogOrder.remove(key)
                            }
                            traktDiscoveryRows = emptyList()
                            traktDiscoveryService.clearCache()
                            scheduleUpdateCatalogRows()
                        }
                        android.util.Log.d(TAG, "Trakt not authenticated, skipping discovery rows")
                        return@collectLatest
                    }

                    delay(200)

                    try {
                        val rows = traktDiscoveryService.getDiscoveryRows()
                        if (rows.isNotEmpty()) {
                            traktDiscoveryRows = rows
                            rows.forEach { row ->
                                val key = catalogKey(
                                    addonId = row.addonId,
                                    type = row.apiType,
                                    catalogId = row.catalogId
                                )
                                catalogsMap[key] = row
                                if (key !in catalogOrder) {
                                    catalogOrder.add(key)
                                }
                            }
                            android.util.Log.d(TAG, "Trakt discovery: ${rows.size} rows loaded")
                            scheduleUpdateCatalogRows()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to load Trakt discovery: ${e.message}", e)
                    }
                }
        }
    }

    private fun removeContinueWatching(
        contentId: String,
        season: Int? = null,
        episode: Int? = null,
        isNextUp: Boolean = false
    ) = removeContinueWatchingPipeline(
        contentId = contentId,
        season = season,
        episode = episode,
        isNextUp = isNextUp
    )

    private fun observeInstalledAddons() = observeInstalledAddonsPipeline()

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) =
        loadAllCatalogsPipeline(addons, forceReload)

    private fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Long) =
        loadCatalogPipeline(addon, catalog, generation)

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) =
        loadMoreCatalogItemsPipeline(catalogId, addonId, type)

    internal fun scheduleUpdateCatalogRows() {
        catalogUpdateJob?.cancel()
        catalogUpdateJob = viewModelScope.launch {
            val debounceMs = when {
                // First render: use minimal debounce to show content ASAP while still
                // batching near-simultaneous arrivals.
                !hasRenderedFirstCatalog && catalogsMap.isNotEmpty() -> {
                    hasRenderedFirstCatalog = true
                    50L
                }
                pendingCatalogLoads > 8 -> 200L
                pendingCatalogLoads > 3 -> 150L
                pendingCatalogLoads > 0 -> 100L
                else -> 50L
            }
            delay(debounceMs)
            updateCatalogRows()
        }
    }

    private suspend fun updateCatalogRows() = updateCatalogRowsPipeline()

    internal var posterStatusReconcileJob: Job? = null

    private fun schedulePosterStatusReconcile(rows: List<CatalogRow>) =
        schedulePosterStatusReconcilePipeline(rows)

    private fun reconcilePosterStatusObservers(rows: List<CatalogRow>) =
        reconcilePosterStatusObserversPipeline(rows)

    private fun navigateToDetail(itemId: String, itemType: String) {
        _uiState.update { it.copy(selectedItemId = itemId) }
    }

    private suspend fun enrichHeroItems(
        items: List<MetaPreview>,
        settings: TmdbSettings
    ): List<MetaPreview> = enrichHeroItemsPipeline(items, settings)

    private fun replaceGridHeroItems(
        gridItems: List<GridItem>,
        heroItems: List<MetaPreview>
    ): List<GridItem> = replaceGridHeroItemsPipeline(gridItems, heroItems)

    private fun heroEnrichmentSignature(items: List<MetaPreview>, settings: TmdbSettings): String =
        heroEnrichmentSignaturePipeline(items, settings)

    /**
     * Saves the current focus and scroll state for restoration when returning to this screen.
     */
    fun saveFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        catalogRowScrollStates: Map<String, Int>
    ) {
        val nextState = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex,
            catalogRowScrollStates = catalogRowScrollStates,
            hasSavedFocus = true
        )
        if (_focusState.value == nextState) return
        _focusState.value = nextState
    }

    /**
     * Clears the saved focus state.
     */
    fun clearFocusState() {
        _focusState.value = HomeScreenFocusState()
    }

    /**
     * Saves the grid layout focus and scroll state.
     */
    fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0
    ) {
        _gridFocusState.value = HomeScreenFocusState(
            verticalScrollIndex = verticalScrollIndex,
            verticalScrollOffset = verticalScrollOffset,
            focusedRowIndex = focusedRowIndex,
            focusedItemIndex = focusedItemIndex
        )
    }

    override fun onCleared() {
        startupAuthNoticeJob?.cancel()
        posterStatusReconcileJob?.cancel()
        movieWatchedBatchJob?.cancel()
        cancelInFlightCatalogLoads()
        posterLibraryObserverJobs.values.forEach { it.cancel() }
        movieWatchedObserverJobs.values.forEach { it.cancel() }
        posterLibraryObserverJobs.clear()
        movieWatchedObserverJobs.clear()
        super.onCleared()
    }
}
