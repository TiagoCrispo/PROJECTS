# Galaxy A53 Performance v1.17.0 — Gallery 2.0 BETA

This is the first feature release built after the v1.16.1 SAFE RECOVERY baseline was confirmed to launch on the physical Galaxy A53.

## Scope

v1.17 changes one product area only: photo/video cleanup UX. Performance profiles, Shizuku behavior, storage scanner logic and the known-working legacy UI are intentionally left untouched.

Gallery 2.0 adds:
- three-column photo/video grid;
- asynchronous MediaStore thumbnails with bounded RAM cache;
- filters for screenshots, WhatsApp, downloads, videos larger than 500 MB and media older than one year;
- sorting by newest, oldest or largest;
- tap selection and visible-item bulk selection;
- selected item/byte counter;
- long-press preview;
- Android system-trash flow through `MediaStore.createTrashRequest()` on Android 11+;
- trash requests split into bounded batches;
- no automatic destructive selection.

## Stability architecture

The known-working v1.16.1 executable core is preserved. `classes.dex`, `classes2.dex`, `classes3.dex`, resources and assets remain byte-identical. The legacy `MainActivity` family in `classes4.dex` is preserved under the equal-length alias `MainActivitx`, while a source-compiled `classes5.dex` provides the new `MainActivity` subclass and Gallery 2.0 UI. This avoids hand-editing method control flow.

The new overlay source is compiled in GitHub Actions with Android SDK 35, javac and D8.

## Status

**BETA — physical A53 acceptance required.** The development PR remains draft and must not be merged/promoted until launch, original UI, gallery selection, preview and one real system-trash/restore cycle are tested on the target phone.

APK SHA-256: `6b94ca9525a7b8a7b731b5cccad16bb6cab01f866598ae7662c8e1c2cda958f2`
