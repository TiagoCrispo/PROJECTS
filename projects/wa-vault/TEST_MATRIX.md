# WA Vault v0.5.31 — Acceptance Matrix

| Area | Required result |
|---|---|
| Clean install / launch | No FATAL / ANR |
| Startup screen | Mensajes borrados |
| 20 messages + reopen | 0 false deletes / 0 redownloads |
| Force-stop / process death | 0 false deletes |
| Listener reconnect | 0 false deletes |
| Reboot | 0 false deletes |
| Empty/incomplete snapshot | 0 confirmed deletes |
| Notification removal / APP_CANCEL | 0 confirmed deletes |
| One ambiguous marker | 0 historical rows altered |
| Exact real deletion | exactly one confirmed row or UNKNOWN |
| 1000 messages × 20 restarts | stable unique rows; 0 redownloads |
| Duplicate content | one physical blob, all logical message links retained |
| Crash during media commit | no permanent plaintext; recoverable durable state |
| DB migration 13→14→15 | integrity_check=ok; rows preserved |
| Queue saturation | bounded; no CallerRuns fallback / OOM fan-out |
| targetSdk | 36 |
| Release R8/shrink | enabled; mapping retained |
| INTERNET / POST_NOTIFICATIONS | absent |
| Biometric permission | present |
| Runtime Android | emulator/device pending until executed |
