# Galaxy A53 Performance v1.18.0 — Background Center BETA

Status: **BETA / awaiting physical Galaxy A53 acceptance**.

v1.18 builds on the physically accepted v1.17 Gallery 2.0 release and adds a source-compiled per-app Background Center as a new overlay DEX.

## User-facing behavior

- Lists non-system user apps with label, icon and last-use information when Usage Access is granted.
- Shows whether an app is exempt from Android battery optimization / Doze.
- Filters apps by recent use, inactivity, sensitive-app classification and Galaxy-managed policy.
- Reads back `RUN_ANY_IN_BACKGROUND`, Data Saver UID allowlist/blacklist and the current standby bucket through the existing Shizuku shell layer.
- Offers only three policies: **Sin tocar / Optimizar fondo / No restringir**.
- Warns before restricting sensitive messaging, dialer, clock, mail, media and document apps.

## Safety model

Before the first policy change on an app, Galaxy reads its real AppOps + netpolicy state and synchronously persists a rollback snapshot with `SharedPreferences.commit()`. If state cannot be read or the snapshot cannot be persisted, Galaxy does not execute any policy command.

`Optimizar fondo` only applies `RUN_ANY_IN_BACKGROUND=ignore` and removes the UID from the Data Saver allowlist. `No restringir` applies `RUN_ANY_IN_BACKGROUND=allow`, removes the UID from the blacklist and adds it to the allowlist. `Sin tocar` restores the previously captured exact state.

The module deliberately does **not** modify `RUN_IN_BACKGROUND`, does not set standby buckets, and does not add `am kill`, `force-stop`, or a continuous task killer.

## APK

- package: `com.fer.a53performance`
- versionName: `1.18.0`
- versionCode: `41`
- APK SHA-256: `f9fb576159ac423f8923feb4715b6bd1cdb30b37154ba53d0f3767096b44abd5`
- X10 certificate SHA-256: `ce1890deaadeffc2ad03dc7763bd91cd016e4cc9b0ceb5ac42ce4aa99d170910`

Physical-device acceptance is required before this branch is merged into `main`.
