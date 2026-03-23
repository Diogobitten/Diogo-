package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarItemType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CalendarRepository"

@Singleton
class CalendarRepository @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val libraryRepository: LibraryRepository
) {
    suspend fun getCalendarItems(days: Int = 30, pastDays: Int = 0): List<CalendarItem> = withContext(Dispatchers.IO) {
        val allItems = mutableListOf<CalendarItem>()

        // Try Trakt personalized calendar
        try {
            val traktItems = fetchFromTrakt(days, pastDays)
            if (traktItems != null) {
                Log.d(TAG, "Trakt calendar returned ${traktItems.size} items")
                allItems.addAll(traktItems)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trakt calendar failed: ${e.message}")
        }

        // Always also check TMDB for library items (covers non-Trakt users
        // and items Trakt might miss like digital releases)
        try {
            val tmdbItems = fetchFromTmdb(days, pastDays)
            Log.d(TAG, "TMDB calendar returned ${tmdbItems.size} items")
            // Merge, avoiding duplicates by tmdbId+type
            val existingKeys = allItems.map { "${it.tmdbId}_${it.type}" }.toSet()
            tmdbItems.forEach { item ->
                val key = "${item.tmdbId}_${item.type}"
                if (key !in existingKeys) {
                    allItems.add(item)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TMDB calendar failed: ${e.message}", e)
        }

        Log.d(TAG, "Total calendar items: ${allItems.size}")
        allItems.sortedBy { it.date }
    }

    private suspend fun fetchFromTrakt(days: Int, pastDays: Int = 0): List<CalendarItem>? {
        if (!traktAuthService.hasRequiredCredentials()) return null

        val state = traktAuthService.getCurrentAuthState()
        if (state.accessToken.isNullOrBlank()) {
            Log.d(TAG, "Trakt: not authenticated, skipping")
            return null
        }

        val startDate = LocalDate.now().minusDays(pastDays.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val totalDays = days + pastDays
        Log.d(TAG, "Trakt: fetching calendar from $startDate for $totalDays days")

        val showsResponse = traktAuthService.executeAuthorizedRequest { auth ->
            traktApi.getMyCalendarShows(auth, startDate, totalDays)
        }
        val moviesResponse = traktAuthService.executeAuthorizedRequest { auth ->
            traktApi.getMyCalendarMovies(auth, startDate, totalDays)
        }

        if (showsResponse == null && moviesResponse == null) {
            Log.w(TAG, "Trakt: both calendar requests returned null (circuit open?)")
            return null
        }

        val items = mutableListOf<CalendarItem>()

        val shows = showsResponse?.body().orEmpty()
        val movies = moviesResponse?.body().orEmpty()
        Log.d(TAG, "Trakt: ${shows.size} show episodes, ${movies.size} movies")

        shows.forEach { dto ->
            val show = dto.show ?: return@forEach
            val ep = dto.episode ?: return@forEach
            val date = dto.firstAired?.take(10) ?: return@forEach
            items.add(
                CalendarItem(
                    id = "trakt_show_${show.ids?.trakt}_${ep.season}_${ep.number}",
                    title = ep.title ?: "Episode ${ep.number}",
                    type = CalendarItemType.EPISODE,
                    date = date,
                    poster = null,
                    backdrop = null,
                    tmdbId = show.ids?.tmdb,
                    traktId = show.ids?.trakt,
                    imdbId = show.ids?.imdb,
                    showName = show.title,
                    season = ep.season,
                    episode = ep.number,
                    episodeName = ep.title
                )
            )
        }

        movies.forEach { dto ->
            val movie = dto.movie ?: return@forEach
            val date = dto.released ?: return@forEach
            items.add(
                CalendarItem(
                    id = "trakt_movie_${movie.ids?.trakt}",
                    title = movie.title ?: "Unknown",
                    type = CalendarItemType.MOVIE,
                    date = date,
                    poster = null,
                    backdrop = null,
                    tmdbId = movie.ids?.tmdb,
                    traktId = movie.ids?.trakt,
                    imdbId = movie.ids?.imdb,
                    year = movie.year
                )
            )
        }

        return enrichWithTmdbImages(items)
    }

    private suspend fun fetchFromTmdb(days: Int, pastDays: Int = 0): List<CalendarItem> {
        val libraryItems = libraryRepository.libraryItems.first()
        Log.d(TAG, "TMDB: library has ${libraryItems.size} items (${libraryItems.count { it.type == "series" }} series, ${libraryItems.count { it.type == "movie" }} movies)")

        if (libraryItems.isEmpty()) return emptyList()

        val today = LocalDate.now()
        val startDate = today.minusDays(pastDays.toLong())
        val endDate = today.plusDays(days.toLong())
        val apiKey = tmdbService.apiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "TMDB: API key is blank, skipping")
            return emptyList()
        }

        val items = mutableListOf<CalendarItem>()

        coroutineScope {
            val seriesEntries = libraryItems.filter { it.type == "series" }
            val movieEntries = libraryItems.filter { it.type == "movie" }

            val seriesJobs = seriesEntries.map { entry ->
                async {
                    val tmdbId = resolveTmdbId(entry)
                    if (tmdbId == null) {
                        Log.d(TAG, "TMDB: could not resolve tmdbId for series '${entry.name}' (id=${entry.id})")
                        return@async emptyList()
                    }
                    fetchUpcomingEpisodes(entry, tmdbId, apiKey, startDate, endDate)
                }
            }

            val movieJobs = movieEntries.map { entry ->
                async {
                    val tmdbId = resolveTmdbId(entry)
                    if (tmdbId == null) {
                        Log.d(TAG, "TMDB: could not resolve tmdbId for movie '${entry.name}' (id=${entry.id})")
                        return@async emptyList()
                    }
                    fetchUpcomingMovieRelease(entry, tmdbId, apiKey, startDate, endDate)
                }
            }

            (seriesJobs + movieJobs).awaitAll().forEach { result ->
                items.addAll(result)
            }
        }

        return items.sortedBy { it.date }
    }

    /**
     * Resolves a TMDB ID for a library entry. Uses the entry's tmdbId if available,
     * otherwise tries to resolve from the entry's id (which may be an IMDB ID like tt1234567).
     */
    private suspend fun resolveTmdbId(entry: LibraryEntry): Int? {
        entry.tmdbId?.let { return it }

        val resolved = try {
            tmdbService.ensureTmdbId(entry.id, entry.type)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve tmdbId for '${entry.name}' (id=${entry.id}): ${e.message}")
            null
        }

        if (resolved != null) {
            Log.d(TAG, "Resolved tmdbId=$resolved for '${entry.name}' (id=${entry.id})")
        }
        return resolved
    }

    private suspend fun fetchUpcomingEpisodes(
        entry: LibraryEntry,
        tmdbId: Int,
        apiKey: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<CalendarItem> {
        val results = mutableListOf<CalendarItem>()
        try {
            // First get the show details to find the latest season number
            val detailsResponse = tmdbApi.getTvDetails(tmdbId, apiKey)
            val details = detailsResponse.body()
            if (details == null) {
                Log.d(TAG, "TMDB: no details for series tmdb:$tmdbId (${entry.name})")
                return emptyList()
            }

            // Check last_air_date and status to determine which seasons to check
            val status = details.status?.lowercase()
            if (status == "ended" || status == "canceled") {
                Log.d(TAG, "TMDB: series '${entry.name}' status=$status, skipping")
                return emptyList()
            }

            val latestSeason = details.numberOfSeasons ?: 1
            Log.d(TAG, "TMDB: series '${entry.name}' has $latestSeason seasons, status=$status")

            // Check latest season and the one before
            val seasonsToCheck = listOf(latestSeason, latestSeason - 1).filter { it > 0 }
            for (seasonNum in seasonsToCheck) {
                val response = tmdbApi.getTvSeasonDetails(tmdbId, seasonNum, apiKey)
                if (!response.isSuccessful) continue

                val episodes = response.body()?.episodes.orEmpty()
                for (ep in episodes) {
                    val airDateStr = ep.airDate ?: continue
                    val airDate = try {
                        LocalDate.parse(airDateStr)
                    } catch (_: Exception) { continue }

                    if (airDate in startDate..endDate) {
                        results.add(
                            CalendarItem(
                                id = "tmdb_ep_${tmdbId}_${seasonNum}_${ep.episodeNumber}",
                                title = ep.name ?: "Episode ${ep.episodeNumber}",
                                type = CalendarItemType.EPISODE,
                                date = airDateStr,
                                poster = entry.poster,
                                backdrop = ep.stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                                    ?: entry.background,
                                tmdbId = tmdbId,
                                traktId = entry.traktId,
                                imdbId = entry.imdbId,
                                showName = entry.name,
                                season = seasonNum,
                                episode = ep.episodeNumber,
                                episodeName = ep.name
                            )
                        )
                    }
                }
            }

            Log.d(TAG, "TMDB: '${entry.name}' -> ${results.size} upcoming episodes")
        } catch (e: Exception) {
            Log.w(TAG, "TMDB: failed to fetch episodes for '${entry.name}': ${e.message}")
        }
        return results
    }

    private suspend fun fetchUpcomingMovieRelease(
        entry: LibraryEntry,
        tmdbId: Int,
        apiKey: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<CalendarItem> {
        try {
            val response = tmdbApi.getMovieReleaseDates(tmdbId, apiKey)
            if (!response.isSuccessful) return emptyList()

            val countries = response.body()?.results.orEmpty()

            // Type 4 = Digital, Type 3 = Theatrical — show both upcoming
            val validTypes = setOf(3, 4, 5) // theatrical, digital, physical
            val upcomingRelease = countries
                .sortedByDescending { it.iso31661 == "US" }
                .flatMap { country ->
                    country.releaseDates.orEmpty().mapNotNull { item ->
                        if (item.type !in validTypes || item.releaseDate.isNullOrBlank()) return@mapNotNull null
                        val dateStr = item.releaseDate.take(10)
                        val date = try { LocalDate.parse(dateStr) } catch (_: Exception) { return@mapNotNull null }
                        if (date in startDate..endDate) Pair(dateStr, item.type) else null
                    }
                }
                // Prefer digital (4), then physical (5), then theatrical (3)
                .sortedBy { when (it.second) { 4 -> 0; 5 -> 1; else -> 2 } }
                .firstOrNull()

            if (upcomingRelease != null) {
                Log.d(TAG, "TMDB: movie '${entry.name}' has upcoming release on ${upcomingRelease.first} (type ${upcomingRelease.second})")
                return listOf(
                    CalendarItem(
                        id = "tmdb_movie_${tmdbId}",
                        title = entry.name,
                        type = CalendarItemType.MOVIE,
                        date = upcomingRelease.first,
                        poster = entry.poster,
                        backdrop = entry.background,
                        tmdbId = tmdbId,
                        traktId = entry.traktId,
                        imdbId = entry.imdbId,
                        year = entry.releaseInfo?.take(4)?.toIntOrNull()
                    )
                )
            } else {
                Log.d(TAG, "TMDB: movie '${entry.name}' has no upcoming releases in range ${startDate}..${endDate}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TMDB: failed to fetch release dates for '${entry.name}': ${e.message}")
        }
        return emptyList()
    }

    private suspend fun enrichWithTmdbImages(items: List<CalendarItem>): List<CalendarItem> {
        if (items.isEmpty()) return items
        val apiKey = tmdbService.apiKey()
        if (apiKey.isBlank()) return items

        return coroutineScope {
            items.map { item ->
                async {
                    if (item.poster != null) return@async item
                    val tmdbId = item.tmdbId ?: return@async item
                    try {
                        val response = when (item.type) {
                            CalendarItemType.EPISODE -> tmdbApi.getTvDetails(tmdbId, apiKey)
                            CalendarItemType.MOVIE -> tmdbApi.getMovieDetails(tmdbId, apiKey)
                        }
                        val details = response.body() ?: return@async item
                        item.copy(
                            poster = details.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                            backdrop = item.backdrop ?: details.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
                        )
                    } catch (_: Exception) {
                        item
                    }
                }
            }.awaitAll()
        }
    }
}
