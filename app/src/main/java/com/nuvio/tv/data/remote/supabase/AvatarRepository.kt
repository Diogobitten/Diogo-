package com.nuvio.tv.data.remote.supabase

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.TmdbApi
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null
)

@Singleton
class AvatarRepository @Inject constructor(
    private val postgrest: Postgrest,
    private val tmdbApi: TmdbApi,
    private val okHttpClient: OkHttpClient
) {
    private var cachedCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        cachedCatalog?.let { return it }

        // Try Supabase first
        val supabaseUrl = BuildConfig.SUPABASE_URL
        if (supabaseUrl.isNotBlank()) {
            try {
                val response = postgrest.rpc("get_avatar_catalog")
                val remote = response.decodeList<SupabaseAvatarCatalogItem>()
                val catalog = remote.map { item ->
                    AvatarCatalogItem(
                        id = item.id,
                        displayName = item.displayName,
                        imageUrl = avatarImageUrl(item.storagePath),
                        category = item.category,
                        sortOrder = item.sortOrder,
                        bgColor = item.bgColor
                    )
                }
                if (catalog.isNotEmpty()) {
                    cachedCatalog = catalog
                    return catalog
                }
            } catch (e: Exception) {
                Log.w("AvatarRepository", "Supabase avatar catalog failed, falling back to DiceBear", e)
            }
        }

        // Fallback: DiceBear + TMDB actors + Superhero + Rick and Morty + Anime
        val catalog = mutableListOf<AvatarCatalogItem>()
        catalog.addAll(generateDiceBearAvatars())
        try {
            catalog.addAll(fetchTmdbPopularPeople())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "TMDB popular people fetch failed", e)
        }
        try {
            catalog.addAll(fetchSuperheroCharacters())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Superhero characters fetch failed", e)
        }
        try {
            catalog.addAll(fetchRickAndMortyCharacters())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Rick and Morty characters fetch failed", e)
        }
        try {
            catalog.addAll(fetchAnimeCharacters())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Anime characters fetch failed", e)
        }
        try {
            catalog.addAll(fetchSaintSeiyaCharacters())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Saint Seiya characters fetch failed", e)
        }
        try {
            catalog.addAll(fetchPokemonCharacters())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Pokemon characters fetch failed", e)
        }
        try {
            catalog.addAll(fetchCartoonNetworkCharacters())
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Cartoon Network characters fetch failed", e)
        }
        if (catalog.isNotEmpty()) {
            cachedCatalog = catalog
        }
        return catalog
    }

    /**
     * Generates a catalog of cartoon-style avatars using the DiceBear API.
     * Each style produces unique character avatars from deterministic seeds.
     * SVG format — requires SvgDecoder in Coil image requests.
     */
    private fun generateDiceBearAvatars(): List<AvatarCatalogItem> {
        val allItems = mutableListOf<AvatarCatalogItem>()

        val baseUrl = "https://api.dicebear.com/9.x"

        // Style configs: style name → (category label, background color, seed names)
        val styles = listOf(
            DiceBearStyle(
                style = "adventurer",
                category = "adventurer",
                bgColor = "#6C63FF",
                seeds = listOf(
                    "Felix", "Aneka", "Milo", "Luna", "Sophie",
                    "Jasper", "Nala", "Oliver", "Cleo", "Zara",
                    "Leo", "Mia", "Oscar", "Ruby", "Finn",
                    "Aria", "Max", "Lily", "Sam", "Nova"
                )
            ),
            DiceBearStyle(
                style = "big-ears",
                category = "big ears",
                bgColor = "#FF6B6B",
                seeds = listOf(
                    "Charlie", "Daisy", "Bear", "Pepper", "Coco",
                    "Buddy", "Rosie", "Teddy", "Maple", "Ziggy",
                    "Biscuit", "Honey", "Mochi", "Peanut", "Waffles",
                    "Cookie", "Ginger", "Mocha", "Toffee", "Sprout"
                )
            ),
            DiceBearStyle(
                style = "avataaars",
                category = "cartoon",
                bgColor = "#4ECDC4",
                seeds = listOf(
                    "Alex", "Jordan", "Riley", "Casey", "Morgan",
                    "Taylor", "Quinn", "Avery", "Blake", "Drew",
                    "Sage", "River", "Sky", "Phoenix", "Rowan",
                    "Kai", "Reese", "Emery", "Harley", "Jude"
                )
            ),
            DiceBearStyle(
                style = "fun-emoji",
                category = "emoji",
                bgColor = "#FFD93D",
                seeds = listOf(
                    "Happy", "Sunny", "Star", "Heart", "Rainbow",
                    "Cloud", "Moon", "Fire", "Wave", "Spark",
                    "Bloom", "Breeze", "Coral", "Dawn", "Echo",
                    "Frost", "Glow", "Haze", "Iris", "Jade"
                )
            ),
            DiceBearStyle(
                style = "lorelei",
                category = "lorelei",
                bgColor = "#A78BFA",
                seeds = listOf(
                    "Aurora", "Celeste", "Diana", "Elena", "Freya",
                    "Grace", "Helena", "Iris", "Julia", "Kira",
                    "Lena", "Maya", "Nina", "Olivia", "Petra",
                    "Rosa", "Stella", "Thea", "Uma", "Vera"
                )
            ),
            DiceBearStyle(
                style = "personas",
                category = "personas",
                bgColor = "#45B7D1",
                seeds = listOf(
                    "Atlas", "Blaze", "Cruz", "Dante", "Ezra",
                    "Flynn", "Gage", "Hugo", "Ivan", "Knox",
                    "Lance", "Marco", "Noel", "Orion", "Pierce",
                    "Reed", "Shane", "Troy", "Vince", "Wade"
                )
            )
        )

        styles.forEachIndexed { styleIndex, diceBearStyle ->
            diceBearStyle.seeds.forEachIndexed { seedIndex, seed ->
                val avatarUrl = "$baseUrl/${diceBearStyle.style}/svg?seed=$seed&radius=50"
                allItems.add(
                    AvatarCatalogItem(
                        id = "db_${diceBearStyle.style}_$seed",
                        displayName = seed,
                        imageUrl = avatarUrl,
                        category = diceBearStyle.category,
                        sortOrder = styleIndex * 100 + seedIndex,
                        bgColor = diceBearStyle.bgColor
                    )
                )
            }
        }

        return allItems
    }

    /**
     * Fetches popular actors/actresses from TMDB and maps them to avatar catalog items.
     * Fetches 5 pages (100 people), filters to those with profile photos.
     */
    private suspend fun fetchTmdbPopularPeople(): List<AvatarCatalogItem> {
        val apiKey = BuildConfig.TMDB_API_KEY
        if (apiKey.isBlank()) return emptyList()

        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 700 // after DiceBear styles (6 styles × 100 = 600)

        for (page in 1..2) {
            try {
                val response = tmdbApi.getPopularPeople(apiKey = apiKey, page = page)
                val people = response.body()?.results.orEmpty()
                people.forEach { person ->
                    val profilePath = person.profilePath ?: return@forEach
                    val imageUrl = "https://image.tmdb.org/t/p/w185$profilePath"
                    val name = person.name ?: return@forEach
                    items.add(
                        AvatarCatalogItem(
                            id = "tmdb_person_${person.id}",
                            displayName = name,
                            imageUrl = imageUrl,
                            category = "actors",
                            sortOrder = baseSortOrder + items.size
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("AvatarRepository", "TMDB popular people page $page failed: ${e.message}")
                break
            }
        }

        Log.d("AvatarRepository", "TMDB popular people: ${items.size} avatars loaded")
        return items
    }

    /**
     * Fetches superhero/villain character images from the Akabab Superhero API.
     * Images hosted on cdn.jsdelivr.net (stable). Covers Marvel, DC, and others.
     * No API key required.
     */
    private suspend fun fetchSuperheroCharacters(): List<AvatarCatalogItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://akabab.github.io/superhero-api/api/all.json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w("AvatarRepository", "Superhero API returned ${response.code}")
            return@withContext emptyList()
        }

        val body = response.body?.string() ?: return@withContext emptyList()
        val jsonArray = JSONArray(body)
        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 800

        // Limit to first 100 characters to avoid memory issues on TV
        val limit = minOf(jsonArray.length(), 100)
        for (i in 0 until limit) {
            val obj = jsonArray.getJSONObject(i)
            val id = obj.optInt("id", -1)
            val name = obj.optString("name", "").takeIf { it.isNotBlank() } ?: continue
            val images = obj.optJSONObject("images") ?: continue
            val imageUrl = images.optString("md", "").takeIf { it.isNotBlank() } ?: continue

            // Determine category from publisher
            val biography = obj.optJSONObject("biography")
            val publisher = biography?.optString("publisher", "")?.lowercase() ?: ""
            val category = when {
                publisher.contains("marvel") -> "marvel"
                publisher.contains("dc") -> "dc comics"
                publisher.contains("star wars") -> "star wars"
                else -> "heroes"
            }

            items.add(
                AvatarCatalogItem(
                    id = "hero_$id",
                    displayName = name,
                    imageUrl = imageUrl,
                    category = category,
                    sortOrder = baseSortOrder + i
                )
            )
        }

        Log.d("AvatarRepository", "Superhero characters: ${items.size} avatars loaded")
        items
    }

    /**
     * Fetches Rick and Morty character avatars.
     * Images hosted on rickandmortyapi.com (stable, square format).
     * No API key required.
     */
    private suspend fun fetchRickAndMortyCharacters(): List<AvatarCatalogItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 1400

        // Fetch first page (20 characters)
        for (page in 1..1) {
            try {
                val request = Request.Builder()
                    .url("https://rickandmortyapi.com/api/character?page=$page")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) break

                val body = response.body?.string() ?: break
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: break

                for (i in 0 until results.length()) {
                    val char = results.getJSONObject(i)
                    val id = char.optInt("id", -1)
                    val name = char.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                    val image = char.optString("image", "").takeIf { it.isNotBlank() } ?: continue

                    items.add(
                        AvatarCatalogItem(
                            id = "rm_$id",
                            displayName = name,
                            imageUrl = image,
                            category = "rick and morty",
                            sortOrder = baseSortOrder + items.size
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("AvatarRepository", "Rick and Morty page $page failed: ${e.message}")
                break
            }
        }

        Log.d("AvatarRepository", "Rick and Morty characters: ${items.size} avatars loaded")
        items
    }

    /**
     * Fetches top anime characters from Jikan API (MyAnimeList).
     * No API key required. Rate limited — fetches 2 pages (50 characters).
     */
    private suspend fun fetchAnimeCharacters(): List<AvatarCatalogItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 1500

        try {
            val request = Request.Builder()
                .url("https://api.jikan.moe/v4/top/characters?page=1&limit=25")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()

            for (i in 0 until data.length()) {
                val char = data.getJSONObject(i)
                val id = char.optInt("mal_id", -1)
                val name = char.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                val images = char.optJSONObject("images")
                    ?.optJSONObject("jpg") ?: continue
                val imageUrl = images.optString("image_url", "").takeIf { it.isNotBlank() } ?: continue

                items.add(
                    AvatarCatalogItem(
                        id = "anime_$id",
                        displayName = name,
                        imageUrl = imageUrl,
                        category = "anime",
                        sortOrder = baseSortOrder + items.size
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Anime characters fetch failed: ${e.message}")
        }

        Log.d("AvatarRepository", "Anime characters: ${items.size} avatars loaded")
        items
    }

    /**
     * Fetches Saint Seiya (Cavaleiros do Zodíaco) characters from Jikan API.
     * Uses the anime ID 1254 (original series) character list.
     * No API key required. Includes delay to respect Jikan rate limits.
     */
    private suspend fun fetchSaintSeiyaCharacters(): List<AvatarCatalogItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 1600

        // Delay to avoid Jikan rate limit (3 req/s) after anime top characters calls
        Thread.sleep(2000)

        try {
            val request = Request.Builder()
                .url("https://api.jikan.moe/v4/anime/1254/characters")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("AvatarRepository", "Saint Seiya API returned ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()

            // Limit to 30 most prominent characters
            val limit = minOf(data.length(), 30)
            for (i in 0 until limit) {
                val entry = data.getJSONObject(i)
                val char = entry.optJSONObject("character") ?: continue
                val id = char.optInt("mal_id", -1)
                val name = char.optString("name", "").takeIf { it.isNotBlank() } ?: continue
                val imageUrl = char.optJSONObject("images")
                    ?.optJSONObject("jpg")
                    ?.optString("image_url", "")
                    ?.takeIf { it.isNotBlank() } ?: continue

                items.add(
                    AvatarCatalogItem(
                        id = "seiya_$id",
                        displayName = name,
                        imageUrl = imageUrl,
                        category = "saint seiya",
                        sortOrder = baseSortOrder + i
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("AvatarRepository", "Saint Seiya fetch failed: ${e.message}")
        }

        Log.d("AvatarRepository", "Saint Seiya characters: ${items.size} avatars loaded")
        items
    }

    /**
     * Fetches Pokémon official artwork sprites from PokeAPI.
     * Images hosted on raw.githubusercontent.com (stable).
     * No API key required. Fetches first 50 Pokémon (Gen 1 classics).
     */
    private suspend fun fetchPokemonCharacters(): List<AvatarCatalogItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 1700

        // PokeAPI sprites follow a predictable URL pattern — no need to fetch each one
        val pokemonNames = listOf(
            1 to "Bulbasaur", 4 to "Charmander", 6 to "Charizard",
            7 to "Squirtle", 9 to "Blastoise", 25 to "Pikachu",
            26 to "Raichu", 39 to "Jigglypuff", 52 to "Meowth",
            54 to "Psyduck", 58 to "Growlithe", 63 to "Abra",
            94 to "Gengar", 129 to "Magikarp", 130 to "Gyarados",
            131 to "Lapras", 133 to "Eevee", 143 to "Snorlax",
            150 to "Mewtwo", 151 to "Mew", 152 to "Chikorita",
            155 to "Cyndaquil", 158 to "Totodile", 175 to "Togepi",
            196 to "Espeon", 197 to "Umbreon", 248 to "Tyranitar",
            249 to "Lugia", 250 to "Ho-Oh", 252 to "Treecko",
            255 to "Torchic", 258 to "Mudkip", 282 to "Gardevoir",
            384 to "Rayquaza", 385 to "Jirachi", 393 to "Piplup",
            448 to "Lucario", 658 to "Greninja", 700 to "Sylveon",
            778 to "Mimikyu", 812 to "Rillaboom", 815 to "Cinderace",
            818 to "Inteleon", 887 to "Dragapult", 906 to "Sprigatito",
            909 to "Fuecoco", 912 to "Quaxly", 1007 to "Koraidon",
            1008 to "Miraidon", 3 to "Venusaur", 9 to "Blastoise"
        ).distinctBy { it.first }

        pokemonNames.forEachIndexed { index, (id, name) ->
            val imageUrl = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/$id.png"
            items.add(
                AvatarCatalogItem(
                    id = "poke_$id",
                    displayName = name,
                    imageUrl = imageUrl,
                    category = "pokémon",
                    sortOrder = baseSortOrder + index
                )
            )
        }

        Log.d("AvatarRepository", "Pokémon characters: ${items.size} avatars loaded")
        items
    }

    /**
     * Fetches Cartoon Network character images from multiple Fandom wikis.
     * Each wiki is queried in batch via MediaWiki API pageimages prop.
     * Images hosted on static.wikia.nocookie.net.
     */
    private suspend fun fetchCartoonNetworkCharacters(): List<AvatarCatalogItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<AvatarCatalogItem>()
        val baseSortOrder = 1800

        // wiki subdomain → list of page titles
        val wikiCharacters = listOf(
            "adventuretimewithfinnandjake" to listOf("Finn", "Jake", "Princess_Bubblegum", "Marceline", "Ice_King", "BMO", "Lumpy_Space_Princess", "Flame_Princess"),
            "dexterslab" to listOf("Dexter", "Dee_Dee", "Mandark"),
            "johnnybravo" to listOf("Johnny_Bravo_(character)"),
            "courage" to listOf("Courage", "Eustace_Bagge", "Muriel_Bagge"),
            "codename-kids-next-door" to listOf("Numbuh_1", "Numbuh_2", "Numbuh_3", "Numbuh_4", "Numbuh_5"),
            "powerpuff" to listOf("Blossom_(1998_TV_series)", "Bubbles_(1998_TV_series)", "Buttercup_(1998_TV_series)", "Mojo_Jojo"),
            "samuraijack" to listOf("Jack", "Aku"),
            "ben10" to listOf("Ben_Tennyson", "Gwen_Tennyson", "Kevin_Levin", "Vilgax"),
            "theamazingworldofgumball" to listOf("Gumball_Watterson", "Darwin_Watterson", "Anais_Watterson", "Nicole_Watterson"),
            "steven-universe" to listOf("Steven_Universe_(character)", "Garnet", "Pearl_(Steven_Universe)", "Amethyst_(Steven_Universe)"),
            "regularshow" to listOf("Mordecai", "Rigby"),
            "ededdneddy" to listOf("Ed", "Edd", "Eddy"),
        )

        for ((wiki, titles) in wikiCharacters) {
            try {
                val titlesParam = titles.joinToString("|")
                val url = "https://$wiki.fandom.com/api.php?action=query&titles=$titlesParam&prop=pageimages&format=json&pithumbsize=300&piprop=thumbnail"

                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: continue

                val keys = pages.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "-1") continue
                    val page = pages.getJSONObject(key)
                    val title = page.optString("title", "").takeIf { it.isNotBlank() } ?: continue
                    val thumbnail = page.optJSONObject("thumbnail")
                        ?.optString("source", "")
                        ?.takeIf { it.isNotBlank() } ?: continue

                    // Clean up display name: remove parenthetical suffixes
                    val displayName = title.replace(Regex("\\s*\\(.*\\)"), "").replace("_", " ")

                    items.add(
                        AvatarCatalogItem(
                            id = "cn_${wiki}_${page.optInt("pageid", 0)}",
                            displayName = displayName,
                            imageUrl = thumbnail,
                            category = "cartoon network",
                            sortOrder = baseSortOrder + items.size
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("AvatarRepository", "Fandom wiki $wiki failed: ${e.message}")
            }
        }

        Log.d("AvatarRepository", "Cartoon Network characters: ${items.size} avatars loaded")
        items
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedCatalog = null
    }

    private data class DiceBearStyle(
        val style: String,
        val category: String,
        val bgColor: String,
        val seeds: List<String>
    )

    companion object {
        fun avatarImageUrl(storagePath: String): String {
            val baseUrl = BuildConfig.AVATAR_PUBLIC_BASE_URL.trimEnd('/')
            return if (baseUrl.isNotEmpty()) "$baseUrl/$storagePath" else storagePath
        }
    }
}
