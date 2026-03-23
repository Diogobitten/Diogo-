# NuvioTV — Project Structure

## Top Level
```
app/                  # Main application module
baselineprofile/      # Baseline profile generation module
gradle/               # Gradle wrapper and version catalog (libs.versions.toml)
scripts/              # Build/release scripts
assets/               # Brand assets (logos, images)
```

## App Module — Source Layout

All source code lives under `app/src/main/java/com/nuvio/tv/`. The architecture follows a clean-ish layered pattern with `domain`, `data`, `core`, and `ui` packages.

```
com/nuvio/tv/
├── MainActivity.kt              # Single Activity entry point
├── NuvioApplication.kt          # @HiltAndroidApp, Coil ImageLoaderFactory
├── ModernSidebarBlurPanel.kt    # Shared sidebar blur component
│
├── core/                        # Cross-cutting infrastructure
│   ├── auth/                    # AuthManager (Supabase session handling)
│   ├── di/                      # Hilt modules: NetworkModule, RepositoryModule, ProfileModule, SupabaseModule
│   ├── network/                 # IPv4FirstDns, NetworkResult sealed class, SafeApiCall
│   ├── player/                  # ExoPlayer utilities: frame rate, subtitle format, stream auto-play
│   ├── plugin/                  # QuickJS plugin manager and runtime
│   ├── profile/                 # ProfileManager (multi-profile support)
│   ├── qr/                      # QR code generation
│   ├── server/                  # NanoHTTPD local servers for addon/repo config and auth login
│   ├── sync/                    # Sync services: addons, library, plugins, profiles, watch progress
│   ├── tmdb/                    # TMDB metadata service
│   └── util/                    # Misc utilities (release info parsing)
│
├── data/                        # Data layer
│   ├── local/                   # DataStore preferences (player settings, layout, debug, etc.)
│   ├── mapper/                  # DTO → domain model mappers (Addon, Catalog, Meta, Stream)
│   ├── remote/
│   │   ├── api/                 # Retrofit API interfaces (AddonApi, TraktApi, TmdbApi, etc.)
│   │   ├── dto/                 # Network response DTOs
│   │   └── supabase/            # Supabase-specific data access (AvatarRepository with TMDB fallback)
│   ├── repository/              # Repository implementations (*Impl classes), AI/TMDB discovery services
│   ├── trailer/                 # Trailer data handling
│   └── themesong/               # Theme song service (ThemerrDB + YouTube audio extraction)
│
├── di/                          # Additional Hilt modules (PluginModule)
│
├── domain/                      # Domain layer (pure Kotlin, no Android deps)
│   ├── model/                   # Domain models (Addon, Meta, Stream, UserProfile, etc.)
│   └── repository/              # Repository interfaces
│
├── ui/                          # Presentation layer (Compose)
│   ├── components/              # Shared composables (cards, carousels, dialogs, skeletons)
│   ├── navigation/              # NavHost, Screen sealed class (route definitions)
│   ├── screens/                 # Feature screens, each in its own package:
│   │   ├── home/                # Home screen (Classic/Grid/Modern layouts, ViewModel, Ken Burns hero animation)
│   │   ├── detail/              # Content detail screen
│   │   ├── player/              # Video player screen
│   │   ├── stream/              # Stream selection screen
│   │   ├── search/              # Search / discover screen
│   │   ├── library/             # User library screen
│   │   ├── calendar/            # Calendar screen (monthly grid + daily items panel)
│   │   ├── settings/            # Settings screens
│   │   ├── addon/               # Addon management
│   │   ├── plugin/              # Plugin management
│   │   ├── profile/             # Profile selection/management
│   │   ├── account/             # Account / auth screens
│   │   ├── cast/                # Cast/crew detail screen
│   │   └── diobot/              # Diobot AI Concierge screen
│   ├── theme/                   # Color, Theme, Typography definitions
│   └── util/                    # UI utilities (blur, language, formatting)
│
└── updater/                     # In-app update feature
    ├── model/                   # Update data models
    ├── ui/                      # Update UI components
    ├── UpdateRepository.kt
    ├── UpdateViewModel.kt
    └── ...
```

