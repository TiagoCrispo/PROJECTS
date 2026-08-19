# Testing — WA Vault v0.5.31

## Static gate

```bash
tools/run_static_validation.sh
```

The gate includes lifecycle/deletion regression, DB migrations, media stress, concurrency/backpressure, security/Android 16 invariants, parser/type smoke tests and historical freeze checks.

## Clean build gate

```bash
tools/build_and_verify.sh
```

Required outputs include debug APK, AndroidTest APK, optimized release APK, release AAB, R8 mapping, lint reports and SHA-256 manifest.

## Device gate

```bash
tools/device_acceptance.sh <debug-apk> <androidTest-apk>
```

The script performs clean install, package contract checks, launch/logcat checks, background/resume, instrumentation, force-stop/reopen loops and captures runtime diagnostics. All-files access is deliberately denied by default so degraded-mode stability is tested first.

## Manual WhatsApp acceptance

1. Receive at least 20 messages; delete none; close/reopen WA Vault → 0 false deletions and 0 redownloads.
2. Force-stop/process-death and reopen → 0 false deletions.
3. Disable/re-enable notification listener → 0 historical deletions.
4. Reboot → 0 historical deletions.
5. After `BASELINE_READY`, delete exactly one new message. Exactly one historical row may become confirmed only if strong one-to-one correlation exists; otherwise it must remain unknown.
6. Exercise photo/video/audio/document capture, duplicate content, replay/retry and background/screen-off scenarios.
7. Inspect structured `WHY_DETECTED`, `SOURCE_EVENT`, `MATCH_METHOD` and `CONFIDENCE` logs for every deletion candidate.

Only a physical-device pass with real WhatsApp events can move the project to `PRODUCTION_VERIFIED`.
