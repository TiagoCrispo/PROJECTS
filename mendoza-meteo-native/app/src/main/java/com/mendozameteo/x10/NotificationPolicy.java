package com.mendozameteo.x10;

import java.text.Normalizer;
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
        final int notificationId;
        final String event;
        final long startMillis;

        PreviousOfficial(String stateKey, String id, OfficialAlert.Source source, int levelRank,
                         int contentHash, long notifiedAtMillis, long expiresMillis,
                         int notificationId, String event, long startMillis) {
            this.stateKey = stateKey == null ? "" : stateKey;
            this.id = id == null ? "" : id;
            this.source = source;
            this.levelRank = levelRank;
            this.contentHash = contentHash;
            this.notifiedAtMillis = notifiedAtMillis;
            this.expiresMillis = expiresMillis;
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
        int currentHash = officialContentHash(current);
        if (currentHash != previous.contentHash
                && Math.max(0L, nowMillis - previous.notifiedAtMillis) >= OFFICIAL_UPDATE_COOLDOWN_MILLIS) {
            return OfficialChange.IMPORTANT_UPDATE;
        }
        return OfficialChange.NONE;
    }

    static int officialContentHash(OfficialAlert alert) {
        if (alert == null) return 0;
        return Objects.hash(normalize(alert.event), normalize(alert.headline), normalize(alert.description),
                normalize(alert.instruction), normalize(alert.area), alert.startIso, alert.expiresIso);
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
            if (alert == null) continue;
            String text = normalize(alert.event + " " + alert.headline + " " + alert.description);
            switch (event.kind) {
                case ZONDA:
                    if (text.contains("ZONDA")) return true;
                    break;
                case THUNDERSTORM:
                    if (text.contains("TORMENTA") || text.contains("GRANIZO")) return true;
                    break;
                case RAIN:
                    if (text.contains("LLUVIA") || text.contains("PRECIPIT")) return true;
                    break;
                default:
                    break;
            }
        }
        return false;
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

    private static String normalize(String value) {
        String raw = value == null ? "" : value;
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toUpperCase(Locale.ROOT);
    }
}
