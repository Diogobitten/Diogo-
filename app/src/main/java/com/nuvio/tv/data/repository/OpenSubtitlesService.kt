package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.OpenSubtitlesApi
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OpenSubtitlesService"
private const val ADDON_NAME = "OpenSubtitles"
private const val DOWNLOAD_BASE = "https://api.opensubtitles.com/api/v1/download"

@Singleton
class OpenSubtitlesService @Inject constructor(
    private val api: OpenSubtitlesApi
) {
    fun isConfigured(): Boolean = BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank()

    suspend fun searchSubtitles(
        imdbId: String?,
        type: String,
        season: Int? = null,
        episode: Int? = null,
        movieHash: String? = null,
        languages: String? = null
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENSUBTITLES_API_KEY
        if (apiKey.isBlank()) return@withContext emptyList()

        // Clean IMDB ID (remove "tt" prefix if needed for numeric, or keep as-is)
        val cleanImdbId = imdbId?.removePrefix("tt")?.toIntOrNull()
        if (cleanImdbId == null && movieHash == null) {
            Log.d(TAG, "No IMDB ID or hash available, skipping OpenSubtitles search")
            return@withContext emptyList()
        }

        val osType = when {
            type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true) -> "episode"
            else -> "movie"
        }

        try {
            val response = api.searchSubtitles(
                apiKey = apiKey,
                imdbId = imdbId?.let { if (it.startsWith("tt")) it.removePrefix("tt") else it },
                type = osType,
                seasonNumber = if (osType == "episode") season else null,
                episodeNumber = if (osType == "episode") episode else null,
                movieHash = movieHash,
                languages = languages
            )

            if (!response.isSuccessful) {
                Log.w(TAG, "OpenSubtitles API error: ${response.code()}")
                return@withContext emptyList()
            }

            val results = response.body()?.data.orEmpty()
            Log.d(TAG, "OpenSubtitles returned ${results.size} results")

            results.mapNotNull { result ->
                val attrs = result.attributes ?: return@mapNotNull null
                val file = attrs.files.firstOrNull() ?: return@mapNotNull null
                val lang = attrs.language ?: return@mapNotNull null

                // Skip machine/AI translated subs — quality is usually poor
                if (attrs.machineTranslated || attrs.aiTranslated) return@mapNotNull null

                Subtitle(
                    id = "os-${file.fileId}",
                    url = "$DOWNLOAD_BASE?file_id=${file.fileId}&api_key=$apiKey",
                    lang = lang,
                    addonName = ADDON_NAME,
                    addonLogo = null,
                    format = "srt"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenSubtitles search failed: ${e.message}")
            emptyList()
        }
    }
}
