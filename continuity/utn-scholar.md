# UTN Scholar — continuation state

**Canonical public project:** UTN Scholar  
**Internal/current product name in source:** UTN AI  
**Old names:** APPS UTN V2, NotebookLM para la UTN, app UTN  
**Current baseline:** **UTN AI 0.55.0+55 — Block 55**

A future session should treat Blocks 02–55 as cumulative work. Do not go back to Block 48/50/51 as if later blocks did not happen.

## Architecture that must stay intact

The secure PC ↔ Android/LAN model stabilized before Block 55 and remains part of the current baseline:

- backend binds to `127.0.0.1` by default;
- `0.0.0.0` is used only when `UTN_AI_LAN_MODE=1`;
- private LAN routes require a persistent Bearer token;
- localhost retains the intended Windows-runtime bypass;
- pairing uses a 6-digit one-shot code with rate limit/cooldown;
- the code is invalidated after valid pairing;
- wildcard CORS `*` is not used for private traffic;
- UDP discovery uses `UTN_AI_DISCOVER_V1` on port 8788;
- UTN Doctor covers storage, backend, AI, pairing and toolchain health;
- diagnostic ZIP export is sanitized;
- `.bak/.tmp` recovery is observable;
- local/degraded mode remains explicit when backend/AI are unavailable;
- provider auth, rate limit, timeout and upstream failures remain differentiated;
- build flow is transactional with snapshot/restore, a bounded retry/repair path and diagnostics instead of pretending a failed build succeeded;
- backend watchdog/port-owner diagnostics remain part of Windows startup.

## Desktop + Android behavior retained from later blocks

- adaptive primary navigation: NavigationBar on mobile, NavigationRail on wide desktop;
- major sections preserve state instead of rebuilding navigation state unnecessarily;
- desktop library drag & drop;
- right-click file actions and desktop double-click open behavior;
- master-detail library layout on wide windows;
- Android Share Target feeds shared files into intelligent import;
- Windows remembers window size, position and maximized state atomically;
- system notifications can report long-task completion/failure when enabled.

## Block 55 quality layer

- official Flutter `integration_test` dependency;
- testable/public `StartupProgressView` and `StartupFailureView` with stable semantics/actions;
- testable `AdaptiveAppShell` for responsive navigation without duplicated home logic;
- interaction tests for startup and responsive navigation;
- accessibility checks for tap targets, labels and contrast;
- narrow-layout test at 140% text scale;
- preferences for high contrast, reduced motion and text scale 90–140%;
- explicit semantics for subject cards, file/transcription states and UTN Doctor checks;
- JSON chaos/recovery cases: corrupt primary/tmp can fall back safely without destroying the original when no valid recovery exists;
- `PerformanceTelemetryService` records local in-memory FrameTiming, p95, frames over 16.7/32 ms and startup duration;
- **no external telemetry upload** was added;
- UTN Doctor/diagnostics include current-session performance evidence;
- GitHub Actions contains a Flutter quality gate that precedes Android/Windows jobs;
- `PRUEBAS_CALIDAD_UTN_AI.bat` runs dependency/analyze/widget/integration/regression quality work;
- `ACTUALIZAR_GOLDENS_UTN_AI.bat` is the deliberate path for reference-machine visual baselines.

## Last verified source-level regression

- source regression Blocks 02–55: PASS after the UTF-8 entrypoint correction;
- Block 55 integrity: PASS;
- UI quality guard: PASS;
- Dart structure: PASS, 149 files;
- Dart imports: PASS;
- analyzer regression guard: PASS;
- build pipeline guard: PASS;
- BAT entrypoint integrity: PASS;
- backend Node tests: **56/56 PASS**;
- `node --check` on backend/security/streaming: PASS.

## Physical gates are separate — never fabricate them

The environment that produced the Block 55 audit did not have the target Flutter/Windows/Android toolchain. Therefore the following are **not automatically considered passed** just because source guards pass:

- real `flutter analyze`;
- real `flutter test --coverage`;
- Flutter `integration_test` execution;
- real PNG golden generation/comparison;
- Windows/Android profiling;
- Windows release build;
- Android release APK build;
- native permission/share/notification interaction on the real phone;
- real LAN discovery/pairing from the target phone when a release changes that layer.

## Exact next step

Before calling a new build release-ready, run the physical Flutter quality/build gates on the actual Windows/Android environment. If a future Block 56 or later is created, it must start from **0.55.0+55**, preserve the secure LAN/pairing/recovery/build architecture above, and add its regression gates on top of Blocks 02–55.

## Before delivering a future UTN Scholar / UTN AI build

- confirm the source is based on 0.55.0+55 or a newer documented continuity state;
- never remove older regression gates merely because UI contracts changed;
- keep local/degraded mode usable;
- never place provider secrets in the client;
- distinguish static/source validation from physical Windows/Android validation;
- update this file with the new version/block, test counts, plugins/toolchain changes, physical gates run and the next exact continuation point.
