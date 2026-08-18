# WA Vault v0.5.31

Privacy-first Android vault for locally preserving message events and media exposed by WhatsApp notifications and Android storage APIs.

> **Validation:** `STATIC_PASS — BUILD_CI_PENDING — EMULATOR_PENDING — REAL_DEVICE_PENDING`  
> WA Vault is not declared production-verified until the generated APK passes install, launch, logcat, lifecycle, reboot and real WhatsApp acceptance tests.

## Core guarantees

- **Fail-closed deletion detection:** missing notification state, app restart, process death, listener reconnect, `onNotificationRemoved`, empty snapshots and `APP_CANCEL` never prove a deleted message.
- **One-to-one confirmed deletion:** only a fresh live event with singular deletion evidence, a trusted session baseline, a stable MessagingStyle timestamp and exactly one historical match may reach `DELETE_CONFIRMED`.
- **Process-death safety:** deletion evidence and the message transition commit in one SQLite transaction.
- **Stable logical identity:** repeated identical messages can coexist through `identity_slot`, while exact notification replays remain idempotent.
- **Encrypted media pipeline:** permanent media is AES-256-GCM ciphertext backed by Android Keystore; plaintext capture exists only inside app-private staging and is never committed to the permanent vault.
- **Physical deduplication without logical loss:** one media blob may link to many legitimate messages through `media_message_links`.
- **Bounded background work:** media/recovery executors use bounded queues and rejection policies instead of unbounded memory growth or caller-thread fallback.
- **Local-first privacy:** no `INTERNET` permission, no cloud backup, private provider/listener/receiver surfaces, screen-share sensitivity, optional biometric UI lock.

## Android baseline

- minSdk 26
- compileSdk 36
- targetSdk 36
- Java 17
- Android Gradle Plugin 8.13.2
- Gradle 8.13
- SQLite schema v15

## Build

```bash
./tools/check_android_toolchain.sh
./tools/run_static_validation.sh
./tools/build_and_verify.sh
```

If a Gradle wrapper is not present yet, use Gradle 8.13 once to run `tools/bootstrap_gradle_wrapper.sh`.

Release signing is **never stored in source**. A signed release is enabled only when these environment variables exist:

```text
WA_VAULT_KEYSTORE_FILE
WA_VAULT_KEYSTORE_PASSWORD
WA_VAULT_KEY_ALIAS
WA_VAULT_KEY_PASSWORD
```

Without them, release APK/AAB compilation still validates R8/resource shrinking but the release APK remains unsigned; the debug APK remains installable for runtime acceptance.

## Validation levels

- `STATIC_PASS`: source/regression/security/stress checks pass.
- `BUILD_PASS`: clean Gradle build, unit tests, lint, R8 release APK/AAB and package verification pass.
- `EMULATOR_PASS`: instrumented tests execute on Android.
- `REAL_DEVICE_PASS`: physical-device acceptance passes.
- `PRODUCTION_VERIFIED`: all required levels plus real WhatsApp deletion/media scenarios pass.

See [ARCHITECTURE.md](ARCHITECTURE.md), [SECURITY.md](SECURITY.md), [TESTING.md](TESTING.md), [RELEASE.md](RELEASE.md) and [CHANGELOG.md](CHANGELOG.md).
