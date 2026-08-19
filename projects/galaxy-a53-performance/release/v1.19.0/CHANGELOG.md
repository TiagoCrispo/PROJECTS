# Changelog — v1.19.0 Gaming 2.0 BETA

- Adds a new source-compiled Gaming 2.0 overlay (`classes7.dex`).
- Keeps the accepted v1.18 lower layers intact: Gallery 2.0 and Background Center remain unchanged.
- Adds per-game local profiles for display policy, temporary power-saver state, Data Saver state and selected background apps.
- Adds preflight diagnostics for Shizuku, thermal status, free RAM, current display refresh, min/peak system refresh, Power Saver and Data Saver.
- Adds manual session activation plus optional game launch.
- Adds persistent session snapshot and explicit restore after app/process restart.
- Activation aborts before making changes if required prior state cannot be read or the snapshot cannot be synchronously persisted.
- Partial activation triggers immediate rollback; failed rollback leaves the snapshot pending for manual retry.
- Background restrictions use `RUN_ANY_IN_BACKGROUND` only and only for user-selected apps.
- No `RUN_IN_BACKGROUND`, `force-stop`, `am kill`, standby-bucket writes, `am set-inactive`, device-idle exemptions or continuous task killing.
- `versionName 1.19.0`, `versionCode 42`, `debuggable=false`, `allowBackup=false`.
