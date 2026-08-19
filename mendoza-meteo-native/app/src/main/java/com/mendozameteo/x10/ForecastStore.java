package com.mendozameteo.x10;

interface ForecastStore {
    final class Entry {
        final WeatherClient.Forecast forecast;
        final ForecastFreshness.State freshness;
        final long ageMillis;

        Entry(WeatherClient.Forecast forecast, ForecastFreshness.State freshness, long ageMillis) {
            this.forecast = forecast;
            this.freshness = freshness;
            this.ageMillis = ageMillis;
        }
    }

    void save(String slot, WeatherClient.Forecast forecast) throws Exception;
    Entry load(String slot, double requestedLat, double requestedLon, long nowMillis) throws Exception;
}
