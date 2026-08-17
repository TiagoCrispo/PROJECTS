# Meteora Weather

Meteora Weather is a weather application focused on presenting useful forecast information clearly without hiding data-quality problems behind a polished interface.

## What it does

- shows current conditions and feels-like temperature;
- displays humidity, wind, gusts, direction, pressure and cloudiness;
- summarizes expected rain over the next 24 hours;
- shows rain probability, accumulation and the first expected rain event;
- provides hourly and daily forecasts;
- includes refresh/loading state so requests do not overlap silently;
- uses local caching as a fallback when live data is unavailable;
- rejects stale cache instead of presenting old information as current weather;
- validates expected weather-data structures before rendering them.

## Engineering focus

The project treats missing, failed and stale data differently from real zero values. A network error should never become a believable-looking “0% rain” or another fabricated condition.

Requests use bounded timeouts and the interface can fall back to valid cached data when the provider is temporarily unavailable. If the API/provider changes, the new response fields need to be mapped and validated explicitly.

## Direction

The goal is a compact, readable weather experience that stays useful during normal API failures and makes it clear when information is cached, incomplete or unavailable.
