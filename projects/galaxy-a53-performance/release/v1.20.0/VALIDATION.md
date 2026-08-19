# Validation — v1.20.0 Doctor 2.0 BETA

## Build

- GitHub Actions: PASS
- Android SDK 35: PASS
- Java compilation: PASS
- D8 overlay compilation: PASS

## APK integration

- `versionName 1.20.0`: PASS
- `versionCode 43`: PASS
- `debuggable=false`: PASS
- `allowBackup=false`: PASS
- `classes1.dex` through `classes6.dex` byte-identical to accepted v1.19: PASS
- `classes7.dex` differs only by the same-length Activity alias `MainActivity -> MainActivjty` plus repaired DEX header: PASS
- `classes8.dex` contains Doctor 2.0: PASS
- Activity chain `MainActivity -> MainActivjty -> MainActivitw -> MainActivitz -> MainActivitx`: PASS
- duplicate DEX classes: 0
- DEX SHA-1 / Adler32: PASS
- DEX string ordering: PASS
- resources/assets/resources.arsc unchanged from accepted v1.19: PASS

## Read-only audit

Doctor overlay contains no:

- `settings put`
- `cmd appops set`
- `cmd netpolicy set`
- `RUN_ANY_IN_BACKGROUND`
- `RUN_IN_BACKGROUND`
- `force-stop`
- `am kill`
- `set-standby-bucket`
- `am set-inactive`
- `deviceidle whitelist`
- `cmd power set-mode`

## Signing/package

- ZIP integrity: PASS
- stored-entry 4-byte alignment: PASS
- APK Signature Scheme v2 digest: PASS
- RSA/SHA-256 signature: PASS
- X10 signing certificate continuity: PASS

## Runtime acceptance

Physical Samsung Galaxy A53 acceptance: **PENDING**.

Static/cryptographic validation does not substitute for ART/One UI runtime testing. The distribution package includes a clean-install and crash-capture script for this purpose.
