package com.mendozameteo.x10;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.widget.RemoteViews;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WeatherWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.mendozameteo.x10.REFRESH_WIDGET";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final double FALLBACK_LAT = -32.896748;
    private static final double FALLBACK_LON = -68.853418;
    private static final String PREFS = "widget_cache_v6";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                for (int id : ids) updateOne(app, manager, id);
            } finally {
                pending.finish();
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) refreshAll(context);
    }

    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        android.content.ComponentName component = new android.content.ComponentName(context, WeatherWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length == 0) return;
        Intent update = new Intent(context, WeatherWidgetProvider.class);
        update.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        update.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        context.sendBroadcast(update);
    }

    private static void updateOne(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.weather_widget);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);

        try {
            double[] point = resolvePoint(context);
            WeatherClient.Forecast forecast = WeatherClient.fetch(point[0], point[1]);
            bind(views, forecast);
            saveCache(context, forecast);
        } catch (Exception error) {
            if (!loadCache(context, views)) {
                views.setTextViewText(R.id.current_temp, "--°");
            }
        }
        manager.updateAppWidget(widgetId, views);
    }

    private static double[] resolvePoint(Context context) {
        SharedPreferences last = context.getSharedPreferences("last_location_v6", Context.MODE_PRIVATE);
        double savedLat = Double.longBitsToDouble(last.getLong("lat", Double.doubleToLongBits(Double.NaN)));
        double savedLon = Double.longBitsToDouble(last.getLong("lon", Double.doubleToLongBits(Double.NaN)));
        if (!Double.isNaN(savedLat) && !Double.isNaN(savedLon)) return new double[]{savedLat, savedLon};

        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                for (String provider : lm.getProviders(true)) {
                    Location candidate = lm.getLastKnownLocation(provider);
                    if (candidate == null) continue;
                    if (best == null || candidate.getTime() > best.getTime()
                            || (candidate.getTime() == best.getTime() && candidate.getAccuracy() < best.getAccuracy())) {
                        best = candidate;
                    }
                }
                if (best != null) return new double[]{best.getLatitude(), best.getLongitude()};
            } catch (SecurityException ignored) { }
        }
        return new double[]{FALLBACK_LAT, FALLBACK_LON};
    }

    private static void bind(RemoteViews views, WeatherClient.Forecast f) {
        views.setTextViewText(R.id.current_temp, f.current.temp + "°");
        int[] dayIds = {R.id.day1,R.id.day2,R.id.day3,R.id.day4,R.id.day5,R.id.day6,R.id.day7};
        int[] tempIds = {R.id.temp1,R.id.temp2,R.id.temp3,R.id.temp4,R.id.temp5,R.id.temp6,R.id.temp7};
        int[] rainIds = {R.id.rain1,R.id.rain2,R.id.rain3,R.id.rain4,R.id.rain5,R.id.rain6,R.id.rain7};
        for (int i = 0; i < 7; i++) {
            WeatherClient.Day d = f.days.get(i);
            views.setTextViewText(dayIds[i], d.label);
            views.setTextViewText(tempIds[i], d.max + "° / " + d.min + "°");
            views.setTextViewText(rainIds[i], d.rainProbability + "%");
        }
    }

    private static void saveCache(Context context, WeatherClient.Forecast f) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt("current", f.current.temp);
        for (int i = 0; i < 7; i++) {
            WeatherClient.Day d = f.days.get(i);
            e.putString("day"+i, d.label);
            e.putInt("max"+i, d.max);
            e.putInt("min"+i, d.min);
            e.putInt("rain"+i, d.rainProbability);
        }
        e.putLong("updated", System.currentTimeMillis());
        e.apply();
    }

    private static boolean loadCache(Context context, RemoteViews views) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("updated")) return false;
        views.setTextViewText(R.id.current_temp, p.getInt("current", 0) + "°");
        int[] dayIds = {R.id.day1,R.id.day2,R.id.day3,R.id.day4,R.id.day5,R.id.day6,R.id.day7};
        int[] tempIds = {R.id.temp1,R.id.temp2,R.id.temp3,R.id.temp4,R.id.temp5,R.id.temp6,R.id.temp7};
        int[] rainIds = {R.id.rain1,R.id.rain2,R.id.rain3,R.id.rain4,R.id.rain5,R.id.rain6,R.id.rain7};
        for (int i = 0; i < 7; i++) {
            views.setTextViewText(dayIds[i], p.getString("day"+i, i == 0 ? "Hoy" : "---"));
            views.setTextViewText(tempIds[i], p.getInt("max"+i, 0) + "° / " + p.getInt("min"+i, 0) + "°");
            views.setTextViewText(rainIds[i], p.getInt("rain"+i, 0) + "%");
        }
        return true;
    }
}
