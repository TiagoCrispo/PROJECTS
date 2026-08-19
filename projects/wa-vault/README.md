# WA Vault v0.5.31

Privacy-first Android vault for locally preserving message events and media exposed by WhatsApp notifications and Android storage APIs.

> **Validation:** `STATIC_PASS — BUILD_PASS — REPRODUCIBILITY_PASS — SIGNED_RELEASE_VERIFIED — EMULATOR_PENDING — REAL_DEVICE_PENDING`  
> The v0.5.31 release is source-compiled, R8-optimized and cryptographically verified. `PRODUCTION_VERIFIED` remains reserved for physical-device and real WhatsApp acceptance.

## Core guarantees

- **Fail-closed deletion detection:** missing notification state, app restart, process death, listener reconnect, `onNotificationRemoved`, empty snapshots and `APP_CANCEL` never prove a deleted message.
- **One-to-one confirmed deletion:** only a fresh live event with singular deletion evidence, a trusted session baseline, a stable MessagingStyle timestamp and exactly one historical match may reach `DELETE_CONFIRMED`.
- **Process-death safety:** deletion evidence and the message transition commit in one SQLite transaction.
- **Stable logical identity:** repeated identical messages can coexist through `identity_slot`, while exact notification replays remain idempotent.
- **Encrypted media pipeline:** permanent media is AES-256-GCM ciphertext backed by Android Keystore; plaintext capture exists only inside app-private staging and is never committed to the permanent vault.
- **Physical deduplication without logical loss:** one media blob may link to many legitimate messages through `media_message_links`.
- **Bounded background work:** media/recovery executors use bounded queues and rejection policies instead of unbounded memory growth or caller-thread fallback.
- **Local-first privacy:** no `INTERNET` permission, no cloud backup, private provider/listener/receiver surfaces, screen-share sensitivity and optional biometric UI lock.

## Android baseline

- versionCode 81 / versionName 0.5.31
- minSdk 26
- compileSdk 36
- targetSdk 36
- Java 17
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- SQLite schema v15

## Build and verification

```bash
./tools/check_android_toolchain.sh
./tools/run_static_validation.sh
./tools/build_and_verify.sh
```

GitHub Actions validates static regressions, debug/release unit tests, Android lint, R8/resource shrinking, APK/AAB packaging, packaged security contracts and reproducible optimized release builds.

Release signing is **never stored in source**. Production signing happens only in a trusted environment through external keystore credentials. The final v0.5.31 APK was verified with APK Signature Scheme v2 and v3 and preserves the established WA Vault signing identity.

## Validation levels

- `STATIC_PASS`: source/regression/security/stress checks pass.
- `BUILD_PASS`: clean Gradle build, unit tests, lint, R8 release APK/AAB and package verification pass.
- `REPRODUCIBILITY_PASS`: two clean optimized release builds produce matching protected release payloads.
- `SIGNED_RELEASE_VERIFIED`: the final APK verifies cryptographically with the established release certificate.
- `EMULATOR_PASS`: instrumented tests execute on Android.
- `REAL_DEVICE_PASS`: physical-device acceptance passes.
- `PRODUCTION_VERIFIED`: all required levels plus real WhatsApp deletion/media scenarios pass.

See [ARCHITECTURE.md](ARCHITECTURE.md), [SECURITY.md](SECURITY.md), [TESTING.md](TESTING.md), [RELEASE.md](RELEASE.md), [TEST_MATRIX.md](TEST_MATRIX.md) and [CHANGELOG.md](CHANGELOG.md).
