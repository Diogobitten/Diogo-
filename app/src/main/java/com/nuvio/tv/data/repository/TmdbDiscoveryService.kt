package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbDiscoveryService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    companion object {
        private const val TAG = "TmdbDiscoveryService"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val ADDON_ID = "tmdb-discovery"
        private const val ADDON_NAME = "TMDB"
    }

    private data class CacheEntry(
        val row: CatalogRow,
        val timestamp: Long
    )

    @Volatile
    private var recentlyReleasedCache: CacheEntry? = null

    fun clearCache() {
        recentlyReleasedCache = null
    }

    suspend fun getRecentlyReleasedRow(): CatalogRow? = withContext(Dispatchers.IO) {
        val cached = recentlyReleasedCache
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return@withContext cached.row
        }

        try {
            val apiKey = tmdbService.apiKey()
            // Fetch 2 pages for more variety
            val items = coroutineScope {
                val page1 = async { fetchNowPlaying(apiKey, 1) }
                val page2 = async { fetchNowPlaying(apiKey, 2) }
                (page1.await() + page2.await()).distinctBy { it.id }
            }

            if (items.isEmpty()) return@withContext null

            val previews = items
                .filter { it.posterPath != null && it.title != null }
                .map { movie ->
                    MetaPreview(
                        id = "tmdb:${movie.id}",
                        type = ContentType.MOVIE,
                        name = movie.title ?: "",
                        poster = movie.posterPath?.let { "${TMDB_IMAGE_BASE}w500$it" },
                        posterShape = PosterShape.POSTER,
                        background = movie.backdropPath?.let { "${TMDB_IMAGE_BASE}original$it" },
                        logo = null,
                        description = movie.overview,
                        releaseInfo = movie.releaseDate?.take(4),
                        imdbRating = movie.voteAverage?.toFloat(),
                        genres = emptyList()
                    )
                }

            if (previews.isEmpty()) return@withContext null

            // Enrich with IMDB IDs for navigation
            val enriched = enrichWithImdbIds(previews, apiKey)

            val row = CatalogRow(
                addonId = ADDON_ID,
                addonName = ADDON_NAME,
                addonBaseUrl = "https://www.themoviedb.org",
                catalogId = "tmdb-recently-released",
                catalogName = "Lançamentos Recentes",
                type = ContentType.MOVIE,
                rawType = "movie",
                items = enriched,
                isLoading = false,
                hasMore = false,
                currentPage = 0,
                supportsSkip = false
            )

            recentlyReleasedCache = CacheEntry(row, System.currentTimeMillis())
            row
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recently released movies: ${e.message}", e)
            null
        }
    }

    private suspend fun fetchNowPlaying(apiKey: String, page: Int): List<com.nuvio.tv.data.remote.api.TmdbSearchMultiResult> {
        return try {
            val resp = tmdbApi.getNowPlayingMovies(apiKey = apiKey, page = page)
            if (resp.isSuccessful) resp.body()?.results ?: emptyList() else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchNowPlaying page $page failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun enrichWithImdbIds(items: List<MetaPreview>, apiKey: String): List<MetaPreview> {
        val semaphore = Semaphore(6)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull() ?: return@async item
                        try {
                            val extResp = tmdbApi.getMovieExternalIds(tmdbId, apiKey)
                            val imdbId = if (extResp.isSuccessful) extResp.body()?.imdbId else null
                            if (imdbId != null) {
                                tmdbService.preCacheMapping(imdbId, tmdbId)
                                item.copy(id = imdbId, imdbId = imdbId)
                            } else item
                        } catch (_: Exception) { item }
                    }
                }
            }.awaitAll()
        }
    }
}
