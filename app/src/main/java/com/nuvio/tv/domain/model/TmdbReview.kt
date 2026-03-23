package com.nuvio.tv.domain.model

data class TmdbReview(
    val id: String,
    val author: String,
    val avatarUrl: String?,
    val rating: Double?,
    val content: String,
    val createdAt: String?
)
