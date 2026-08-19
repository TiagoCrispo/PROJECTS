# Changelog — v1.17.0 Gallery 2.0 BETA

## Added
- `Fotos · limpiar` entry layered over the known-working interface.
- Full-screen `Limpieza Visual 2.0` dialog.
- 3-column image/video grid with recycled views.
- Background thumbnail loading and bounded LRU bitmap cache.
- Filters: all, screenshots, WhatsApp, downloads, videos >500 MB, media >1 year.
- Sort: newest, largest, oldest.
- Per-item selection, select-visible and clear-selection controls.
- Live selected item and selected-byte totals.
- Long-press full-screen preview with file metadata.
- System-trash request flow on Android 11+ in batches of 80 URIs.

## Stability boundaries
- No changes to performance profiles.
- No changes to AppOps/Data Saver/Shizuku behavior.
- No changes to storage indexing logic.
- No hand-edited legacy method instructions.
- No automatic file selection or automatic destructive cleanup.

## Identity
- versionName: `1.17.0`
- versionCode: `40`
- package: `com.fer.a53performance`
- status: `BETA / REAL_DEVICE_TEST_REQUIRED`
