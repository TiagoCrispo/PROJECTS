package com.mendozameteo.x10;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class WidgetUpdateWorker extends Worker {
    public WidgetUpdateWorker(Context appContext, WorkerParameters params) {
        super(appContext, params);
    }

    @Override
    public Result doWork() {
        boolean updated = WeatherWidgetProvider.performWorkerUpdate(getApplicationContext());
        if (updated) return Result.success();
        // WorkManager supplies exponential backoff for transient failures. The widget already displays cached data.
        return getRunAttemptCount() < 2 ? Result.retry() : Result.failure();
    }
}
