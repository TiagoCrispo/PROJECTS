# v1.21.0 — Ready Mode BETA

`versionCode 44`

Ready Mode is the fifth feature layer added after recovery of the known-working Galaxy A53 baseline. The accepted base is v1.20.0 Doctor 2.0.

## What changed

- Added a read-only preflight screen with a game selector.
- Reports Android thermal status and 10-second thermal headroom when supported.
- Reports battery level/charging/battery temperature, available/total RAM plus `lowMemory`, and internal storage availability.
- Reports current display refresh rate and Shizuku read-back of min/peak refresh settings and Data Saver.
- Shows the saved Gaming 2.0 profile for the selected game.
- Shows active Background Center rule count and pending/active Gaming session state.
- Adds quick navigation to Gaming 2.0, Doctor 2.0, Background Center and Gallery 2.0.
- Adds `Open game without changes`, which only launches the selected package.

## Safety

Ready Mode introduces no new performance writes. It contains no `settings put`, AppOps set, netpolicy set, force-stop, `am kill`, standby-bucket write, deviceidle exemption or power-mode write.

State-changing behavior remains inside the already accepted Gaming 2.0 / Background Center layers.

## Status

Source compilation: PASS.
Static APK integration/signature validation: PASS.
Physical Galaxy A53 acceptance: PENDING.
