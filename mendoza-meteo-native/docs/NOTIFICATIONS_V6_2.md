# Mendoza Meteo X10 — Notification subsystem (v6.2)

## Goal

Deliver useful weather notifications while keeping official alerts and X10 heuristics strictly separated. Official information always wins; X10 is only a forecast-derived complement.

## Android contract

- `targetSdk 36`, `minSdk 24`.
- `POST_NOTIFICATIONS` requested on Android 13+ through `LauncherActivity`.
- Three user-configurable channels: `official_urgent_v1` (orange/red, high), `official_alerts_v1` (other official alerts, default), and `x10_signals_v1` (important/danger heuristics, default).
- Per-channel opt-outs are respected; a blocked channel is never recorded as delivered.
- No full-screen intents, exact alarms or foreground service.
- WorkManager 2.11.2 runs unique periodic work every 30 minutes with a 10-minute flex window, connectivity constraint and `ExistingPeriodicWorkPolicy.UPDATE`.

## Privacy and location

The worker does not request `ACCESS_BACKGROUND_LOCATION` or obtain a new GPS fix in the background. It uses the last foreground location persisted by `LocationResolver` for up to 48 hours, then explicitly falls back to UTN Mendoza. Official-alert cache reuse is location-bound so a cached alert from a distant Mendoza zone cannot follow the user to another region.

## Official-alert policy

- New official alert: notify.
- Escalation and de-escalation: update immediately.
- Start/end window change: update immediately so the visible timing and Android timeout remain correct.
- Same-level textual/material change: 30-minute cooldown.
- Identical payload: suppress.
- CAP `references` and SMN CAP/API family matching reuse the existing Android notification when possible.
- Cleared/expired official alerts cancel their prior Android card.
- Source-provided expiry is stored separately from the internal stale guard.
- Untimed official messages get a 36-hour internal stale guard anchored to issue time; that guard is never presented as an official expiry.

## X10 anti-spam policy

- `PRECAUTION`: app-only, no notification.
- `IMPORTANT` / `DANGER`: notification eligible.
- An unchanged event is notified once; it does not become a recurring three-hour reminder.
- Severity escalation notifies immediately.
- A start-window shift of at least three hours is treated as a distinct episode.
- A fresh evaluation clears both Android card and persisted X10 state when an event resolves, falls below notification severity, is superseded by a thunderstorm, or is covered by an official alert.
- Failed/stale weather never clears X10 state because the current condition is unknown.
- Official/X10 suppression requires the same hazard family and overlapping windows when timing exists. A future official alert therefore cannot hide an earlier distinct X10 episode.
- At most two distinct X10 families are retained per fresh evaluation.

## Execution order and retry

Official alerts are loaded, pruned and posted before the general forecast is fetched, so a slow model request cannot delay an already retrieved official warning. Retry is source-aware; transient official-source failures can request one WorkManager retry, while the normal periodic schedule remains the long-term fallback.

## Platform limits

WorkManager is deferred background work, not a real-time push transport. Doze/OEM battery policy can delay a run. Swiping the app away normally does not remove persisted WorkManager scheduling, while Android **Force stop** intentionally blocks scheduled/background execution until the app is launched again.

The SMN origin currently returns HTTP 403 to GitHub-hosted runners, so CI records that restriction rather than pretending a live SMN payload passed. The registered official contract and live Mendoza provincial endpoint remain in the automated gate; physical-device/network validation remains a final release gate.
