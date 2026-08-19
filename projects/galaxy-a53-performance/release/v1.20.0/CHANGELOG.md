# Changelog — v1.20.0

## Added

- Galaxy Doctor 2.0 read-only dashboard.
- Android thermal-status reporting.
- Battery level, charging state and battery-temperature reporting.
- `ActivityManager.MemoryInfo` RAM diagnostics including `lowMemory` and threshold.
- `StatFs` internal-storage capacity diagnostics.
- Current display refresh plus Shizuku min/peak refresh read-back.
- Shizuku Data Saver read-back.
- Capability status for Usage Access, All Files Access, WRITE_SETTINGS and notifications.
- Count of active Background Center rules.
- Detection of pending/active Gaming 2.0 rollback state.
- `LISTO / ATENCIÓN / LIMITADO` summary derived from observed Android state.
- Copy-to-clipboard diagnostic report and direct Android-settings shortcuts.

## Safety

- Doctor 2.0 is read-only.
- No AppOps writes.
- No refresh-rate writes.
- No Data Saver writes.
- No power-mode writes.
- No task killer, `force-stop`, `am kill`, standby-bucket writes or continuous polling.
- No fake CPU temperature and no fabricated performance percentages.

## Release state

Static/CI validation passed. Physical Galaxy A53 acceptance remains pending.
