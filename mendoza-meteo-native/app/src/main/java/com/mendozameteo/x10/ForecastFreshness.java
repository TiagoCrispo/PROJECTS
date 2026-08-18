package com.mendozameteo.x10;

final class ForecastFreshness {
    static final long FRESH_MILLIS = 90L * 60L * 1000L;
    static final long STALE_MILLIS = 6L * 60L * 60L * 1000L;
    static final long VERY_STALE_MILLIS = 24L * 60L * 60L * 1000L;
    static final long MAX_FUTURE_SKEW_MILLIS = 10L * 60L * 1000L;

    enum State { FRESH, STALE, VERY_STALE, EXPIRED }

    private ForecastFreshness() { }

    static long ageMillis(long fetchedAtMillis, long nowMillis) {
        if (fetchedAtMillis <= 0L || nowMillis <= 0L) return Long.MAX_VALUE;
        if (fetchedAtMillis > nowMillis + MAX_FUTURE_SKEW_MILLIS) return Long.MAX_VALUE;
        return Math.max(0L, nowMillis - fetchedAtMillis);
    }

    static State classify(long fetchedAtMillis, long nowMillis) {
        long age = ageMillis(fetchedAtMillis, nowMillis);
        if (age <= FRESH_MILLIS) return State.FRESH;
        if (age <= STALE_MILLIS) return State.STALE;
        if (age <= VERY_STALE_MILLIS) return State.VERY_STALE;
        return State.EXPIRED;
    }

    static boolean safeForAlerts(State state) { return state == State.FRESH; }

    static String ageLabel(long ageMillis) {
        if (ageMillis == Long.MAX_VALUE) return "antiguos";
        long minutes = Math.max(0L, ageMillis / 60000L);
        if (minutes < 2) return "menos de 2 min";
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60L;
        if (hours < 24) return hours + " h";
        long days = hours / 24L;
        return days + (days == 1 ? " día" : " días");
    }
}
