# Galaxy A53 Performance

Android maintenance and performance utility built around the Samsung Galaxy A53 5G, with a strong emphasis on reversible changes, truthful read-back and physical-device acceptance.

## Current candidate

**v1.21.0 — Ready Mode BETA** (`versionCode 44`)

The accepted runtime base is **v1.20.0 Galaxy Doctor 2.0**. v1.21 adds a read-only preflight and quick-navigation layer; it introduces no new performance writes.

## Current feature stack

- **SAFE baseline** — known-working v1.15.2 executable core, hardened manifest (`debuggable=false`, `allowBackup=false`).
- **v1.17 Gallery 2.0** — visual media cleanup, filters, multi-select, preview and Android system trash.
- **v1.18 Background Center** — per-app UsageStats diagnostics and reversible `RUN_ANY_IN_BACKGROUND` / Data Saver policy handling with pre-change snapshots.
- **v1.19 Gaming 2.0** — per-game reversible sessions for refresh-rate policy, power saver, Data Saver and optional user-selected background restrictions.
- **v1.20 Galaxy Doctor 2.0** — read-only dashboard for thermal state, battery, RAM, storage, refresh-rate read-back, permissions/capabilities and pending rollback state.
- **v1.21 Ready Mode** — read-only game preflight combining thermal status/headroom, battery, RAM, storage, refresh state, Data Saver, Shizuku, saved Gaming profile, active background rules and pending rollback state, with quick navigation to accepted feature layers.

The versioned overlay directories are retained because they are executable source layers used by the current build chain. Obsolete release-note folders are intentionally removed so `release/` contains only the current candidate.

## Engineering rules

- one major feature per version;
- clean-install physical acceptance on the Galaxy A53 before promotion to `main`;
- no task-killer loop, `force-stop`, blind `am kill`, fake CPU temperature or fabricated performance percentage;
- state-changing features snapshot first, persist synchronously, apply, read back and preserve rollback if verification fails;
- unsupported or unreadable state is reported as **NC**, never as a fake success;
- new overlays are compiled with Android SDK 35 + `javac` + D8 through GitHub Actions;
- final APKs are verified with the official Android `apksigner` tool;
- APK signing/integrity checks are necessary but do not replace on-device ART/One UI testing.

## Ready Mode contract

Ready Mode itself is read-only. It can launch the selected game without changing Android settings, or navigate to Gaming 2.0, Doctor 2.0, Background Center and Gallery 2.0. Any state-changing operation remains inside the previously accepted modules with their existing snapshot/read-back/rollback protections.

## Release status

v1.21.0 has passed Android SDK compilation and static APK integration/signing validation. Physical A53 acceptance is still required before the pull request is merged and before the build is considered accepted.

See [`release/v1.21.0/`](./release/v1.21.0/) for the current release notes, hashes and validation record.
