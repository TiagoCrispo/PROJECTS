package com.mendozameteo.x10;

final class WidgetContextPolicy {
    static final double MAX_CONTEXT_DISTANCE_METERS = 10_000d;

    private WidgetContextPolicy() { }

    static String forecastCacheKey(NotificationLocation.Point point) {
        return point != null && point.personalized ? "local" : "utn";
    }

    static boolean sameContext(boolean cachedPersonalized, double cachedLat, double cachedLon,
                               NotificationLocation.Point current) {
        if (current == null) return false;
        if (cachedPersonalized != current.personalized) return false;
        if (!cachedPersonalized) return true;
        if (!LocationPolicy.validCoordinate(cachedLat, cachedLon)
                || !LocationPolicy.validCoordinate(current.lat, current.lon)) return false;
        return LocationPolicy.distanceMeters(cachedLat, cachedLon, current.lat, current.lon)
                <= MAX_CONTEXT_DISTANCE_METERS;
    }
}
