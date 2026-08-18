package com.mendozameteo.x10;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OfficialAlertRepository {
    private static final String PREFS = "official_alerts_v1";
    private static final String KEY_CACHE = "cache";
    private static final long MAX_CACHE_AGE_MS = 6L * 60L * 60L * 1000L;

    static final class Result {
        final List<OfficialAlert> alerts;
        final boolean smnAvailable;
        final boolean mendozaAvailable;
        final boolean usedCache;
        final long fetchedAtMillis;

        Result(List<OfficialAlert> alerts, boolean smnAvailable, boolean mendozaAvailable,
               boolean usedCache, long fetchedAtMillis) {
            this.alerts = Collections.unmodifiableList(new ArrayList<>(alerts));
            this.smnAvailable = smnAvailable;
            this.mendozaAvailable = mendozaAvailable;
            this.usedCache = usedCache;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        boolean hasAlerts() { return !alerts.isEmpty(); }
        boolean anyOfficialSourceAvailable() { return smnAvailable || mendozaAvailable; }
        String statusText() {
            if (smnAvailable && mendozaAvailable) return "SMN + Mendoza oficiales";
            if (smnAvailable) return "SMN oficial" + (usedCache ? " · Mendoza en caché" : "");
            if (mendozaAvailable) return "Mendoza oficial" + (usedCache ? " · SMN en caché" : "");
            return usedCache ? "alertas oficiales en caché" : "alertas oficiales no disponibles";
        }
    }

    private final SharedPreferences prefs;
    private final SmnOfficialAlertSource smn;
    private final MendozaOfficialBulletinSource mendoza;

    OfficialAlertRepository(Context context) {
        this(context, new HttpTextTransport());
    }

    OfficialAlertRepository(Context context, HttpTextTransport transport) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        smn = new SmnOfficialAlertSource(transport);
        mendoza = new MendozaOfficialBulletinSource(transport);
    }

    Result load(double latitude, double longitude) {
        long now = System.currentTimeMillis();
        Cache cached = readCache(now);
        List<OfficialAlert> smnAlerts = new ArrayList<>();
        List<OfficialAlert> mendozaAlerts = new ArrayList<>();
        boolean smnAvailable = false;
        boolean mendozaAvailable = false;
        boolean usedCache = false;

        try {
            smnAlerts = smn.load(latitude, longitude, now);
            smnAvailable = true;
        } catch (Exception ignored) {
            smnAlerts = smnFromCache(cached, now);
            usedCache |= !smnAlerts.isEmpty();
        }

        try {
            mendozaAlerts = mendoza.load(now);
            mendozaAvailable = true;
        } catch (Exception ignored) {
            mendozaAlerts = sourceFromCache(cached, OfficialAlert.Source.MENDOZA_DCC, now);
            usedCache |= !mendozaAlerts.isEmpty();
        }

        List<OfficialAlert> merged = mergeActive(smnAlerts, mendozaAlerts, now);
        if (smnAvailable || mendozaAvailable) writeCache(merged, now);
        else if (merged.isEmpty() && cached != null) {
            merged = mergeActive(cached.alerts, Collections.emptyList(), now);
            usedCache = !merged.isEmpty();
        }
        return new Result(merged, smnAvailable, mendozaAvailable, usedCache, now);
    }

    static List<OfficialAlert> mergeActive(List<OfficialAlert> first, List<OfficialAlert> second, long now) {
        LinkedHashMap<String, OfficialAlert> map = new LinkedHashMap<>();
        ArrayList<OfficialAlert> all = new ArrayList<>();
        if (first != null) all.addAll(first);
        if (second != null) all.addAll(second);

        all.sort(Comparator.comparingLong(a -> a == null || a.sentMillis <= 0 ? Long.MIN_VALUE : a.sentMillis));
        for (OfficialAlert alert : all) {
            if (alert == null) continue;
            if (alert.cancellation) {
                removeReferences(map, alert.references);
                continue;
            }
            if (!alert.activeAt(now)) continue;
            String key = alert.fingerprint();
            OfficialAlert previous = map.get(key);
            if (previous == null || alert.sentMillis >= previous.sentMillis) map.put(key, alert);
        }
        ArrayList<OfficialAlert> result = new ArrayList<>(map.values());
        result.sort(Comparator
                .comparingInt((OfficialAlert a) -> -a.level.rank)
                .thenComparingLong(a -> a.startMillis > 0 ? a.startMillis : Long.MAX_VALUE)
                .thenComparing(a -> a.title().toLowerCase(Locale.ROOT)));
        return result;
    }

    private static void removeReferences(Map<String, OfficialAlert> map, String references) {
        if (references == null || references.trim().isEmpty()) return;
        String[] tokens = references.trim().split("\\s+");
        for (String token : tokens) {
            String id = token;
            int comma = id.indexOf(',');
            if (comma >= 0) id = id.substring(0, comma);
            final String expected = id;
            map.entrySet().removeIf(entry -> entry.getValue().id.equals(expected));
        }
    }

    private static List<OfficialAlert> smnFromCache(Cache cache, long now) {
        ArrayList<OfficialAlert> result = new ArrayList<>();
        if (cache == null) return result;
        for (OfficialAlert alert : cache.alerts) {
            if ((alert.source == OfficialAlert.Source.SMN_CAP || alert.source == OfficialAlert.Source.SMN_API)
                    && alert.activeAt(now)) result.add(alert);
        }
        return result;
    }

    private static List<OfficialAlert> sourceFromCache(Cache cache, OfficialAlert.Source source, long now) {
        ArrayList<OfficialAlert> result = new ArrayList<>();
        if (cache == null) return result;
        for (OfficialAlert alert : cache.alerts) {
            if (alert.source == source && alert.activeAt(now)) result.add(alert);
        }
        return result;
    }

    private Cache readCache(long now) {
        String raw = prefs.getString(KEY_CACHE, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(raw);
            long savedAt = root.optLong("savedAt", -1L);
            if (savedAt <= 0 || now - savedAt > MAX_CACHE_AGE_MS) return null;
            JSONArray items = root.optJSONArray("alerts");
            ArrayList<OfficialAlert> alerts = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    OfficialAlert alert = OfficialAlert.fromJson(item);
                    if (alert.activeAt(now)) alerts.add(alert);
                }
            }
            return new Cache(savedAt, alerts);
        } catch (JSONException ignored) {
            prefs.edit().remove(KEY_CACHE).apply();
            return null;
        }
    }

    private void writeCache(List<OfficialAlert> alerts, long now) {
        try {
            JSONObject root = new JSONObject();
            root.put("savedAt", now);
            JSONArray items = new JSONArray();
            for (OfficialAlert alert : alerts) items.put(alert.toJson());
            root.put("alerts", items);
            prefs.edit().putString(KEY_CACHE, root.toString()).apply();
        } catch (JSONException ignored) { }
    }

    private static final class Cache {
        final long savedAt;
        final List<OfficialAlert> alerts;
        Cache(long savedAt, List<OfficialAlert> alerts) { this.savedAt = savedAt; this.alerts = alerts; }
    }
}
