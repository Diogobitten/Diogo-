<div align="center">

  <img src="dplus1.png" alt="Diogo+" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    A modern Android TV media player built on the Stremio addon ecosystem.
    <br />
    Stremio Addons • Android TV Optimized • Playback-focused experience
  </p>

  > ⚠️ This is a personal fork of [NuvioTV](https://github.com/tapframe/NuvioTV) for private use. Not accepting external contributions.

</div>

## About

Diogo+ is a modern media player designed specifically for Android TV.

It works as a client-side playback interface that integrates with the Stremio addon ecosystem for content discovery and stream resolution through user-installed extensions.

Built with Kotlin and Jetpack Compose, optimized for a TV-first experience with remote control (D-pad) navigation.

## Features

- 🎬 Media catalogs via Stremio addons (movies, series, anime)
- 🏠 3 Home Screen layouts: Classic, Grid, and Modern
- 📺 Streaming Services row with SVG logos (Netflix, Disney+, HBO MAX, etc.)
- 📅 Calendar screen with monthly grid of upcoming releases from library items
- 🆕 "New Releases" row showing today's new episodes and movie releases
- 🎵 Automatic theme songs on detail screen (via ThemerrDB)
- 🎥 Ambient trailer loop on detail screen (muted, crop-to-fill)
- 🤖 Diobot AI Concierge — voice/text assistant via phone (OpenAI ChatGPT + real-time TMDB data)
- 👤 Profiles with avatars from multiple sources (DiceBear, TMDB, Superhero API, Rick and Morty, Anime, Pokémon, Cartoon Network)
- 🔐 QR Code login — local HTTP server, no external service dependency
- 📊 Trakt.tv integration (scrobbling, library, discovery rows)
- 🔍 Content search and discovery
- 📚 Personal library with watch progress and "Continue Watching"
- 🔌 Local JavaScript plugin system (QuickJS)
- ⏭️ Intro/outro skip detection (AniSkip, AnimeSkip, IntroDB)
- 🔄 Auto-updater via GitHub Releases
- 🌐 Subtitle support including ASS/SSA via libass

## Installation

Download the latest APK from [GitHub Releases](https://github.com/Diogobitten/Diogo-/releases/latest) and install on your Android TV device.

## Development

### Prerequisites

- Java 17 (`brew install openjdk@17`)
- Android SDK with `ANDROID_HOME` configured
- Android TV (physical or emulator) for testing

### Setup

```bash
git clone https://github.com/Diogobitten/Diogo-.git
cd Diogo-
cp local.example.properties local.properties
# Fill in the required keys in local.properties
```

### Configuration

Copy `local.example.properties` to `local.properties` and fill in the keys:

- `TMDB_API_KEY` — required for metadata and images
- `SUPABASE_URL` / `SUPABASE_ANON_KEY` — optional, for cloud auth and sync
- `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` — optional, for Trakt integration
- `OPENAI_API_KEY` — optional, for Diobot AI Concierge

### Build & Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Connect via ADB Wi-Fi (Android TV)
adb connect <TV_IP>:5555

# Run unit tests
./gradlew testDebugUnitTest

# Build release
./gradlew assembleRelease
```

## Tech Stack

- Kotlin 2.3.0 + Jetpack Compose (BOM 2026.01.01)
- TV Material + Material3
- ExoPlayer/Media3 (local fork with extra decoders: FFmpeg, AV1, IAMF, MPEG-H)
- Hilt (Dependency Injection)
- Retrofit + Moshi (REST APIs)
- Supabase SDK (Auth + Postgrest)
- OkHttp with 50MB cache
- Coil 2.x (image loading with SVG support)
- QuickJS-KT (JavaScript plugins)
- DataStore Preferences (local state)

## Based on

Fork of [NuvioTV](https://github.com/tapframe/NuvioTV) — an open-source media player for Android TV.

## License

Distributed under the GPL-3.0 license. See [LICENSE](LICENSE) for more information.

<!-- MARKDOWN LINKS -->
[license-shield]: https://img.shields.io/github/license/Diogobitten/Diogo-.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
