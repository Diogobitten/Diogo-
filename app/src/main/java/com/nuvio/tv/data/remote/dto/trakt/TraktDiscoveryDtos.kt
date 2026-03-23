package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktTrendingMovieDto(
    @Json(name = "watchers") val watchers: Int? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktTrendingShowDto(
    @Json(name = "watchers") val watchers: Int? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPopularMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPopularShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktAnticipatedMovieDto(
    @Json(name = "list_count") val listCount: Int? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktAnticipatedShowDto(
    @Json(name = "list_count") val listCount: Int? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRecommendedMovieDto(
    @Json(name = "user_count") val userCount: Int? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktRecommendedShowDto(
    @Json(name = "user_count") val userCount: Int? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)
