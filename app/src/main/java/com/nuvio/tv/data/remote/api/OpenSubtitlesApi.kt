package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface OpenSubtitlesApi {

    @GET("subtitles")
    suspend fun searchSubtitles(
        @Header("Api-Key") apiKey: String,
        @Header("User-Agent") userAgent: String = "NuvioTV",
        @Query("imdb_id") imdbId: String? = null,
        @Query("tmdb_id") tmdbId: Int? = null,
        @Query("type") type: String? = null,
        @Query("season_number") seasonNumber: Int? = null,
        @Query("episode_number") episodeNumber: Int? = null,
        @Query("moviehash") movieHash: String? = null,
        @Query("languages") languages: String? = null,
        @Query("order_by") orderBy: String = "download_count",
        @Query("order_direction") orderDirection: String = "desc"
    ): Response<OpenSubtitlesSearchResponse>
}

@JsonClass(generateAdapter = true)
data class OpenSubtitlesSearchResponse(
    @Json(name = "total_pages") val totalPages: Int = 0,
    @Json(name = "total_count") val totalCount: Int = 0,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "data") val data: List<OpenSubtitlesResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesResult(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: OpenSubtitlesAttributes? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesAttributes(
    @Json(name = "subtitle_id") val subtitleId: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "download_count") val downloadCount: Int = 0,
    @Json(name = "hearing_impaired") val hearingImpaired: Boolean = false,
    @Json(name = "fps") val fps: Double = 0.0,
    @Json(name = "votes") val votes: Int = 0,
    @Json(name = "ratings") val ratings: Double = 0.0,
    @Json(name = "from_trusted") val fromTrusted: Boolean? = null,
    @Json(name = "foreign_parts_only") val foreignPartsOnly: Boolean = false,
    @Json(name = "ai_translated") val aiTranslated: Boolean = false,
    @Json(name = "machine_translated") val machineTranslated: Boolean = false,
    @Json(name = "release") val release: String? = null,
    @Json(name = "files") val files: List<OpenSubtitlesFile> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesFile(
    @Json(name = "file_id") val fileId: Int = 0,
    @Json(name = "file_name") val fileName: String? = null
)
