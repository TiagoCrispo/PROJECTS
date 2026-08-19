# Validation — v1.19.0 Gaming 2.0 BETA

## Identity
- package: `com.fer.a53performance`
- versionName: `1.19.0`
- versionCode: `42`
- targetSdk: `35`
- `debuggable=false`
- `allowBackup=false`

## Build
- GitHub Actions: PASS
- Android SDK 35: PASS
- javac: PASS
- D8: PASS

## Integration
- `classes.dex` through `classes5.dex` byte-identical to the physically accepted v1.18 APK: PASS
- `classes6.dex` differs only by same-length Activity alias `MainActivity -> MainActivitw` plus repaired DEX header: PASS
- `classes7.dex` contains the new Gaming 2.0 layer: PASS
- Activity chain: `MainActivity -> MainActivitw -> MainActivitz -> MainActivitx`: PASS
- duplicate class descriptors: 0
- changed DEX string ordering: PASS
- DEX SHA-1 / Adler32: PASS
- runtime Shizuku references (`run`, `ready`, `request`, `Result.ok`, `Result.out`): PASS

## Negative command audit
Absent from the new Gaming overlay:
- `RUN_IN_BACKGROUND`
- `force-stop`
- `am kill`
- `set-standby-bucket`
- `am set-inactive`
- device-idle whitelist changes

Present as designed:
- `RUN_ANY_IN_BACKGROUND`
- `settings ... min_refresh_rate`
- `settings ... peak_refresh_rate`
- `cmd power set-mode`
- `cmd netpolicy set restrict-background`
- synchronous snapshot `commit()`

## Visual/resource regression
- `res/*` byte-identical to accepted v1.18: PASS
- `assets/*` byte-identical: PASS
- `resources.arsc` byte-identical: PASS

## APK package/signing
- ZIP integrity: PASS
- STORED-entry four-byte alignment: PASS
- APK Signature Scheme v2 content digest: PASS
- RSA/SHA-256 signature: PASS
- X10 certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK SHA-256: `aba41c7f8084ee8dddada77c1e03c6d6a268459e4a8dbf44c2c2595225f238c7`
- test package SHA-256: `39a017ca54c9c9c81f1138d09152ccd72ba907c4af999b37c28b4ac768cdd913`

## Runtime boundary
This remains BETA until physical Galaxy A53 acceptance. Static read-back guarantees refer to Android shell/settings responses, not guaranteed in-game FPS. The system/game may override effective refresh rate because of game frame-rate behavior, battery or thermal policy.
