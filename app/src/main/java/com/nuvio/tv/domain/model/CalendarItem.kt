package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CalendarItem(
    val id: String,
    val title: String,
    val type: CalendarItemType,
    val date: String,
    val poster: String?,
    val backdrop: String?,
    val tmdbId: Int?,
    val traktId: Int?,
    val imdbId: String?,
    // Episode-specific
    val showName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    // Movie-specific
    val year: Int? = null
)

enum class CalendarItemType {
    EPISODE,
    MOVIE
}
