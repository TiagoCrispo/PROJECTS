package com.mendozameteo.x10;

import android.content.Context;
import android.content.SharedPreferences;

final class NotificationLocation {
    private static final String PREFS = "last_location_v6";
    private static final long MAX_PERSONALIZED_AGE_MILLIS = 48L * 60L * 60L * 1000L;
    private static final double UTN_LAT = -32.896748;
    private static final double UTN_LON = -68.853418;

    static final class Point {
        final double lat;
        final double lon;
        final boolean personalized;
        final long ageMillis;

        Point(double lat, double lon, boolean personalized, long ageMillis) {
            this.lat = lat;
            this.lon = lon;
            this.personalized = personalized;
            this.ageMillis = ageMillis;
        }

        String label() { return personalized ? "última ubicación" : "referencia UTN Mendoza"; }
    }

    private NotificationLocation() { }

    static Point load(Context context, long nowMillis) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long savedAt = prefs.getLong("saved_at", 0L);
        long age = LocationPolicy.wallClockAgeMillis(savedAt, nowMillis);
        double lat = Double.longBitsToDouble(prefs.getLong("lat", Double.doubleToRawLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(prefs.getLong("lon", Double.doubleToRawLongBits(Double.NaN)));
        if (age <= MAX_PERSONALIZED_AGE_MILLIS && LocationPolicy.validCoordinate(lat, lon)) {
            return new Point(lat, lon, true, age);
        }
        return new Point(UTN_LAT, UTN_LON, false, Long.MAX_VALUE);
    }
}
