# WA Vault

> Privacy-focused Android reliability project for preserving message-related events and associated media locally without inventing deletion events from weak evidence.

| | |
|---|---|
| **Status** | Active release · **v0.5.31** |
| **Platform** | Android · minSdk 26 · target/compileSdk 36 |
| **Version** | versionCode 81 |
| **Focus** | Local persistence · notification lifecycle · encrypted media · restart/process-death safety |

## Overview

WA Vault preserves and organizes supported local message/event information and associated media exposed through Android notification flows. Its core engineering challenge is timing: notifications, files, service lifecycle changes and process restarts do not necessarily occur together or in a reliable order.

The project therefore treats **correlation, durable state and false-positive prevention** as core behavior rather than edge cases.

## Product capabilities

- captures supported local event information exposed through Android notifications;
- opens directly into the deleted-message recovery experience;
- uses fail-closed deletion detection so notification disappearance or process restart is not treated as proof of deletion;
- keeps delayed files associable with the correct event when Android delivers them later;
- preserves pending work across app restarts and process death;
- uses stable logical identity, durable SQLite state and idempotent processing;
- protects against duplicate downloads and duplicate deletion transitions;
- supports images, video, audio/voice notes and documents through durable pending-media logic;
- stores permanent media as encrypted local ciphertext backed by Android Keystore;
- uses bounded background executors and conservative recovery paths;
- intentionally requests **no `INTERNET` permission**.

## Reliability model

When evidence is weak, WA Vault prefers an **UNKNOWN / non-deleted state** over creating a believable but unsupported deletion event from notification removal, missing snapshots, generic counters or summaries.

This design is specifically intended to survive:

- app restarts;
- Android process death;
- delayed media delivery;
- duplicate work;
- lifecycle ordering changes.

## Release engineering

v0.5.31 is a source-compiled Android release rather than a DEX hotfix. The documented validation includes:

- 20/20 static regression/security gates;
- debug and release unit tests;
- Android lint with 0 errors;
- R8 full-mode minification and resource shrinking;
- optimized APK + AAB generation;
- packaged security-contract checks;
- reproducible optimized release builds;
- APK Signature Scheme v2/v3 with the established release identity.

## Validation boundary

Physical-device and real WhatsApp acceptance remain a separate runtime gate and are not presented as completed until those tests are actually performed.

## Privacy

The project is intentionally local-first: no cloud backup dependency, no requested network access, encrypted local media storage and constrained exported surfaces.

## Source and engineering documentation

Full source and technical documentation live in **[`projects/wa-vault/`](./wa-vault/)**.
