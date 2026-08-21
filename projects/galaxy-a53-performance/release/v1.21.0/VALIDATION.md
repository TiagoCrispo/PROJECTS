# Validation — v1.21.0

## Build

- GitHub Actions Android SDK 35 + Java 17 toolchain: PASS.
- `javac -source 8 -target 8`: PASS.
- D8 minSdk 26: PASS.

## Integration

- `versionName 1.21.0`: PASS.
- `versionCode 44`: PASS.
- `classes.dex` through `classes7.dex`: byte-identical to physically accepted v1.20.0.
- `classes8.dex`: only the equal-length Doctor Activity alias `MainActivity -> MainActivivy`, plus required DEX header checksum/signature repair.
- `classes9.dex`: source-compiled Ready Mode.
- `res/*`, `assets/*`, `resources.arsc`: byte-identical to v1.20.0.
- DEX SHA-1 / Adler32: PASS.
- No duplicate class descriptors across all nine DEX files: PASS.
- STORED ZIP entries 4-byte aligned: PASS.

## Ready Mode safety audit

Required read-only references present:
- `getCurrentThermalStatus` / `getThermalHeadroom`;
- `settings get`;
- `cmd netpolicy get restrict-background`;
- quick-navigation targets for Doctor, Gaming, Background Center and Gallery.

Absent from Ready Mode:
- `settings put`;
- `cmd appops set`;
- `cmd netpolicy set`;
- `RUN_ANY_IN_BACKGROUND` / `RUN_IN_BACKGROUND` writes;
- `force-stop`;
- `am kill`;
- `set-standby-bucket`;
- `am set-inactive`;
- `deviceidle whitelist`;
- `cmd power set-mode`.

## Signing

Official Android Build Tools `apksigner`: PASS.
- APK Signature Scheme v2: VERIFIED.
- APK Signature Scheme v3: VERIFIED.
- signer certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`.
- RSA key size: 3072 bits.

Static validation does not replace physical ART / One UI testing on the Galaxy A53.
