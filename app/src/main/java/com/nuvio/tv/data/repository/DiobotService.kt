package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.OpenAiApi
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbSearchMultiResult
import com.nuvio.tv.data.remote.dto.openai.ChatCompletionRequest
import com.nuvio.tv.data.remote.dto.openai.ChatMessage
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DiobotSuggestion(
    val title: String,
    val type: String, // "movie" or "series"
    val tmdbId: Int?,
    val imdbId: String?,
    val posterPath: String? = null
)

data class DiobotResponse(
    val message: String,
    val suggestions: List<DiobotSuggestion>,
    val action: DiobotAction? = null
)

sealed class DiobotAction {
    data class SaveToLibrary(val suggestion: DiobotSuggestion) : DiobotAction()
    data class Play(val suggestion: DiobotSuggestion) : DiobotAction()
}

@Singleton
class DiobotService @Inject constructor(
    private val openAiApi: OpenAiApi,
    private val tmdbApi: TmdbApi,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "DiobotService"
        private val TMDB_KEY get() = BuildConfig.TMDB_API_KEY
    }

    // Cache of tmdbId → posterPath from latest TMDB fetch
    @Volatile
    private var lastTmdbPosterCache: Map<Int, String> = emptyMap()
    @Volatile
    private var lastTmdbImdbCache: Map<Int, String> = emptyMap()

    // ── TMDB genre maps for enriching search results ──
    private val movieGenres = mapOf(
        28 to "Ação", 12 to "Aventura", 16 to "Animação", 35 to "Comédia",
        80 to "Crime", 99 to "Documentário", 18 to "Drama", 10751 to "Família",
        14 to "Fantasia", 36 to "História", 27 to "Terror", 10402 to "Música",
        9648 to "Mistério", 10749 to "Romance", 878 to "Ficção Científica",
        10770 to "Telefilme", 53 to "Thriller", 10752 to "Guerra", 37 to "Faroeste"
    )
    private val tvGenres = mapOf(
        10759 to "Ação & Aventura", 16 to "Animação", 35 to "Comédia", 80 to "Crime",
        99 to "Documentário", 18 to "Drama", 10751 to "Família", 10762 to "Kids",
        9648 to "Mistério", 10763 to "News", 10764 to "Reality", 10765 to "Sci-Fi & Fantasia",
        10766 to "Novela", 10767 to "Talk", 10768 to "Guerra & Política", 37 to "Faroeste"
    )

    // ── Detect user intent to decide which TMDB endpoints to call ──
    private data class TmdbQueryPlan(
        val searchTerms: List<String>,
        val wantsTrending: Boolean,
        val wantsNowPlaying: Boolean,
        val wantsUpcoming: Boolean
    )

    private fun analyzeUserMessage(message: String): TmdbQueryPlan {
        val lower = message.lowercase()
        val wantsTrending = lower.containsAny("tendência", "trending", "popular", "famoso", "mais assistido", "top", "alta")
        val wantsNowPlaying = lower.containsAny("cartaz", "cinema", "lançamento", "novo", "recente", "estreia", "saiu agora")
        val wantsUpcoming = lower.containsAny("vai lançar", "próximo", "futuro", "em breve", "upcoming", "aguardado")

        // Extract potential search terms: remove common filler words
        val fillers = setOf(
            "me", "um", "uma", "de", "do", "da", "dos", "das", "que", "pra", "para",
            "eu", "quero", "queria", "gostaria", "assistir", "ver", "assista", "bota",
            "coloca", "reproduzir", "play", "salvar", "salva", "adicionar", "adiciona",
            "recomendar", "recomenda", "recomende", "sugira", "sugere", "indica",
            "filme", "filmes", "série", "séries", "series", "algo", "tipo", "como",
            "sobre", "com", "no", "na", "nos", "nas", "o", "a", "os", "as", "e",
            "ou", "mais", "muito", "bem", "bom", "boa", "legal", "ótimo", "ótima",
            "por", "favor", "pode", "poderia", "tem", "ter", "é", "são", "foi",
            "parecido", "parecida", "estilo", "gênero", "categoria"
        )
        val words = message.split(Regex("[\\s,;.!?]+")).filter { it.length > 2 && it.lowercase() !in fillers }
        val searchTerms = if (words.isNotEmpty()) {
            // Try to find quoted titles or proper nouns
            val quotedPattern = Regex(""""(.+?)"|'(.+?)'|\u201c(.+?)\u201d""")
            val quoted = quotedPattern.findAll(message).map { it.groupValues.drop(1).first { g -> g.isNotEmpty() } }.toList()
            if (quoted.isNotEmpty()) quoted
            else listOf(words.joinToString(" "))
        } else emptyList()

        return TmdbQueryPlan(
            searchTerms = searchTerms,
            wantsTrending = wantsTrending,
            wantsNowPlaying = wantsNowPlaying,
            wantsUpcoming = wantsUpcoming
        )
    }

    private fun String.containsAny(vararg terms: String): Boolean = terms.any { this.contains(it) }

    // ── Fetch TMDB data based on query plan ──
    private suspend fun fetchTmdbContext(plan: TmdbQueryPlan): String = coroutineScope {
        val sections = mutableListOf<String>()

        // Search for specific titles
        val searchJobs = plan.searchTerms.take(3).map { term ->
            async {
                try {
                    val resp = tmdbApi.searchMulti(apiKey = TMDB_KEY, query = term)
                    if (resp.isSuccessful) {
                        resp.body()?.results
                            ?.filter { it.mediaType == "movie" || it.mediaType == "tv" }
                            ?.take(8)
                            ?: emptyList<TmdbSearchMultiResult>()
                    } else emptyList()
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB search failed for '$term': ${e.message}")
                    emptyList<TmdbSearchMultiResult>()
                }
            }
        }

        // Trending
        val trendingJob = if (plan.wantsTrending || plan.searchTerms.isEmpty()) {
            async {
                try {
                    val movies = tmdbApi.getTrendingMovies(apiKey = TMDB_KEY)
                    val tv = tmdbApi.getTrendingTv(apiKey = TMDB_KEY)
                    val movieResults = (movies.body()?.results ?: emptyList()).take(5).map { it.copy(mediaType = "movie") }
                    val tvResults = (tv.body()?.results ?: emptyList()).take(5).map { it.copy(mediaType = "tv") }
                    movieResults + tvResults
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB trending failed: ${e.message}")
                    emptyList<TmdbSearchMultiResult>()
                }
            }
        } else null

        // Now playing
        val nowPlayingJob = if (plan.wantsNowPlaying) {
            async {
                try {
                    val resp = tmdbApi.getNowPlayingMovies(apiKey = TMDB_KEY)
                    (resp.body()?.results ?: emptyList()).take(8).map { it.copy(mediaType = "movie") }
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB now playing failed: ${e.message}")
                    emptyList<TmdbSearchMultiResult>()
                }
            }
        } else null

        // Upcoming
        val upcomingJob = if (plan.wantsUpcoming) {
            async {
                try {
                    val resp = tmdbApi.getUpcomingMovies(apiKey = TMDB_KEY)
                    (resp.body()?.results ?: emptyList()).take(8).map { it.copy(mediaType = "movie") }
                } catch (e: Exception) {
                    Log.w(TAG, "TMDB upcoming failed: ${e.message}")
                    emptyList<TmdbSearchMultiResult>()
                }
            }
        } else null

        // Collect search results
        val searchResults = searchJobs.flatMap { it.await() }
        if (searchResults.isNotEmpty()) {
            sections.add("RESULTADOS DE BUSCA TMDB:\n${formatTmdbResults(searchResults)}")
        }

        trendingJob?.await()?.let { results ->
            if (results.isNotEmpty()) sections.add("TENDÊNCIAS DA SEMANA:\n${formatTmdbResults(results)}")
        }
        nowPlayingJob?.await()?.let { results ->
            if (results.isNotEmpty()) sections.add("EM CARTAZ AGORA:\n${formatTmdbResults(results)}")
        }
        upcomingJob?.await()?.let { results ->
            if (results.isNotEmpty()) sections.add("PRÓXIMOS LANÇAMENTOS:\n${formatTmdbResults(results)}")
        }

        // Enrich with IMDB IDs for the top results
        val allResults = (searchResults + (trendingJob?.await() ?: emptyList())).distinctBy { it.id }.take(10)
        val imdbMap = fetchImdbIds(allResults)
        if (imdbMap.isNotEmpty()) {
            sections.add("MAPEAMENTO TMDB→IMDB:\n${imdbMap.entries.joinToString("\n") { "tmdb:${it.key} → ${it.value}" }}")
        }

        if (sections.isEmpty()) return@coroutineScope ""

        // Cache poster paths and IMDB IDs for enriching ChatGPT suggestions later
        val allForCache = (searchResults + (trendingJob?.await() ?: emptyList()) +
                (nowPlayingJob?.await() ?: emptyList()) + (upcomingJob?.await() ?: emptyList()))
            .distinctBy { it.id }
        lastTmdbPosterCache = allForCache.mapNotNull { r ->
            r.posterPath?.let { r.id to it }
        }.toMap()
        lastTmdbImdbCache = imdbMap

        "\n\n--- DADOS TMDB EM TEMPO REAL ---\n${sections.joinToString("\n\n")}\n--- FIM DADOS TMDB ---"
    }

    private fun formatTmdbResults(results: List<TmdbSearchMultiResult>): String {
        return results.joinToString("\n") { r ->
            val title = r.title ?: r.name ?: "?"
            val type = if (r.mediaType == "movie") "movie" else "series"
            val year = (r.releaseDate ?: r.firstAirDate)?.take(4) ?: "?"
            val rating = r.voteAverage?.let { "%.1f".format(it) } ?: "?"
            val genres = r.genreIds?.take(3)?.mapNotNull {
                if (r.mediaType == "movie") movieGenres[it] else tvGenres[it]
            }?.joinToString(", ") ?: ""
            val overview = r.overview?.take(120)?.let { if (it.length == 120) "$it..." else it } ?: ""
            "- $title ($year) | tmdbId=${r.id} | type=$type | nota=$rating | gêneros=$genres | $overview"
        }
    }

    private suspend fun fetchImdbIds(results: List<TmdbSearchMultiResult>): Map<Int, String> = coroutineScope {
        results.map { r ->
            async {
                try {
                    val resp = if (r.mediaType == "movie") {
                        tmdbApi.getMovieExternalIds(r.id, TMDB_KEY)
                    } else {
                        tmdbApi.getTvExternalIds(r.id, TMDB_KEY)
                    }
                    if (resp.isSuccessful) {
                        resp.body()?.imdbId?.let { r.id to it }
                    } else null
                } catch (_: Exception) { null }
            }
        }.mapNotNull { it.await() }.toMap()
    }

    // ── System prompt with TMDB real-time data ──
    private fun buildSystemPrompt(libraryTitles: List<String>, tmdbContext: String): String {
        val librarySection = if (libraryTitles.isNotEmpty()) {
            val titles = libraryTitles.take(50).joinToString(", ")
            "\n\nBIBLIOTECA DO USUÁRIO (títulos salvos): $titles"
        } else {
            "\n\nO usuário ainda não tem itens na biblioteca."
        }

        return """Você é o Diobot, um assistente de filmes e séries integrado ao app Diogo+. Você é simpático, direto e fala português brasileiro.

Você tem acesso a dados em tempo real do TMDB (The Movie Database) que são fornecidos abaixo. USE ESSES DADOS como fonte primária para IDs, notas, sinopses e informações atualizadas. Não invente tmdbId ou imdbId — use APENAS os que aparecem nos dados TMDB fornecidos.

Você conhece a biblioteca do usuário e pode recomendar baseado nos gostos dele.
$librarySection
$tmdbContext

AÇÕES DISPONÍVEIS:
- Recomendar filmes/séries (padrão)
- SALVAR na biblioteca do usuário (quando ele pedir para salvar/adicionar)
- REPRODUZIR um filme/série na TV (quando ele pedir para assistir/reproduzir/play)

O formato DEVE ser exatamente:
MENSAGEM: sua mensagem aqui
ACAO: nenhuma|salvar|reproduzir
ALVO: {"title":"Nome","type":"movie","tmdbId":12345,"imdbId":"tt1234567"}
SUGESTOES: [{"title":"Nome","type":"movie","tmdbId":12345,"imdbId":"tt1234567"},...]

Regras:
- ACAO é obrigatório. Use "nenhuma" quando for só recomendação/conversa
- ALVO só é necessário quando ACAO é "salvar" ou "reproduzir" — é o item específico da ação
- type deve ser "movie" ou "series"
- tmdbId e imdbId DEVEM vir dos dados TMDB fornecidos acima. Se o item não estiver nos dados, use tmdbId e imdbId como null
- Use o MAPEAMENTO TMDB→IMDB para preencher imdbId quando disponível
- Use apenas títulos reais que existem
- Retorne entre 3 e 8 sugestões nas recomendações
- SUGESTOES pode ser [] vazio quando a ação é salvar ou reproduzir
- Se o usuário pedir para reproduzir algo, coloque ACAO: reproduzir e o item em ALVO
- Se o usuário pedir para salvar algo, coloque ACAO: salvar e o item em ALVO
- Se o usuário pedir algo que não é sobre filmes/séries, responda educadamente que você só ajuda com conteúdo audiovisual
- Se o usuário cumprimentar, se apresente como Diobot e pergunte o que ele quer assistir
- Quando recomendar, use a biblioteca do usuário como referência para entender os gostos dele
- Se o usuário pedir "coloca pra assistir" ou "bota pra rodar" ou similar, use ACAO: reproduzir
- Se o usuário pedir "salva" ou "adiciona na biblioteca" ou similar, use ACAO: salvar
- Priorize itens dos dados TMDB em tempo real para garantir IDs corretos"""
    }

    private val conversationHistory = java.util.Collections.synchronizedList(mutableListOf<ChatMessage>())

    fun clearHistory() {
        synchronized(conversationHistory) { conversationHistory.clear() }
    }

    suspend fun chat(userMessage: String, libraryTitles: List<String> = emptyList()): DiobotResponse = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.OPENAI_API_KEY
        Log.d(TAG, "chat() called. API key present: ${apiKey.isNotBlank()}, key length: ${apiKey.length}")
        if (apiKey.isBlank()) {
            return@withContext DiobotResponse(
                message = "API key do OpenAI não configurada. Adicione OPENAI_API_KEY no local.properties.",
                suggestions = emptyList()
            )
        }

        // 1. Analyze user message and fetch TMDB data in parallel
        val plan = analyzeUserMessage(userMessage)
        val tmdbContext = try {
            fetchTmdbContext(plan)
        } catch (e: Exception) {
            Log.w(TAG, "TMDB context fetch failed: ${e.message}")
            ""
        }

        synchronized(conversationHistory) {
            conversationHistory.add(ChatMessage(role = "user", content = userMessage))
        }

        val messages = mutableListOf(
            ChatMessage(role = "system", content = buildSystemPrompt(libraryTitles, tmdbContext))
        )
        synchronized(conversationHistory) {
            messages.addAll(conversationHistory.takeLast(10))
        }

        try {
            val request = ChatCompletionRequest(messages = messages)
            val response = openAiApi.chatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )

            if (!response.isSuccessful) {
                val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                Log.e(TAG, "OpenAI API error: ${response.code()} $errorBody")
                val userMsg = when (response.code()) {
                    401 -> "API key inválida. Verifique OPENAI_API_KEY no local.properties."
                    429 -> {
                        val isQuota = errorBody?.contains("insufficient_quota") == true
                        if (isQuota) "Sem créditos na conta OpenAI. Adicione saldo em platform.openai.com"
                        else "Limite de requisições atingido. Aguarde um momento."
                    }
                    500, 502, 503 -> "Servidor da OpenAI indisponível. Tente novamente."
                    else -> "Erro ${response.code()} ao processar. Tenta de novo?"
                }
                synchronized(conversationHistory) {
                    if (conversationHistory.isNotEmpty()) conversationHistory.removeLastOrNull()
                }
                return@withContext DiobotResponse(message = userMsg, suggestions = emptyList())
            }

            val content = response.body()?.choices?.firstOrNull()?.message?.content ?: ""
            synchronized(conversationHistory) {
                conversationHistory.add(ChatMessage(role = "assistant", content = content))
            }

            parseResponse(content)
        } catch (e: Exception) {
            Log.e(TAG, "DiobotService error: ${e.javaClass.simpleName}: ${e.message}", e)
            DiobotResponse(
                message = "Erro de conexão: ${e.javaClass.simpleName}. Verifique a internet.",
                suggestions = emptyList()
            )
        }
    }

    private fun parseResponse(content: String): DiobotResponse {
        val messageMatch = Regex("MENSAGEM:\\s*(.+?)(?=\\nACAO:|\\nSUGESTOES:|$)", RegexOption.DOT_MATCHES_ALL)
            .find(content)
        val actionMatch = Regex("ACAO:\\s*(\\S+)").find(content)
        val targetMatch = Regex("ALVO:\\s*(\\{.+?\\})", RegexOption.DOT_MATCHES_ALL).find(content)
        val suggestionsMatch = Regex("SUGESTOES:\\s*(\\[.+])", RegexOption.DOT_MATCHES_ALL)
            .find(content)

        val message = messageMatch?.groupValues?.get(1)?.trim() ?: content.trim()
        val actionStr = actionMatch?.groupValues?.get(1)?.trim()?.lowercase()

        val target = targetMatch?.groupValues?.get(1)?.let { json ->
            parseSingleSuggestion(json)
        }

        val action = when (actionStr) {
            "salvar" -> target?.let { DiobotAction.SaveToLibrary(enrichSuggestions(listOf(it)).first()) }
            "reproduzir" -> target?.let { DiobotAction.Play(enrichSuggestions(listOf(it)).first()) }
            else -> null
        }

        val suggestions = suggestionsMatch?.groupValues?.get(1)?.let { json ->
            parseSuggestionsList(json)
        } ?: emptyList()

        return DiobotResponse(message = message, suggestions = enrichSuggestions(suggestions), action = action)
    }

    private fun enrichSuggestions(suggestions: List<DiobotSuggestion>): List<DiobotSuggestion> {
        return suggestions.map { s ->
            val poster = s.posterPath ?: s.tmdbId?.let { lastTmdbPosterCache[it] }
            val imdb = s.imdbId ?: s.tmdbId?.let { lastTmdbImdbCache[it] }
            s.copy(posterPath = poster, imdbId = imdb)
        }
    }

    private fun parseSingleSuggestion(json: String): DiobotSuggestion? {
        return try {
            val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            val adapter = moshi.adapter<Map<String, Any?>>(type)
            val map = adapter.fromJson(json) ?: return null
            val title = map["title"]?.toString() ?: return null
            val itemType = map["type"]?.toString() ?: "movie"
            val tmdbId = (map["tmdbId"] as? Number)?.toInt()
            val imdbId = map["imdbId"]?.toString()?.takeIf { it != "null" }
            DiobotSuggestion(title = title, type = itemType, tmdbId = tmdbId, imdbId = imdbId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse target JSON: ${e.message}")
            null
        }
    }

    private fun parseSuggestionsList(json: String): List<DiobotSuggestion> {
        return try {
            val type = Types.newParameterizedType(List::class.java, Map::class.java)
            val adapter = moshi.adapter<List<Map<String, Any?>>>(type)
            val list = adapter.fromJson(json) ?: emptyList()
            list.mapNotNull { map ->
                val title = map["title"]?.toString() ?: return@mapNotNull null
                val itemType = map["type"]?.toString() ?: "movie"
                val tmdbId = (map["tmdbId"] as? Number)?.toInt()
                val imdbId = map["imdbId"]?.toString()?.takeIf { it != "null" }
                DiobotSuggestion(title = title, type = itemType, tmdbId = tmdbId, imdbId = imdbId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse suggestions JSON: ${e.message}")
            emptyList()
        }
    }
}
