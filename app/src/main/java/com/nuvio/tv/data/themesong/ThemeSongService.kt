package com.nuvio.tv.data.themesong

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.trailer.InAppYouTubeExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ThemeSongService"
private const val THEMERR_BASE = "https://app.lizardbyte.dev/ThemerrDB"

/**
 * Fetches theme song audio URLs from ThemerrDB (LizardByte).
 * ThemerrDB stores YouTube URLs keyed by TMDB ID for movies and TV shows.
 * We extract audio-only streams via [InAppYouTubeExtractor].
 */
@Singleton
class ThemeSongService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tmdbService: TmdbService,
    private val youTubeExtractor: InAppYouTubeExtractor
) {
    // Cache: "type|tmdbId" -> audio URL (empty string = negative cache)
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Returns an audio-only playback URL for the theme song, or null if unavailable.
     */
    suspend fun getThemeSongAudioUrl(
        itemId: String,
        itemType: String
    ): String? = withContext(Dispatchers.IO) {
        val tmdbId = try {
            tmdbService.ensureTmdbId(itemId, itemType)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve TMDB ID for $itemId/$itemType: ${e.message}")
            null
        } ?: return@withContext null

        val mediaPath = when (itemType.lowercase()) {
            "movie", "film" -> "movies"
            "series", "tv", "show", "tvshow" -> "tv_shows"
            else -> return@withContext null
        }

        val cacheKey = "$mediaPath|$tmdbId"
        cache[cacheKey]?.let { cached ->
            return@withContext cached.takeIf { it.isNotBlank() }
        }

        try {
            val youtubeUrl = fetchThemerrYouTubeUrl(mediaPath, tmdbId)
            if (youtubeUrl == null) {
                Log.d(TAG, "No theme song in ThemerrDB for $mediaPath/$tmdbId")
                cache[cacheKey] = ""
                return@withContext null
            }

            Log.d(TAG, "ThemerrDB hit for $mediaPath/$tmdbId, extracting audio...")
            val source = youTubeExtractor.extractPlaybackSource(youtubeUrl)

            // Prefer separate audio stream; fall back to combined video+audio URL
            val audioUrl = source?.audioUrl?.takeIf { it.isNotBlank() }
                ?: source?.videoUrl?.takeIf { it.isNotBlank() }

            if (audioUrl != null) {
                cache[cacheKey] = audioUrl
                Log.d(TAG, "Theme song audio resolved for $mediaPath/$tmdbId")
            } else {
                cache[cacheKey] = ""
                Log.w(TAG, "YouTube extraction returned no usable URL for $mediaPath/$tmdbId")
            }

            audioUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching theme song for $mediaPath/$tmdbId: ${e.message}", e)
            cache[cacheKey] = ""
            null
        }
    }

    private fun fetchThemerrYouTubeUrl(mediaPath: String, tmdbId: String): String? {
        val url = "$THEMERR_BASE/$mediaPath/themoviedb/$tmdbId.json"
        val request = Request.Builder().url(url).get().build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code != 404) {
                    Log.w(TAG, "ThemerrDB request failed ($url): ${response.code}")
                }
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val ytUrl = json.optString("youtube_theme_url", "").trim()
            return ytUrl.takeIf { it.isNotBlank() }
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
