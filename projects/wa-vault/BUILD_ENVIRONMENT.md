# WA Vault v0.5.31 — Build Environment

Canonical toolchain:

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- compileSdk 36 / targetSdk 36
- Android Build Tools 35.0.0

`tools/check_android_toolchain.sh` is the local preflight. GitHub Actions installs the same versions, generates a pinned Gradle wrapper, executes `tools/build_and_verify.sh`, and performs an independent unsigned-release reproducibility job.

This source tree intentionally contains no APK/AAB, compiled DEX/class files or signing keys.