## Key Patterns
- Single Activity architecture with Compose Navigation
- Screen routes defined as `sealed class Screen` with `data object` variants
- Each screen package typically contains: `*Screen.kt` (composable), `*ViewModel.kt` (Hilt ViewModel), `*UiState.kt` (state model)
- Repository pattern: interfaces in `domain/repository/`, implementations in `data/repository/`
- DI bindings: `@Provides` for concrete instances (NetworkModule), `@Binds` for interface→impl (RepositoryModule)
- DTOs in `data/remote/dto/`, mapped to domain models via `data/mapper/`
- `NetworkResult<T>` sealed class wraps API responses (Success/Error/Loading)
- Local AARs in `app/libs/` for forked ExoPlayer — stock `media3-exoplayer` and `media3-ui` are globally excluded

## Home Screen Performance
- `NuvioApplication` configures Coil with aggressive caching (50% memory, 250MB disk) and parallel decoding (4 decoders, 4 fetchers, 4 bitmap factory)
- All image requests use explicit `memoryCacheKey` and `diskCacheKey` with URL + size for deterministic cache hits
- `allowHardware(true)` on all image requests (detail backdrop, cast photos, episode thumbnails, company logos, hero logo, grid/content cards, new releases, continue watching)
- `respectCacheHeaders(false)` to skip HTTP revalidation
- Both Classic and Modern home `LazyColumn`s use `LazyListPrefetchStrategy(nestedPrefetchItemCount = 5)` to spread card composition across frames before rows scroll in
- D-pad key repeat throttling (120ms in Grid, 80ms in Classic and Modern) to prevent HWUI overload when holding a direction key
- Modern home caches built carousel rows/items (`ModernCarouselRowBuildCache`) to skip recomposition of unchanged data
- Grid home `SectionDivider` display names are `remember`ed to avoid string operations on recomposition
- Hero backdrop updates debounced during rapid navigation to avoid unnecessary image loads
- Detail screen backdrop uses `crossfade(false)` for instant display on navigation (no fade-in delay)
- `MetaDetailsViewModel.applyMetaWithEnrichment()` launches `loadMoreLikeThisAsync`, `loadMDBListRatings`, and `enrichMeta` all in parallel — MDBList ratings no longer wait for enrichment to complete

