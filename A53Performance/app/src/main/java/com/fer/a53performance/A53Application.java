package com.fer.a53performance;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public final class A53Application extends Application {
    private static final long PRUNE_INTERVAL_MS=7L*24L*60L*60L*1000L;

    @Override public void onCreate(){
        super.onCreate();
        SharedPreferences prefs=getSharedPreferences("a53_ui", Context.MODE_PRIVATE);
        long now=System.currentTimeMillis(),last=prefs.getLong("last_cache_prune",0L);
        if(now-last<PRUNE_INTERVAL_MS)return;
        Constraints constraints=new Constraints.Builder().setRequiresBatteryNotLow(true).build();
        Data input=new Data.Builder().putBoolean("maintenance",true).build();
        OneTimeWorkRequest maintenance=new OneTimeWorkRequest.Builder(AutoWorker.class).setInputData(input).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniqueWork("a53-cache-maintenance", ExistingWorkPolicy.KEEP,maintenance);
    }
}
