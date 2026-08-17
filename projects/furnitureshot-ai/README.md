# FurnitureShot AI

Android-only product-photo editor focused on furniture and marketplace/catalog photography.

Current version: **v0.1.0-alpha01**.

## What works in alpha01

- First-launch runtime permission gate for the permissions that are actually required.
- Android Photo Picker for gallery import without broad photo-library permission.
- Camera capture through a FileProvider URI.
- Internal copy of every source image: the original file is never overwritten.
- Four catalog presets.
- Prompt-like editing instructions with a local `Fidelity Lock` policy that ignores structural changes.
- Conservative local tone correction.
- Conservative background-to-white isolation for sufficiently uniform backgrounds; automatic fallback to the original background when confidence is poor.
- Cancelable processing.
- Original/result comparison.
- JPEG export to `Pictures/FurnitureShot AI`.
- Local history with atomic history-file replacement.

## Permission model

Runtime permissions are deliberately minimal:

- `CAMERA`: requested on first launch because the app exposes camera capture.
- `WRITE_EXTERNAL_STORAGE`: requested only on Android 9/API 28 or lower, where it is needed for legacy public-picture export.

The app does **not** request broad photo access. Gallery selection uses Android's Photo Picker. On modern Android, writing an image created by this app to MediaStore also does not require a storage runtime permission.

Normal manifest permissions (`INTERNET`, `ACCESS_NETWORK_STATE`) are reserved for the future optional model-download module and do not show runtime dialogs.

## Fidelity policy

The original is immutable. Image processing is intentionally conservative and must prefer a less dramatic result over a structurally altered product.

`Vistas IA`, ML segmentation and local super-resolution are intentionally not claimed as implemented yet.

## Build

The repository workflow builds this project with:

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin 2.3.21
- compileSdk / targetSdk 36
- minSdk 26
- JDK 17

From an Android development machine with Gradle 8.13 available:

```bash
gradle :app:assembleDebug
```

## Roadmap

1. alpha01 — stable Android shell + safe local editing.
2. alpha02 — ML segmentation engine abstraction and higher-quality matte refinement.
3. alpha03 — tiled super-resolution and detail-preservation QA.
4. alpha04 — downloadable `ViewSynthesisEngine` prototype with explicit reconstructed-view labeling.
5. beta — physical-device test matrix on Galaxy A53 5G and Galaxy S20 FE.
