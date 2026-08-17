# A53 Performance — continuation state

**Canonical project:** A53 Performance  
**Old names:** APP - GALAXY, GALAXY V2, app Galaxy, A53Performance  
**Latest recovered baseline:** **v1.15.7 — THERMAL / ANALYSIS**  
**APK anchor:** `A53Performance_v1.15.7_THERMAL_ANALYSIS.apk`

This project started specifically around a Samsung Galaxy A53. Future changes should preserve the practical behavior already built instead of replacing it with generic Android “cleaner/booster” claims.

## Baseline behavior to preserve

- storage scanning and cleanup flows;
- deleted items disappear from the UI immediately instead of waiting for a full rescan;
- free-space information updates as actions happen;
- long operations expose progress/state rather than making the app look frozen;
- cleanup/RAM/performance actions should explain what they do and avoid fake results;
- performance profiles must remain explicit about what they changed;
- local diagnostics remain available for debugging actual device behavior.

## v1.15.7 recovered changes

- incremental/uncapped photo analysis rather than forcing the whole library through one blocking pass;
- cache support for photo analysis;
- similarity analysis uses dHash + aHash + aspect-ratio evidence rather than a single weak heuristic;
- severe thermal state can pause/resume expensive work instead of continuing blindly;
- Shizuku connection handling includes reconnect/circuit-breaker behavior;
- profile application is verified instead of assuming a command succeeded;
- diagnostics remain local;
- partial internal refactor without discarding the existing app behavior;
- broad `QUERY_ALL_PACKAGES` access was removed;
- signing/update continuity was kept compatible with the immediately previous v1.15.4–v1.15.6 line.

## Shizuku rule

Do not turn Shizuku into an arbitrary command console. The hardened line deliberately restricts what the app can request. New privileged actions should be individually defined, justified and verified.

## Thermal/performance rule

The app must prefer correct device state over an impressive-looking progress bar. If the phone reports severe thermal pressure, expensive analysis should yield/pause and recover cleanly rather than forcing work through and heating the device further.

## Build/toolchain anchor

The recovered project setup uses:

- JDK 17;
- Gradle 8.11.1;
- Android Studio/Gradle project workflow;
- Galaxy A53 5G physical debugging for real-device verification;
- canonical CI artifact naming around `A53Performance-debug` and the Android debug workflow when working from the repository.

## What is not considered physically verified yet

The v1.15.7 state was recovered as the newest continuation point, but Samsung/One UI behavior still needs a real-device gate for changes that depend on:

- Shizuku availability/reconnect;
- thermal callbacks and pause/resume timing;
- large real photo libraries;
- Samsung storage/media behavior;
- performance-profile effects;
- installation/update compatibility on the actual phone.

Do not claim those physical behaviors passed just because the APK builds or static checks pass.

## Exact next step

Before adding another aggressive optimization feature, **physically validate the v1.15.7 THERMAL / ANALYSIS behavior on the Galaxy A53**. Use that run to collect any real errors/latency/thermal edge cases, then patch from v1.15.7 rather than falling back to v1.15.6 or an older APK.

## Before delivering a future A53 Performance APK

- verify the input source really is v1.15.7 or a newer documented baseline;
- keep deletion/free-space UI state live and consistent;
- preserve restricted Shizuku commands and verify privileged outcomes;
- keep expensive scanning thermal-aware and cancellable/recoverable;
- test upgrade/install compatibility with the previous signed version when possible;
- distinguish emulator/build validation from physical Galaxy A53 validation;
- update this file with APK name, version, signing/update notes, device tests and exact next action.
