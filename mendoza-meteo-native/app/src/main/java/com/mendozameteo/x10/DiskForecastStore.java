package com.mendozameteo.x10;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

final class DiskForecastStore implements ForecastStore {
    private static final double MAX_CACHE_DISTANCE_KM = 10.0;
    private final File directory;

    DiskForecastStore(Context context) {
        directory = new File(context.getApplicationContext().getFilesDir(), "weather-cache-v2");
    }

    @Override
    public void save(String slot, WeatherClient.Forecast forecast) throws Exception {
        AtomicFile file = atomicFile(slot);
        File parent = file.getBaseFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IllegalStateException("Cannot create weather cache directory");
        }
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            ForecastSerializer.write(out, forecast);
            file.finishWrite(out);
            out = null;
        } catch (Exception error) {
            if (out != null) file.failWrite(out);
            throw error;
        }
    }

    @Override
    public Entry load(String slot, double requestedLat, double requestedLon, long nowMillis) throws Exception {
        AtomicFile file = atomicFile(slot);
        if (!file.getBaseFile().exists()) return null;
        WeatherClient.Forecast forecast;
        try (FileInputStream in = file.openRead()) {
            forecast = ForecastSerializer.read(in);
        } catch (Exception corrupt) {
            file.delete();
            throw corrupt;
        }
        if (distanceKm(requestedLat, requestedLon, forecast.latitude, forecast.longitude) > MAX_CACHE_DISTANCE_KM) return null;
        ForecastFreshness.State freshness = ForecastFreshness.classify(forecast.fetchedAtMillis, nowMillis);
        if (freshness == ForecastFreshness.State.EXPIRED) {
            file.delete();
            return null;
        }
        return new Entry(forecast, freshness, ForecastFreshness.ageMillis(forecast.fetchedAtMillis, nowMillis));
    }

    private AtomicFile atomicFile(String slot) {
        if (slot == null || !slot.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid weather cache slot");
        return new AtomicFile(new File(directory, slot + ".bin"));
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double earthKm = 6371.0088;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return earthKm * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }
}
