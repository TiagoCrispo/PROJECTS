# Mendoza Meteo X10 — Notification subsystem (v6.1)

## Goal

Deliver useful weather notifications while keeping official alerts and X10 heuristics strictly separated.

## Android contract

- `targetSdk 36`, `minSdk 24`.
- `POST_NOTIFICATIONS` requested once on Android 13+ through `LauncherActivity`.
- Three user-configurable channels:
  - `official_urgent_v1`: orange/red official alerts, high importance.
  - `official_alerts_v1`: official information/advisories/yellow alerts, default importance.
  - `x10_signals_v1`: important/danger X10 heuristic signals, default importance.
- No full-screen intents.
- No exact alarms.
- No foreground service.
- WorkManager 2.11.2: 30-minute periodic work with a 10-minute flex window plus an immediate one-time sync after scheduling.

## Privacy and location

The notification worker does **not** request `ACCESS_BACKGROUND_LOCATION` and does not obtain a new GPS fix in the background.

It uses the last location that `LocationResolver` persisted while the app was active. That personalized point is accepted for up to 48 hours. After that, the worker uses UTN Mendoza as an explicit reference point.

## Priority and anti-spam rules

Official alerts always take precedence over heuristics.

- New official alert: notify.
- Official escalation: notify immediately even inside cooldown.
- Same-level material update: notify only after a 30-minute cooldown.
- Identical official payload: suppress.
- CAP/API SMN source switching can be matched by event + start-time family to reduce duplicate notifications.
- CAP `references` are used to follow superseded messages.
- Expired or cleared official alerts cancel their prior Android notification.
- Official messages without an explicit expiry receive an internal 36-hour stale guard for cleanup only; no fabricated expiry is shown to the user.
- X10 `PRECAUTION`: app-only, no push.
- X10 `IMPORTANT` / `DANGER`: eligible for push through the existing 3-hour cooldown policy.
- X10 rain is suppressed when a thunderstorm event already covers the same report.
- X10 event families matching a current official alert are suppressed from push.
- Maximum two X10 pushes per worker run.

## Reliability limits

WorkManager is reliable deferred work, not a real-time push transport. Android/Doze/OEM battery policy can delay a periodic run. The app therefore must not claim second-by-second official alert delivery.

The SMN origin currently returns HTTP 403 to GitHub-hosted runners; CI validates the registered official contract plus the live Mendoza provincial source, while device-network validation remains part of the final physical-device release gate.
