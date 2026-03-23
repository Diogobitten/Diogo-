package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.OpenAiApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.dto.openai.ChatCompletionRequest
import com.nuvio.tv.data.remote.dto.openai.ChatMessage
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRecommendationService @Inject constructor(
    private val openAiApi: OpenAiApi,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService,
    private val libraryRepository: LibraryRepository
) {
    companion object {
        private const val TAG = "AiRecommendationService"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val AI_ADDON_ID = "ai-recommendations"
        private const val AI_ADDON_NAME = "IA"
    }

    private data class CacheEntry(
        val row: CatalogRow,
        val timestamp: Long
    )

    @Volatile
    private var cache: CacheEntry? = null

    suspend fun getAiRecommendationRow(): CatalogRow? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENAI_API_KEY
        if (apiKey.isBlank()) {
            Log.d(TAG, "OpenAI API key not configured, skipping AI recommendations")
            return@withContext null
        }

        val cached = cache
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return@withContext cached.row
        }

        try {
            val libraryItems = libraryRepository.libraryItems.first()
            if (libraryItems.isEmpty()) {
                Log.d(TAG, "Library is empty, skipping AI recommendations")
                return@withContext null
            }

            val libraryContext = buildLibraryContext(libraryItems)
            val aiResponse = callOpenAi(apiKey, libraryContext)
            if (aiResponse.isNullOrEmpty()) return@withContext null

            val parsed = parseAiResponse(aiResponse)
            if (parsed.isEmpty()) return@withContext null

            val enriched = enrichWithTmdbImages(parsed)
            if (enriched.isEmpty()) return@withContext null

            val row = CatalogRow(
                addonId = AI_ADDON_ID,
                addonName = AI_ADDON_NAME,
                addonBaseUrl = "",
                catalogId = "ai-for-you",
                catalogName = "Feito pra Você \uD83E\uDD16",
                type = ContentType.MOVIE,
                rawType = "mixed",
                items = enriched,
                isLoading = false,
                hasMore = false,
                currentPage = 0,
                supportsSkip = false
            )

            cache = CacheEntry(row, System.currentTimeMillis())
            row
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get AI recommendations: ${e.message}", e)
            null
        }
    }

    fun clearCache() {
        cache = null
    }

    private fun buildLibraryContext(items: List<LibraryEntry>): String {
        val movies = items.filter { it.type == "movie" }.take(30)
        val series = items.filter { it.type == "series" }.take(30)

        val sb = StringBuilder()
        if (movies.isNotEmpty()) {
            sb.append("Filmes na biblioteca: ")
            sb.append(movies.joinToString(", ") { "${it.name} (${it.releaseInfo ?: "?"})" })
            sb.append("\n")
        }
        if (series.isNotEmpty()) {
            sb.append("Séries na biblioteca: ")
            sb.append(series.joinToString(", ") { "${it.name} (${it.releaseInfo ?: "?"})" })
        }
        return sb.toString()
    }

    private suspend fun callOpenAi(apiKey: String, libraryContext: String): String? {
        val systemPrompt = """Você é um assistente de recomendação de filmes e séries.
Com base na biblioteca do usuário abaixo, sugira exatamente 15 filmes e séries que ele provavelmente vai gostar.
Misture filmes e séries. Não repita itens que já estão na biblioteca.
Priorize títulos populares e bem avaliados que combinem com o perfil do usuário.

Responda APENAS com um JSON array, sem texto adicional. Cada item deve ter:
- "title": nome original ou mais conhecido
- "type": "movie" ou "tv"
- "tmdb_id": ID do TMDB (número inteiro)

Exemplo: [{"title":"Inception","type":"movie","tmdb_id":27205}]

Biblioteca do usuário:
$libraryContext"""

        val request = ChatCompletionRequest(
            model = "gpt-4o-mini",
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = "Me recomende filmes e séries baseado no meu perfil.")
            ),
            temperature = 0.9,
            maxTokens = 800
        )

        val response = openAiApi.chatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )

        if (!response.isSuccessful) {
            Log.w(TAG, "OpenAI API error: ${response.code()}")
            return null
        }

        return response.body()?.choices?.firstOrNull()?.message?.content
    }

    private fun parseAiResponse(response: String): List<MetaPreview> {
        return try {
            // Extract JSON array from response (may have markdown wrapping)
            val jsonStr = response
                .replace("```json", "").replace("```", "")
                .trim()
            val array = JSONArray(jsonStr)
            val items = mutableListOf<MetaPreview>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val title = obj.optString("title", "") 
                val type = obj.optString("type", "movie")
                val tmdbId = obj.optInt("tmdb_id", 0)
                if (title.isNotBlank() && tmdbId > 0) {
                    items.add(
                        MetaPreview(
                            id = "tmdb:$tmdbId",
                            type = if (type == "tv") ContentType.SERIES else ContentType.MOVIE,
                            name = title,
                            poster = null,
                            posterShape = PosterShape.POSTER,
                            background = null,
                            logo = null,
                            description = null,
                            releaseInfo = null,
                            imdbRating = null,
                            genres = emptyList()
                        )
                    )
                }
            }
            items
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse AI response: ${e.message}")
            emptyList()
        }
    }

    private suspend fun enrichWithTmdbImages(items: List<MetaPreview>): List<MetaPreview> {
        val semaphore = Semaphore(6)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit { enrichSingleItem(item) }
                }
            }.awaitAll()
        }.filter { it.poster != null || it.background != null }
    }

    private suspend fun enrichSingleItem(item: MetaPreview): MetaPreview {
        val tmdbId = item.id.removePrefix("tmdb:").toIntOrNull() ?: return item
        val mediaType = if (item.type == ContentType.MOVIE) "movie" else "tv"

        return try {
            val detailsResponse = when (mediaType) {
                "movie" -> tmdbApi.getMovieDetails(tmdbId, tmdbService.apiKey())
                else -> tmdbApi.getTvDetails(tmdbId, tmdbService.apiKey())
            }
            if (!detailsResponse.isSuccessful) return item
            val details = detailsResponse.body() ?: return item

            val imdbId = try {
                val extResponse = when (mediaType) {
                    "movie" -> tmdbApi.getMovieExternalIds(tmdbId, tmdbService.apiKey())
                    else -> tmdbApi.getTvExternalIds(tmdbId, tmdbService.apiKey())
                }
                if (extResponse.isSuccessful) extResponse.body()?.imdbId else null
            } catch (_: Exception) { null }

            if (imdbId != null) {
                tmdbService.preCacheMapping(imdbId, tmdbId)
            }

            item.copy(
                id = imdbId ?: item.id,
                name = (if (mediaType == "movie") details.title else details.name) ?: item.name,
                poster = details.posterPath?.let { "${TMDB_IMAGE_BASE}w500$it" },
                background = details.backdropPath?.let { "${TMDB_IMAGE_BASE}original$it" },
                releaseInfo = (details.releaseDate ?: details.firstAirDate)
                    ?.take(4)?.takeIf { it.length == 4 },
                imdbId = imdbId
            )
        } catch (e: Exception) {
            Log.w(TAG, "TMDB enrich failed for $tmdbId: ${e.message}")
            item
        }
    }
}
