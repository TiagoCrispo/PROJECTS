# Validation — v1.17.0 Gallery 2.0 BETA

## Baseline preservation
- `classes.dex`: byte-identical to v1.16.1 SAFE RECOVERY — PASS
- `classes2.dex`: byte-identical — PASS
- `classes3.dex`: byte-identical — PASS
- `resources.arsc`: byte-identical — PASS
- `res/*` (41 entries): byte-identical — PASS
- removed APK entries: none

## Expected APK diff
- changed: `AndroidManifest.xml`
- changed: `classes4.dex`
- added: `classes5.dex`

## DEX integration
- `classes4.dex` SHA-1 / Adler32 — PASS
- `classes5.dex` SHA-1 / Adler32 — PASS
- classes4 string table sorted — PASS
- classes5 string table sorted — PASS
- 148 legacy `MainActivity*` descriptors renamed to equal-length `MainActivitx*`
- old `MainActivity` type descriptor absent from classes4 — PASS
- legacy `MainActivitx` definition in classes4 — PASS
- new `MainActivity` definition in classes5 — PASS
- new `MainActivity` superclass = `MainActivitx` — PASS
- duplicate class definitions across classes1–5 — NONE

## Overlay build
- GitHub Actions — PASS
- Android SDK 35 — PASS
- javac — PASS
- D8, minApi 26 — PASS
- overlay DEX SHA-256: `dea43325ce77f9d7f1ee5346e0f1af2badaec95c7a492f27aff2998d35854d6d`

## Manifest
- package `com.fer.a53performance`
- versionName `1.17.0`
- versionCode `40`
- `debuggable=false`
- `allowBackup=false`
- launcher target remains `com.fer.a53performance.MainActivity`

## APK signing
- ZIP integrity — PASS
- STORED-entry 4-byte alignment — PASS
- APK Signature Scheme v2 algorithm `0x0103` — PASS
- RSA/SHA-256 signature verification — PASS
- stored/recomputed v2 content digest — MATCH
- v2 digest: `6ea745e1956fdf141a162f0073ef8c6224e85a1eb02cf6783f6291f577bdd6ba`
- X10 certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK SHA-256: `6b94ca9525a7b8a7b731b5cccad16bb6cab01f866598ae7662c8e1c2cda958f2`

## Runtime boundary
Static/package validation is complete, but this build is not promoted or merged as a final release until physical Galaxy A53 acceptance is reported. The draft PR intentionally remains unmerged.
