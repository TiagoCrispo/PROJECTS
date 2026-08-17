# Project continuity

Last consolidated: **2026-08-17**

This folder is the handoff point I use before changing any of my active projects.

The point is simple: a new chat, machine or work session should not have to reconstruct the project from memory. Read the project file here first, then inspect the actual source/package/repository before making changes.

## Rules before touching a project

1. **Start from the baseline listed here, never from an older APK/ZIP/EXE just because it is easier to find.**
2. **The real source/package wins over chat history.** If a newer verified artifact exists, update this registry before continuing.
3. **Preserve existing behavior unless the requested change explicitly replaces it.** A new feature is not a reason to silently remove an older one.
4. **Run regression checks after changes.** If a project has block/version-specific tests, keep the old gates and add new ones instead of deleting them.
5. **Do not claim hardware, Windows, Android, Roblox Studio or device tests that were not actually run.** Source/static checks and physical runtime checks are different things.
6. **Keep risky operations bounded and reversible where the project already supports that.** Do not replace safe logic with generic optimizer/repair shortcuts.
7. **After every accepted update:** bump the version when appropriate, record what changed, record what was validated, update the exact next step here, and keep the previous known-good state recoverable.
8. **Old project names are aliases only.** Canonical names live in [`../PROJECTS.md`](../PROJECTS.md).

## Current baselines

| Project | Current continuation baseline | Next anchor |
|---|---|---|
| [Forge3D Studio](./forge3d-studio.md) | **v7.0.9 · Block 9** | Block 10 — Roblox Compiler + Live Link |
| [UTN Scholar](./utn-scholar.md) | **UTN AI 0.55.0+55 · Block 55** | physical Flutter/Windows/Android quality gates before claiming a release |
| [ProAim](./proaim.md) | **V25.0 · Block 11** | target-PC lifecycle/update validation |
| [A53 Performance](./a53-performance.md) | **v1.15.7 · THERMAL / ANALYSIS** | physical Galaxy A53 verification before further risky tuning |
| [WA Vault](./wa-vault.md) | **v0.5.21 · Stable Privacy Freeze** | continue without reintroducing backup paths or plaintext storage |
| [Meteora Weather](./meteora-weather.md) | **Mendoza Meteo Pro GPS v1.2 source baseline** | preserve behavior while migrating to the Meteora name/structure |

## How a future session should start

A safe opening sequence is:

1. Read `PROJECTS.md` to resolve the project name/old aliases.
2. Read this folder's matching continuity file.
3. Inspect the actual latest source/package and its changelog/tests.
4. Confirm the requested work is being applied on top of that baseline.
5. Make the change, regress old behavior, then update the continuity file.

<!--
These files are intentionally practical rather than decorative. They are here to survive deleted chats and avoid accidental regressions.
-->
