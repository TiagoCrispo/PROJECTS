# AniFlow

AniFlow is a personal Android anime platform built as a polished streaming-style experience without copying proprietary branding, code or media assets. The app separates anime metadata from playback sources and is designed around fast navigation, durable local state and a minimal black/orange interface.

## Highlights

- Home feed of newly aired episodes backed by AniList metadata and local Room cache.
- Search and visual anime detail pages with banners, covers and compact episode lists.
- Franchise chronology graph based on AniList prequel/sequel relationships rather than release-year guesses.
- `Viendo` with up to six distinct current titles and persistent episode progress.
- `Guardados` with `Por ver` and `Visto`, including conservative automatic completion rules.
- Media3/ExoPlayer player with HLS/DASH/local-file support, track selection, subtitles, audio, PiP, fullscreen, ±10 s seeking and persistent preferences.
- Room migrations preserve history, library state, chronology cache and playback bindings.
- Coil singleton image pipeline, DataStore player preferences and WorkManager refresh scheduling.

## Stack

Kotlin · Jetpack Compose · Material 3 · Room · Coroutines/Flow · AniList GraphQL · OkHttp · Media3/ExoPlayer · Coil · DataStore · WorkManager

## Source

The Android source lives in [`projects/aniflow/`](./aniflow/). The project deliberately does not include copyrighted anime video content or DRM circumvention. Playback is supplied through user-owned/local files or other authorized media sources.

## Validation

The repository includes local regression guards plus GitHub Actions that run the real Android Gradle toolchain, unit tests, lint, debug assembly and minified release assembly. Build artifacts are produced by CI for validated checkpoints.
