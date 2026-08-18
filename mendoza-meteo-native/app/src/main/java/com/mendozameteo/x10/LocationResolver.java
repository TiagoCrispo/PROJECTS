package com.mendozameteo.x10;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class LocationResolver {
    private static final String PREFS = "last_location_v6";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_ACCURACY = "accuracy";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final String KEY_FIX_TIME = "fix_time";
    private static final String KEY_PRECISE = "precise";
    private static final String FUSED_PROVIDER = "fused";

    enum Source { CURRENT, LAST_KNOWN, SAVED, REFERENCE_UTN }
    enum Permission { FINE, COARSE, NONE }

    static final class Point {
        final double lat;
        final double lon;
        final float accuracyMeters;
        final long ageMillis;
        final long fixTimeMillis;
        final Source source;
        final Permission permission;
        final boolean stabilized;

        Point(double lat, double lon, float accuracyMeters, long ageMillis, long fixTimeMillis,
              Source source, Permission permission, boolean stabilized) {
            this.lat = lat;
            this.lon = lon;
            this.accuracyMeters = accuracyMeters;
            this.ageMillis = ageMillis;
            this.fixTimeMillis = fixTimeMillis;
            this.source = source;
            this.permission = permission;
            this.stabilized = stabilized;
        }

        boolean fromUserLocation() { return source != Source.REFERENCE_UTN; }
        boolean approximate() { return permission != Permission.FINE || accuracyMeters > 1_000f; }

        String statusLabel() {
            if (source == Source.REFERENCE_UTN) return "Sin ubicación · referencia UTN";
            String quality = approximate() ? "Ubicación aproximada" : "Ubicación precisa";
            if (source == Source.CURRENT) return quality;
            if (source == Source.LAST_KNOWN) return quality + " reciente · hace " + ForecastFreshness.ageLabel(ageMillis);
            return quality + " guardada · hace " + ForecastFreshness.ageLabel(ageMillis);
        }

        String cardPrefix() {
            if (source == Source.REFERENCE_UTN) return "Referencia UTN · ";
            if (source == Source.SAVED) return approximate() ? "Ubicación aprox. guardada · " : "Ubicación guardada · ";
            if (source == Source.LAST_KNOWN) return approximate() ? "Ubicación aprox. reciente · " : "Ubicación reciente · ";
            return approximate() ? "Ubicación aprox. · " : "";
        }
    }

    private final Context context;
    private final LocationManager manager;
    private final SharedPreferences prefs;
    private volatile boolean cancelled;
    private volatile CancellationSignal activeSignal;
    private volatile LocationListener activeListener;

    LocationResolver(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Point resolve(double fallbackLat, double fallbackLon, long timeoutMillis) {
        Permission permission = permission();
        if (cancelled || Thread.currentThread().isInterrupted() || permission == Permission.NONE || manager == null) {
            return reference(fallbackLat, fallbackLon);
        }

        long now = System.currentTimeMillis();
        Point saved = readSaved(now, permission);
        Point lastKnown = bestLastKnown(now, permission);
        Point quick = betterFallback(lastKnown, saved);
        if (quick != null && LocationPolicy.reusableWithoutSensor(quick.ageMillis, quick.accuracyMeters)) {
            return quick.source == Source.LAST_KNOWN ? stabilizeAndPersist(quick, saved, now) : quick;
        }

        Point current = requestBestCurrent(now, permission, Math.max(0L, timeoutMillis));
        if (current != null) return stabilizeAndPersist(current, saved, now);

        Point fallback = betterFallback(lastKnown, saved);
        if (fallback != null) {
            return fallback.source == Source.LAST_KNOWN ? stabilizeAndPersist(fallback, saved, now) : fallback;
        }
        return reference(fallbackLat, fallbackLon);
    }

    void cancel() {
        cancelled = true;
        CancellationSignal signal = activeSignal;
        if (signal != null) signal.cancel();
        LocationListener listener = activeListener;
        if (listener != null && manager != null) {
            try { manager.removeUpdates(listener); } catch (SecurityException ignored) { }
        }
    }

    private Permission permission() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return Permission.FINE;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) return Permission.COARSE;
        return Permission.NONE;
    }

    private Point bestLastKnown(long now, Permission permission) {
        Point best = null;
        try {
            List<String> providers = manager.getProviders(false);
            for (String provider : providers) {
                if (cancelled || Thread.currentThread().isInterrupted()) break;
                Location location;
                try { location = manager.getLastKnownLocation(provider); }
                catch (SecurityException | IllegalArgumentException ignored) { continue; }
                Point candidate = fromLocation(location, Source.LAST_KNOWN, permission, now);
                if (candidate != null && (best == null || LocationPolicy.candidateIsBetter(
                        candidate.ageMillis, candidate.accuracyMeters, best.ageMillis, best.accuracyMeters))) best = candidate;
            }
        } catch (RuntimeException ignored) { }
        return best;
    }

    private Point requestBestCurrent(long now, Permission permission, long timeoutMillis) {
        if (timeoutMillis <= 0L || cancelled) return null;
        List<String> providers = enabledCurrentProviders(permission);
        if (providers.isEmpty()) return null;
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        Point best = null;
        for (int i = 0; i < providers.size(); i++) {
            if (cancelled || Thread.currentThread().isInterrupted()) break;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            String provider = providers.get(i);
            long budget = i == providers.size() - 1 ? remaining : Math.min(1_800L, remaining);
            Location location = requestCurrent(provider, budget);
            Point candidate = fromLocation(location, Source.CURRENT, permission, System.currentTimeMillis());
            if (candidate != null && (best == null || LocationPolicy.candidateIsBetter(
                    candidate.ageMillis, candidate.accuracyMeters, best.ageMillis, best.accuracyMeters))) best = candidate;
            if (best != null) {
                float enough = permission == Permission.FINE ? 500f : 5_000f;
                if (best.accuracyMeters <= enough) break;
            }
        }
        return best;
    }

    private List<String> enabledCurrentProviders(Permission permission) {
        ArrayList<String> result = new ArrayList<>();
        Set<String> all;
        try { all = new HashSet<>(manager.getAllProviders()); }
        catch (RuntimeException error) { return result; }
        addIfEnabled(result, all, FUSED_PROVIDER);
        addIfEnabled(result, all, LocationManager.NETWORK_PROVIDER);
        addIfEnabled(result, all, LocationManager.GPS_PROVIDER);
        if (permission == Permission.COARSE && result.size() > 1 && result.contains(LocationManager.GPS_PROVIDER)) {
            result.remove(LocationManager.GPS_PROVIDER);
            result.add(LocationManager.GPS_PROVIDER);
        }
        return result;
    }

    private void addIfEnabled(List<String> result, Set<String> all, String provider) {
        if (!all.contains(provider) || result.contains(provider)) return;
        try { if (manager.isProviderEnabled(provider)) result.add(provider); }
        catch (RuntimeException ignored) { }
    }

    private Location requestCurrent(String provider, long timeoutMillis) {
        if (timeoutMillis <= 0L || cancelled) return null;
        if (Build.VERSION.SDK_INT >= 30) return requestCurrent30(provider, timeoutMillis);
        return requestCurrentLegacy(provider, timeoutMillis);
    }

    @TargetApi(30)
    private Location requestCurrent30(String provider, long timeoutMillis) {
        CountDownLatch latch = new CountDownLatch(1);
        Location[] box = new Location[1];
        CancellationSignal signal = new CancellationSignal();
        activeSignal = signal;
        try {
            manager.getCurrentLocation(provider, signal, Runnable::run, location -> {
                box[0] = location;
                latch.countDown();
            });
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) signal.cancel();
            return box[0];
        } catch (InterruptedException interrupted) {
            signal.cancel();
            Thread.currentThread().interrupt();
            return null;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return null;
        } finally {
            if (activeSignal == signal) activeSignal = null;
        }
    }

    @SuppressWarnings("deprecation")
    private Location requestCurrentLegacy(String provider, long timeoutMillis) {
        CountDownLatch latch = new CountDownLatch(1);
        Location[] box = new Location[1];
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { box[0] = location; latch.countDown(); }
            @Override public void onStatusChanged(String providerName, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String providerName) { }
            @Override public void onProviderDisabled(String providerName) { }
        };
        activeListener = listener;
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
            return box[0];
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (SecurityException | IllegalArgumentException ignored) {
            return null;
        } finally {
            try { manager.removeUpdates(listener); } catch (SecurityException ignored) { }
            if (activeListener == listener) activeListener = null;
        }
    }

    private Point fromLocation(Location location, Source source, Permission permission, long nowWall) {
        if (location == null || !LocationPolicy.validCoordinate(location.getLatitude(), location.getLongitude())) return null;
        float accuracy = location.hasAccuracy() && location.getAccuracy() > 0f
                ? location.getAccuracy() : LocationPolicy.MAX_ACCEPTABLE_ACCURACY_METERS;
        long age = locationAgeMillis(location, nowWall);
        if (!LocationPolicy.usableDeviceFix(age, accuracy)) return null;
        long fixTime = location.getTime() > 0L ? location.getTime() : Math.max(1L, nowWall - age);
        return new Point(location.getLatitude(), location.getLongitude(), accuracy, age, fixTime, source, permission, false);
    }

    private long locationAgeMillis(Location location, long nowWall) {
        if (Build.VERSION.SDK_INT >= 17 && location.getElapsedRealtimeNanos() > 0L) {
            long delta = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
            if (delta >= 0L) return delta / 1_000_000L;
        }
        if (location.getTime() <= 0L) return Long.MAX_VALUE;
        return Math.max(0L, nowWall - location.getTime());
    }

    private Point readSaved(long now, Permission permission) {
        if (!prefs.contains(KEY_SAVED_AT)) {
            if (prefs.contains(KEY_LAT) || prefs.contains(KEY_LON)) prefs.edit().clear().apply();
            return null;
        }
        long savedAt = prefs.getLong(KEY_SAVED_AT, 0L);
        long savedAge = savedAt <= 0L ? Long.MAX_VALUE : Math.max(0L, now - savedAt);
        double lat = Double.longBitsToDouble(prefs.getLong(KEY_LAT, Double.doubleToRawLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(prefs.getLong(KEY_LON, Double.doubleToRawLongBits(Double.NaN)));
        float accuracy = prefs.getFloat(KEY_ACCURACY, LocationPolicy.MAX_ACCEPTABLE_ACCURACY_METERS);
        boolean precise = prefs.getBoolean(KEY_PRECISE, false);
        if (!LocationPolicy.validCoordinate(lat, lon)
                || !LocationPolicy.usableSaved(savedAge, accuracy, precise, permission == Permission.FINE)) {
            prefs.edit().clear().apply();
            return null;
        }
        long fixTime = prefs.getLong(KEY_FIX_TIME, savedAt);
        return new Point(lat, lon, accuracy, savedAge, fixTime, Source.SAVED, permission, false);
    }

    private Point stabilizeAndPersist(Point candidate, Point saved, long now) {
        Point result = candidate;
        if (saved != null && LocationPolicy.shouldStabilize(saved.lat, saved.lon, saved.accuracyMeters, saved.ageMillis,
                candidate.lat, candidate.lon, candidate.accuracyMeters)) {
            result = new Point(saved.lat, saved.lon, candidate.accuracyMeters, candidate.ageMillis,
                    candidate.fixTimeMillis, candidate.source, candidate.permission, true);
        }
        persist(result, now);
        return result;
    }

    private void persist(Point point, long now) {
        if (point == null || !point.fromUserLocation() || point.permission == Permission.NONE) return;
        prefs.edit()
                .putLong(KEY_LAT, Double.doubleToRawLongBits(point.lat))
                .putLong(KEY_LON, Double.doubleToRawLongBits(point.lon))
                .putFloat(KEY_ACCURACY, point.accuracyMeters)
                .putLong(KEY_SAVED_AT, now)
                .putLong(KEY_FIX_TIME, point.fixTimeMillis)
                .putBoolean(KEY_PRECISE, point.permission == Permission.FINE)
                .apply();
    }

    private Point betterFallback(Point a, Point b) {
        if (a == null) return b;
        if (b == null) return a;
        return LocationPolicy.candidateIsBetter(a.ageMillis, a.accuracyMeters, b.ageMillis, b.accuracyMeters) ? a : b;
    }

    private Point reference(double lat, double lon) {
        return new Point(lat, lon, Float.POSITIVE_INFINITY, Long.MAX_VALUE, 0L,
                Source.REFERENCE_UTN, Permission.NONE, false);
    }
}
