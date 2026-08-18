# Validation — v1.16.1 SAFE RECOVERY

## Identity
- package: `com.fer.a53performance`
- versionName: `1.16.1`
- versionCode: `39`
- targetSdk: `35`
- `debuggable=false`
- `allowBackup=false`

## Known-working baseline invariant
Compared against `A53Performance_v1.15.2_SAFE_AREA.apk`:

- `classes.dex`: byte-identical — PASS
- `classes2.dex`: byte-identical — PASS
- `classes3.dex`: byte-identical — PASS
- `classes4.dex`: byte-identical — PASS
- `resources.arsc`: byte-identical — PASS
- all `res/` entries: byte-identical — PASS
- all `assets/` entries: byte-identical — PASS

No DEX control-flow or executable instruction changes are present in this recovery release.

## Package/signing
- ZIP integrity: PASS
- four-byte alignment for STORED APK entries: PASS
- APK Signature Scheme v2 content digest recomputation: PASS
- X10 certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK SHA-256: `2c8d2d7fc8f7db29c48a263fe69c6953a785f36ddbdc31a3241d528aa942aeae`

## Runtime boundary
Not yet REAL_DEVICE_PASS. The release package includes `INSTALAR_LIMPIO_Y_CAPTURAR_CRASH.bat` and `CAPTURAR_CRASH_SIN_REINSTALAR.bat`. A clean physical launch is required before promoting this branch to a final production release.
