# Galaxy A53 Performance v1.19.0 — Gaming 2.0 BETA

`versionCode 42`

This beta is built on the physically accepted v1.18.0 Background Center release.

## Gaming 2.0

- Per-game local profiles.
- Display policy: Keep / 60 Hz / 60–120 Hz dynamic / 120 Hz fixed.
- Power saver: Keep / OFF during session.
- Data Saver: Keep / ON / OFF during session.
- Optional temporary background restriction for explicitly selected non-sensitive apps only.
- Preflight shows Shizuku, Android Thermal Status, Power Saver, current display refresh, system min/peak refresh, Data Saver and free RAM.
- Activate-only or Activate-and-launch-game flows.
- Persistent RESTORE action after process/app restart when a session snapshot is pending.

## Safety model

Before the first shell modification Galaxy reads the required previous state and persists a synchronous snapshot with `SharedPreferences.commit()`. If any required read fails, nothing is changed. If activation fails halfway, Galaxy attempts immediate rollback. If rollback cannot be confirmed, the snapshot remains available for retry.

The temporary background policy uses only `RUN_ANY_IN_BACKGROUND` for apps the user explicitly selects. It does not use `RUN_IN_BACKGROUND`, `force-stop`, `am kill`, standby-bucket writes, or a continuous task killer. Sensitive communication/clock/music/Shizuku apps and the selected game are excluded from that selector.

The app only confirms the min/peak display policy read back from Android. It does not claim that a game is actually rendering 120 FPS; Android, the game itself, battery and thermal policy may override the effective refresh/frame rate.

Physical Galaxy A53 acceptance is required before this PR is merged to `main`.
