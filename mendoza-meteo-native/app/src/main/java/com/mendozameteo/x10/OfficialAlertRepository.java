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
    private static final double MAX_CACHE_DISTANCE_KM = 10.0;

    static final class Result {
        final List<OfficialAlert> alerts;
        final boolean smnAvailable;
        final boolean mendozaAvailable;
        final boolean smnRetryableFailure;
        final boolean mendozaRetryableFailure;
        final boolean usedCache;
        final long fetchedAtMillis;

        Result(List<OfficialAlert> alerts, boolean smnAvailable, boolean mendozaAvailable,
               boolean smnRetryableFailure, boolean mendozaRetryableFailure,
               boolean usedCache, long fetchedAtMillis) {
            this.alerts = Collections.unmodifiableList(new ArrayList<>(alerts));
            this.smnAvailable = smnAvailable;
            this.mendozaAvailable = mendozaAvailable;
            this.smnRetryableFailure = smnRetryableFailure;
            this.mendozaRetryableFailure = mendozaRetryableFailure;
            this.usedCache = usedCache;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        boolean hasAlerts() { return !alerts.isEmpty(); }
        boolean anyOfficialSourceAvailable() { return smnAvailable || mendozaAvailable; }
        boolean shouldRetryBackground() {
            return smnRetryableFailure || (!smnAvailable && mendozaRetryableFailure);
        }
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
        Cache cached = readCache(now, latitude, longitude);
        List<OfficialAlert> smnAlerts = new ArrayList<>();
        List<OfficialAlert> mendozaAlerts = new ArrayList<>();
        boolean smnAvailable = false;
        boolean mendozaAvailable = false;
        boolean smnRetryableFailure = false;
        boolean mendozaRetryableFailure = false;
        boolean usedCache = false;

        try {
            smnAlerts = smn.load(latitude, longitude, now);
            smnAvailable = true;
        } catch (Exception error) {
            smnRetryableFailure = retryableFailure(error);
            smnAlerts = smnFromCache(cached, now);
            usedCache |= !smnAlerts.isEmpty();
        }

        try {
            mendozaAlerts = mendoza.load(now);
            mendozaAvailable = true;
        } catch (Exception error) {
            mendozaRetryableFailure = retryableFailure(error);
            mendozaAlerts = sourceFromCache(cached, OfficialAlert.Source.MENDOZA_DCC, now);
            usedCache |= !mendozaAlerts.isEmpty();
        }

        List<OfficialAlert> merged = mergeActive(smnAlerts, mendozaAlerts, now);
        if (smnAvailable || mendozaAvailable) writeCache(merged, now, latitude, longitude);
        else if (merged.isEmpty() && cached != null) {
            merged = mergeActive(cached.alerts, Collections.emptyList(), now);
            usedCache = !merged.isEmpty();
        }
        return new Result(merged, smnAvailable, mendozaAvailable, smnRetryableFailure,
                mendozaRetryableFailure, usedCache, now);
    }

    static boolean retryableFailure(Throwable error) {
        if (error == null) return false;
        if (error instanceof WeatherException && ((WeatherException) error).retryable()) return true;
        if (retryableFailure(error.getCause())) return true;
        for (Throwable suppressed : error.getSuppressed()) {
            if (retryableFailure(suppressed)) return true;
        }
        return false;
    }

    static boolean cacheLocationCompatible(double savedLat, double savedLon,
                                           double currentLat, double currentLon) {
        if (!LocationPolicy.validCoordinate(savedLat, savedLon)
                || !LocationPolicy.validCoordinate(currentLat, currentLon)) return false;
        double dLat = Math.toRadians(currentLat - savedLat);
        double dLon = Math.toRadians(currentLon - savedLon);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(savedLat)) * Math.cos(Math.toRadians(currentLat))
                * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double distanceKm = 6371.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return distanceKm <= MAX_CACHE_DISTANCE_KM;
    }

    static List<OfficialAlert> mergeActive(List<OfficialAlert> first, List<OfficialAlert> second, long now) {
        LinkedHashMap<String, OfficialAlert> map = new LinkedHashMap<>();
        ArrayList<OfficialAlert> all = new ArrayList<>();
        if (first != null) all.addAll(first);
        if (second != null) all.addAll(second);

        all.sort(Comparator.comparingLong(a -> a == null || a.sentMillis <= 0 ? Long.MIN_VALUE : a.sentMillis));
        for (OfficialAlert alert : all) {
            if (alert == null) continue;
            // CAP Update and Cancel both reference the message(s) they supersede. Remove
            // references before handling the new message so feeds cannot leave duplicates.
            if (!alert.references.isEmpty()) removeReferences(map, alert.references);
            if (alert.cancellation) continue;
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

    private Cache readCache(long now, double latitude, double longitude) {
        String raw = prefs.getString(KEY_CACHE, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(raw);
            long savedAt = root.optLong("savedAt", -1L);
            double savedLat = root.optDouble("lat", Double.NaN);
            double savedLon = root.optDouble("lon", Double.NaN);
            if (savedAt <= 0 || now - savedAt > MAX_CACHE_AGE_MS
                    || !cacheLocationCompatible(savedLat, savedLon, latitude, longitude)) return null;
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
            return new Cache(savedAt, savedLat, savedLon, alerts);
        } catch (JSONException ignored) {
            prefs.edit().remove(KEY_CACHE).apply();
            return null;
        }
    }

    private void writeCache(List<OfficialAlert> alerts, long now, double latitude, double longitude) {
        try {
            JSONObject root = new JSONObject();
            root.put("savedAt", now);
            root.put("lat", latitude);
            root.put("lon", longitude);
            JSONArray items = new JSONArray();
            for (OfficialAlert alert : alerts) items.put(alert.toJson());
            root.put("alerts", items);
            prefs.edit().putString(KEY_CACHE, root.toString()).apply();
        } catch (JSONException ignored) { }
    }

    private static final class Cache {
        final long savedAt;
        final double lat;
        final double lon;
        final List<OfficialAlert> alerts;
        Cache(long savedAt, double lat, double lon, List<OfficialAlert> alerts) {
            this.savedAt = savedAt;
            this.lat = lat;
            this.lon = lon;
            this.alerts = alerts;
        }
    }
}
