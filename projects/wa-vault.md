# WA Vault

**Current release: v0.5.31 (versionCode 81)**  
Android · local-first privacy · notification lifecycle · encrypted media · reliability engineering

WA Vault is a privacy-focused Android project for preserving and organizing message-related events and associated media locally without treating notification disappearance, app restarts or service lifecycle changes as proof that a WhatsApp message was deleted.

## What it does

- captures supported local message/event information exposed through Android notifications;
- opens directly in the deleted-message recovery experience;
- uses fail-closed deletion detection to prevent restart/process-death false positives;
- keeps delayed files associable with the correct event even when Android delivers them later;
- preserves pending work across app restarts and process death;
- uses stable logical identity, durable SQLite state and idempotent processing;
- protects against duplicate downloads and duplicate deletion transitions;
- handles images, video, audio/voice notes and documents through durable pending-media logic;
- stores permanent media as encrypted local ciphertext backed by Android Keystore;
- uses bounded background executors and conservative recovery paths;
- has no `INTERNET` permission.

## Release engineering

v0.5.31 is a **source-compiled** Android release, not a DEX hotfix. The repository validates:

- 20/20 static regression/security gates;
- debug and release unit tests;
- Android lint with 0 errors;
- targetSdk / compileSdk 36 and minSdk 26;
- R8 full-mode minification and resource shrinking;
- optimized APK + AAB generation;
- packaged security-contract checks;
- reproducible optimized release builds;
- APK Signature Scheme v2/v3 with the established WA Vault release identity.

Physical-device and real WhatsApp acceptance remain a separate runtime gate and are not represented as completed until actually executed.

## Engineering focus

The difficult part of the project is timing and ordering. Android notifications and files do not always arrive together, so correlation, restart recovery, process-death safety and duplicate protection are treated as core behavior rather than edge cases.

When evidence is weak, WA Vault prefers `UNKNOWN`/non-deleted state over inventing a deletion event from notification removal, missing snapshots, generic counters or summaries.

## Privacy

The project is intentionally local-first. It does not depend on cloud backup, does not request network access, and treats encrypted storage and constrained exported surfaces as product requirements rather than optional hardening.

Full source and engineering documentation live in [`projects/wa-vault/`](./wa-vault/).
