package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverItem
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

private const val TAG = "DiscoverService"
private val API_KEY = BuildConfig.TMDB_API_KEY

data class DiscoverCategory(
    val key: String,
    val title: String,
    val items: List<MetaPreview>,
    val hasMore: Boolean = true
)

data class TrailerItem(
    val id: String,
    val tmdbId: Int,
    val title: String,
    val type: ContentType,
    val backdropUrl: String?,
    val posterUrl: String?,
    val youtubeKey: String?,
    val releaseInfo: String?,
    val rating: Float?
)

@Singleton
class DiscoverService @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    private val cache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val trailerCache = ConcurrentHashMap<String, List<TrailerItem>>()

    suspend fun getLatestTrailers(language: String = "pt-BR"): List<TrailerItem> =
        withContext(Dispatchers.IO) {
            trailerCache["trailers:$language"]?.let { return@withContext it }
            try {
                // Only upcoming movies and upcoming/trending TV for trailers
                val (movies, tvShows) = coroutineScope {
                    val moviesDeferred = async {
                        tmdbApi.getUpcomingMovies(apiKey = API_KEY, language = language).body()?.results.orEmpty()
                    }
                    val tvDeferred = async {
                        tmdbApi.getTrendingTv(apiKey = API_KEY, language = language).body()?.results.orEmpty()
                    }
                    moviesDeferred.await() to tvDeferred.await()
                }

                val semaphore = Semaphore(6)
                val items = coroutineScope {
                    val movieTrailers = movies.take(10).map { movie ->
                        async {
                            semaphore.withPermit {
                                val tmdbId = movie.id
                                val videos = runCatching {
                                    tmdbApi.getMovieVideos(tmdbId, API_KEY, language).body()?.results.orEmpty()
                                }.getOrDefault(emptyList())
                                val trailer = videos.firstOrNull {
                                    it.site == "YouTube" && it.type == "Trailer"
                                } ?: videos.firstOrNull {
                                    it.site == "YouTube" && it.type == "Teaser"
                                }
                                if (trailer?.key == null) return@async null
                                TrailerItem(
                                    id = "tmdb:$tmdbId",
                                    tmdbId = tmdbId,
                                    title = movie.title ?: movie.name ?: return@async null,
                                    type = ContentType.MOVIE,
                                    backdropUrl = movie.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
                                    posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                                    youtubeKey = trailer.key,
                                    releaseInfo = movie.releaseDate?.take(4),
                                    rating = movie.voteAverage?.toFloat()
                                )
                            }
                        }
                    }
                    val tvTrailers = tvShows.take(6).map { show ->
                        async {
                            semaphore.withPermit {
                                val tmdbId = show.id
                                val videos = runCatching {
                                    tmdbApi.getTvVideos(tmdbId, API_KEY, language).body()?.results.orEmpty()
                                }.getOrDefault(emptyList())
                                val trailer = videos.firstOrNull {
                                    it.site == "YouTube" && it.type == "Trailer"
                                } ?: videos.firstOrNull {
                                    it.site == "YouTube" && it.type == "Teaser"
                                }
                                if (trailer?.key == null) return@async null
                                TrailerItem(
                                    id = "tmdb:$tmdbId",
                                    tmdbId = tmdbId,
                                    title = show.name ?: show.title ?: return@async null,
                                    type = ContentType.SERIES,
                                    backdropUrl = show.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
                                    posterUrl = show.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                                    youtubeKey = trailer.key,
                                    releaseInfo = show.firstAirDate?.take(4),
                                    rating = show.voteAverage?.toFloat()
                                )
                            }
                        }
                    }
                    (movieTrailers + tvTrailers).awaitAll().filterNotNull()
                }
                trailerCache["trailers:$language"] = items
                items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load trailers: ${e.message}", e)
                emptyList()
            }
        }

    suspend fun getUpcoming(language: String = "pt-BR"): List<MetaPreview> =
        getCachedOrFetch("upcoming:$language") {
            val resp = tmdbApi.getUpcomingMovies(apiKey = API_KEY, language = language).body()
            resp?.results.orEmpty().mapNotNull { mapSearchResult(it, ContentType.MOVIE) }
        }

    suspend fun getBest90s(language: String = "pt-BR"): List<MetaPreview> =
        getCachedOrFetch("best90s:$language") {
            val resp = tmdbApi.discoverMovies(
                apiKey = API_KEY,
                sortBy = "vote_average.desc",
                language = language,
                primaryReleaseDateGte = "1990-01-01",
                primaryReleaseDateLte = "1999-12-31",
                voteCountGte = 500
            ).body()
            resp?.results.orEmpty().mapNotNull { mapDiscoverItem(it, ContentType.MOVIE) }
        }

    suspend fun getBest80s(language: String = "pt-BR"): List<MetaPreview> =
        getCachedOrFetch("best80s:$language") {
            val resp = tmdbApi.discoverMovies(
                apiKey = API_KEY,
                sortBy = "vote_average.desc",
                language = language,
                primaryReleaseDateGte = "1980-01-01",
                primaryReleaseDateLte = "1989-12-31",
                voteCountGte = 500
            ).body()
            resp?.results.orEmpty().mapNotNull { mapDiscoverItem(it, ContentType.MOVIE) }
        }

    // TMDB keyword IDs: werewolf=12564
    suspend fun getWerewolfMovies(language: String = "pt-BR"): List<MetaPreview> =
        getCachedOrFetch("werewolf:$language") {
            val resp = tmdbApi.discoverMovies(
                apiKey = API_KEY,
                withKeywords = "12564",
                sortBy = "vote_average.desc",
                language = language,
                voteCountGte = 50
            ).body()
            resp?.results.orEmpty().mapNotNull { mapDiscoverItem(it, ContentType.MOVIE) }
        }

    // TMDB keyword IDs: vampire=3133
    suspend fun getVampireMovies(language: String = "pt-BR"): List<MetaPreview> =
        getCachedOrFetch("vampire:$language") {
            val resp = tmdbApi.discoverMovies(
                apiKey = API_KEY,
                withKeywords = "3133",
                sortBy = "vote_average.desc",
                language = language,
                voteCountGte = 50
            ).body()
            resp?.results.orEmpty().mapNotNull { mapDiscoverItem(it, ContentType.MOVIE) }
        }

    // Oscar Best Picture winners — curated TMDB IDs (most recent first)
    suspend fun getOscarWinners(language: String = "pt-BR"): List<MetaPreview> =
        getCachedOrFetch("oscar:$language") {
            val oscarTmdbIds = listOf(
                1064028, // Anora (2025)
                872585,  // Oppenheimer (2024)
                545611,  // Everything Everywhere All at Once (2023)
                776503,  // CODA (2022)
                581734,  // Nomadland (2021)
                496243,  // Parasite (2020)
                490132,  // Green Book (2019)
                399055,  // The Shape of Water (2018)
                314365,  // Moonlight (2017)
                198184,  // Spotlight (2016)
                76203,   // 12 Years a Slave (2014)
                68718,   // Argo (2013)
                74643,   // The Artist (2012)
                45269,   // The King's Speech (2011)
                12162,   // The Hurt Locker (2010)
                4922,    // Slumdog Millionaire (2009)
                1422,    // No Country for Old Men (2008)
                1640,    // Crash (2006)
                770,     // Million Dollar Baby (2005)
                122,     // The Lord of the Rings: The Return of the King (2004)
                597,     // Titanic (1998)
                424,     // Schindler's List (1994)
                13,      // Forrest Gump (1995)
                197,     // Braveheart (1996)
                409,     // The English Patient (1997)
                453,     // A Beautiful Mind (2002)
                14,      // American Beauty (2000)
                98,      // Gladiator (2001)
                73,      // American History X — not oscar, remove
                510,     // Shakespeare in Love (1999)
                274,     // The Silence of the Lambs (1992)
                769,     // GoodFellas — not best picture
                111,     // Scarface — not best picture
                550,     // Fight Club — not best picture
            ).distinct()
            // Only fetch the actual Best Picture winners (remove non-winners)
            val validOscarIds = listOf(
                1064028, 872585, 545611, 776503, 581734, 496243, 490132, 399055, 314365,
                198184, 76203, 68718, 74643, 45269, 12162, 4922, 1422, 1640, 770, 122,
                597, 424, 13, 197, 409, 453, 14, 98, 510, 274
            )
            val semaphore = Semaphore(6)
            coroutineScope {
                validOscarIds.map { tmdbId ->
                    async {
                        semaphore.withPermit {
                            try {
                                val resp = tmdbApi.getMovieDetails(tmdbId, API_KEY, language).body()
                                    ?: return@withPermit null
                                val title = resp.title ?: resp.name ?: return@withPermit null
                                val poster = resp.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                                    ?: return@withPermit null
                                MetaPreview(
                                    id = "tmdb:$tmdbId",
                                    type = ContentType.MOVIE,
                                    name = title,
                                    poster = poster,
                                    posterShape = PosterShape.POSTER,
                                    background = resp.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
                                    logo = null,
                                    description = resp.overview?.takeIf { it.isNotBlank() },
                                    releaseInfo = resp.releaseDate?.take(4),
                                    imdbRating = resp.voteAverage?.toFloat(),
                                    genres = emptyList()
                                )
                            } catch (_: Exception) { null }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    // Multi-page loaders for "See All" screens
    suspend fun getAllWerewolfMovies(language: String = "pt-BR"): List<MetaPreview> =
        fetchAllPages("werewolf_all:$language") { page ->
            tmdbApi.discoverMovies(
                apiKey = API_KEY, withKeywords = "12564", sortBy = "vote_average.desc",
                language = language, page = page, voteCountGte = 50
            ).body()
        }

    suspend fun getAllVampireMovies(language: String = "pt-BR"): List<MetaPreview> =
        fetchAllPages("vampire_all:$language") { page ->
            tmdbApi.discoverMovies(
                apiKey = API_KEY, withKeywords = "3133", sortBy = "vote_average.desc",
                language = language, page = page, voteCountGte = 50
            ).body()
        }

    // Oscar already returns all winners from curated list — reuse
    suspend fun getAllOscarWinners(language: String = "pt-BR"): List<MetaPreview> =
        getOscarWinners(language)

    suspend fun getAllBest90s(language: String = "pt-BR"): List<MetaPreview> =
        fetchAllPages("best90s_all:$language") { page ->
            tmdbApi.discoverMovies(
                apiKey = API_KEY, sortBy = "vote_average.desc", language = language,
                primaryReleaseDateGte = "1990-01-01", primaryReleaseDateLte = "1999-12-31",
                voteCountGte = 500, page = page
            ).body()
        }

    suspend fun getAllBest80s(language: String = "pt-BR"): List<MetaPreview> =
        fetchAllPages("best80s_all:$language") { page ->
            tmdbApi.discoverMovies(
                apiKey = API_KEY, sortBy = "vote_average.desc", language = language,
                primaryReleaseDateGte = "1980-01-01", primaryReleaseDateLte = "1989-12-31",
                voteCountGte = 500, page = page
            ).body()
        }

    private suspend fun fetchAllPages(
        cacheKey: String,
        maxPages: Int = 5,
        fetcher: suspend (page: Int) -> com.nuvio.tv.data.remote.api.TmdbDiscoverResponse?
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        cache[cacheKey]?.let { return@withContext it }
        try {
            val allItems = mutableListOf<MetaPreview>()
            for (page in 1..maxPages) {
                val resp = fetcher(page) ?: break
                val items = resp.results.orEmpty().mapNotNull { mapDiscoverItem(it, ContentType.MOVIE) }
                allItems.addAll(items)
                if ((resp.page) >= (resp.totalPages ?: 1)) break
            }
            if (allItems.isNotEmpty()) cache[cacheKey] = allItems
            allItems
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch all pages for $cacheKey: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun enrichWithImdbIds(items: List<MetaPreview>, type: ContentType): List<MetaPreview> {
        val semaphore = Semaphore(6)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull() ?: return@async item
                        try {
                            val resp = when (type) {
                                ContentType.SERIES, ContentType.TV -> tmdbApi.getTvExternalIds(tmdbId, API_KEY)
                                else -> tmdbApi.getMovieExternalIds(tmdbId, API_KEY)
                            }
                            val imdbId = if (resp.isSuccessful) resp.body()?.imdbId else null
                            if (imdbId != null) item.copy(id = imdbId, imdbId = imdbId) else item
                        } catch (_: Exception) { item }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun getCachedOrFetch(
        key: String,
        fetch: suspend () -> List<MetaPreview>
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        cache[key]?.let { return@withContext it }
        try {
            val items = fetch()
            if (items.isNotEmpty()) cache[key] = items
            items
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch $key: ${e.message}", e)
            emptyList()
        }
    }

    private fun mapDiscoverItem(item: TmdbDiscoverItem, type: ContentType): MetaPreview? {
        val title = item.title ?: item.name ?: return null
        val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: return null
        return MetaPreview(
            id = "tmdb:${item.id}",
            type = type,
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = item.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            logo = null,
            description = null,
            releaseInfo = (item.releaseDate ?: item.firstAirDate)?.take(4),
            imdbRating = item.voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    private fun mapSearchResult(
        item: com.nuvio.tv.data.remote.api.TmdbSearchMultiResult,
        type: ContentType
    ): MetaPreview? {
        val title = item.title ?: item.name ?: return null
        val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: return null
        return MetaPreview(
            id = "tmdb:${item.id}",
            type = type,
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = item.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            logo = null,
            description = null,
            releaseInfo = (item.releaseDate ?: item.firstAirDate)?.take(4),
            imdbRating = item.voteAverage?.toFloat(),
            genres = emptyList()
        )
    }
}
