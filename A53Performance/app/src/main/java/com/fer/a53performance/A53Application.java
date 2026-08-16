package com.fer.a53performance;

import android.app.Application;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public final class A53Application extends Application {
    @Override public void onCreate(){
        super.onCreate();
        Constraints constraints=new Constraints.Builder().setRequiresBatteryNotLow(true).build();
        OneTimeWorkRequest warm=new OneTimeWorkRequest.Builder(AutoWorker.class).setInitialDelay(20,TimeUnit.SECONDS).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniqueWork("a53-auto-warm",ExistingWorkPolicy.REPLACE,warm);
        PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(AutoWorker.class,12,TimeUnit.HOURS).setConstraints(constraints).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("a53-auto-maintenance",ExistingPeriodicWorkPolicy.UPDATE,periodic);
    }
}
