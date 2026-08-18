package com.mendozameteo.x10;

final class LocationPolicy {
    static final long QUICK_REUSE_MILLIS = 5L * 60L * 1000L;
    static final long DEVICE_MAX_AGE_MILLIS = 2L * 60L * 60L * 1000L;
    static final long SAVED_MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;
    static final long STABILIZE_MAX_AGE_MILLIS = 30L * 60L * 1000L;
    static final float MAX_ACCEPTABLE_ACCURACY_METERS = 10_000f;
    static final float QUICK_REUSE_ACCURACY_METERS = 2_000f;
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private LocationPolicy() { }

    static boolean validCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90d && latitude <= 90d
                && longitude >= -180d && longitude <= 180d;
    }

    static boolean usableDeviceFix(long ageMillis, float accuracyMeters) {
        return ageMillis >= 0L && ageMillis <= DEVICE_MAX_AGE_MILLIS
                && Float.isFinite(accuracyMeters) && accuracyMeters > 0f
                && accuracyMeters <= MAX_ACCEPTABLE_ACCURACY_METERS;
    }

    static boolean reusableWithoutSensor(long ageMillis, float accuracyMeters) {
        return usableDeviceFix(ageMillis, accuracyMeters)
                && ageMillis <= QUICK_REUSE_MILLIS
                && accuracyMeters <= QUICK_REUSE_ACCURACY_METERS;
    }

    static boolean usableSaved(long savedAgeMillis, float accuracyMeters, boolean savedWasPrecise, boolean finePermissionNow) {
        if (savedWasPrecise && !finePermissionNow) return false;
        return savedAgeMillis >= 0L && savedAgeMillis <= SAVED_MAX_AGE_MILLIS
                && Float.isFinite(accuracyMeters) && accuracyMeters > 0f
                && accuracyMeters <= MAX_ACCEPTABLE_ACCURACY_METERS;
    }

    static boolean candidateIsBetter(long candidateAgeMillis, float candidateAccuracy,
                                     long currentAgeMillis, float currentAccuracy) {
        if (!usableDeviceFix(candidateAgeMillis, candidateAccuracy)) return false;
        if (!usableDeviceFix(currentAgeMillis, currentAccuracy)) return true;
        long meaningfulAgeGap = 5L * 60L * 1000L;
        if (candidateAgeMillis + meaningfulAgeGap < currentAgeMillis) return true;
        if (currentAgeMillis + meaningfulAgeGap < candidateAgeMillis) return false;
        return candidateAccuracy < currentAccuracy;
    }

    static boolean shouldStabilize(double savedLat, double savedLon, float savedAccuracy, long savedAgeMillis,
                                   double freshLat, double freshLon, float freshAccuracy) {
        if (!validCoordinate(savedLat, savedLon) || !validCoordinate(freshLat, freshLon)) return false;
        if (savedAgeMillis < 0L || savedAgeMillis > STABILIZE_MAX_AGE_MILLIS) return false;
        if (!Float.isFinite(savedAccuracy) || savedAccuracy <= 0f || !Float.isFinite(freshAccuracy) || freshAccuracy <= 0f) return false;
        double threshold = Math.max(250d, Math.min(1_500d, Math.max(savedAccuracy, freshAccuracy) * 0.75d));
        return distanceMeters(savedLat, savedLon, freshLat, freshLon) <= threshold;
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2d) * Math.sin(dp / 2d)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2d) * Math.sin(dl / 2d);
        return EARTH_RADIUS_METERS * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }
}
