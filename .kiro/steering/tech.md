# NuvioTV — Tech Stack & Build

## Language & SDK
- Kotlin (2.3.0), JVM target 11
- Android SDK: minSdk 24, targetSdk/compileSdk 36
- Core library desugaring enabled for Java 8+ APIs on older devices

## Build System
- Gradle with Kotlin DSL (`build.gradle.kts`)
- Version catalog at `gradle/libs.versions.toml`
- AGP 8.13.2
- KSP for annotation processing (Hilt, Moshi codegen)
- Baseline Profile plugin for startup optimization

## UI
- Jetpack Compose with Compose BOM 2026.01.01
- TV Material (`androidx.tv:tv-material:1.0.1`) for TV-optimized components
- Material3 for standard components
- Compose stability config at `compose_stability_config.conf`
- Coil 2.x for image loading (with SVG support)
- Haze for blur effects
- Navigation Compose for screen routing

## Dependency Injection
- Hilt (Dagger 2.58)
- DI modules in `core/di/` and `di/`
- `@InstallIn(SingletonComponent::class)` for app-scoped singletons
- Repository bindings use `@Binds` in abstract modules

## Networking
- Retrofit 2.9 + Moshi for REST APIs
- OkHttp 4.12 with logging interceptor and 50MB disk cache
- Named Retrofit instances for different API backends (`@Named("trakt")`, `@Named("tmdb")`, `@Named("openai")`, etc.)
- Supabase SDK (3.1.4) with Ktor OkHttp engine for auth and Postgrest
- Kotlinx Serialization for Supabase/Ktor payloads

## Media Playback
- Forked ExoPlayer/Media3 via local AARs in `app/libs/` (replaces stock `media3-exoplayer` and `media3-ui`)
- Media3 modules: HLS, DASH, Smooth Streaming, RTSP, OkHttp datasource
- Additional decoders: FFmpeg audio, AV1 (libgav1), IAMF, MPEG-H
- libass-android for ASS/SSA subtitles
- nextlib for extended media info

## Local Plugin System
- QuickJS-KT for JavaScript execution
- Jsoup for HTML parsing
- Gson for JSON in plugin context
- crypto-js bundled as WebJar for plugin crypto operations

## Local Data
- DataStore Preferences for settings and local state
- Per-profile DataStore instances via `ProfileDataStoreFactory`

## Testing
- JUnit 4 for unit tests
- MockK for mocking
- kotlinx-coroutines-test for coroutine testing
- Compose UI test (JUnit4) for instrumented tests
- Benchmark macro (JUnit4) for performance tests in `baselineprofile/` module

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug on connected device
./gradlew installDebug

# Run unit tests
./gradlew testDebugUnitTest

# Run all checks (lint + tests)
./gradlew check

# Generate baseline profile
./gradlew :baselineprofile:connectedBenchmarkAndroidTest

# Clean build
./gradlew clean
```

## Configuration
- API keys and secrets go in `local.properties` (production) or `local.dev.properties` (debug)
- See `local.example.properties` for required keys
- `TMDB_API_KEY` is set in `defaultConfig` (from `local.properties`) and applies to all build types
- `OPENAI_API_KEY` is set in `defaultConfig` (from `local.properties`) and applies to all build types — used by Diobot AI Concierge
- Debug build type overrides Supabase/server URLs from `local.dev.properties` but inherits `TMDB_API_KEY` from `defaultConfig`
- Environment variables override local.properties (used in CI): `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_KEY_ALIAS`, etc.
- Debug builds use `com.nuviodebug.com` applicationId; release uses `com.nuvio.tv`

## Graceful Degradation
- Supabase features (auth, sync, cloud avatars) degrade silently when `SUPABASE_URL` / `SUPABASE_ANON_KEY` are empty
- `AvatarRepository` falls back to TMDB popular people when Supabase is unavailable
- `ProfileSyncService.pushToRemote()` catches exceptions and returns `Result.failure` without crashing
- Profile creation/management works fully offline via `ProfileManager` + `ProfileDataStore`
- QR code sign-in (`AuthQrSignInScreen`) uses local HTTP server (`AuthLoginServer`) instead of Supabase RPC — TV serves login page on `0.0.0.0`, phone authenticates directly with Supabase Auth API, tokens sent back to TV via local network
- Sign out clears ALL local synced data (library, watch progress, watched items, profiles) via `clearAll()` / `resetToDefault()` methods and resets active profile to 1 before Supabase sign out
- Sign in pulls all data from Supabase (plugins, addons, watch progress, library, watched items, profiles) via `pullRemoteData()` — profiles restored via `ProfileSyncService.pullFromRemote()`
- Sign up pushes all local data to Supabase (plugins, addons, watch progress, library, watched items, profiles) via `pushLocalDataToRemote()`

## Performance Tuning
- Coil ImageLoader (`NuvioApplication`): 50% memory cache, 250MB disk cache, 4 decoder threads, 4 fetcher threads, 4 bitmap factory parallelism, RGB565 enabled, hardware bitmaps enabled, crossfade disabled, `respectCacheHeaders(false)` to skip HTTP revalidation
- All image requests use explicit `memoryCacheKey` and `diskCacheKey` with URL + size for deterministic cache hits
- `allowHardware(true)` on all image requests (detail backdrop, cast photos, episode thumbnails, company logos, hero logo, grid/content cards, new releases, continue watching)
- Detail screen backdrop uses `crossfade(false)` for instant display on navigation (no fade-in delay)
- `MetaDetailsViewModel.applyMetaWithEnrichment()` launches `loadMoreLikeThisAsync`, `loadMDBListRatings`, and `enrichMeta` all in parallel — MDBList ratings no longer wait for enrichment to complete
- Both `ClassicHomeContent` and `ModernHomeContent` use `LazyListPrefetchStrategy(nestedPrefetchItemCount = 5)` on their vertical `LazyColumn` to pre-compose cards in nested `LazyRow`s across multiple frames before rows scroll into view
- D-pad key repeat throttling (120ms in Grid, 80ms in Classic and Modern) to prevent HWUI overload when holding a direction key
- `ModernHomeContent` uses extensive row/item build caching (`ModernCarouselRowBuildCache`) to avoid recomposing unchanged catalog rows
- JankStats integration in Modern home: tags `HomeScrolling` and `HeroEnriching` states for actionable jank reports
- Grid home `SectionDivider` display names are `remember`ed to avoid string operations on recomposition

## Local Development & TV Deploy
- Requires Java 17 (`brew install openjdk@17`), Android SDK, and `ANDROID_HOME` env var
- Debug builds use the default Android debug signing key (no keystore file needed)
- Deploy to Android TV via ADB over Wi-Fi:
  1. Enable Developer Options on the TV (tap Build Number 7 times)
  2. Enable USB Debugging (or Network Debugging on Android TV 11+)
  3. Connect: `adb connect <TV_IP>:5555`
  4. Build and install: `./gradlew installDebug`
- Debug app installs alongside release (separate applicationId) — both can coexist on the device
- Each code change requires a new `./gradlew installDebug` cycle (no hot-reload)
