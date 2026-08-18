# Validation — v1.16.0 FINAL

## Identity
- package: `com.fer.a53performance`
- versionName: `1.16.0`
- versionCode: `38`
- targetSdk: `35`
- `debuggable=false`
- `allowBackup=false`

## Crash hotfix
- `TrashStore.writeAll()` code_item equals original v1.15.2: PASS
- equals P12 known base: PASS
- differs from experimental P16 rewrite: PASS
- P16 custom try/catch rewrite absent: PASS

## DEX
- classes.dex SHA-1 / Adler32: PASS
- classes2.dex SHA-1 / Adler32: PASS
- classes3.dex SHA-1 / Adler32: PASS
- classes4.dex SHA-1 / Adler32: PASS
- structural control-flow audit: PASS
- methods checked: 23,702 + 32 + 79 + 995
- branch/payload instruction-boundary checks: PASS
- move-result adjacency structural checks: PASS
- try/catch boundary/handler/move-exception checks: PASS

## Visual regression
- `res/*` byte-identical vs v1.15.2 SAFE_AREA: PASS
- `assets/*` byte-identical: PASS
- `resources.arsc` byte-identical: PASS
- visual resource diff: 0

## Package / signing
- ZIP integrity: PASS
- 4-byte alignment for STORED APK entries: PASS
- APK Signature Scheme v2 content digest recomputation: PASS
- RSA/SHA-256 signature verification: PASS
- X10 certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`
- APK SHA-256: `3b9b26d3b1bb3af8d693bb8579104d7448adf99f3afe202a994c3c5c2170b231`

## Runtime boundary
Physical Galaxy A53 execution was not available in the build environment. `STATIC_PASS` is not treated as `REAL_DEVICE_PASS`. Runtime acceptance requires launch/background/resume plus manual Shizuku/Gaming/AUTO/Data Saver/Storage checks on the target phone.
