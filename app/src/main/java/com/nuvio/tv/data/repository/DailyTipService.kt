package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailyTipService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) {
    companion object {
        private const val TAG = "DailyTipService"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
    }

    private data class CacheEntry(
        val items: List<MetaPreview>,
        val date: LocalDate,
        val timestampMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var cache: CacheEntry? = null

    suspend fun getDailyTips(): List<MetaPreview> = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val cached = cache
        if (cached != null && cached.date == today) {
            return@withContext cached.items
        }

        // Date changed — clear stale cache and fetch fresh tips
        if (cached != null && cached.date != today) {
            Log.d(TAG, "Day changed (${cached.date} → $today), refreshing daily tips")
            cache = null
        }

        try {
            val items = fetchDailyMovies(today)
            if (items.isNotEmpty()) {
                cache = CacheEntry(items, today)
            }
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch daily tips: ${e.message}", e)
            emptyList()
        }
    }

    fun clearCache() {
        cache = null
    }

    private suspend fun fetchDailyMovies(today: LocalDate): List<MetaPreview> = coroutineScope {
        val apiKey = tmdbService.apiKey()

        // Fetch trending and now playing in parallel, pick 3 deterministic movies based on day
        val trendingJob = async {
            try {
                val resp = tmdbApi.getTrendingMovies(apiKey = apiKey)
                if (resp.isSuccessful) resp.body()?.results ?: emptyList() else emptyList()
            } catch (_: Exception) { emptyList() }
        }
        val nowPlayingJob = async {
            try {
                val resp = tmdbApi.getNowPlayingMovies(apiKey = apiKey)
                if (resp.isSuccessful) resp.body()?.results ?: emptyList() else emptyList()
            } catch (_: Exception) { emptyList() }
        }

        val trending = trendingJob.await()
        val nowPlaying = nowPlayingJob.await()

        // Merge and deduplicate by ID
        val allMovies = (trending + nowPlaying)
            .distinctBy { it.id }
            .filter { it.posterPath != null && it.title != null }

        if (allMovies.isEmpty()) return@coroutineScope emptyList()

        // Use day-of-year as seed for deterministic but daily-changing selection
        val seed = today.dayOfYear + today.year * 366
        val shuffled = allMovies.shuffled(java.util.Random(seed.toLong()))
        val selected = shuffled.take(3)

        // Enrich with IMDB IDs
        selected.map { movie ->
            async {
                val tmdbId = movie.id
                val imdbId = try {
                    val extResp = tmdbApi.getMovieExternalIds(tmdbId, apiKey)
                    if (extResp.isSuccessful) extResp.body()?.imdbId else null
                } catch (_: Exception) { null }

                if (imdbId != null) {
                    tmdbService.preCacheMapping(imdbId, tmdbId)
                }

                MetaPreview(
                    id = imdbId ?: "tmdb:$tmdbId",
                    type = ContentType.MOVIE,
                    name = movie.title ?: "",
                    poster = movie.posterPath?.let { "${TMDB_IMAGE_BASE}w500$it" },
                    posterShape = PosterShape.POSTER,
                    background = movie.backdropPath?.let { "${TMDB_IMAGE_BASE}original$it" },
                    logo = null,
                    description = movie.overview,
                    releaseInfo = movie.releaseDate?.take(4),
                    imdbRating = movie.voteAverage?.toFloat(),
                    genres = emptyList(),
                    imdbId = imdbId
                )
            }
        }.mapNotNull { it.await() }
    }
}
