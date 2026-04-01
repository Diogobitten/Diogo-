# NuvioTV — Product Overview

NuvioTV is an Android TV media player that integrates with the Stremio addon ecosystem for content discovery and stream resolution. It is a client-side playback interface — it does not host, store, or distribute media content.

## Core Capabilities
- Browse and search media catalogs provided by user-installed Stremio addons
- Resolve and play streams (HLS, DASH, Smooth Streaming, RTSP) via a forked ExoPlayer/Media3 stack
- Sidebar category filters (Movies / Series) with visual dividers and dedicated icons, filtering catalog rows by content type
- Manage a personal library with watch progress tracking and continue-watching
- User profiles with avatar selection from multiple sources (DiceBear cartoons, TMDB actors, Superhero API, Rick and Morty, anime, Saint Seiya, Pokémon, Cartoon Network), synced via Supabase when configured
- Local JavaScript plugin system (QuickJS) for extensibility
- Trakt.tv integration for scrobbling and library sync
- Trakt discovery rows on Home screen (Tendências, Recomendados, Mais Esperados, Populares) — appears when authenticated with Trakt, items enriched with TMDB poster/backdrop images
- TMDB metadata enrichment
- In-app self-updater via GitHub Releases
- Skip intro/outro detection (AniSkip, AnimeSkip, IntroDB)
- Subtitle support including ASS/SSA via libass, with OpenSubtitles.com integration as additional subtitle source
- Multiple home screen layouts (Classic, Grid, Modern) with Ken Burns zoom animation (1.08x, 10s cycle) on Classic HeroCarousel and detail screen backdrops (not on Modern hero for performance reasons)
- QR code sign-in via local HTTP server: TV starts NanoHTTPD server, shows QR code with local IP, user opens on phone browser to login/signup directly against Supabase Auth API, tokens sent back to TV automatically
- Theme song playback on detail screen via ThemerrDB (audio-only, fades in, loops, pauses during manual trailer, continues during ambient trailer and stream selection)
- Ambient trailer loop on detail screen: 3s backdrop → 60s muted trailer (seeked +20s, crop-to-fill) → repeat; backdrop only fades after first trailer frame renders
- Manual trailer button (enabled by default) in hero action row for full trailer with sound and controls (crop-to-fill)
- Intro video splash on app startup (`res/raw/dplus.mp4`) — plays once, zoomed to fill TV, with 15s safety timeout
- Calendar screen with monthly grid view showing upcoming and past episodes and movie digital releases from library items (TMDB-only, 90 days forward + 90 days back)
- "Novidades" (New Releases) row on Home screen showing today's new episodes and movie releases from library items
- Streaming service cards row on Home screen (Netflix, Disney+, HBO MAX, Globoplay, etc.) — auto-detected from "Streaming Catalogs" addon, cards show white SVG logos (Simple Icons CDN + SVGL) with text fallback, clicking a card opens a type picker dialog (Movies/Series) when both types are available; focused cards play brand intro video (muted, looping) after 2s dwell time from local `res/raw/intro_*.mp4` files with 500ms fade-in
- Diobot AI Concierge: voice/text assistant via phone (QR code + local HTTP server), powered by OpenAI ChatGPT with real-time TMDB data access, can recommend content based on user's library and current TMDB trends/releases, save items to library, and auto-play content on TV
- "Feito pra Você 🤖" AI Recommendations row on Home screen — OpenAI analyzes user's library (with genres, ratings) to suggest 15 personalized mainstream movies/series, temperature 0.5, TMDB quality gate ≥7.0, cached for 1 hour, selectable as Hero Catalog
- "Dica do Dia 🎬" Daily Tips section on Home screen — 3 compact landscape cards showing trending/now-playing movies from TMDB, deterministic daily selection using day-of-year seed, resets daily, auto-refreshes every hour on always-on TVs
- "Lançamentos Recentes" TMDB Discovery row on Home screen — recently released movies from TMDB Now Playing endpoint, enriched with IMDB IDs, cached for 1 hour, selectable as Hero Catalog
- HD backdrop images — all TMDB backdrop/background images use `original` size (native resolution, typically 1920x1080+) instead of `w1280` for sharp display on TV screens
- Zero-flash TMDB backdrop — detail screen pre-fetches TMDB enrichment in parallel with addon meta loading; hero carousel applies cached TMDB backdrop/logo synchronously before first render; Stremio addon images used only as fallback
- TMDB Reviews on detail screen — user reviews fetched from TMDB API (`movie/{id}/reviews` and `tv/{id}/reviews`), displayed as horizontally scrollable cards with author avatar, rating, date, and truncated review text; cards are D-pad focusable with scale/border animation and 80% transparent background
- TMDB Entity Browse — clicking a production company or network card on the detail screen navigates to a dedicated browse screen showing movies and TV shows by that entity, with horizontal rails (Popular, Top Rated, Recent) for both movies and TV, infinite scroll pagination, and IMDB ID enrichment for Stremio addon compatibility

## Target Platform
- Android TV (API 24+, targeting API 36)
- Leanback launcher compatible
- D-pad / remote-first navigation (no touchscreen required)

## Key Integrations
- Stremio addon protocol (content catalogs, metadata, streams, subtitles)
- Supabase (auth, profiles, cloud sync)
- Trakt.tv (scrobbling, library, watch history, personalized calendar)
- TMDB (metadata, images, cast, release dates, season/episode air dates, user reviews, entity browse via Discover API)
- DiceBear API (cartoon-style profile avatars — SVG, no API key required)
- Akabab Superhero API (Marvel, DC, Star Wars character images — cdn.jsdelivr.net, no API key)
- Rick and Morty API (character avatars — no API key required)
- Jikan API / MyAnimeList (top anime characters + Saint Seiya — no API key required)
- PokeAPI (Pokémon official artwork sprites — GitHub-hosted, no API key required)
- Fandom MediaWiki API (Cartoon Network character images from show-specific wikis)
- MDBList (ratings aggregation)
- ThemerrDB / LizardByte (theme songs for movies and TV shows — YouTube audio extraction, no API key required)
- GitHub API (in-app updates)
- OpenAI ChatGPT API (Diobot AI Concierge + AI Recommendations — `gpt-4o-mini` model, requires `OPENAI_API_KEY` in `local.properties`)
- OpenSubtitles.com REST API v1 (additional subtitle source — searches by IMDB ID, season/episode, or file hash; filters out machine/AI translated subs; requires `OPENSUBTITLES_API_KEY` in `local.properties`)

## Discover Screen
- Trailer carousel: daily trending movies (`/trending/movie/day`) + now playing (`/movie/now_playing`) + daily trending TV (`/trending/tv/day`), selects latest official trailer per title via `published_at` + `official == true`
- Category rows: Filmes em Alta, Em Cartaz, Séries em Alta, Séries Populares, Em Breve, Séries Mais Bem Avaliadas, Oscar — Melhor Filme, Melhores dos Anos 90/80, Filmes de Lobisomem/Vampiro
- All data cached with 30-minute TTL, auto-refreshes on next visit after expiry
- All items enriched with IMDB IDs for Stremio addon compatibility
