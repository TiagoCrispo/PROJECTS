# Meteora Weather

> Weather application focused on readable forecasts, trustworthy degraded states and clear handling of stale or missing data.

| | |
|---|---|
| **Status** | Active development |
| **Area** | Weather · data presentation · caching |
| **Focus** | Forecast clarity · API validation · offline/degraded behavior |

## Overview

Meteora Weather is built around a simple product requirement: weather data should stay understandable even when the provider, network or cached data is imperfect. The interface distinguishes real zero values from missing information and avoids turning API failures into believable-looking weather conditions.

## Product capabilities

- current conditions and feels-like temperature;
- humidity, wind, gusts, direction, pressure and cloudiness;
- expected rain summary for the next 24 hours;
- rain probability, accumulation and first expected rain event;
- hourly and daily forecasts;
- explicit refresh/loading state so requests do not overlap silently;
- local cache fallback when live data is temporarily unavailable;
- stale-cache rejection rather than presenting old information as current;
- validation of expected weather-data structures before rendering.

## Engineering focus

The project treats **missing**, **failed**, **stale** and **real zero** as different states.

For example, a failed request should not silently become “0% rain”. Requests use bounded timeouts, valid cached data can be used as a fallback, and provider/API schema changes require explicit mapping and validation rather than optimistic rendering.

## Why this project matters

Meteora Weather is a compact example of reliability at the UI/data boundary: a polished interface is useful only if it communicates uncertainty and degraded state honestly.

## Direction

The goal is a fast, readable weather experience that remains useful during normal provider/network failures while making cached, incomplete or unavailable information obvious to the user.
