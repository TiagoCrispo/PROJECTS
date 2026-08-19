# Release Engineering — WA Vault v0.5.31

## Release status

- versionCode: **81**
- versionName: **0.5.31**
- `STATIC_PASS`
- `BUILD_PASS`
- `REPRODUCIBILITY_PASS`
- `SIGNED_RELEASE_VERIFIED`
- Android lint: **0 errors**
- R8 mapping: verified
- Optimized APK + AAB: verified
- Emulator/device acceptance: pending physical Android execution

The release is source-compiled. It is not a DEX/binary hotfix of an older APK.

## Toolchain

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- JDK 17
- compileSdk / targetSdk 36
- minSdk 26
- Android Build Tools 35.0.0 in CI

AGP 9.x is intentionally deferred: changing major build tooling in the same release as the lifecycle/deletion architecture would enlarge the regression surface without solving a current defect.

## Release optimization

Release enables R8 full-mode minification, optimized resource shrinking, `proguard-android-optimize.txt`, and retained source/line attributes for retraceable stack traces. Debug remains unminified for runtime diagnosis.

## Signing

Source never contains a keystore, private key or password. CI builds the unsigned optimized release; production signing is performed only in a trusted environment.

Final APK verification:

- APK Signature Scheme v2: **verified**
- APK Signature Scheme v3: **verified**
- signer count: **1**
- certificate SHA-256: `5e142d9605d4d63c8264d013e71a7d5e1aa04ca35438cf973e04c388c85d5be6`
- final APK SHA-256: `91a6ad57093aa3950ccbe7123e2d7a46497cfd594c2ac2940fccaadd8c8fe331`
- AAB SHA-256: `e9bfc1d9527f15cffaf833d43b0e3bb141b3313341d219e1436c8e1885b16bb5`

The signed APK contains the same application ZIP entries and byte-identical entry payloads as the CI-generated unsigned R8 release; only the APK signing block differs.

## Repository hygiene

The temporary source bootstrap, temporary signing-tool export workflow and obsolete build-environment guide were removed after validation. Forge3D was removed from the public portfolio index and its project page was deleted before merge. The repository retains only the canonical WA Vault source, active regression tests and maintained documentation.

## Acceptance boundary

The repository can assert build/reproducibility/signature verification. `PRODUCTION_VERIFIED` is intentionally withheld until installation, launch, logcat, force-stop/process-death, reboot and real WhatsApp deletion/media acceptance are executed on a physical Android device.
