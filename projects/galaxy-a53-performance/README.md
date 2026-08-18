# Galaxy A53 Performance

Galaxy A53 Performance is an Android device-maintenance and performance utility originally built around the Samsung Galaxy A53 5G.

The current recovery release is **v1.16.1 SAFE RECOVERY** (`versionCode 39`). It intentionally prioritizes launch stability over accumulated binary tweaks after the v1.16.0 branch continued to crash on the physical device.

## Current stability strategy

v1.16.1 uses the exact executable DEX payload from the known-working v1.15.2 SAFE_AREA baseline:

- `classes.dex` byte-identical;
- `classes2.dex` byte-identical;
- `classes3.dex` byte-identical;
- `classes4.dex` byte-identical;
- `res/`, `assets/` and `resources.arsc` byte-identical.

Only the manifest release identity/security fields change: `versionName 1.16.1`, `versionCode 39`, `debuggable=false`, and `allowBackup=false`. The APK is signed with the X10 branch certificate so it can replace later X10 builds.

This deliberately removes every hand-edited DEX instruction introduced in P2-P16. Static branch/checksum validation was not sufficient to reproduce ART's full bytecode/type verifier, so the project now treats physical launch evidence as mandatory before reintroducing any behavioral patch.

## Product scope

The known-working baseline contains:

- Gaming / Battery / Cool profiles;
- Shizuku-backed device actions;
- Data Saver and per-app rules;
- storage scanning, duplicates and similar-photo workflows;
- application-managed trash and restore;
- RAM/cache actions;
- thermal status integration;
- protected storage rules and local diagnostics.

## Validation contract

A build is not called production/final merely because ZIP integrity, DEX checksums or APK signing pass. The release package includes an ADB runtime capture script that performs a clean install, launches the application and captures `logcat`, package/activity state and process survival.

See [`release/v1.16.1/`](./release/v1.16.1/) for the current recovery notes and validation record.
