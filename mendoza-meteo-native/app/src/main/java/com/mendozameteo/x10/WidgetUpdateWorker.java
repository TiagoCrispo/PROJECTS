package com.mendozameteo.x10;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class WidgetUpdateWorker extends Worker {
    public WidgetUpdateWorker(Context appContext, WorkerParameters params){super(appContext,params);}
    @Override public Result doWork(){
        WeatherWidgetProvider.UpdateResult result=WeatherWidgetProvider.performWorkerUpdate(getApplicationContext());
        switch(result){
            case UPDATED:
            case CACHED: return Result.success();
            case CACHED_RETRYABLE:
            case RETRYABLE_FAILURE: return getRunAttemptCount()<1?Result.retry():Result.success();
            case PERMANENT_FAILURE:
            default: return Result.failure();
        }
    }
}
