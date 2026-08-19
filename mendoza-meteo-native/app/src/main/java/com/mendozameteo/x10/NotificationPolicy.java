package com.mendozameteo.x10;

import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

final class NotificationPolicy {
    static final long OFFICIAL_UPDATE_COOLDOWN_MILLIS = 30L * 60L * 1000L;

    enum OfficialChange { NONE, NEW, ESCALATION, DEESCALATION, IMPORTANT_UPDATE }

    static final class PreviousOfficial {
        final String stateKey;
        final String id;
        final OfficialAlert.Source source;
        final int levelRank;
        final int contentHash;
        final long notifiedAtMillis;
        final long expiresMillis;
        final long sourceExpiresMillis;
        final int notificationId;
        final String event;
        final long startMillis;

        PreviousOfficial(String stateKey, String id, OfficialAlert.Source source, int levelRank,
                         int contentHash, long notifiedAtMillis, long expiresMillis,
                         long sourceExpiresMillis, int notificationId, String event, long startMillis) {
            this.stateKey = stateKey == null ? "" : stateKey;
            this.id = id == null ? "" : id;
            this.source = source;
            this.levelRank = levelRank;
            this.contentHash = contentHash;
            this.notifiedAtMillis = notifiedAtMillis;
            this.expiresMillis = expiresMillis;
            this.sourceExpiresMillis = sourceExpiresMillis;
            this.notificationId = notificationId;
            this.event = event == null ? "" : event;
            this.startMillis = startMillis;
        }
    }

    private NotificationPolicy() { }

    static OfficialChange officialChange(PreviousOfficial previous, OfficialAlert current, long nowMillis) {
        if (current == null || !current.activeAt(nowMillis)) return OfficialChange.NONE;
        if (previous == null) return OfficialChange.NEW;
        if (current.level.rank > previous.levelRank) return OfficialChange.ESCALATION;
        if (current.level.rank < previous.levelRank) return OfficialChange.DEESCALATION;

        // Official timing is operational state, not cosmetic content. A changed start/end must
        // update the existing Android card immediately so its visible window/timeout is correct.
        if (!current.startIso.isEmpty() && current.startMillis > 0L
                && previous.startMillis > 0L && current.startMillis != previous.startMillis) {
            return OfficialChange.IMPORTANT_UPDATE;
        }
        if (current.expiresMillis != previous.sourceExpiresMillis) {
            return OfficialChange.IMPORTANT_UPDATE;
        }

        int currentHash = officialContentHash(current);
        if (currentHash != previous.contentHash
                && Math.max(0L, nowMillis - previous.notifiedAtMillis) >= OFFICIAL_UPDATE_COOLDOWN_MILLIS) {
            return OfficialChange.IMPORTANT_UPDATE;
        }
        return OfficialChange.NONE;
    }

    static int officialContentHash(OfficialAlert alert) {
        if (alert == null) return 0;
        // Timing is deliberately excluded: timing changes bypass the textual update cooldown.
        return Objects.hash(normalize(alert.event), normalize(alert.headline), normalize(alert.description),
                normalize(alert.instruction), normalize(alert.area));
    }

    static boolean shouldNotifyX10(AlertCooldownPolicy.Previous previous, AlertEngine.Event current,
                                   long nowMillis) {
        if (current == null || current.source != AlertEngine.Source.HEURISTIC_X10) return false;
        if (current.severity.rank < AlertEngine.Severity.IMPORTANT.rank) return false;
        return AlertCooldownPolicy.shouldNotify(previous, current, nowMillis);
    }

    static boolean officialCoversX10(List<OfficialAlert> officialAlerts, AlertEngine.Event event) {
        if (officialAlerts == null || event == null) return false;
        for (OfficialAlert alert : officialAlerts) {
            if (alert == null || !sameHazardFamily(alert, event)) continue;
            if (timeWindowsOverlap(alert, event)) return true;
        }
        return false;
    }

    static boolean timeWindowsOverlap(OfficialAlert alert, AlertEngine.Event event) {
        if (alert == null || event == null) return false;
        long eventStart = parseEventMillis(event.startIso);
        long eventEnd = parseEventMillis(event.endExclusiveIso);
        if (eventStart <= 0L || eventEnd <= eventStart) return true; // unknown => conservative

        long officialStart = alert.startIso.isEmpty() ? -1L : alert.startMillis;
        long officialEnd = alert.expiresMillis;
        if (officialStart > 0L && officialEnd > 0L) {
            return officialStart < eventEnd && eventStart < officialEnd;
        }
        if (officialStart > 0L) return eventEnd > officialStart;
        if (officialEnd > 0L) return eventStart < officialEnd;
        return true; // an untimed official bulletin conservatively owns its hazard family
    }

    static boolean isExpired(PreviousOfficial previous, long nowMillis) {
        return previous != null && previous.expiresMillis > 0L && nowMillis >= previous.expiresMillis;
    }

    static boolean sameSmnFamily(PreviousOfficial previous, OfficialAlert current) {
        if (previous == null || current == null || previous.source == null) return false;
        boolean previousSmn = previous.source == OfficialAlert.Source.SMN_CAP || previous.source == OfficialAlert.Source.SMN_API;
        boolean currentSmn = current.source == OfficialAlert.Source.SMN_CAP || current.source == OfficialAlert.Source.SMN_API;
        if (!previousSmn || !currentSmn) return false;
        if (!normalize(previous.event).equals(normalize(current.event))) return false;
        if (previous.startMillis <= 0L || current.startMillis <= 0L) return false;
        return Math.abs(previous.startMillis - current.startMillis) <= 6L * 60L * 60L * 1000L;
    }

    private static boolean sameHazardFamily(OfficialAlert alert, AlertEngine.Event event) {
        String text = normalize(alert.event + " " + alert.headline + " " + alert.description);
        switch (event.kind) {
            case ZONDA:
                return text.contains("ZONDA");
            case THUNDERSTORM:
                return text.contains("TORMENTA") || text.contains("GRANIZO");
            case RAIN:
                return text.contains("LLUVIA") || text.contains("PRECIPIT");
            default:
                return false;
        }
    }

    private static long parseEventMillis(String iso) {
        if (iso == null || iso.length() < 16) return -1L;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        format.setLenient(false);
        format.setTimeZone(WeatherClient.MENDOZA_TZ);
        try {
            Date date = format.parse(iso.substring(0, 16));
            return date == null ? -1L : date.getTime();
        } catch (ParseException ignored) {
            return -1L;
        }
    }

    private static String normalize(String value) {
        String raw = value == null ? "" : value;
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
    }
}
