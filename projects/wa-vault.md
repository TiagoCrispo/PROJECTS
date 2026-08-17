# WA Vault

WA Vault is a privacy-focused Android project for preserving and organizing message-related events and associated media locally.

## What it does

- captures supported local message/event information;
- keeps delayed files associable with the correct event even when Android delivers them later;
- preserves pending work across app restarts;
- uses FIFO/batch correlation for groups of related media;
- protects against duplicate processing;
- handles images, video, audio/voice notes and documents through durable pending-media logic;
- keeps document handling consistent with the rest of the media pipeline;
- uses encrypted local persistence;
- prioritizes conservative deleted-message detection to reduce false positives.

## Engineering focus

The difficult part of the project is timing and ordering. Android notifications and files do not always arrive together, so the app treats correlation, restart recovery and duplicate protection as core behavior rather than edge cases.

When evidence is weak, the parser prefers an unknown/non-deleted state over inventing a deletion event from generic notification counters or summaries.

## Privacy

The project is intentionally local-first. It does not depend on a cloud backup workflow, and encrypted storage is treated as part of the product rather than an optional add-on.
