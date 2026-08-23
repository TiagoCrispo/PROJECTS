# Galaxy A53 Performance

> Reversible Android maintenance and diagnostics tooling built around real device state on the Samsung Galaxy A53 5G.

| | |
|---|---|
| **Accepted release** | **v1.20.0 — Galaxy Doctor 2.0** |
| **Current development** | **v1.21.0 — Ready Mode BETA** under physical-device validation |
| **Platform** | Android · Samsung Galaxy A53 5G |
| **Focus** | Device diagnostics · storage · reversible system controls · Shizuku · physical-device acceptance |

## Overview

Galaxy A53 Performance is a device-specific Android utility built around a conservative rule: system changes should be **observable, reversible and verified after application**. The project combines storage cleanup, background-app controls, gaming-oriented sessions and read-only diagnostics without presenting unsupported state as success.

## Accepted feature stack through v1.20

- **SAFE baseline** — known-working executable core with hardened manifest settings;
- **Gallery 2.0** — visual media cleanup, filters, multi-select, preview and Android system trash;
- **Background Center** — per-app UsageStats diagnostics plus reversible background/Data Saver policy handling with pre-change snapshots;
- **Gaming 2.0** — per-game reversible sessions for refresh-rate policy, power saver, Data Saver and optional user-selected background restrictions;
- **Galaxy Doctor 2.0** — read-only dashboard for thermal state, battery, RAM, storage, refresh-rate read-back, permissions/capabilities and pending rollback state.

## Current v1.21 work

Ready Mode adds a read-only preflight experience for choosing a game and checking device readiness before launch. The current candidate surfaces thermal status/headroom, battery, RAM, storage, display refresh, min/peak refresh read-back, Data Saver, Shizuku state, active Background Center rules and pending Gaming rollback state.

Ready Mode itself does not add new performance writes. It provides navigation to the already accepted feature layers and an option to open the selected game without applying changes.

## Engineering rules

The project deliberately avoids fake or irreversible optimization behavior:

- no task-killer loop, blind `force-stop`/`am kill`, fabricated CPU temperature or invented performance percentages;
- state-changing features snapshot first, persist synchronously, apply, read back and preserve rollback when verification fails;
- unsupported or unreadable state is reported as **NC**, never converted into a fake success;
- diagnostic/readiness layers remain read-only unless the user deliberately enters an already accepted state-changing feature;
- signing/integrity checks are treated as packaging evidence, not as a replacement for ART/One UI device testing.

## Galaxy Doctor data sources

Doctor uses Android framework signals rather than estimates, including `ActivityManager.MemoryInfo`, `StatFs`, `PowerManager`, the battery broadcast, Usage Access/AppOps state, All Files Access, `WRITE_SETTINGS`, notification status, display refresh rate and Shizuku read-back where available.

## Validation status

**v1.20.0 completed physical Galaxy A53 acceptance and is the accepted base.**

The v1.21 Ready Mode candidate has passed its documented CI/static/signing checks but remains intentionally unmerged until physical Galaxy A53 acceptance is completed. This keeps the public status aligned with what has actually been tested.

## Release documentation

Versioned overlay directories are retained when they remain executable source layers in the current build chain. Release documentation is kept focused on the current accepted/candidate line rather than accumulating obsolete release-note folders.
