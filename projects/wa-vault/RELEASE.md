# Release Engineering — WA Vault v0.5.31

## Toolchain

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17
- compileSdk / targetSdk 36
- Build Tools 35.0.0

AGP 9.x is intentionally deferred: changing major build tooling in the same release as the lifecycle/deletion architecture would enlarge the regression surface without solving a current defect.

## Release optimization

Release enables R8 full mode, code minification, resource shrinking, optimized resource shrinking for AGP 8.13, `proguard-android-optimize.txt`, and retained source/line attributes for retraceable stack traces. Debug remains unminified for runtime diagnosis.

## Signing

Source never contains a keystore. A signed release is configured only through `WA_VAULT_KEYSTORE_FILE`, `WA_VAULT_KEYSTORE_PASSWORD`, `WA_VAULT_KEY_ALIAS` and `WA_VAULT_KEY_PASSWORD`.

If those values are absent, CI still builds/validates the unsigned optimized release and an installable debug APK. Production signing happens only in a trusted environment.

## Required release evidence

A release is not accepted without static regression pass, clean unit tests and Android lint, debug APK + AndroidTest APK, optimized release APK + AAB, packaged-manifest security contract verification, R8 mapping, SHA-256 manifest, emulator/device runtime evidence and real WhatsApp acceptance before `PRODUCTION_VERIFIED`.
