# Validation — Galaxy A53 Performance v1.18.0 BETA

## Build
- Android SDK 35: PASS
- javac: PASS
- D8: PASS
- GitHub Actions overlay build: PASS
- final `classes6.dex` SHA-256: `916cefb65cc18829e8956f6d8701a70e00551dfbccae303c6ab399e0c85b6e6c`

## APK integration
- package: `com.fer.a53performance`
- versionName: `1.18.0`
- versionCode: `41`
- `debuggable=false`: PASS
- `allowBackup=false`: PASS
- classes1-4 byte-identical to physically accepted v1.17: PASS
- `res/`, `assets/`, `resources.arsc` byte-identical to v1.17: PASS
- duplicate class descriptors: none
- Activity chain `MainActivity -> MainActivitz -> MainActivitx`: PASS
- DEX SHA-1 / Adler32: PASS
- modified overlay string ordering: PASS

## Background Center safety
- runtime field refs `ShizukuShell.Result.code/err/out`: PASS
- `RUN_ANY_IN_BACKGROUND ignore`: present
- `RUN_ANY_IN_BACKGROUND allow`: present
- standby bucket: read only
- `set-standby-bucket`: absent
- `RUN_IN_BACKGROUND` write command in v1.18 module: absent
- `am kill`: absent
- `force-stop`: absent
- synchronous rollback-snapshot `commit()` before first modification: PASS
- sensitive-app restriction warning: PASS
- read-back required before displaying confirmed success: PASS

## Signing / package
- ZIP integrity: PASS
- four-byte alignment for STORED APK entries: PASS
- APK Signature Scheme v2 digest recomputation: PASS
- RSA/SHA-256 verification: PASS
- v2 content digest: `e0e4d54eade46a3ddb2ea2c6f2c5df314ca5d1100131f2d41d4715bab675f137`
- certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK SHA-256: `f9fb576159ac423f8923feb4715b6bd1cdb30b37154ba53d0f3767096b44abd5`

## Acceptance boundary
`STATIC_PASS` and successful GitHub Actions compilation are not considered physical Samsung validation. PR #28 remains draft and must not be merged until v1.18 launches and its modify/restore cycle is tested on the target Galaxy A53.
