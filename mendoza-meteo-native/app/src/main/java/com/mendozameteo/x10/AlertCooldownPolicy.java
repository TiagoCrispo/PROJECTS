package com.mendozameteo.x10;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AlertCooldownPolicy {
    private static final long DISTINCT_WINDOW_MILLIS = 3L * 60L * 60L * 1000L;

    static final class Previous {
        final AlertEngine.Kind kind;
        final AlertEngine.Severity severity;
        final String startIso;
        final long notifiedAtMillis;

        Previous(AlertEngine.Kind kind, AlertEngine.Severity severity, String startIso, long notifiedAtMillis) {
            this.kind = kind;
            this.severity = severity;
            this.startIso = startIso;
            this.notifiedAtMillis = notifiedAtMillis;
        }

        static Previous from(AlertEngine.Event event, long notifiedAtMillis) {
            return new Previous(event.kind, event.severity, event.startIso, notifiedAtMillis);
        }
    }

    private AlertCooldownPolicy() { }

    static boolean shouldNotify(Previous previous, AlertEngine.Event current, long nowMillis) {
        if (current == null) return false;
        if (previous == null) return true;
        if (current.kind != previous.kind) return true;
        if (current.severity.rank > previous.severity.rank) return true;

        // Do not turn a persistent forecast event into a three-hour reminder loop. The same
        // event remains silent until it escalates or its forecast start moves far enough to be
        // treated as a genuinely different episode.
        long startShift = absoluteStartShiftMillis(previous.startIso, current.startIso);
        return startShift >= DISTINCT_WINDOW_MILLIS;
    }

    private static long absoluteStartShiftMillis(String firstIso, String secondIso) {
        Long first = parseMillis(firstIso), second = parseMillis(secondIso);
        if (first == null || second == null) {
            return firstIso != null && firstIso.equals(secondIso) ? 0L : Long.MAX_VALUE;
        }
        long delta = second - first;
        if (delta == Long.MIN_VALUE) return Long.MAX_VALUE;
        return Math.abs(delta);
    }

    private static Long parseMillis(String iso) {
        if (iso == null || iso.length() < 16) return null;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        format.setLenient(false);
        format.setTimeZone(WeatherClient.MENDOZA_TZ);
        try {
            Date date = format.parse(iso.substring(0, 16));
            return date == null ? null : date.getTime();
        } catch (ParseException ignored) {
            return null;
        }
    }
}
