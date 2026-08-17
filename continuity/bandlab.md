# BandLab — continuation state

**Canonical project:** BandLab  
**Old names:** APPS BANDLAB V2, BandLab V2, BandLab9Active  
**Latest canonical runtime baseline:** **v2.6.4**  
**APK anchor:** `BandLab9Active_v2.6.4.apk`  
**APK SHA-256:** `66a154376f5609c2820f95109197715492ae9b40eb62a9c55864ca91cd3667e4`

BandLab is an Android local-first training/health utility built around a Samsung Galaxy A53 5G and a Xiaomi Smart Band 9 Active (M2435B1). The Android package is `com.fer.bandlab` and the current line targets Android API 36.

## Critical source/runtime distinction

The newest APK that must be treated as the runtime baseline is **v2.6.4**.

The newest independently verified source archive currently preserved is:

- `BandLab9Active_v2.6_FINAL_SOURCE_VERIFIED.zip`
- source version: **2.6.0**
- SHA-256: `55bf38f8f627dc60f26b60d2d538b7b0590fc667f1f6557900cd207aa1f9ebe3`

The verified v2.6 source archive is therefore **not equivalent to the final v2.6.4 APK**. Do not silently rebuild from 2.6.0 and label the result 2.6.4. Before future source-level feature work, either recover/reconstruct the exact v2.6.4 source delta or explicitly document that a change is being ported forward from the v2.6.0 verified source base.

## v2.6 behavior to preserve

The v2.6 verified source/release line includes:

- direct Xiaomi Smart Band 9 Active BLE connection;
- Xiaomi V2 encrypted authentication/session transport;
- automatic reconnection behavior;
- direct battery, activity and sleep synchronization;
- live Band heart-rate samples during workouts without fabricating HR when unavailable;
- local workout storage backed by SQLite;
- walking, running, cycling and jump-rope workout flows;
- phone GPS/GNSS tracking with bad-point rejection;
- auto-pause, configurable intervals, 500 m / 1 km splits and manual laps;
- ghost pacing against comparable previous sessions;
- return-to-start guidance and local/offline route display;
- low-battery GPS protection;
- personal-record tracking only when real checkpoints support the calculation;
- GPX / TCX / CSV export;
- Health Connect fallback/merge behavior;
- local diagnostics and truthful capability gating rather than invented sensor results.

## v2.6.4 delta that must not regress

The final v2.6.4 APK is the continuation point because it added/fixed the following on top of the 2.6 line:

- Xiaomi authentication is provisioned automatically so the user does not need to manually enter the auth key;
- secure local provisioning/storage is handled automatically;
- diagnostic wording around Xiaomi authentication was corrected;
- the app has the custom BandLab icon requested for this line;
- release signing identity remains compatible with the immediately previous signed 2.6.x build so updates can install over it.

Treat those as required behavior even though the exact v2.6.4 source archive is not currently preserved alongside the verified v2.6.0 source ZIP.

## Secret-handling rule

Never publish or commit any of the following to GitHub, logs, documentation or public release bundles:

- the Xiaomi authentication key;
- Android Keystore secrets;
- the private APK signing key / keystore / P12;
- passwords or raw secure-storage blobs.

A future build may automate secure provisioning, but the secret itself must stay outside the public repository.

## Validation already established

For the final v2.6.4 artifact, package integrity/signing continuity and the presence of the automated Xiaomi-auth/custom-icon changes were checked in the build flow that produced the APK.

However, emulator/static/build checks are not a substitute for the physical Xiaomi Smart Band 9 Active.

## What is still not physically verified

The real-device gate still includes:

- BLE radio connection to the actual M2435B1;
- real Xiaomi V2 authentication/handshake using the user's Band;
- reconnect behavior after real Android/Bluetooth interruptions;
- real-time heart-rate streaming during workouts;
- direct activity/sleep sync against the physical Band;
- battery impact and GNSS/BLE behavior on the Galaxy A53 5G.

Do not claim these passed unless they were actually tested on the user's phone and Band.

## Exact next step

**Do not start the next BandLab feature from an older APK or from v2.6.0 source alone.**

The next safe continuation is:

1. treat `BandLab9Active_v2.6.4.apk` as the canonical runtime behavior;
2. use `BandLab9Active_v2.6_FINAL_SOURCE_VERIFIED.zip` only as the latest verified source base;
3. recover/reconstruct and preserve the missing v2.6.4 source delta so source and APK are aligned;
4. then physically validate BLE/auth/reconnect/live-HR on the Xiaomi Smart Band 9 Active;
5. only after those two gates continue into a new version.

## Before delivering a future BandLab APK

- confirm the work is based on v2.6.4 behavior or a newer documented baseline;
- preserve automatic Xiaomi auth provisioning and the custom icon;
- preserve signing/update compatibility unless a deliberate signing migration is documented;
- never expose Xiaomi/signing secrets;
- distinguish verified source state from verified APK/runtime state;
- distinguish emulator/build validation from physical Galaxy A53 + Band validation;
- update this file with the new APK name, source anchor, hashes, validation performed and exact next action.
