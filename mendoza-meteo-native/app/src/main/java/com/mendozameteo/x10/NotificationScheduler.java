package com.mendozameteo.x10;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

final class NotificationScheduler {
    private static final String UNIQUE_PERIODIC = "mendoza-meteo-notifications-periodic-v1";

    private NotificationScheduler() { }

    static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();
        WeatherNotifier.createChannels(app);
        if (!WeatherNotifier.canPost(app)) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                NotificationUpdateWorker.class, 30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES)
                .setInitialDelay(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic);
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_PERIODIC);
    }
}
