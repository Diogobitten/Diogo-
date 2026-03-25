package com.nuvio.tv.core.tmdb

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverItem
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.data.remote.api.TmdbImage
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCrew
import com.nuvio.tv.data.remote.api.TmdbRecommendationResult
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaCompany
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TmdbMetadataService"
private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY

@Singleton
class TmdbMetadataService @Inject constructor(
    private val tmdbApi: TmdbApi
) {
    // In-memory caches
    private val enrichmentCache = ConcurrentHashMap<String, TmdbEnrichment>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, TmdbEpisodeEnrichment>>()
    private val personCache = ConcurrentHashMap<String, PersonDetail>()
    private val moreLikeThisCache = ConcurrentHashMap<String, List<MetaPreview>>()
    private val entityHeaderCache = ConcurrentHashMap<String, TmdbEntityHeader>()
    private val entityBrowseCache = ConcurrentHashMap<String, TmdbEntityBrowseData>()
    /**
     * Returns a cached enrichment if available, without triggering a network fetch.
     */
    fun getCachedEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbEnrichment? {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage"
        return enrichmentCache[cacheKey]
    }

    suspend fun fetchEnrichment(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en"
    ): TmdbEnrichment? =
        withContext(Dispatchers.IO) {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage"
            enrichmentCache[cacheKey]?.let { return@withContext it }

            val numericId = tmdbId.toIntOrNull() ?: return@withContext null
            val tmdbType = when (contentType) {
                ContentType.SERIES, ContentType.TV -> "tv"
                else -> "movie"
            }

            try {
                val includeImageLanguage = buildString {
                    append(normalizedLanguage.substringBefore("-"))
                    append(",")
                    append(normalizedLanguage)
                    append(",en,null")
                }

                // Fetch details, credits, and images in parallel
                val (details, credits, images, ageRating) = coroutineScope {
                    val detailsDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvDetails(numericId, TMDB_API_KEY, normalizedLanguage)
                            else -> tmdbApi.getMovieDetails(numericId, TMDB_API_KEY, normalizedLanguage)
                        }.body()
                    }
                    val creditsDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvCredits(numericId, TMDB_API_KEY, normalizedLanguage)
                            else -> tmdbApi.getMovieCredits(numericId, TMDB_API_KEY, normalizedLanguage)
                        }.body()
                    }
                    val imagesDeferred = async {
                        when (tmdbType) {
                            "tv" -> tmdbApi.getTvImages(numericId, TMDB_API_KEY, includeImageLanguage)
                            else -> tmdbApi.getMovieImages(numericId, TMDB_API_KEY, includeImageLanguage)
                        }.body()
                    }
                    val ageRatingDeferred = async {
                        when (tmdbType) {
                            "tv" -> {
                                val ratings = tmdbApi.getTvContentRatings(numericId, TMDB_API_KEY).body()?.results.orEmpty()
                                selectTvAgeRating(ratings, normalizedLanguage)
                            }
                            else -> {
                                val releases = tmdbApi.getMovieReleaseDates(numericId, TMDB_API_KEY).body()?.results.orEmpty()
                                selectMovieAgeRating(releases, normalizedLanguage)
                            }
                        }
                    }
                    Quadruple(
                        detailsDeferred.await(),
                        creditsDeferred.await(),
                        imagesDeferred.await(),
                        ageRatingDeferred.await()
                    )
                }

                val genres = details?.genres?.mapNotNull { genre ->
                    genre.name.trim().takeIf { name -> name.isNotBlank() }
                } ?: emptyList()
                val description = details?.overview?.takeIf { it.isNotBlank() }
                val releaseInfo = details?.releaseDate
                    ?: details?.firstAirDate
                val status = details?.status?.trim()?.takeIf { it.isNotBlank() }
                val rating = details?.voteAverage
                val runtime = details?.runtime ?: details?.episodeRunTime?.firstOrNull()
                val countries = details?.productionCountries
                    ?.mapNotNull { it.iso31661?.trim()?.uppercase()?.takeIf { code -> code.isNotBlank() } }
                    ?.takeIf { it.isNotEmpty() }
                    ?: details?.originCountry?.takeIf { it.isNotEmpty() }
                val language = details?.originalLanguage?.takeIf { it.isNotBlank() }
                val localizedTitle = (details?.title ?: details?.name)?.takeIf { it.isNotBlank() }
                val productionCompanies = details?.productionCompanies
                    .orEmpty()
                    .mapNotNull { company ->
                        val name = company.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCompany(
                            name = name,
                            logo = buildImageUrl(company.logoPath, size = "w300"),
                            tmdbId = company.id
                        )
                    }
                val networks = details?.networks
                    .orEmpty()
                    .mapNotNull { network ->
                        val name = network.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCompany(
                            name = name,
                            logo = buildImageUrl(network.logoPath, size = "w300"),
                            tmdbId = network.id
                        )
                    }
                val poster = buildImageUrl(details?.posterPath, size = "w500")
                val backdrop = buildImageUrl(details?.backdropPath, size = "original")
                
                val collectionId = details?.belongsToCollection?.id
                val collectionName = details?.belongsToCollection?.name

                val logoPath = images?.logos
                    ?.sortedWith(
                        compareByDescending<com.nuvio.tv.data.remote.api.TmdbImage> {
                            it.iso6391 == normalizedLanguage.substringBefore("-")
                        }
                            .thenByDescending { it.iso6391 == "en" }
                            .thenByDescending { it.iso6391 == null }
                    )
                    ?.firstOrNull()
                    ?.filePath

                val logo = buildImageUrl(logoPath, size = "w500")

                val castMembers = credits?.cast
                    .orEmpty()
                    .mapNotNull { member ->
                        val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = member.character?.takeIf { it.isNotBlank() },
                            photo = buildImageUrl(member.profilePath, size = "w500"),
                            tmdbId = member.id
                        )
                    }

                val creatorMembers = if (tmdbType == "tv") {
                    details?.createdBy
                        .orEmpty()
                        .mapNotNull { creator ->
                            val tmdbPersonId = creator.id ?: return@mapNotNull null
                            val name = creator.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            MetaCastMember(
                                name = name,
                                character = "Creator",
                                photo = buildImageUrl(creator.profilePath, size = "w500"),
                                tmdbId = tmdbPersonId
                            )
                        }
                        .distinctBy { it.tmdbId ?: it.name.lowercase() }
                } else {
                    emptyList()
                }

                val creator = if (tmdbType == "tv") {
                    details?.createdBy
                        .orEmpty()
                        .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }
                } else {
                    emptyList()
                }

                val directorCrew = credits?.crew
                    .orEmpty()
                    .filter { it.job.equals("Director", ignoreCase = true) }

                val directorMembers = directorCrew
                    .mapNotNull { member ->
                        val tmdbPersonId = member.id ?: return@mapNotNull null
                        val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Director",
                            photo = buildImageUrl(member.profilePath, size = "w500"),
                            tmdbId = tmdbPersonId
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase() }

                val director = directorCrew
                    .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

                val writerCrew = credits?.crew
                    .orEmpty()
                    .filter { crew ->
                        val job = crew.job?.lowercase() ?: ""
                        job.contains("writer") || job.contains("screenplay")
                    }

                val writerMembers = writerCrew
                    .mapNotNull { member ->
                        val tmdbPersonId = member.id ?: return@mapNotNull null
                        val name = member.name?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = "Writer",
                            photo = buildImageUrl(member.profilePath, size = "w500"),
                            tmdbId = tmdbPersonId
                        )
                    }
                    .distinctBy { it.tmdbId ?: it.name.lowercase() }

                val writer = writerCrew
                    .mapNotNull { it.name?.trim()?.takeIf { name -> name.isNotBlank() } }

                // Only expose either Director or Writer people (prefer Director).
                val hasCreator = creatorMembers.isNotEmpty() || creator.isNotEmpty()
                val hasDirector = directorMembers.isNotEmpty() || director.isNotEmpty()

                val exposedDirectorMembers = when {
                    tmdbType == "tv" && hasCreator -> creatorMembers
                    tmdbType != "tv" && hasDirector -> directorMembers
                    else -> emptyList()
                }
                val exposedWriterMembers = when {
                    tmdbType == "tv" && hasCreator -> emptyList()
                    tmdbType != "tv" && hasDirector -> emptyList()
                    else -> writerMembers
                }

                val exposedDirector = when {
                    tmdbType == "tv" && hasCreator -> creator
                    tmdbType != "tv" && hasDirector -> director
                    else -> emptyList()
                }
                val exposedWriter = when {
                    tmdbType == "tv" && hasCreator -> emptyList()
                    tmdbType != "tv" && hasDirector -> emptyList()
                    else -> writer
                }

                if (
                    genres.isEmpty() && description == null && backdrop == null && logo == null &&
                    poster == null && castMembers.isEmpty() && director.isEmpty() && writer.isEmpty() &&
                    releaseInfo == null && rating == null && runtime == null && countries.isNullOrEmpty() && language == null &&
                    productionCompanies.isEmpty() && networks.isEmpty() && ageRating == null && status == null
                ) {
                    return@withContext null
                }

                val enrichment = TmdbEnrichment(
                    localizedTitle = localizedTitle,
                    description = description,
                    genres = genres,
                    backdrop = backdrop,
                    logo = logo,
                    poster = poster,
                    directorMembers = exposedDirectorMembers,
                    writerMembers = exposedWriterMembers,
                    castMembers = castMembers,
                    releaseInfo = releaseInfo,
                    rating = rating,
                    runtimeMinutes = runtime,
                    director = exposedDirector,
                    writer = exposedWriter,
                    productionCompanies = productionCompanies,
                    networks = networks,
                    ageRating = ageRating,
                    status = status,
                    countries = countries,
                    language = language,
                    collectionId = collectionId,
                    collectionName = collectionName
                )
                enrichmentCache[cacheKey] = enrichment
                enrichment
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch TMDB enrichment: ${e.message}", e)
                null
            }
        }

    suspend fun fetchEpisodeEnrichment(
        tmdbId: String,
        seasonNumbers: List<Int>,
        language: String = "en"
    ): Map<Pair<Int, Int>, TmdbEpisodeEnrichment> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${seasonNumbers.sorted().joinToString(",")}:$normalizedLanguage"
        episodeCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyMap()
        val result = mutableMapOf<Pair<Int, Int>, TmdbEpisodeEnrichment>()

        seasonNumbers.distinct().forEach { season ->
            try {
                val response = tmdbApi.getTvSeasonDetails(numericId, season, TMDB_API_KEY, normalizedLanguage)
                val episodes = response.body()?.episodes.orEmpty()
                episodes.forEach { ep ->
                    val epNum = ep.episodeNumber ?: return@forEach
                    result[season to epNum] = ep.toEnrichment()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch TMDB season $season: ${e.message}")
            }
        }

        if (result.isNotEmpty()) {
            episodeCache[cacheKey] = result
        }
        result
    }

    suspend fun fetchMoreLikeThis(
        tmdbId: String,
        contentType: ContentType,
        language: String = "en",
        maxItems: Int = 12
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$tmdbId:${contentType.name}:$normalizedLanguage:more_like"
        moreLikeThisCache[cacheKey]?.let { return@withContext it }

        val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
        val tmdbType = when (contentType) {
            ContentType.SERIES, ContentType.TV -> "tv"
            else -> "movie"
        }

        val includeImageLanguage = buildString {
            append(normalizedLanguage.substringBefore("-"))
            append(",")
            append(normalizedLanguage)
            append(",en,null")
        }

        try {
            val recommendations = when (tmdbType) {
                "tv" -> tmdbApi.getTvRecommendations(numericId, TMDB_API_KEY, normalizedLanguage).body()
                else -> tmdbApi.getMovieRecommendations(numericId, TMDB_API_KEY, normalizedLanguage).body()
            }

            val rawResults = recommendations?.results
                .orEmpty()
                .filter { it.id > 0 }
            val languageCode = normalizedLanguage.substringBefore("-")
            val sortedResults = rawResults
                .sortedWith(
                    compareByDescending<TmdbRecommendationResult> {
                        it.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                    }
                        .thenByDescending { it.voteCount ?: 0 }
                        .thenByDescending { it.voteAverage ?: 0.0 }
                )
            val qualityFilteredResults = sortedResults.filter { rec ->
                val voteCount = rec.voteCount ?: 0
                val voteAverage = rec.voteAverage ?: 0.0
                val localized = rec.originalLanguage?.equals(languageCode, ignoreCase = true) == true
                localized || voteCount >= 20 || voteAverage >= 6.0
            }
            val recommendationResults = (if (qualityFilteredResults.isNotEmpty()) {
                qualityFilteredResults
            } else {
                sortedResults
            }).take(maxItems.coerceAtLeast(1))

            val items = coroutineScope {
                recommendationResults.map { rec ->
                    async {
                        val recTmdbType = when (rec.mediaType?.trim()?.lowercase()) {
                            "tv" -> "tv"
                            "movie" -> "movie"
                            else -> tmdbType
                        }
                        val recContentType = if (recTmdbType == "tv") ContentType.SERIES else ContentType.MOVIE
                        val title = rec.title?.takeIf { it.isNotBlank() }
                            ?: rec.name?.takeIf { it.isNotBlank() }
                            ?: rec.originalTitle?.takeIf { it.isNotBlank() }
                            ?: rec.originalName?.takeIf { it.isNotBlank() }
                            ?: return@async null

                        val localizedBackdropPath = runCatching {
                            when (recTmdbType) {
                                "tv" -> tmdbApi.getTvImages(rec.id, TMDB_API_KEY, includeImageLanguage).body()
                                else -> tmdbApi.getMovieImages(rec.id, TMDB_API_KEY, includeImageLanguage).body()
                            }
                        }.getOrNull()?.let { images ->
                            selectBestLocalizedImagePath(
                                images = images.backdrops.orEmpty(),
                                normalizedLanguage = normalizedLanguage
                            )
                        }

                        val backdrop = buildImageUrl(localizedBackdropPath ?: rec.backdropPath, size = "original")
                        val fallbackPoster = buildImageUrl(rec.posterPath, size = "w780")

                        val releaseInfo = if (recTmdbType == "tv") {
                            val startYear = rec.firstAirDate?.take(4)
                            if (startYear != null) {
                                val tvDetails = runCatching {
                                    tmdbApi.getTvDetails(rec.id, TMDB_API_KEY, normalizedLanguage).body()
                                }.getOrNull()
                                val status = tvDetails?.status
                                val endYear = tvDetails?.lastAirDate?.take(4)
                                buildShowYearRange(startYear, endYear, status)
                            } else null
                        } else {
                            rec.releaseDate?.take(4)
                        }

                        MetaPreview(
                            id = "tmdb:${rec.id}",
                            type = recContentType,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = rec.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = rec.voteAverage?.toFloat(),
                            genres = emptyList()
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            moreLikeThisCache[cacheKey] = items
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recommendations for $tmdbId: ${e.message}")
            emptyList()
        }
    }

    private val collectionCache = ConcurrentHashMap<String, List<MetaPreview>>()

    suspend fun fetchMovieCollection(
        collectionId: Int,
        language: String = "en"
    ): List<MetaPreview> = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$normalizedLanguage:collection"
        collectionCache[cacheKey]?.let { return@withContext it }

        try {
            val collectionResponse = tmdbApi.getCollectionDetails(collectionId, TMDB_API_KEY, normalizedLanguage).body()
            val rawParts = collectionResponse?.parts.orEmpty()
            
            // Show in release order
            val sortedParts = rawParts.sortedBy { it.releaseDate ?: "9999" }
            
            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            val items = coroutineScope {
                sortedParts.map { part ->
                    async {
                        val title = part.title ?: return@async null

                        val localizedBackdropPath = runCatching {
                            tmdbApi.getMovieImages(part.id, TMDB_API_KEY, includeImageLanguage).body()
                        }.getOrNull()?.let { images ->
                            selectBestLocalizedImagePath(
                                images = images.backdrops.orEmpty(),
                                normalizedLanguage = normalizedLanguage
                            )
                        }

                        val backdrop = buildImageUrl(localizedBackdropPath ?: part.backdropPath, size = "original")
                        val fallbackPoster = buildImageUrl(part.posterPath, size = "w780")
                        val releaseInfo = part.releaseDate?.take(4)

                        MetaPreview(
                            id = "tmdb:${part.id}",
                            type = ContentType.MOVIE,
                            name = title,
                            poster = backdrop ?: fallbackPoster,
                            posterShape = PosterShape.LANDSCAPE,
                            background = backdrop,
                            logo = null,
                            description = part.overview?.takeIf { it.isNotBlank() },
                            releaseInfo = releaseInfo,
                            imdbRating = part.voteAverage?.toFloat(),
                            genres = emptyList()
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            collectionCache[cacheKey] = items
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch collection for $collectionId: ${e.message}")
            emptyList()
        }
    }

    private fun buildShowYearRange(startYear: String, endYear: String?, status: String?): String {
        val isEnded = status != null && status != "Returning Series" && status != "In Production"
        return when {
            isEnded && endYear != null && endYear != startYear -> "$startYear - $endYear"
            isEnded -> startYear
            else -> "$startYear - "
        }
    }

    private fun buildImageUrl(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    private fun normalizeTmdbLanguage(language: String?): String {
        return language
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace('_', '-')
            ?: "en"
    }

    private fun selectBestLocalizedImagePath(
        images: List<TmdbImage>,
        normalizedLanguage: String
    ): String? {
        if (images.isEmpty()) return null
        val languageCode = normalizedLanguage.substringBefore("-")
        return images
            .sortedWith(
                compareByDescending<TmdbImage> { it.iso6391 == normalizedLanguage }
                    .thenByDescending { it.iso6391 == languageCode }
                    .thenByDescending { it.iso6391 == "en" }
                    .thenByDescending { it.iso6391 == null }
            )
            .firstOrNull()
            ?.filePath
    }

    suspend fun fetchReviews(
        tmdbId: String,
        contentType: ContentType
    ): List<com.nuvio.tv.domain.model.TmdbReview> = withContext(Dispatchers.IO) {
        try {
            val numericId = tmdbId.toIntOrNull() ?: return@withContext emptyList()
            val tmdbType = if (contentType == ContentType.SERIES) "tv" else "movie"
            val response = when (tmdbType) {
                "tv" -> tmdbApi.getTvReviews(numericId, TMDB_API_KEY)
                else -> tmdbApi.getMovieReviews(numericId, TMDB_API_KEY)
            }
            val results = response.body()?.results.orEmpty()
            results.take(10).mapNotNull { review ->
                val content = review.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val author = review.authorDetails?.username
                    ?: review.author
                    ?: return@mapNotNull null
                val avatarPath = review.authorDetails?.avatarPath
                val avatarUrl = when {
                    avatarPath == null -> null
                    avatarPath.startsWith("/http") -> avatarPath.removePrefix("/")
                    else -> "https://image.tmdb.org/t/p/w185$avatarPath"
                }
                com.nuvio.tv.domain.model.TmdbReview(
                    id = review.id,
                    author = author,
                    avatarUrl = avatarUrl,
                    rating = review.authorDetails?.rating,
                    content = content,
                    createdAt = review.createdAt?.take(10)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch reviews for $tmdbId", e)
            emptyList()
        }
    }

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
        language: String = "en"
    ): PersonDetail? =
        withContext(Dispatchers.IO) {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            val cacheKey = "$personId:${preferCrewCredits?.toString() ?: "auto"}:$normalizedLanguage"
            personCache[cacheKey]?.let { return@withContext it }

            try {
                val (person, credits) = coroutineScope {
                    val personDeferred = async {
                        tmdbApi.getPersonDetails(personId, TMDB_API_KEY, normalizedLanguage).body()
                    }
                    val creditsDeferred = async {
                        tmdbApi.getPersonCombinedCredits(personId, TMDB_API_KEY, normalizedLanguage).body()
                    }
                    Pair(personDeferred.await(), creditsDeferred.await())
                }

                if (person == null) return@withContext null

                // If biography is empty and language is not English, fetch English fallback
                val biography = if (person.biography.isNullOrBlank() && normalizedLanguage != "en") {
                    runCatching {
                        tmdbApi.getPersonDetails(personId, TMDB_API_KEY, "en").body()?.biography
                    }.getOrNull()
                } else {
                    person.biography
                }?.takeIf { it.isNotBlank() }

                val preferCrewFilmography = preferCrewCredits ?: shouldPreferCrewCredits(person.knownForDepartment)

                val castMovieCredits = mapMovieCreditsFromCast(credits?.cast.orEmpty())
                val crewMovieCredits = mapMovieCreditsFromCrew(credits?.crew.orEmpty())
                val movieCredits = when {
                    preferCrewFilmography && crewMovieCredits.isNotEmpty() -> crewMovieCredits
                    castMovieCredits.isNotEmpty() -> castMovieCredits
                    else -> crewMovieCredits
                }

                val castTvCredits = mapTvCreditsFromCast(credits?.cast.orEmpty())
                val crewTvCredits = mapTvCreditsFromCrew(credits?.crew.orEmpty())
                val tvCredits = when {
                    preferCrewFilmography && crewTvCredits.isNotEmpty() -> crewTvCredits
                    castTvCredits.isNotEmpty() -> castTvCredits
                    else -> crewTvCredits
                }

                val detail = PersonDetail(
                    tmdbId = person.id,
                    name = person.name ?: "Unknown",
                    biography = biography,
                    birthday = person.birthday?.takeIf { it.isNotBlank() },
                    deathday = person.deathday?.takeIf { it.isNotBlank() },
                    placeOfBirth = person.placeOfBirth?.takeIf { it.isNotBlank() },
                    profilePhoto = buildImageUrl(person.profilePath, "w500"),
                    knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() },
                    movieCredits = movieCredits,
                    tvCredits = tvCredits
                )
                personCache[cacheKey] = detail
                detail
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch person detail: ${e.message}", e)
                null
            }
        }

    private fun shouldPreferCrewCredits(knownForDepartment: String?): Boolean {
        val department = knownForDepartment?.trim()?.lowercase() ?: return false
        if (department.isBlank()) return false
        return department != "acting" && department != "actors"
    }

    private fun mapMovieCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "original"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapMovieCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seenMovieIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "movie" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenMovieIds.add(credit.id)) return@mapNotNull null
                val title = credit.title ?: credit.name ?: return@mapNotNull null
                val year = credit.releaseDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.MOVIE,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "original"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCast(cast: List<TmdbPersonCreditCast>): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return cast
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "original"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    private fun mapTvCreditsFromCrew(crew: List<TmdbPersonCreditCrew>): List<MetaPreview> {
        val seenTvIds = mutableSetOf<Int>()
        return crew
            .filter { it.mediaType == "tv" && it.posterPath != null }
            .sortedByDescending { it.voteAverage ?: 0.0 }
            .mapNotNull { credit ->
                if (!seenTvIds.add(credit.id)) return@mapNotNull null
                val title = credit.name ?: credit.title ?: return@mapNotNull null
                val year = credit.firstAirDate?.take(4)
                MetaPreview(
                    id = "tmdb:${credit.id}",
                    type = ContentType.SERIES,
                    name = title,
                    poster = buildImageUrl(credit.posterPath, "w500"),
                    posterShape = PosterShape.POSTER,
                    background = buildImageUrl(credit.backdropPath, "original"),
                    logo = null,
                    description = credit.overview?.takeIf { it.isNotBlank() },
                    releaseInfo = year,
                    imdbRating = credit.voteAverage?.toFloat(),
                    genres = emptyList()
                )
            }
    }

    // ── Entity Browse (Production Company / Network) ──

    suspend fun fetchEntityHeader(
        entityKind: TmdbEntityKind,
        entityId: Int
    ): TmdbEntityHeader? = withContext(Dispatchers.IO) {
        val cacheKey = "${entityKind.name}:$entityId"
        entityHeaderCache[cacheKey]?.let { return@withContext it }
        try {
            val header = when (entityKind) {
                TmdbEntityKind.COMPANY -> {
                    val resp = tmdbApi.getCompanyDetails(entityId, TMDB_API_KEY).body()
                        ?: return@withContext null
                    TmdbEntityHeader(
                        id = resp.id,
                        name = resp.name ?: "Unknown",
                        description = resp.description?.takeIf { it.isNotBlank() },
                        logoUrl = buildImageUrl(resp.logoPath, "w300"),
                        originCountry = resp.originCountry
                    )
                }
                TmdbEntityKind.NETWORK -> {
                    val resp = tmdbApi.getNetworkDetails(entityId, TMDB_API_KEY).body()
                        ?: return@withContext null
                    TmdbEntityHeader(
                        id = resp.id,
                        name = resp.name ?: "Unknown",
                        description = null,
                        logoUrl = buildImageUrl(resp.logoPath, "w300"),
                        originCountry = resp.originCountry
                    )
                }
            }
            entityHeaderCache[cacheKey] = header
            header
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch entity header: ${e.message}", e)
            null
        }
    }

    suspend fun fetchEntityBrowse(
        entityKind: TmdbEntityKind,
        entityId: Int,
        language: String = "en"
    ): TmdbEntityBrowseData? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "${entityKind.name}:$entityId:$normalizedLanguage"
        entityBrowseCache[cacheKey]?.let { return@withContext it }
        try {
            val header = fetchEntityHeader(entityKind, entityId)
            val today = java.time.LocalDate.now().toString()
            val semaphore = Semaphore(6)
            val railTypes = listOf(
                TmdbEntityRailType.POPULAR,
                TmdbEntityRailType.TOP_RATED,
                TmdbEntityRailType.RECENT
            )
            val mediaTypes = listOf(
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.TV
            )
            val rails = coroutineScope {
                val deferred = railTypes.flatMap { railType ->
                    mediaTypes.map { mediaType ->
                        async {
                            semaphore.withPermit {
                                fetchEntityRail(entityKind, entityId, mediaType, railType, normalizedLanguage, today)
                            }
                        }
                    }
                }
                deferred.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
            }
            val data = TmdbEntityBrowseData(
                header = header,
                rails = rails
            )
            entityBrowseCache[cacheKey] = data
            data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch entity browse: ${e.message}", e)
            null
        }
    }

    suspend fun fetchEntityRailPage(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        page: Int,
        language: String = "en"
    ): TmdbEntityRailPageResult = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val today = java.time.LocalDate.now().toString()
        try {
            val sortBy = when (railType) {
                TmdbEntityRailType.POPULAR -> "popularity.desc"
                TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
                TmdbEntityRailType.RECENT -> when (mediaType) {
                    TmdbEntityMediaType.MOVIE -> "release_date.desc"
                    TmdbEntityMediaType.TV -> "first_air_date.desc"
                }
            }
            val voteCountGte = if (railType == TmdbEntityRailType.TOP_RATED) 50 else null
            val releaseDateLte = if (railType == TmdbEntityRailType.RECENT) today else null
            val response = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    withCompanies = entityId.toString(),
                    sortBy = sortBy,
                    language = normalizedLanguage,
                    page = page,
                    releaseDateLte = releaseDateLte,
                    voteCountGte = voteCountGte
                )
                TmdbEntityMediaType.TV -> {
                    val companyParam = if (entityKind == TmdbEntityKind.COMPANY) entityId.toString() else null
                    val networkParam = if (entityKind == TmdbEntityKind.NETWORK) entityId.toString() else null
                    tmdbApi.discoverTv(
                        apiKey = TMDB_API_KEY,
                        withCompanies = companyParam,
                        withNetworks = networkParam,
                        sortBy = sortBy,
                        language = normalizedLanguage,
                        page = page,
                        firstAirDateLte = releaseDateLte,
                        voteCountGte = voteCountGte
                    )
                }
            }
            val body = response.body()
            val items = body?.results.orEmpty().mapNotNull { mapDiscoverItem(it, mediaType) }
            val enriched = enrichWithImdbIds(items, mediaType)
            TmdbEntityRailPageResult(
                items = enriched,
                hasMore = (body?.page ?: 1) < (body?.totalPages ?: 1)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch entity rail page: ${e.message}", e)
            TmdbEntityRailPageResult(items = emptyList(), hasMore = false)
        }
    }

    private suspend fun fetchEntityRail(
        entityKind: TmdbEntityKind,
        entityId: Int,
        mediaType: TmdbEntityMediaType,
        railType: TmdbEntityRailType,
        language: String,
        today: String
    ): TmdbEntityRail? {
        return try {
            val sortBy = when (railType) {
                TmdbEntityRailType.POPULAR -> "popularity.desc"
                TmdbEntityRailType.TOP_RATED -> "vote_average.desc"
                TmdbEntityRailType.RECENT -> when (mediaType) {
                    TmdbEntityMediaType.MOVIE -> "release_date.desc"
                    TmdbEntityMediaType.TV -> "first_air_date.desc"
                }
            }
            val voteCountGte = if (railType == TmdbEntityRailType.TOP_RATED) 50 else null
            val releaseDateLte = if (railType == TmdbEntityRailType.RECENT) today else null
            val response = when (mediaType) {
                TmdbEntityMediaType.MOVIE -> tmdbApi.discoverMovies(
                    apiKey = TMDB_API_KEY,
                    withCompanies = entityId.toString(),
                    sortBy = sortBy,
                    language = language,
                    page = 1,
                    releaseDateLte = releaseDateLte,
                    voteCountGte = voteCountGte
                )
                TmdbEntityMediaType.TV -> {
                    val companyParam = if (entityKind == TmdbEntityKind.COMPANY) entityId.toString() else null
                    val networkParam = if (entityKind == TmdbEntityKind.NETWORK) entityId.toString() else null
                    tmdbApi.discoverTv(
                        apiKey = TMDB_API_KEY,
                        withCompanies = companyParam,
                        withNetworks = networkParam,
                        sortBy = sortBy,
                        language = language,
                        page = 1,
                        firstAirDateLte = releaseDateLte,
                        voteCountGte = voteCountGte
                    )
                }
            }
            val body = response.body()
            val items = body?.results.orEmpty().mapNotNull { mapDiscoverItem(it, mediaType) }
            if (items.isEmpty()) return null
            val enriched = enrichWithImdbIds(items, mediaType)
            TmdbEntityRail(
                mediaType = mediaType,
                railType = railType,
                items = enriched,
                currentPage = 1,
                hasMore = (body?.page ?: 1) < (body?.totalPages ?: 1)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch entity rail: ${e.message}", e)
            null
        }
    }

    private suspend fun enrichWithImdbIds(
        items: List<MetaPreview>,
        mediaType: TmdbEntityMediaType
    ): List<MetaPreview> {
        val semaphore = Semaphore(6)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull() ?: return@async item
                        try {
                            val resp = when (mediaType) {
                                TmdbEntityMediaType.MOVIE -> tmdbApi.getMovieExternalIds(tmdbId, TMDB_API_KEY)
                                TmdbEntityMediaType.TV -> tmdbApi.getTvExternalIds(tmdbId, TMDB_API_KEY)
                            }
                            val imdbId = if (resp.isSuccessful) resp.body()?.imdbId else null
                            if (imdbId != null) {
                                item.copy(id = imdbId, imdbId = imdbId)
                            } else item
                        } catch (_: Exception) { item }
                    }
                }
            }.awaitAll()
        }
    }

    private fun mapDiscoverItem(item: TmdbDiscoverItem, mediaType: TmdbEntityMediaType): MetaPreview? {
        val title = item.title ?: item.name ?: return null
        val poster = buildImageUrl(item.posterPath, "w500") ?: return null
        val contentType = when (mediaType) {
            TmdbEntityMediaType.MOVIE -> ContentType.MOVIE
            TmdbEntityMediaType.TV -> ContentType.SERIES
        }
        val year = (item.releaseDate ?: item.firstAirDate)?.take(4)
        return MetaPreview(
            id = "tmdb:${item.id}",
            type = contentType,
            name = title,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = buildImageUrl(item.backdropPath, "original"),
            logo = null,
            description = null,
            releaseInfo = year,
            imdbRating = item.voteAverage?.toFloat(),
            genres = emptyList()
        )
    }

    private val collectionDetailCache = ConcurrentHashMap<String, CollectionDetail>()

    suspend fun fetchCollectionDetail(
        collectionId: Int,
        language: String = "en"
    ): CollectionDetail? = withContext(Dispatchers.IO) {
        val normalizedLanguage = normalizeTmdbLanguage(language)
        val cacheKey = "$collectionId:$normalizedLanguage:detail"
        collectionDetailCache[cacheKey]?.let { return@withContext it }

        try {
            val resp = tmdbApi.getCollectionDetails(collectionId, TMDB_API_KEY, normalizedLanguage).body()
                ?: return@withContext null
            val sortedParts = resp.parts.orEmpty().sortedBy { it.releaseDate ?: "9999" }

            val includeImageLanguage = buildString {
                append(normalizedLanguage.substringBefore("-"))
                append(",")
                append(normalizedLanguage)
                append(",en,null")
            }

            // Fetch logo for the collection
            val logoPath = sortedParts.firstOrNull()?.let { firstPart ->
                runCatching {
                    tmdbApi.getMovieImages(firstPart.id, TMDB_API_KEY, includeImageLanguage).body()
                }.getOrNull()?.logos
                    ?.sortedWith(
                        compareByDescending<TmdbImage> { it.iso6391 == normalizedLanguage.substringBefore("-") }
                            .thenByDescending { it.iso6391 == "en" }
                            .thenByDescending { it.iso6391 == null }
                    )
                    ?.firstOrNull()?.filePath
            }

            val semaphore = Semaphore(6)
            val parts = coroutineScope {
                sortedParts.map { part ->
                    async {
                        semaphore.withPermit {
                            val title = part.title ?: return@async null
                            val poster = buildImageUrl(part.posterPath, "w500")
                            val backdrop = buildImageUrl(part.backdropPath, "original")
                            val year = part.releaseDate?.take(4)

                            // Resolve IMDB ID
                            val imdbId = runCatching {
                                tmdbApi.getMovieExternalIds(part.id, TMDB_API_KEY).body()?.imdbId
                            }.getOrNull()

                            MetaPreview(
                                id = imdbId ?: "tmdb:${part.id}",
                                type = ContentType.MOVIE,
                                name = title,
                                poster = poster ?: backdrop,
                                posterShape = PosterShape.POSTER,
                                background = backdrop,
                                logo = null,
                                description = part.overview?.takeIf { it.isNotBlank() },
                                releaseInfo = year,
                                imdbRating = part.voteAverage?.toFloat(),
                                imdbId = imdbId,
                                genres = emptyList()
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val detail = CollectionDetail(
                id = resp.id,
                name = resp.name ?: "Collection",
                overview = resp.overview?.takeIf { it.isNotBlank() },
                posterUrl = buildImageUrl(resp.posterPath, "w500"),
                backdropUrl = buildImageUrl(resp.backdropPath, "original"),
                logoUrl = buildImageUrl(logoPath, "w500"),
                firstMovieTmdbId = sortedParts.firstOrNull()?.id,
                parts = parts
            )
            collectionDetailCache[cacheKey] = detail
            detail
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch collection detail: ${e.message}", e)
            null
        }
    }

    /**
     * Resolves TMDB collection info for a movie by its IMDB or TMDB ID.
     * Returns the collectionId and collectionName if the movie belongs to a collection.
     */
    suspend fun resolveCollectionForMovie(
        movieId: String,
        mediaType: String,
        language: String = "en"
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val normalizedLanguage = normalizeTmdbLanguage(language)
            // Check enrichment cache first
            val cleanId = movieId.removePrefix("tmdb:")
            val tmdbIdStr = if (cleanId.startsWith("tt")) null else cleanId.takeIf { it.all { c -> c.isDigit() } }
            val contentType = ContentType.MOVIE
            val cacheKey = "${tmdbIdStr ?: movieId}:${contentType.name}:$normalizedLanguage"
            enrichmentCache[cacheKey]?.let { enrichment ->
                if (enrichment.collectionId != null && enrichment.collectionName != null) {
                    return@withContext enrichment.collectionId to enrichment.collectionName
                }
            }
            // If not cached, we need to fetch — but only if we have a TMDB ID
            null
        } catch (_: Exception) {
            null
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

private fun preferredRegions(normalizedLanguage: String): List<String> {
    val fromLanguage = normalizedLanguage.substringAfter("-", "").uppercase(Locale.US).takeIf { it.length == 2 }
    return buildList {
        if (!fromLanguage.isNullOrBlank()) add(fromLanguage)
        add("US")
        add("GB")
    }.distinct()
}

private fun selectMovieAgeRating(
    countries: List<com.nuvio.tv.data.remote.api.TmdbMovieReleaseDateCountry>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = countries.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]
            ?.releaseDates
            .orEmpty()
            .mapNotNull { it.certification?.trim() }
            .firstOrNull { it.isNotBlank() }
        if (!rating.isNullOrBlank()) return rating
    }
    return countries
        .asSequence()
        .flatMap { it.releaseDates.orEmpty().asSequence() }
        .mapNotNull { it.certification?.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun selectTvAgeRating(
    ratings: List<com.nuvio.tv.data.remote.api.TmdbTvContentRatingItem>,
    normalizedLanguage: String
): String? {
    val preferred = preferredRegions(normalizedLanguage)
    val byRegion = ratings.associateBy { it.iso31661?.uppercase(Locale.US) }
    preferred.forEach { region ->
        val rating = byRegion[region]?.rating?.trim()
        if (!rating.isNullOrBlank()) return rating
    }
    return ratings
        .mapNotNull { it.rating?.trim() }
        .firstOrNull { it.isNotBlank() }
}

data class TmdbEnrichment(
    val localizedTitle: String?,
    val description: String?,
    val genres: List<String>,
    val backdrop: String?,
    val logo: String?,
    val poster: String?,
    val directorMembers: List<MetaCastMember>,
    val writerMembers: List<MetaCastMember>,
    val castMembers: List<MetaCastMember>,
    val releaseInfo: String?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val director: List<String>,
    val writer: List<String>,
    val productionCompanies: List<MetaCompany>,
    val networks: List<MetaCompany>,
    val ageRating: String?,
    val status: String?,
    val countries: List<String>?,
    val language: String?,
    val collectionId: Int?,
    val collectionName: String?
)

data class TmdbEpisodeEnrichment(
    val title: String?,
    val overview: String?,
    val thumbnail: String?,
    val airDate: String?,
    val runtimeMinutes: Int?
)

private fun TmdbEpisode.toEnrichment(): TmdbEpisodeEnrichment {
    val title = name?.takeIf { it.isNotBlank() }
    val overview = overview?.takeIf { it.isNotBlank() }
    val thumbnail = stillPath?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
    val airDate = airDate?.takeIf { it.isNotBlank() }
    return TmdbEpisodeEnrichment(
        title = title,
        overview = overview,
        thumbnail = thumbnail,
        airDate = airDate,
        runtimeMinutes = runtime
    )
}

// ── Entity Browse Data Models ──

enum class TmdbEntityKind { COMPANY, NETWORK }
enum class TmdbEntityMediaType { MOVIE, TV }
enum class TmdbEntityRailType { POPULAR, TOP_RATED, RECENT }

data class TmdbEntityHeader(
    val id: Int,
    val name: String,
    val description: String?,
    val logoUrl: String?,
    val originCountry: String?
)

data class TmdbEntityRail(
    val mediaType: TmdbEntityMediaType,
    val railType: TmdbEntityRailType,
    val items: List<MetaPreview>,
    val currentPage: Int = 1,
    val hasMore: Boolean = false
)

data class TmdbEntityBrowseData(
    val header: TmdbEntityHeader?,
    val rails: List<TmdbEntityRail>
)

data class TmdbEntityRailPageResult(
    val items: List<MetaPreview>,
    val hasMore: Boolean
)

// ── Library Collection Models ──

data class LibraryCollectionInfo(
    val collectionId: Int,
    val collectionName: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val libraryMovieIds: List<String>
)

data class CollectionDetail(
    val id: Int,
    val name: String,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val logoUrl: String?,
    val firstMovieTmdbId: Int?,
    val parts: List<MetaPreview>
)
