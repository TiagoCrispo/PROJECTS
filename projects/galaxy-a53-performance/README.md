# Galaxy A53 Performance

> Reversible Android maintenance and diagnostics tooling built around real device state on the Samsung Galaxy A53 5G.

| | |
|---|---|
| **Status** | Release candidate · **v1.20.0 — Galaxy Doctor 2.0 BETA** |
| **Accepted base** | v1.19.0 Gaming 2.0 |
| **Platform** | Android · Samsung Galaxy A53 5G |
| **Focus** | Device diagnostics · storage · reversible system controls · Shizuku · physical-device acceptance |

## Overview

Galaxy A53 Performance is a device-specific Android utility built around a conservative rule: system changes should be **observable, reversible and verified after application**. The project combines storage cleanup, background-app controls, gaming-oriented sessions and read-only diagnostics without presenting unsupported state as success.

## Current feature stack

- **SAFE baseline** — known-working executable core with hardened manifest settings;
- **Gallery 2.0** — visual media cleanup, filters, multi-select, preview and Android system trash;
- **Background Center** — per-app UsageStats diagnostics plus reversible background/Data Saver policy handling with pre-change snapshots;
- **Gaming 2.0** — per-game reversible sessions for refresh-rate policy, power saver, Data Saver and optional user-selected background restrictions;
- **Galaxy Doctor 2.0** — read-only dashboard for thermal state, battery, RAM, storage, refresh-rate read-back, permissions/capabilities and pending rollback state.

## Engineering rules

The project deliberately avoids fake or irreversible optimization behavior:

- no task-killer loop, blind `force-stop`/`am kill`, fabricated CPU temperature or invented performance percentages;
- state-changing features snapshot first, persist synchronously, apply, read back and preserve rollback when verification fails;
- unsupported or unreadable state is reported as **NC**, never converted into a fake success;
- Doctor remains read-only; access actions open Android settings or request Shizuku authorization rather than silently changing unrelated state;
- signing/integrity checks are treated as packaging evidence, not as a replacement for ART/One UI device testing.

## Galaxy Doctor data sources

Doctor uses Android framework signals rather than estimates, including `ActivityManager.MemoryInfo`, `StatFs`, `PowerManager`, the battery broadcast, Usage Access/AppOps state, All Files Access, `WRITE_SETTINGS`, notification status, display refresh rate and Shizuku read-back where available.

## Validation status

v1.20.0 has passed source compilation and static APK integration/signing validation. **Physical Galaxy A53 acceptance is still required** before the candidate is considered fully accepted.

The versioned overlay directories are retained because they are executable source layers used by the current build chain. Obsolete release-note folders are intentionally excluded from the active release directory.

## Current release documentation

See **[`release/v1.20.0/`](./release/v1.20.0/)** for the candidate release notes, hashes and validation record.
