# BandLab

> Local-first Android training companion that combines phone sensors, GPS/GNSS and direct Xiaomi Smart Band integration.

| | |
|---|---|
| **Status** | Verified release · **BandLab9Active 2.6.0** |
| **Platform** | Android · minSdk 28 · targetSdk 36 |
| **Package** | `com.fer.bandlab` · versionCode 260 |
| **Focus** | Bluetooth LE · live heart rate · GPS/GNSS · workout analytics · local data |

## Overview

BandLab is a training and health companion built around real sensor data from the phone and a compatible Xiaomi Smart Band. It supports workout tracking, pacing, routes, history and exports while keeping the core experience local-first.

## Product capabilities

- direct Bluetooth LE connection to a compatible Xiaomi Smart Band;
- secure Xiaomi session/authentication flows without exposing raw secrets in the UI;
- available Band activity, sleep and battery synchronization;
- live heart-rate samples during workouts when the Band actually provides them;
- walking, running, cycling and jump-rope workout flows;
- phone GPS/GNSS route tracking with rejection of obviously bad location points;
- auto-pause, intervals, splits, manual laps and pacing comparisons;
- configurable intervals, ghost pacing and return-to-start guidance;
- local/offline route views;
- SQLite-backed workout history and personal-record tracking;
- TCX/CSV workout export;
- optional Health Connect integration where appropriate.

## Engineering focus

The project treats sensor uncertainty explicitly. Missing heart-rate, GPS or Band data remains missing rather than being replaced with plausible-looking values.

Release 2.6 also focused on lifecycle and data reliability, including workout-finalization fixes, heart-rate accumulation stability, persistent workout-history migration and GPS battery protection.

## Validation status

The recorded 2.6 release passed:

- unit tests;
- debug and release builds;
- package/manifest checks;
- emulator upgrade and startup/crash gates;
- APK signature-continuity checks against v2.5;
- ZIP integrity and zipalign verification.

Bluetooth authentication, reconnection and live sensor behavior remain hardware-dependent and therefore still require real-device testing when those paths change.

## Privacy and security

Authentication material, signing keys, Android Keystore data and other secrets are intentionally excluded from public source, logs and documentation.

## Why this project matters

BandLab combines mobile UI, BLE protocol work, sensor uncertainty, location processing, local persistence and release engineering in one product-oriented Android project.
