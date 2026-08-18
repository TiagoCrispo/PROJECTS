# BandLab

BandLab is a local-first Android training and health companion built around phone sensors and a Xiaomi Smart Band.

## Current verified release

**BandLab9Active 2.6.0** (`com.fer.bandlab`, versionCode 260, minSdk 28, targetSdk 36).

The final 2.6 release passed the recorded unit, debug/release build, package/manifest, emulator upgrade and startup/crash gates. Its final APK was also checked for signature continuity with v2.5, ZIP integrity and zipalign.

### Key 2.6 changes

- live Xiaomi heart-rate integration during workouts, without fabricating HR when the Band does not provide it;
- SQLite-backed workout-history migration;
- configurable intervals and ghost pacing;
- return-to-start guidance and local/offline route view;
- GPS battery protection and personal-record tracking;
- TCX/CSV workout export;
- workout-finalization and heart-rate accumulation stability fixes;
- updated local-first privacy wording.

## What it does

- connects directly to a compatible Xiaomi Smart Band over Bluetooth LE;
- handles secure Xiaomi session/authentication flows without exposing raw secrets to the user interface;
- synchronizes available Band information such as activity, sleep and battery data;
- uses live heart-rate samples during workouts when the Band provides them;
- supports walking, running, cycling and jump-rope workout flows;
- tracks routes with phone GPS/GNSS and rejects obviously bad location points;
- supports auto-pause, intervals, splits, manual laps and pacing comparisons;
- provides route guidance and local/offline route views;
- stores workout history locally;
- supports personal-record tracking and workout export formats;
- can combine direct Band data with Health Connect where appropriate.

## Engineering focus

Missing heart-rate, GPS or Band data stays missing rather than being replaced with believable-looking values. Emulator/build validation is useful, but Bluetooth authentication, reconnection and live sensor behavior still require real-device testing.

## Privacy

Authentication material, signing keys, Android Keystore data and other secrets are not intended for public source, logs or documentation. Private signing material is excluded from the verified source/release bundles.
