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
- Channel opt-outs are respected individually; a blocked channel is not recorded as successfully delivered.
- No full-screen intents.
- No exact alarms.
- No foreground service.
- WorkManager 2.11.2: periodic work every 30 minutes with a 10-minute flex window and a 30-minute initial delay. There is no duplicate immediate worker while the foreground UI is already fetching the same data.

## Privacy and location

The notification worker does **not** request `ACCESS_BACKGROUND_LOCATION` and does not obtain a new GPS fix in the background.

It uses the last location that `LocationResolver` persisted while the app was active. That personalized point is accepted for up to 48 hours. After that, the worker uses UTN Mendoza as an explicit reference point.

## Priority and anti-spam rules

Official alerts always take precedence over heuristics.

- New official alert: notify.
- Official escalation: notify immediately even inside cooldown.
- Official de-escalation: update immediately so an old higher severity cannot remain visible; the replacement is configured not to re-alert when Android can update the same notification.
- Same-level material update: notify only after a 30-minute cooldown.
- Identical official payload: suppress.
- CAP/API SMN source switching can be matched by event + start-time family to reduce duplicate notifications and reuse the existing Android notification ID.
- CAP `references` are used to follow superseded messages.
- Expired or cleared official alerts cancel their prior Android notification.
- Official messages without an explicit expiry receive an internal 36-hour stale guard anchored to their issue time for cleanup only; no fabricated expiry is shown to the user. On Android 8+ the same internal guard is also used as a notification timeout so a stale card cannot remain indefinitely if later background work is delayed.
- X10 `PRECAUTION`: app-only, no push.
- X10 `IMPORTANT` / `DANGER`: eligible for push through the existing 3-hour cooldown policy.
- X10 rain is suppressed and any prior rain push is cleared when a thunderstorm event covers the same report.
- X10 event families matching a current official alert are suppressed from push and any prior same-family X10 card is cleared on a fresh evaluation.
- X10 cards are removed when the fresh forecast no longer contains that retained event; on Android 8+ they also time out at the event end time.
- At most two distinct X10 event families are retained for notification from one fresh worker evaluation.

## Retry and reliability

Retry is source-aware rather than blind. Timeouts, network failures and retryable HTTP responses can request one WorkManager retry. Permanent HTTP responses such as 403 and malformed/non-retryable payloads do not enter a repeated retry loop. A transient SMN failure gets one retry because SMN is the primary authority; a transient Mendoza-source failure is retried when SMN is also unavailable. The normal 30-minute periodic schedule remains the long-term fallback.

WorkManager is reliable deferred work, not a real-time push transport. Android/Doze/OEM battery policy can delay a periodic run. Swiping the app away or letting its process die normally does not remove the persisted WorkManager schedule. Android **Force stop** from system settings is different: the OS intentionally prevents scheduled/background execution until the user launches the app again. Mendoza Meteo does not attempt to bypass that platform rule.

The app therefore must not claim second-by-second official alert delivery. The SMN origin currently returns HTTP 403 to GitHub-hosted runners; CI validates the registered official contract plus the live Mendoza provincial source, while device-network validation remains part of the final physical-device release gate.