## Avatar System
- `AvatarRepository` tries Supabase catalog first, falls back to multiple public APIs when Supabase is unavailable
- Fallback avatar sources (all fetched in parallel, cached in-memory):
  - DiceBear: 6 cartoon styles × 20 seeds = 120 SVG avatars (`api.dicebear.com`)
  - TMDB Popular People: 2 pages (40 actors) via `TmdbApi.getPopularPeople()` — `w185` profile images
  - Akabab Superhero API: top 100 heroes/villains from `akabab.github.io/superhero-api/api/all.json` — categorized by publisher (Marvel, DC Comics, Star Wars, Heroes). Images on `cdn.jsdelivr.net` (stable)
  - Rick and Morty API: 20 characters from `rickandmortyapi.com/api/character` — square avatar images
  - Jikan API (top anime): 25 top characters from `api.jikan.moe/v4/top/characters` — MyAnimeList images
  - Jikan API (Saint Seiya): characters from anime ID 1254 (limited to 30) — 2s delay before call to respect rate limits after anime top characters
  - PokeAPI: 48 hardcoded iconic Pokémon with predictable sprite URLs from `raw.githubusercontent.com/PokeAPI/sprites/` — no API calls needed
  - Fandom MediaWiki API (Cartoon Network): batch queries to 12 show-specific wikis (Adventure Time, Dexter's Lab, Powerpuff Girls, Samurai Jack, Ben 10, etc.) via `{wiki}.fandom.com/api.php?action=query&prop=pageimages`
- Total: ~400 avatars across 16 categories (Actors, Marvel, DC Comics, Star Wars, Heroes, Rick and Morty, Anime, Cavaleiros do Zodíaco, Pokémon, Cartoon Network, + 6 DiceBear styles)
- `AvatarPickerGrid` renders category tabs + `LazyColumn` with `Row` items (lazy rendering to handle large catalogs without OOM on TV)
- `ProfileAvatarCircle` and `AvatarGridItem` detect SVG URLs (containing "/svg") and add `SvgDecoder.Factory()` to Coil image requests
- Avatar images are circular with focus/selection ring animations and scale-on-focus
- Profile creation is fully local via `ProfileManager` — Supabase sync fails silently when unconfigured
- OkHttpClient injected into `AvatarRepository` for direct HTTP calls to non-Retrofit APIs

## Theme Song System
- `ThemeSongService` fetches theme song YouTube URLs from ThemerrDB (`https://app.lizardbyte.dev/ThemerrDB/{media_type}/themoviedb/{tmdb_id}.json`)
- Supports both movies (`movies/`) and TV shows (`tv_shows/`) — keyed by TMDB ID
- Audio extracted from YouTube via existing `InAppYouTubeExtractor` (prefers separate audio stream, falls back to combined)
- `ThemeSongPlayer` composable: invisible ExoPlayer instance, audio-only, loops (`REPEAT_MODE_ALL`), fades in to 30% volume over 2s
- Pauses during manual trailer playback (`shouldPause = isTrailerPlaying && !isAmbientTrailer`), continues during ambient trailer (muted video, no audio conflict)
- Keeps playing across navigation (e.g. detail → stream selection) — only stops when composable is disposed (leaving detail screen)
- Player is released on composable disposal (screen exit)
- Results cached in-memory per session (`ConcurrentHashMap`) with negative caching for misses
- No API key required — ThemerrDB is a public community database

## Ambient Trailer System
- Automatic background trailer loop on detail screen when trailer URL is available
- Cycle: 3s backdrop (Ken Burns zoom) → 60s muted trailer (seeked +20s) → backdrop → repeat
- `MetaDetailsViewModel.startAmbientTrailerCycle()` manages the loop via coroutine job
- `isAmbientTrailer` flag in `MetaDetailsUiState` distinguishes ambient from manual trailer
- Trailer rendered with `cropToFill = true` and `overscanZoom = 1.15f` to fill entire backdrop area
- Backdrop only fades out after trailer's first frame is rendered (`onFirstFrameRendered` callback) — no black flash between transitions
- `TrailerPlayer` supports `initialSeekMs` parameter to skip ahead 20s into the trailer
- `TrailerPlayer` always uses `YoutubeChunkedDataSourceFactory` for all YouTube URLs (with or without separate audio) to avoid throttling
- During ambient: hero buttons/title stay visible, D-pad navigation not blocked, theme song continues playing
- Manual trailer button click (`handleTrailerButtonClick`) stops ambient cycle and opens full trailer with sound/controls
- After manual trailer ends, ambient cycle restarts automatically
- User interaction (old auto-trailer system) does not stop ambient cycle — only manual trailer button does
- Trailer button enabled by default (`detailPageTrailerButtonEnabled` defaults to `true` in `LayoutPreferenceDataStore`)

## Trailer URL Caching
- `TrailerService` caches resolved YouTube playback URLs in two levels: title/tmdbId cache and YouTube video ID cache
- Both caches use a 2-hour TTL (`YOUTUBE_CACHE_TTL_MS`) — expired entries are evicted and re-extracted on next access
- Prevents stale/expired `googlevideo.com` URLs from being reused across long sessions
- Negative cache entries (no trailer found) also expire after 2 hours

## Intro Video Splash
- `IntroVideoSplash` composable in `MainActivity.kt` plays `res/raw/dplus.mp4` on app startup
- Plays before any other UI (onboarding, profile selection, main content)
- ExoPlayer instance with `RESIZE_MODE_ZOOM` + `graphicsLayer(scaleX/scaleY = 1.15f)` to fill entire TV screen
- Automatically transitions to main app when video ends (`STATE_ENDED`)
- Safety timeout of 15s — skips intro if video hangs or fails to play
- Player creation wrapped in `runCatching` — skips immediately if ExoPlayer fails to initialize
- `onPlayerError` callback also skips intro on playback errors
- Player is released on composable disposal

## Calendar System
- `CalendarRepository` fetches upcoming episodes and movie releases for library items
- Supports `pastDays` parameter to also look back in time (used by Novidades row with `pastDays = 7`)
- Dual data source: Trakt personalized calendar (`calendars/my/shows` + `calendars/my/movies`) when authenticated, TMDB fallback for all users
- TMDB fallback resolves `tmdbId` from library entry IDs (often IMDB IDs like `tt1234567`) via `TmdbService.ensureTmdbId()`
- Series: fetches `getTvDetails` for `numberOfSeasons` and status, then `getTvSeasonDetails` for latest 2 seasons to find upcoming air dates
- Movies: checks `getMovieReleaseDates` for types 3 (theatrical), 4 (digital), 5 (physical) within the date range
- Trakt and TMDB results are merged with deduplication by `tmdbId + type`
- Trakt items enriched with TMDB poster/backdrop images
- `CalendarScreen` uses a split layout: left side = monthly grid with D-pad navigable day cells, right side = selected day's items panel
- Day cells show colored dots: blue (`#4FC3F7`) for episodes, orange (`#FFA726`) for movies; today highlighted in green
- Selected day panel shows list items with poster thumbnail, type dot, title, episode label (S1E2), and release type
- Month navigation via left/right arrows; 90-day data window
- Route: `Screen.Calendar` ("calendar"), positioned in sidebar between Library and Addons
- SVG icon: `res/raw/sidebar_calendar.svg`
- DTOs: `TraktCalendarShowDto`, `TraktCalendarMovieDto` in `data/remote/dto/trakt/`
- Domain model: `CalendarItem` with `CalendarItemType` (EPISODE, MOVIE)

## Novidades (New Releases) Row
- Horizontal row on Home screen showing library items that released new content today
- Appears below the hero/featured section, before continue watching and catalog rows
- Data sourced from `CalendarRepository.getCalendarItems(days = 14, pastDays = 7)`, filtered to `date == today` in `HomeViewModel.loadNewReleases()`
- `HomeViewModel` delays 2s after init for library to load before fetching calendar data
- `NewReleasesSection` composable renders a `LazyRow` of landscape cards (220×124dp) using backdrop images with `fillMaxWidth().height()` to ensure full card coverage
- Cards have colored badge overlays: green (`#66BB6A`) for "Nova temporada" (episode 1), blue (`#4FC3F7`) for "Novo episódio" (other episodes), orange (`#FFA726`) for movies ("Novidade")
- Episode cards show S1E2 label at bottom-right
- Clicking a card navigates to the detail screen via IMDB ID or TMDB ID, with `returnFocusSeason` and `returnFocusEpisode` parameters so the detail screen opens at the correct season/episode
- Supported in all three home layouts: Classic (LazyColumn item), Grid (full-span grid item), Modern (HeroCarouselRow)
- `HomeUiState.newReleases: List<CalendarItem>` holds the filtered items
- String resources: `new_releases`, `new_releases_new_season`, `new_releases_new_episode`, `new_releases_new`

## Streaming Services Row
- Horizontal row of compact cards (160×56dp) showing streaming service logos in white (or text fallback for unmapped services)
- Logo sources: Simple Icons CDN (`cdn.simpleicons.org/{slug}/white`) for Netflix, HBO MAX, Paramount+, MUBI, Crunchyroll, Apple TV+, YouTube; SVGL (`svgl.app/library/{name}.svg`) for Disney+, Prime Video; text fallback for Globoplay and others
- SVG logos rendered via Coil `SvgDecoder.Factory()` with `ColorFilter.tint(Color.White)` to ensure all logos appear white
- Auto-detected from the "Streaming Catalogs" addon: `updateCatalogRowsPipeline` finds `CatalogRow`s whose `addonName` contains "Streaming Catalog" and extracts distinct `catalogName`s
- `HomeUiState.streamingServiceNames: List<String>` holds the service names; `streamingServiceAddonName` holds the matched addon name
- `StreamingServicesRow` composable renders a `LazyRow` of `StreamingServiceCard`s with dark background (`#2A2A2E`), rounded corners (12dp), and 1.08x scale on focus
- Clicking a card: if the service has catalog rows of both movie and series types, a `StreamingTypePickerDialog` appears with "Movies" and "Series" buttons; if only one type exists, navigates directly to the "See All" screen
- `StreamingTypePickerDialog` uses `NuvioDialog` with `type_movie` and `type_series` string resources
- Appears in all three home layouts before "Novidades" and "Continuar Assistindo": Classic (LazyColumn item), Grid (full-span grid item), Modern (separate LazyColumn item before carousel rows)
- Row order in all layouts: Streaming Services → New Releases → Continue Watching → catalog rows
- Only visible when the "Streaming Catalogs" addon is installed and has loaded catalogs

## Sidebar Navigation
- `DrawerItem` data class supports `isDivider` (renders a visual separator line before the item) and `contentTypeFilter` (filters catalog rows by content type)
- Sidebar order: Home → [divider] → Movies → Series → [divider] → Search → Diobot → Library → Calendar → Addons → Settings
- Movies (`home_movies`) and Series (`home_series`) are separate routes that reuse `HomeScreen` with a `contentTypeFilter` parameter
- A single Activity-scoped `HomeViewModel` is shared across Home, Movies, and Series routes (created in `NuvioNavHost` via `hiltViewModel(activityViewModelStoreOwner)`); catalogs load once and switching tabs only applies an in-memory content-type filter
- `HomeViewModel.setContentTypeFilter()` triggers `updateCatalogRowsPipeline` which filters `CatalogRow` entries by `rawType`
- Both Modern (`ModernSidebarBlurPanel`) and Legacy (`LegacySidebarScaffold`) sidebars render dividers for items with `isDivider = true`
- Compact sidebar styling: Legacy items 42dp height / 18dp icons / 6dp spacing; Modern items 28dp icon circle / 17dp icons / 4dp spacing / `titleMedium` typography
- SVG icons for sidebar items live in `res/raw/sidebar_*.svg`

## Auth Login System (Local HTTP Server)
- `AuthLoginServer` (NanoHTTPD) starts on port 8090+ and serves a login/signup web page to the user's phone
- `AuthLoginWebPage` renders a responsive HTML form with Supabase Auth REST API integration (login + signup with auto-login after signup)
- Flow: TV shows QR code with `http://<TV_IP>:<port>` → user scans on phone → login/signup form → tokens posted to `/api/auth-callback` → `AuthLoginServer.awaitTokens()` returns tokens → `AuthManager.importSession()` imports into Supabase SDK
- `AccountViewModel.startQrLogin()` manages the full lifecycle: starts server on `Dispatchers.IO` with 200ms settle delay, generates QR bitmap, polls for tokens via `awaitTokens(timeoutSeconds = 300)`, imports session, pulls remote data
- `stopLocalAuthServer()` stops HTTP server first (releasing port), then cancels polling job; called on: `clearQrLoginSession()`, `onCleared()`, `signOut()`, and before starting a new login attempt
- `signOut()` clears ALL local synced data (library, watch progress, watched items, profiles) before signing out of Supabase — resets profiles to default "Profile 1" and sets active profile to 1
- `pullRemoteData()` restores profiles from Supabase via `ProfileSyncService.pullFromRemote()` on sign in
- `pushLocalDataToRemote()` pushes profiles to Supabase via `ProfileSyncService.pushToRemote()` on sign up
- Server binds on `0.0.0.0` for external access; uses `daemon = true` threads; tries ports 8090–8109
- No Edge Functions or external web app required — fully local network, only Supabase Auth REST API calls from the phone browser
- QR code scanners on phones may upgrade `http://` to `https://` — the URL is also displayed as text on the TV screen so users can type it manually
- Supabase dashboard config: Email provider enabled, "Confirm email" disabled for instant signup
- `AuthManager.importSession(accessToken, refreshToken)` wraps `auth.importAuthToken()` for external token import

## Branding
- App name: "Diogo+" (set in `app_name` string resource across all locales)
- Logo: `dplus_logo.png` in `res/drawable/` — used in profile selection (280×70dp), QR login (85% width × 90dp), legacy sidebar (fullWidth × 56dp), modern sidebar (82% width × 48dp)
- App icon: `ic_launcher.png` in all mipmap densities — center-cropped square from `dplus-icon.png`
- TV banner: `banner.png` in `mipmap-xhdpi` (320×180) — resized from `dplus-icon.png`
- Source images in project root: `dplus1.png` (logo), `dplus-icon.png` (icon/banner source)
- After changing icons/banner, clear Google TV launcher cache: `adb shell pm clear com.google.android.apps.tv.launcherx`
- Localized strings for 20+ languages in `res/values-*/`
- XML configs in `res/xml/` (file_paths, locale_config)
- Custom fonts in `res/font/`

## Tests
- Unit tests mirror main source tree under `app/src/test/java/com/nuvio/tv/`
- JUnit 4 + MockK + coroutines-test
- Benchmark/instrumented tests in `baselineprofile/` module

## Trakt Discovery System
- `TraktDiscoveryService` (`data/repository/`) fetches 4 discovery categories from Trakt API: Trending, Recommended, Anticipated, Popular
- Each category fetches both movies and shows (8 parallel API calls), merged into a single mixed `CatalogRow` per category
- Trakt API endpoints: `GET /movies/trending`, `GET /shows/trending`, `GET /movies/popular`, `GET /shows/popular`, `GET /movies/anticipated`, `GET /shows/anticipated`, `GET /recommendations/movies` (auth required), `GET /recommendations/shows` (auth required)
- Popular endpoints return flat `TraktMovieDto`/`TraktShowDto` objects; Trending/Anticipated/Recommended return wrapped DTOs with watchers/listCount/userCount
- DTOs in `data/remote/dto/trakt/TraktDiscoveryDtos.kt`: `TraktTrendingMovieDto`, `TraktTrendingShowDto`, `TraktAnticipatedMovieDto`, `TraktAnticipatedShowDto`, `TraktRecommendedMovieDto`, `TraktRecommendedShowDto`
- Items enriched with TMDB images: poster (`w500`), backdrop (`original`), and IMDB ID for navigation — fetched via `TmdbApi.getMovieDetails()`/`getTvDetails()` + `getMovieExternalIds()`/`getTvExternalIds()`
- TMDB enrichment runs with concurrency limit of 6 (`Semaphore(6)`), items without poster or backdrop are filtered out
- TMDB image data cached in-memory (`ConcurrentHashMap`) per session; TMDB↔IMDB mappings pre-cached in `TmdbService`
- Discovery rows cached for 30 minutes (`CACHE_TTL_MS`); cache cleared on Trakt logout
- `HomeViewModel.loadTraktDiscovery()` observes `TraktAuthDataStore.state` reactively via `collectLatest` — rows appear when user authenticates, disappear on logout
- Discovery rows inserted into `catalogsMap`/`catalogOrder` as regular `CatalogRow`s with `addonId = "trakt-discovery"`, `addonName = "Trakt"`
- Rows survive `loadAllCatalogsPipeline` resets — re-inserted after `rebuildCatalogOrder()` in the pipeline
- Row names (Portuguese): "Tendências", "Recomendados", "Mais Esperados", "Populares"
- Trakt OkHttpClient automatically adds `trakt-api-key` and `trakt-api-version: 2` headers via interceptor in `NetworkModule`
- `TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET` configured in `local.properties` (read by `defaultConfig` in `build.gradle.kts`)

## Diobot AI Concierge System
- AI-powered movie/series assistant accessible via sidebar ("Diobot" item between Search and Library)
- Uses QR code + local HTTP server approach (same pattern as auth login) — phone is the input device, TV displays chat mirror
- `DiobotServer` (NanoHTTPD) starts on port 8100+ and serves a responsive web chat interface to the user's phone
- `DiobotWebPage` renders HTML with voice input (Web Speech API, pt-BR) + text input, suggestion cards with 3 action buttons (▶ Reproduzir, 💾 Salvar, 📋 Detalhes)
- `DiobotService` (@Singleton) handles OpenAI ChatGPT API calls via Retrofit (`gpt-4o-mini` model), injects `TmdbApi` for real-time data
- System prompt includes user's library titles (up to 50) for personalized recommendations
- Structured response format: `MENSAGEM` (text) + `ACAO` (nenhuma/salvar/reproduzir) + `ALVO` (target item JSON) + `SUGESTOES` (array of suggestions)
- Three capabilities:
  1. **Recommend**: suggests movies/series based on user's library and preferences
  2. **Save to library**: executes `LibraryRepository.toggleDefault()` with `LibraryEntryInput` (IMDB ID or TMDB ID)
  3. **Play on TV**: navigates to `Screen.Stream` route which triggers auto-play via `StreamAutoPlaySelector`
- **TMDB Real-Time Agent**: Before each ChatGPT call, `DiobotService` queries TMDB APIs based on user intent analysis:
  - `analyzeUserMessage()` detects intent: search terms, trending, now playing, upcoming
  - `searchMulti` for specific title lookups (up to 3 search terms, 8 results each)
  - `getTrendingMovies` + `getTrendingTv` for trending/popular requests (or when no specific search terms)
  - `getNowPlayingMovies` for "em cartaz" / "lançamento" / "novo" requests
  - `getUpcomingMovies` for "vai lançar" / "em breve" requests
  - All TMDB calls run in parallel via `coroutineScope { async {} }`
  - Results enriched with IMDB IDs via `getMovieExternalIds` / `getTvExternalIds` (top 10 results)
  - TMDB data injected into system prompt as `--- DADOS TMDB EM TEMPO REAL ---` section
  - ChatGPT instructed to use ONLY provided TMDB IDs (no guessing tmdbId/imdbId)
  - Genre IDs mapped to Portuguese names for context (movieGenres/tvGenres maps)
  - `lastTmdbPosterCache` and `lastTmdbImdbCache` enrich parsed suggestions with poster paths and IMDB IDs
  - `DiobotSuggestion.posterPath` enables real poster images on phone suggestion cards (`https://image.tmdb.org/t/p/w342{posterPath}`)
- `DiobotViewModel` injects `LibraryRepository` to read library items and save; emits `DiobotNavEvent` sealed class for navigation
- `DiobotNavEvent.NavigateToStream` carries videoId + contentType + title → NuvioNavHost routes to `Screen.Stream.createRoute()`
- `DiobotServer.TvCommand` sealed class: `NavigateToDetail`, `PlayContent`, `SaveToLibrary` — emitted via `MutableSharedFlow`
- Phone suggestion cards send commands via `/api/command` POST endpoint with `action` (play/save/detail), `itemId`, `itemType`, `title`
- Chat requests serialized via `Mutex` to prevent concurrent state corruption from NanoHTTPD threads
- `ChatEntry` has unique `id` (System.nanoTime) used as LazyColumn item key for stable recomposition
- Conversation history kept as `synchronizedList`, last 10 messages sent as context to ChatGPT
- Error handling: specific messages for 401 (invalid key), 429 (quota vs rate limit), 500+ (server unavailable)
- `OPENAI_API_KEY` configured in `local.properties` (read by `defaultConfig` in `build.gradle.kts`, NOT overridden by debug build type)
- TV screen: split layout — left side QR code + URL + branding, right side chat messages mirror with typing indicator
- SVG icon: `res/raw/sidebar_diobot.svg` (robot icon)
- Route: `Screen.Diobot` ("diobot")
- DTOs: `OpenAiDtos.kt` in `data/remote/dto/openai/` (ChatCompletionRequest, ChatMessage, etc.)
- API interface: `OpenAiApi.kt` in `data/remote/api/` — `@Named("openai")` Retrofit instance at `https://api.openai.com/v1/`
- TMDB endpoints used by Diobot: `searchMulti`, `getTrendingMovies`, `getTrendingTv`, `getNowPlayingMovies`, `getUpcomingMovies`, `getMovieExternalIds`, `getTvExternalIds`

## AI Recommendations System ("Feito pra Você 🤖")
- `AiRecommendationService` (`data/repository/`) uses OpenAI `gpt-4o-mini` to analyze user's library and suggest 15 personalized movies/series
- System prompt includes up to 50 library titles with genres and ratings for context
- OpenAI returns structured JSON with `title`, `year`, `type` (movie/series), and `reason` fields
- Items enriched with TMDB images: poster (`w500`), backdrop (`original`), and IMDB ID via `TmdbApi.searchMovie()`/`searchTv()` + `getMovieExternalIds()`/`getTvExternalIds()`
- TMDB enrichment runs with `Semaphore(6)` concurrency limit; items without poster are filtered out
- Appears as `CatalogRow` with `addonId = "ai-recommendations"`, `catalogId = "ai-for-you"`, `catalogName = "Feito pra Você 🤖"`
- Cache of 1 hour (`CACHE_TTL_MS`); only appears if `OPENAI_API_KEY` is configured and library is not empty
- Row stored in `HomeViewModel.aiRecommendationRow` and re-inserted after `rebuildCatalogOrder()` in the catalog pipeline
- Selectable as Hero Catalog in Layout Settings (`key = "ai-recommendations_movie_ai-for-you"`)
- `HomeUiState.aiRecommendationsLoading: Boolean` tracks loading state
- Uses `@Named("openai")` Retrofit instance (same as Diobot)

## Daily Tips System ("Dica do Dia 🎬")
- `DailyTipService` (`data/repository/`) selects 3 movies from TMDB trending + now playing using day-of-year as deterministic seed
- Fetches `getTrendingMovies("day")` and `getNowPlayingMovies()` in parallel, merges and deduplicates by TMDB ID
- Seed-based selection: `(dayOfYear * 31 + index * 17) % poolSize` ensures same 3 movies all day, different movies each day
- Items enriched with IMDB IDs via `getMovieExternalIds()` with `Semaphore(4)` concurrency
- `MetaPreview` objects with poster (`w500`), backdrop (`original`), rating, year, and genres
- Cache resets daily (keyed by `dayOfYear`); in-memory `ConcurrentHashMap`
- `DailyTipSection` composable: 3 compact landscape cards (100dp height) in a `Row` layout
- Cards show backdrop image with gradient overlay, title, year, and star rating
- Focusable with scale animation (1.05x) and border highlight on focus
- Added to all 3 home layouts between New Releases and Continue Watching
- In Modern layout, rendered as a `HeroCarouselRow` with `globalRowIndex = -3`
- `HomeUiState.dailyTips: List<MetaPreview>` holds the items
- `onDailyTipClick` callback navigates to detail screen via IMDB ID

## TMDB Discovery System ("Lançamentos Recentes")
- `TmdbDiscoveryService` (`data/repository/`) fetches 2 pages of `getNowPlayingMovies()` from TMDB (~40 movies)
- Items enriched with IMDB IDs via `getMovieExternalIds()` with `Semaphore(6)` concurrency
- `MetaPreview` objects with poster (`w500`), backdrop (`original`), rating, year
- Appears as `CatalogRow` with `addonId = "tmdb-discovery"`, `catalogId = "tmdb-recently-released"`, `catalogName = "Lançamentos Recentes"`
- Cache of 1 hour (`CACHE_TTL_MS`); in-memory `ConcurrentHashMap`
- Row stored in `HomeViewModel.tmdbRecentlyReleasedRow` and re-inserted after `rebuildCatalogOrder()`
- Selectable as Hero Catalog in Layout Settings (`key = "tmdb-discovery_movie_tmdb-recently-released"`)
- No authentication required — uses only public TMDB API

## HD Backdrop Images
- All TMDB backdrop/background images use `original` size instead of `w1280`
- `original` provides native resolution (typically 1920x1080 or higher), ideal for TV displays
- Applied across: `TmdbMetadataService` (recommendations, collection parts, cast credits), `TraktDiscoveryService`, `AiRecommendationService`, `DailyTipService`, `TmdbDiscoveryService`
- Poster images remain at `w500` (sufficient for card thumbnails)
- Calendar items use smaller sizes (`w500` for episode stills, `w780` for calendar card backdrops) as they display in compact UI
