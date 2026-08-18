package com.mendozameteo.x10;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public final class WeatherWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.mendozameteo.x10.REFRESH_WIDGET";
    private static final String UNIQUE_WORK = "mendoza-meteo-widget-update";
    private static final String PREFS = "widget_cache_v7";

    enum UpdateResult { UPDATED, CACHED, CACHED_RETRYABLE, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Context app = context.getApplicationContext();
        renderCachedOrPlaceholder(app, manager, ids);
        enqueueUpdate(app);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) enqueueUpdate(context.getApplicationContext());
    }

    public static void refreshAll(Context context) {
        enqueueUpdate(context.getApplicationContext());
    }

    static void enqueueUpdate(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WidgetUpdateWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request);
    }

    static UpdateResult performWorkerUpdate(Context context) {
        Context app = context.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        ComponentName component = new ComponentName(app, WeatherWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids.length == 0) return UpdateResult.UPDATED;

        NotificationLocation.Point point = NotificationLocation.load(app, System.currentTimeMillis());
        String forecastCacheKey = WidgetContextPolicy.forecastCacheKey(point);
        WeatherRepository.Result result = new WeatherRepository(app)
                .load(forecastCacheKey, point.lat, point.lon);

        if (result.isSuccess()) {
            boolean cached = result.origin == WeatherRepository.Origin.CACHE;
            publishForecast(app, result.forecast, cached, result.freshness, point);
            if (!cached) return UpdateResult.UPDATED;
            return result.failure != null && result.failure.retryable()
                    ? UpdateResult.CACHED_RETRYABLE : UpdateResult.CACHED;
        }

        renderCachedOrPlaceholder(app, manager, ids);
        return result.shouldRetryBackground()
                ? UpdateResult.RETRYABLE_FAILURE : UpdateResult.PERMANENT_FAILURE;
    }

    static void publishForecast(Context context, WeatherClient.Forecast forecast) {
        publishForecast(context, forecast, false, ForecastFreshness.State.FRESH);
    }

    static void publishForecast(Context context, WeatherClient.Forecast forecast,
                                boolean cached, ForecastFreshness.State freshness) {
        Context app = context.getApplicationContext();
        NotificationLocation.Point point = NotificationLocation.load(app, System.currentTimeMillis());
        publishForecast(app, forecast, cached, freshness, point);
    }

    private static void publishForecast(Context app, WeatherClient.Forecast forecast,
                                        boolean cached, ForecastFreshness.State freshness,
                                        NotificationLocation.Point point) {
        saveCache(app, forecast, point);
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        ComponentName component = new ComponentName(app, WeatherWidgetProvider.class);
        for (int id : manager.getAppWidgetIds(component)) {
            RemoteViews views = createViews(app);
            bind(views, forecast, cached, freshness);
            manager.updateAppWidget(id, views);
        }
    }

    private static void renderCachedOrPlaceholder(Context context, AppWidgetManager manager, int[] ids) {
        NotificationLocation.Point point = NotificationLocation.load(context, System.currentTimeMillis());
        for (int id : ids) {
            RemoteViews views = createViews(context);
            if (!loadCache(context, views, point)) {
                views.setTextViewText(R.id.current_temp, "--°");
                views.setContentDescription(R.id.widget_root,
                        point.personalized
                                ? "Mendoza Meteo. Sin datos guardados para la ubicación actual"
                                : "Mendoza Meteo. Sin datos guardados para la referencia UTN");
            }
            manager.updateAppWidget(id, views);
        }
    }

    private static RemoteViews createViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.weather_widget);
        Intent open = new Intent(context, LauncherActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }

    private static void bind(RemoteViews views, WeatherClient.Forecast forecast,
                             boolean cached, ForecastFreshness.State freshness) {
        String suffix = cached
                ? (freshness == ForecastFreshness.State.VERY_STALE ? " ⚠" : " ↻")
                : "";
        views.setTextViewText(R.id.current_temp, forecast.current.temp + "°" + suffix);
        views.setContentDescription(R.id.widget_root,
                cached
                        ? "Mendoza Meteo. Datos guardados: " + ForecastFreshness.ageLabel(
                                ForecastFreshness.ageMillis(forecast.fetchedAtMillis, System.currentTimeMillis()))
                        : "Mendoza Meteo. Pronóstico actualizado");

        int[] dayIds = {R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6, R.id.day7};
        int[] tempIds = {R.id.temp1, R.id.temp2, R.id.temp3, R.id.temp4, R.id.temp5, R.id.temp6, R.id.temp7};
        int[] rainIds = {R.id.rain1, R.id.rain2, R.id.rain3, R.id.rain4, R.id.rain5, R.id.rain6, R.id.rain7};
        for (int i = 0; i < Math.min(7, forecast.days.size()); i++) {
            WeatherClient.Day day = forecast.days.get(i);
            views.setTextViewText(dayIds[i], day.label);
            views.setTextViewText(tempIds[i], day.max + "°/" + day.min + "°");
            views.setTextViewText(rainIds[i], WeatherClient.probabilityText(day.rainProbability));
        }
    }

    private static void saveCache(Context context, WeatherClient.Forecast forecast,
                                  NotificationLocation.Point point) {
        if (forecast.days.size() < 7 || point == null) return;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putInt("current", forecast.current.temp)
                .putBoolean("personalized", point.personalized)
                .putLong("lat", Double.doubleToRawLongBits(point.lat))
                .putLong("lon", Double.doubleToRawLongBits(point.lon));
        for (int i = 0; i < 7; i++) {
            WeatherClient.Day day = forecast.days.get(i);
            editor.putString("day" + i, day.label)
                    .putInt("max" + i, day.max)
                    .putInt("min" + i, day.min)
                    .putInt("rain" + i, day.rainProbability);
        }
        editor.putLong("updated", forecast.fetchedAtMillis).apply();
    }

    private static boolean loadCache(Context context, RemoteViews views,
                                     NotificationLocation.Point point) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains("updated")) return false;

        boolean cachedPersonalized = prefs.getBoolean("personalized", false);
        double cachedLat = Double.longBitsToDouble(
                prefs.getLong("lat", Double.doubleToRawLongBits(Double.NaN)));
        double cachedLon = Double.longBitsToDouble(
                prefs.getLong("lon", Double.doubleToRawLongBits(Double.NaN)));
        if (!WidgetContextPolicy.sameContext(cachedPersonalized, cachedLat, cachedLon, point)) {
            prefs.edit().clear().apply();
            return false;
        }

        long updated = prefs.getLong("updated", 0L);
        ForecastFreshness.State freshness = ForecastFreshness.classify(updated, System.currentTimeMillis());
        if (freshness == ForecastFreshness.State.EXPIRED) {
            prefs.edit().clear().apply();
            return false;
        }

        String suffix = freshness == ForecastFreshness.State.FRESH
                ? ""
                : freshness == ForecastFreshness.State.VERY_STALE ? " ⚠" : " ↻";
        views.setTextViewText(R.id.current_temp, prefs.getInt("current", 0) + "°" + suffix);
        views.setContentDescription(R.id.widget_root,
                freshness == ForecastFreshness.State.FRESH
                        ? "Mendoza Meteo. Pronóstico reciente"
                        : "Mendoza Meteo. Datos guardados: " + ForecastFreshness.ageLabel(
                                ForecastFreshness.ageMillis(updated, System.currentTimeMillis())));

        int[] dayIds = {R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6, R.id.day7};
        int[] tempIds = {R.id.temp1, R.id.temp2, R.id.temp3, R.id.temp4, R.id.temp5, R.id.temp6, R.id.temp7};
        int[] rainIds = {R.id.rain1, R.id.rain2, R.id.rain3, R.id.rain4, R.id.rain5, R.id.rain6, R.id.rain7};
        for (int i = 0; i < 7; i++) {
            views.setTextViewText(dayIds[i], prefs.getString("day" + i, i == 0 ? "Hoy" : "---"));
            views.setTextViewText(tempIds[i], prefs.getInt("max" + i, 0) + "°/"
                    + prefs.getInt("min" + i, 0) + "°");
            views.setTextViewText(rainIds[i], WeatherClient.probabilityText(prefs.getInt("rain" + i, -1)));
        }
        return true;
    }
}
