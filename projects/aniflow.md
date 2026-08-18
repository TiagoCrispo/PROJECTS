# AniFlow

AniFlow is a personal Android anime library and player designed around a visual black/orange streaming experience rather than a text-heavy catalog.

## Current release candidate

**v1.0.0-rc1**

The app combines:

- a recent-episodes home feed backed by AniList AiringSchedule;
- visual search and anime detail pages;
- franchise chronology built from AniList prequel/sequel relationships rather than simple year sorting;
- `Viendo`, `Por ver` and `Visto` with durable Room persistence;
- episode-level watched/progress state and continuation across process restarts;
- Media3 / ExoPlayer playback for authorized local, HLS and DASH sources;
- quality, audio, subtitle, speed, fullscreen and Picture-in-Picture controls;
- DataStore player preferences;
- Coil image caching and bounded prefetch;
- WorkManager metadata refresh;
- additive Room migrations with no destructive fallback.

## Technical stack

Kotlin, Jetpack Compose, Material 3, Navigation Compose, Room 2.8.4, Media3 1.10.1, DataStore 1.2.1, Coil 3.5.0, WorkManager 2.11.2 and AniList GraphQL.

The final Android CI gate uses Android Gradle Plugin 9.3.1, Gradle 9.5.0, JDK 17, compile/target SDK 36 and Build Tools 36.0.0.

## Media boundary

AniFlow intentionally separates metadata from playback. AniList supplies catalog/schedule information; video comes from user-authorized sources such as local files, a personal server, HLS/DASH endpoints or future authorized provider integrations. The project does not bypass DRM or extract protected Crunchyroll streams.

## Validation contract

The project was developed with accumulated domain, data, race-condition, migration, SQL/stress and static UI gates. GitHub Actions is the release gate for real AGP/KSP/Room/Compose compilation, unit tests, Android lint and debug/release APK generation. Physical-device behavior is still validated separately after installing the produced APK.
