package com.mendozameteo.x10;

import android.content.Context;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class WeatherRepository {
    enum Origin { LIVE, CACHE, NONE }

    static final class Result {
        final WeatherClient.Forecast forecast;
        final Origin origin;
        final ForecastFreshness.State freshness;
        final long ageMillis;
        final boolean degradedProvider;
        final boolean cacheWriteFailed;
        final WeatherException failure;
        final int attemptedProviders;

        Result(WeatherClient.Forecast forecast, Origin origin, ForecastFreshness.State freshness, long ageMillis,
               boolean degradedProvider, boolean cacheWriteFailed, WeatherException failure, int attemptedProviders) {
            this.forecast=forecast; this.origin=origin; this.freshness=freshness; this.ageMillis=ageMillis;
            this.degradedProvider=degradedProvider; this.cacheWriteFailed=cacheWriteFailed; this.failure=failure; this.attemptedProviders=attemptedProviders;
        }
        boolean isSuccess() { return forecast != null && origin != Origin.NONE && freshness != ForecastFreshness.State.EXPIRED; }
        boolean safeForAlerts() { return isSuccess() && ForecastFreshness.safeForAlerts(freshness); }
        boolean shouldRetryBackground() { return !isSuccess() && failure != null && failure.retryable(); }
        String statusText() {
            if (!isSuccess()) {
                if (failure != null && failure.kind == WeatherException.Kind.OFFLINE) return "Sin conexión · sin datos guardados";
                return "Fuentes meteorológicas no disponibles";
            }
            String provider = forecast.providerLabel == null || forecast.providerLabel.isEmpty() ? forecast.providerId : forecast.providerLabel;
            if (origin == Origin.LIVE) return degradedProvider ? "Actualizado · " + provider + " (respaldo)" : "Actualizado · " + provider;
            String prefix = failure != null && failure.kind == WeatherException.Kind.OFFLINE ? "Sin conexión" : "Usando datos guardados";
            return prefix + " · hace " + ForecastFreshness.ageLabel(ageMillis) + " · " + provider;
        }
    }

    private final ForecastStore store;
    private final ConnectivityProbe connectivity;
    private final List<WeatherProvider> providers;

    WeatherRepository(Context context) {
        HttpJsonTransport transport = new HttpJsonTransport();
        store = new DiskForecastStore(context);
        connectivity = new AndroidConnectivityProbe(context);
        providers = Collections.unmodifiableList(Arrays.asList(
                new OpenMeteoProvider(OpenMeteoProvider.Model.BEST_MATCH, transport),
                new OpenMeteoProvider(OpenMeteoProvider.Model.GFS, transport),
                new OpenMeteoProvider(OpenMeteoProvider.Model.ECMWF, transport)));
    }

    WeatherRepository(ForecastStore store, ConnectivityProbe connectivity, List<WeatherProvider> providers) {
        this.store=store; this.connectivity=connectivity; this.providers=providers;
    }

    Result load(String slot, double latitude, double longitude) {
        long now = System.currentTimeMillis();
        if (!connectivity.isOnline()) {
            return cachedOrFailure(slot, latitude, longitude, now,
                    new WeatherException(WeatherException.Kind.OFFLINE, "No active internet network"), 0);
        }
        WeatherException lastFailure = null;
        int attempted = 0;
        for (int index=0; index<providers.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                return cachedOrFailure(slot, latitude, longitude, now,
                        new WeatherException(WeatherException.Kind.INTERRUPTED, "Repository load interrupted"), attempted);
            }
            WeatherProvider provider = providers.get(index);
            attempted++;
            try {
                WeatherClient.Forecast forecast = provider.fetch(latitude, longitude);
                boolean cacheWriteFailed = false;
                try { store.save(slot, forecast); } catch (Exception ignored) { cacheWriteFailed = true; }
                return new Result(forecast, Origin.LIVE, ForecastFreshness.State.FRESH, 0L, index>0, cacheWriteFailed, null, attempted);
            } catch (WeatherException error) {
                lastFailure = error;
                if (error.kind == WeatherException.Kind.INTERRUPTED) { Thread.currentThread().interrupt(); break; }
            } catch (RuntimeException unexpected) {
                lastFailure = new WeatherException(WeatherException.Kind.INVALID_DATA, "Unexpected provider failure: " + provider.id(), unexpected);
            }
        }
        if (lastFailure == null) lastFailure = new WeatherException(WeatherException.Kind.NETWORK, "No weather providers configured");
        return cachedOrFailure(slot, latitude, longitude, now, lastFailure, attempted);
    }

    Result peekCache(String slot, double latitude, double longitude) {
        return cachedOrFailure(slot, latitude, longitude, System.currentTimeMillis(), null, 0);
    }

    private Result cachedOrFailure(String slot, double latitude, double longitude, long now, WeatherException networkFailure, int attemptedProviders) {
        try {
            ForecastStore.Entry cached = store.load(slot, latitude, longitude, now);
            if (cached != null) return new Result(cached.forecast, Origin.CACHE, cached.freshness, cached.ageMillis, false, false, networkFailure, attemptedProviders);
        } catch (Exception cacheError) {
            if (networkFailure == null) networkFailure = new WeatherException(WeatherException.Kind.CACHE, "Weather cache unreadable", cacheError);
        }
        return new Result(null, Origin.NONE, ForecastFreshness.State.EXPIRED, Long.MAX_VALUE, false, false, networkFailure, attemptedProviders);
    }
}
