# A53 Performance v1.15.11 — SAFE CLEANER / VISUAL ACCURACY

- Package: `com.fer.a53performance`
- versionCode: `33`
- versionName: `1.15.11`
- Build + Android matrix run: `31928803090`
  - Release + Debug + AndroidTest: PASS
  - Android 30: 11 instrumented tests + 8 restart cycles + orientation + RUNNING_LOW + 300 Monkey events: PASS
  - Android 33: 11 instrumented tests + 8 restart cycles + orientation + RUNNING_LOW + 300 Monkey events: PASS
  - Android 35: 11 instrumented tests + 14 restart cycles + orientation + RUNNING_LOW + 900 Monkey events: PASS
- Final upgrade UI validation run: `31930123378`: PASS
  - v1.15.10 installed and representative preferences/app data seeded
  - update in place to v1.15.11: PASS
  - versionName 1.15.11: PASS
  - last profile, custom protected app list, marker data and granted storage access preserved: PASS
  - full emulator reboot followed by cold launcher start: PASS
  - `Status: ok`, MainActivity resolved, and `A53 Performance` verified in the actual UI hierarchy: PASS
  - no app `FATAL EXCEPTION`: PASS
- Final signed APK SHA-256: `11a779bde700c4864bdaa19b6f71e1a50311672936cf5553ed4438a675ddde43`
- Final APK size: `2309762` bytes
- Installer ZIP SHA-256: `36ac6fafcedbd6db4398a6963b09529206f2ca47789d6fe3259d1e0dbb19dbd2`
- Installer ZIP size: `2230027` bytes
- Signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK Signature Scheme v2: PASS
- APK Signature Scheme v3: PASS
- ZIP integrity: PASS

## Changes

1. RAM Cleaner is now strict fail-safe. Privileged process/foreground/media discovery has explicit success/error sentinels; command timeout or failure cannot be interpreted as an empty safe set.
2. MediaStore incremental refresh checks the live item count after a generation change and immediately reconciles a volume when stale/deleted rows are detected, while retaining periodic full reconciliation as fallback.
3. External storage labels now come from Android `StorageVolume`: primary storage is `Interno`, SD-like descriptions are `microSD`, USB-like descriptions are `USB`, and other volumes use their Android description or `Externo`.
4. Similar-photo signatures normalize EXIF orientation before comparison and add a cached color/contrast signature as a third perceptual gate alongside dHash, aHash and aspect ratio.
5. Visual regression tests now exercise EXIF-rotated/recompressed JPEGs and intentionally different flat images to cover both grouping and false-positive rejection.
6. RAM cleanup has a 12-second global operation budget and stops after repeated Shizuku transport failures instead of continuing through the candidate list.

## Explicitly excluded

The proposed point 7 was **not implemented**: a manually selected duplicate keeper is not persisted across a fresh duplicate analysis. It remains session-only/in-memory.

Physical Samsung Galaxy A53 + One UI, real removable SD/USB media and a real Shizuku Manager session still require device-side validation; Android emulators cannot reproduce those environments exactly.
