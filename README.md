<div align="center">

  <img src="dplus1.png" alt="Diogo+" width="300" />
  <br />
  <br />

  [![License][license-shield]][license-url]

  <p>
    Um media player Android TV moderno, baseado no ecossistema de addons Stremio.
    <br />
    Ecossistema Stremio • Otimizado para Android TV • Experiência focada em playback
  </p>

  > ⚠️ Este é um fork pessoal do [NuvioTV](https://github.com/tapframe/NuvioTV) para uso próprio. Não aceita contribuições externas.

</div>

## Sobre

Diogo+ é um media player moderno projetado especificamente para Android TV.

Funciona como uma interface de reprodução client-side que integra com o ecossistema de addons Stremio para descoberta de conteúdo e resolução de streams através de extensões instaladas pelo usuário.

Construído com Kotlin e Jetpack Compose, otimizado para uma experiência TV-first com navegação por controle remoto (D-pad).

## Funcionalidades

- 🎬 Catálogos de mídia via addons Stremio (filmes, séries, anime)
- 🏠 3 layouts de Home Screen: Classic, Grid e Modern
- 📺 Streaming Services row com logos SVG (Netflix, Disney+, HBO MAX, etc.)
- 📅 Tela de Calendário com grid mensal de lançamentos da biblioteca
- 🆕 Row "Novidades" com episódios e filmes lançados hoje
- 🎵 Theme songs automáticas na tela de detalhes (via ThemerrDB)
- 🎥 Trailer ambiente em loop na tela de detalhes (mudo, crop-to-fill)
- 🤖 Diobot AI Concierge — assistente de voz/texto via celular (OpenAI ChatGPT + TMDB em tempo real)
- 👤 Perfis com avatares de múltiplas fontes (DiceBear, TMDB, Superhero API, Rick and Morty, Anime, Pokémon, Cartoon Network)
- 🔐 Login via QR Code — servidor HTTP local, sem dependência de serviço externo
- 📊 Integração Trakt.tv (scrobbling, biblioteca, discovery rows)
- 🔍 Busca e descoberta de conteúdo
- 📚 Biblioteca pessoal com progresso de assistência e "Continuar Assistindo"
- 🔌 Sistema de plugins JavaScript local (QuickJS)
- ⏭️ Detecção de intro/outro (AniSkip, AnimeSkip, IntroDB)
- 🔄 Auto-updater via GitHub Releases
- 🌐 Suporte a legendas incluindo ASS/SSA via libass

## Instalação

Baixe o APK mais recente em [GitHub Releases](https://github.com/Diogobitten/Diogo-/releases/latest) e instale no seu dispositivo Android TV.

## Desenvolvimento

### Pré-requisitos

- Java 17 (`brew install openjdk@17`)
- Android SDK com `ANDROID_HOME` configurado
- Android TV (físico ou emulador) para testes

### Setup

```bash
git clone https://github.com/Diogobitten/Diogo-.git
cd Diogo-
cp local.example.properties local.properties
# Preencha as chaves necessárias no local.properties
```

### Configuração

Copie `local.example.properties` para `local.properties` e preencha as chaves:

- `TMDB_API_KEY` — obrigatório para metadados e imagens
- `SUPABASE_URL` / `SUPABASE_ANON_KEY` — opcional, para auth e sync na nuvem
- `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` — opcional, para integração Trakt
- `OPENAI_API_KEY` — opcional, para o Diobot AI Concierge

### Build & Deploy

```bash
# Build debug APK
./gradlew assembleDebug

# Instalar no dispositivo conectado
./gradlew installDebug

# Conectar via ADB Wi-Fi (Android TV)
adb connect <TV_IP>:5555

# Rodar testes unitários
./gradlew testDebugUnitTest

# Build release
./gradlew assembleRelease
```

## Tech Stack

- Kotlin 2.3.0 + Jetpack Compose (BOM 2026.01.01)
- TV Material + Material3
- ExoPlayer/Media3 (fork local com decoders extras: FFmpeg, AV1, IAMF, MPEG-H)
- Hilt (Dependency Injection)
- Retrofit + Moshi (REST APIs)
- Supabase SDK (Auth + Postgrest)
- OkHttp com cache de 50MB
- Coil 2.x (imagens com SVG support)
- QuickJS-KT (plugins JavaScript)
- DataStore Preferences (estado local)

## Baseado em

Fork do [NuvioTV](https://github.com/tapframe/NuvioTV) — um projeto open-source de media player para Android TV.

## Licença

Distribuído sob a licença GPL-3.0. Veja [LICENSE](LICENSE) para mais informações.

<!-- MARKDOWN LINKS -->
[license-shield]: https://img.shields.io/github/license/Diogobitten/Diogo-.svg?style=for-the-badge
[license-url]: http://www.gnu.org/licenses/gpl-3.0.en.html
