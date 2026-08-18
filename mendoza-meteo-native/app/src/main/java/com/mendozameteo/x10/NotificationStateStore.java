package com.mendozameteo.x10;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class NotificationStateStore {
    private static final String PREFS = "notification_state_v1";
    private static final String OFFICIAL_PREFIX = "official.";
    private static final String X10_PREFIX = "x10.";

    private final SharedPreferences prefs;

    NotificationStateStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    NotificationPolicy.PreviousOfficial findMatchingOfficial(OfficialAlert current) {
        if (current == null) return null;
        List<NotificationPolicy.PreviousOfficial> all = loadOfficial();
        for (NotificationPolicy.PreviousOfficial previous : all) {
            if (!current.id.isEmpty() && current.id.equals(previous.id)) return previous;
        }
        for (String referenceId : referenceIds(current.references)) {
            for (NotificationPolicy.PreviousOfficial previous : all) {
                if (referenceId.equals(previous.id)) return previous;
            }
        }
        for (NotificationPolicy.PreviousOfficial previous : all) {
            if (NotificationPolicy.sameSmnFamily(previous, current)) return previous;
        }
        return null;
    }

    void markOfficial(OfficialAlert alert, int notificationId, long nowMillis) {
        if (alert == null) return;
        String key = officialKey(alert.source, alert.id, alert.event, alert.startIso);
        try {
            JSONObject value = new JSONObject();
            value.put("stateKey", key);
            value.put("id", alert.id);
            value.put("source", alert.source.name());
            value.put("levelRank", alert.level.rank);
            value.put("contentHash", NotificationPolicy.officialContentHash(alert));
            value.put("notifiedAt", nowMillis);
            value.put("expires", alert.expiresMillis);
            value.put("notificationId", notificationId);
            value.put("event", alert.event);
            value.put("start", alert.startMillis);
            prefs.edit().putString(key, value.toString()).apply();
        } catch (JSONException ignored) { }
    }

    void removeOfficial(NotificationPolicy.PreviousOfficial previous) {
        if (previous == null || previous.stateKey.isEmpty()) return;
        prefs.edit().remove(previous.stateKey).apply();
    }

    List<NotificationPolicy.PreviousOfficial> pruneOfficial(OfficialAlertRepository.Result current,
                                                             long nowMillis) {
        if (current == null) return Collections.emptyList();
        ArrayList<NotificationPolicy.PreviousOfficial> removed = new ArrayList<>();
        for (NotificationPolicy.PreviousOfficial previous : loadOfficial()) {
            boolean expire = NotificationPolicy.isExpired(previous, nowMillis);
            boolean sourceAvailable = isSourceAvailable(previous.source, current);
            boolean stillPresent = matchesAny(previous, current.alerts);
            if (expire || (sourceAvailable && !stillPresent)) {
                removeOfficial(previous);
                removed.add(previous);
            }
        }
        return removed;
    }

    AlertCooldownPolicy.Previous loadX10(AlertEngine.Kind kind) {
        if (kind == null) return null;
        String raw = prefs.getString(X10_PREFIX + kind.name(), null);
        if (raw == null) return null;
        try {
            JSONObject value = new JSONObject(raw);
            AlertEngine.Kind storedKind = AlertEngine.Kind.valueOf(value.optString("kind", kind.name()));
            AlertEngine.Severity severity = AlertEngine.Severity.valueOf(value.getString("severity"));
            return new AlertCooldownPolicy.Previous(storedKind, severity, value.optString("startIso"),
                    value.optLong("notifiedAt", 0L));
        } catch (JSONException | IllegalArgumentException ignored) {
            prefs.edit().remove(X10_PREFIX + kind.name()).apply();
            return null;
        }
    }

    void markX10(AlertEngine.Event event, long nowMillis) {
        if (event == null) return;
        try {
            JSONObject value = new JSONObject();
            value.put("kind", event.kind.name());
            value.put("severity", event.severity.name());
            value.put("startIso", event.startIso);
            value.put("notifiedAt", nowMillis);
            prefs.edit().putString(X10_PREFIX + event.kind.name(), value.toString()).apply();
        } catch (JSONException ignored) { }
    }

    List<NotificationPolicy.PreviousOfficial> loadOfficial() {
        ArrayList<NotificationPolicy.PreviousOfficial> result = new ArrayList<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(OFFICIAL_PREFIX) || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject value = new JSONObject((String) entry.getValue());
                OfficialAlert.Source source = OfficialAlert.Source.valueOf(value.getString("source"));
                result.add(new NotificationPolicy.PreviousOfficial(
                        entry.getKey(), value.optString("id"), source, value.optInt("levelRank", 0),
                        value.optInt("contentHash", 0), value.optLong("notifiedAt", 0L),
                        value.optLong("expires", -1L), value.optInt("notificationId", 0),
                        value.optString("event"), value.optLong("start", -1L)));
            } catch (JSONException | IllegalArgumentException ignored) {
                prefs.edit().remove(entry.getKey()).apply();
            }
        }
        return result;
    }

    private boolean matchesAny(NotificationPolicy.PreviousOfficial previous, List<OfficialAlert> alerts) {
        if (alerts == null) return false;
        for (OfficialAlert current : alerts) {
            if (current == null) continue;
            if (!previous.id.isEmpty() && previous.id.equals(current.id)) return true;
            for (String ref : referenceIds(current.references)) if (previous.id.equals(ref)) return true;
            if (NotificationPolicy.sameSmnFamily(previous, current)) return true;
        }
        return false;
    }

    private static boolean isSourceAvailable(OfficialAlert.Source source, OfficialAlertRepository.Result current) {
        if (source == OfficialAlert.Source.MENDOZA_DCC) return current.mendozaAvailable;
        return current.smnAvailable;
    }

    private static List<String> referenceIds(String references) {
        if (references == null || references.trim().isEmpty()) return Collections.emptyList();
        ArrayList<String> ids = new ArrayList<>();
        for (String token : references.trim().split("\\s+")) {
            String id = token;
            int comma = id.indexOf(',');
            if (comma >= 0) id = id.substring(0, comma);
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    private static String officialKey(OfficialAlert.Source source, String id, String event, String startIso) {
        String stable = id == null || id.isEmpty() ? (event + "|" + startIso) : id;
        return OFFICIAL_PREFIX + source.name() + "." + Integer.toHexString(stable.hashCode());
    }
}
