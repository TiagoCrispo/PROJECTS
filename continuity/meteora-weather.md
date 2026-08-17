# Meteora Weather — continuation state

**Canonical project:** Meteora Weather  
**Old names:** servicio meteorológico, app meteorológica, Mendoza Meteo  
**Latest recovered implementation baseline:** `Mendoza_Meteo_Pro_GPS_v1.2.html`  
**Related mobile baseline:** `Mendoza_Meteo_Pro_Movil.html`

The old filenames are source anchors, not the public product name. Future work should use **Meteora Weather** publicly while preserving the behavior already present in the v1.2 implementation.

## What the recovered v1.2 baseline already does

- current weather conditions;
- temperature and feels-like temperature;
- humidity;
- wind speed, gust and direction;
- pressure;
- cloudiness;
- next-24-hours rain decision/summary;
- maximum rain probability;
- accumulated rain;
- first expected rain event;
- hourly forecast;
- daily forecast;
- refresh state/countdown;
- Spanish UI focused on readable useful information rather than visual overload.

## Existing client behavior to preserve

The current recovered implementation is a standalone HTML/CSS/JavaScript client that consumes meteorological JSON data.

Important behavior:

- requests use an `AbortController` timeout around 12 seconds;
- expected `current`, `hourly` and `daily` structures are validated before treating a response as valid;
- a localStorage weather cache is used as fallback when the network/API fails;
- cached data older than 24 hours should not be presented as current data;
- hourly data should have enough entries for the intended 24-hour view (the baseline expects at least 24);
- refresh controls are disabled while a load is already in progress to avoid overlapping requests/state confusion;
- when live loading fails but valid cache exists, the UI should clearly fall back to cached data instead of going blank.

## Naming/migration rule

Do not “clean up” the project by throwing away the Mendoza Meteo v1.2 behavior and starting a new weather app from scratch. The correct migration path is:

1. take the v1.2 source as the behavior reference;
2. move naming/branding toward Meteora Weather;
3. preserve forecast/cache/error semantics;
4. modernize architecture/UI incrementally;
5. add tests/diagnostics before replacing a working data path.

## Data-quality rule

Weather UI must distinguish missing/stale/failed data from real zero values. Do not turn API/network errors into believable-looking weather values.

If an API/provider is changed, map and validate the new provider fields explicitly before declaring feature parity.

## Exact next step

Use `Mendoza_Meteo_Pro_GPS_v1.2.html` plus the mobile variant as the **behavioral source baseline** for the next Meteora Weather iteration. First consolidate the code under the Meteora name without regressing cache timeout, refresh locking, current/hourly/daily validation or the 24-hour rain summary.

## Before delivering a future Meteora Weather version

- confirm the old v1.2 behavior is covered or document deliberate replacements;
- test live API success, timeout, malformed data and valid-cache fallback;
- test stale cache rejection after 24 hours;
- test short/incomplete hourly data without crashing or inventing values;
- keep loading/refresh state coherent;
- record provider/API changes and validation assumptions;
- update this file with the new package/file/app version and exact next continuation point.
