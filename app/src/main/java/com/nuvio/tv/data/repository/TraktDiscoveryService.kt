package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktDiscoveryService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    companion object {
        private const val TAG = "TraktDiscoveryService"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 min
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val TRAKT_DISCOVERY_ADDON_ID = "trakt-discovery"
        private const val TRAKT_DISCOVERY_ADDON_NAME = "Trakt"
    }

    private data class CacheEntry(
        val rows: List<CatalogRow>,
        val timestamp: Long
    )

    @Volatile
    private var cache: CacheEntry? = null

    private val tmdbImageCache = ConcurrentHashMap<String, TmdbImageData?>()

    private data class TmdbImageData(
        val poster: String?,
        val backdrop: String?,
        val imdbId: String?
    )

    suspend fun getDiscoveryRows(): List<CatalogRow> = withContext(Dispatchers.IO) {
        val cached = cache
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return@withContext cached.rows
        }

        val rows = mutableListOf<CatalogRow>()

        try {
            coroutineScope {
                val trendingMovies = async { fetchTrendingMovies() }
                val trendingShows = async { fetchTrendingShows() }
                val popularMovies = async { fetchPopularMovies() }
                val popularShows = async { fetchPopularShows() }
                val anticipatedMovies = async { fetchAnticipatedMovies() }
                val anticipatedShows = async { fetchAnticipatedShows() }
                val recommendedMovies = async { fetchRecommendedMovies() }
                val recommendedShows = async { fetchRecommendedShows() }

                // Trending
                val trendingMoviesList = trendingMovies.await()
                val trendingShowsList = trendingShows.await()
                Log.d(TAG, "Trending: ${trendingMoviesList.size} movies, ${trendingShowsList.size} shows")
                val trendingItems = interleave(trendingMoviesList, trendingShowsList)
                if (trendingItems.isNotEmpty()) {
                    val enriched = enrichWithTmdbImages(trendingItems)
                    Log.d(TAG, "Trending enriched: ${enriched.size} items")
                    rows.add(buildCatalogRow("trakt-trending", "Tendências", enriched))
                }

                // Recommended (auth required)
                val recommendedMoviesList = recommendedMovies.await()
                val recommendedShowsList = recommendedShows.await()
                Log.d(TAG, "Recommended: ${recommendedMoviesList.size} movies, ${recommendedShowsList.size} shows")
                val recommendedItems = interleave(recommendedMoviesList, recommendedShowsList)
                if (recommendedItems.isNotEmpty()) {
                    val enriched = enrichWithTmdbImages(recommendedItems)
                    Log.d(TAG, "Recommended enriched: ${enriched.size} items")
                    rows.add(buildCatalogRow("trakt-recommended", "Recomendados", enriched))
                }

                // Anticipated
                val anticipatedMoviesList = anticipatedMovies.await()
                val anticipatedShowsList = anticipatedShows.await()
                Log.d(TAG, "Anticipated: ${anticipatedMoviesList.size} movies, ${anticipatedShowsList.size} shows")
                val anticipatedItems = interleave(anticipatedMoviesList, anticipatedShowsList)
                if (anticipatedItems.isNotEmpty()) {
                    val enriched = enrichWithTmdbImages(anticipatedItems)
                    Log.d(TAG, "Anticipated enriched: ${enriched.size} items")
                    rows.add(buildCatalogRow("trakt-anticipated", "Mais Esperados", enriched))
                }

                // Popular
                val popularMoviesList = popularMovies.await()
                val popularShowsList = popularShows.await()
                Log.d(TAG, "Popular: ${popularMoviesList.size} movies, ${popularShowsList.size} shows")
                val popularItems = interleave(popularMoviesList, popularShowsList)
                if (popularItems.isNotEmpty()) {
                    val enriched = enrichWithTmdbImages(popularItems)
                    Log.d(TAG, "Popular enriched: ${enriched.size} items")
                    rows.add(buildCatalogRow("trakt-popular", "Populares", enriched))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch discovery rows: ${e.message}", e)
        }

        cache = CacheEntry(rows, System.currentTimeMillis())
        rows
    }

    fun clearCache() {
        cache = null
        tmdbImageCache.clear()
    }

    private suspend fun fetchTrendingMovies(): List<MetaPreview> {
        return try {
            val response = traktApi.getTrendingMovies(limit = 20)
            if (response.isSuccessful) {
                response.body()?.mapNotNull { dto ->
                    dto.movie?.toMetaPreview(ContentType.MOVIE)
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchTrendingMovies failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchTrendingShows(): List<MetaPreview> {
        return try {
            val response = traktApi.getTrendingShows(limit = 20)
            if (response.isSuccessful) {
                response.body()?.mapNotNull { dto ->
                    dto.show?.toMetaPreview(ContentType.SERIES)
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchTrendingShows failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchPopularMovies(): List<MetaPreview> {
        return try {
            val response = traktApi.getPopularMovies(limit = 20)
            if (response.isSuccessful) {
                response.body()?.mapNotNull { it.toMetaPreview(ContentType.MOVIE) } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchPopularMovies failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchPopularShows(): List<MetaPreview> {
        return try {
            val response = traktApi.getPopularShows(limit = 20)
            if (response.isSuccessful) {
                response.body()?.mapNotNull { it.toMetaPreview(ContentType.SERIES) } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchPopularShows failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchAnticipatedMovies(): List<MetaPreview> {
        return try {
            val response = traktApi.getAnticipatedMovies(limit = 20)
            if (response.isSuccessful) {
                response.body()?.mapNotNull { dto ->
                    dto.movie?.toMetaPreview(ContentType.MOVIE)
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchAnticipatedMovies failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchAnticipatedShows(): List<MetaPreview> {
        return try {
            val response = traktApi.getAnticipatedShows(limit = 20)
            if (response.isSuccessful) {
                response.body()?.mapNotNull { dto ->
                    dto.show?.toMetaPreview(ContentType.SERIES)
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchAnticipatedShows failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchRecommendedMovies(): List<MetaPreview> {
        return try {
            val response = traktAuthService.executeAuthorizedRequest { auth ->
                traktApi.getRecommendedMovies(authorization = auth, limit = 20)
            } ?: return emptyList()
            if (response.isSuccessful) {
                response.body()?.mapNotNull { it.toMetaPreview(ContentType.MOVIE) } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchRecommendedMovies failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchRecommendedShows(): List<MetaPreview> {
        return try {
            val response = traktAuthService.executeAuthorizedRequest { auth ->
                traktApi.getRecommendedShows(authorization = auth, limit = 20)
            } ?: return emptyList()
            if (response.isSuccessful) {
                response.body()?.mapNotNull { it.toMetaPreview(ContentType.SERIES) } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchRecommendedShows failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun enrichWithTmdbImages(items: List<MetaPreview>): List<MetaPreview> {
        val semaphore = Semaphore(6)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        enrichSingleItem(item)
                    }
                }
            }.awaitAll()
        }.filter { it.poster != null || it.background != null }
    }

    private suspend fun enrichSingleItem(item: MetaPreview): MetaPreview {
        val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull() ?: return item
        val mediaType = if (item.type == ContentType.MOVIE) "movie" else "tv"
        val cacheKey = "${mediaType}_$tmdbId"

        val cached = tmdbImageCache[cacheKey]
        if (cached != null) {
            return item.copy(
                id = cached.imdbId ?: item.id,
                poster = cached.poster?.let { "${TMDB_IMAGE_BASE}w500$it" },
                background = cached.backdrop?.let { "${TMDB_IMAGE_BASE}original$it" },
                imdbId = cached.imdbId
            )
        }

        return try {
            val detailsResponse = when (mediaType) {
                "movie" -> tmdbApi.getMovieDetails(tmdbId, tmdbService.apiKey())
                else -> tmdbApi.getTvDetails(tmdbId, tmdbService.apiKey())
            }

            if (!detailsResponse.isSuccessful) return item

            val details = detailsResponse.body() ?: return item

            // Get IMDB ID for navigation
            val imdbId = try {
                val extResponse = when (mediaType) {
                    "movie" -> tmdbApi.getMovieExternalIds(tmdbId, tmdbService.apiKey())
                    else -> tmdbApi.getTvExternalIds(tmdbId, tmdbService.apiKey())
                }
                if (extResponse.isSuccessful) extResponse.body()?.imdbId else null
            } catch (_: Exception) { null }

            val imageData = TmdbImageData(
                poster = details.posterPath,
                backdrop = details.backdropPath,
                imdbId = imdbId
            )
            tmdbImageCache[cacheKey] = imageData

            // Pre-cache the TMDB<->IMDB mapping
            if (imdbId != null) {
                tmdbService.preCacheMapping(imdbId, tmdbId)
            }

            item.copy(
                id = imdbId ?: item.id,
                name = (if (mediaType == "movie") details.title else details.name) ?: item.name,
                poster = details.posterPath?.let { "${TMDB_IMAGE_BASE}w500$it" },
                background = details.backdropPath?.let { "${TMDB_IMAGE_BASE}original$it" },
                releaseInfo = (details.releaseDate ?: details.firstAirDate)
                    ?.take(4)?.takeIf { it.length == 4 },
                imdbId = imdbId
            )
        } catch (e: Exception) {
            Log.w(TAG, "TMDB enrich failed for $tmdbId: ${e.message}")
            item
        }
    }

    private fun TraktMovieDto.toMetaPreview(type: ContentType): MetaPreview? {
        val tmdbId = ids?.tmdb ?: return null
        return MetaPreview(
            id = "tmdb:$tmdbId",
            type = type,
            name = title ?: return null,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = year?.toString(),
            imdbRating = null,
            genres = emptyList(),
            imdbId = ids.imdb
        )
    }

    private fun TraktShowDto.toMetaPreview(type: ContentType): MetaPreview? {
        val tmdbId = ids?.tmdb ?: return null
        return MetaPreview(
            id = "tmdb:$tmdbId",
            type = type,
            name = title ?: return null,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = year?.toString(),
            imdbRating = null,
            genres = emptyList(),
            imdbId = ids.imdb
        )
    }

    private fun buildCatalogRow(
        catalogId: String,
        catalogName: String,
        items: List<MetaPreview>
    ): CatalogRow {
        return CatalogRow(
            addonId = TRAKT_DISCOVERY_ADDON_ID,
            addonName = TRAKT_DISCOVERY_ADDON_NAME,
            addonBaseUrl = "https://trakt.tv",
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.MOVIE, // mixed content — not filtered by content type
            rawType = "mixed",
            items = items,
            isLoading = false,
            hasMore = false,
            currentPage = 0,
            supportsSkip = false
        )
    }

    private fun interleave(a: List<MetaPreview>, b: List<MetaPreview>): List<MetaPreview> {
        val result = mutableListOf<MetaPreview>()
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            if (i < a.size) result.add(a[i])
            if (i < b.size) result.add(b[i])
        }
        return result
    }
}
