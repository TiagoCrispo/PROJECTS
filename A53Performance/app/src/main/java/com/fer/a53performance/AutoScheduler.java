package com.fer.a53performance;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public final class AutoScheduler {
    private AutoScheduler(){}

    public static void scheduleBootRestore(Context context){
        Constraints constraints=new Constraints.Builder().setRequiresBatteryNotLow(true).build();
        Data input=new Data.Builder().putBoolean("restore_profile",true).putBoolean("from_boot",true).build();
        OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(AutoWorker.class)
                .setInitialDelay(45,TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                .setInputData(input).setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniqueWork("a53-boot-restore",ExistingWorkPolicy.REPLACE,work);
    }

    public static void scheduleDeferredRestore(Context context){
        if(!context.getSharedPreferences("a53_ui",Context.MODE_PRIVATE).getBoolean("auto_restore_profile",false))return;
        if(!context.getSharedPreferences("a53_ui",Context.MODE_PRIVATE).getBoolean("auto_deferred",false))return;
        Constraints constraints=new Constraints.Builder().setRequiresBatteryNotLow(true).build();
        Data input=new Data.Builder().putBoolean("restore_profile",true).putBoolean("from_open",true).build();
        OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(AutoWorker.class).setInputData(input).setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniqueWork("a53-deferred-restore",ExistingWorkPolicy.KEEP,work);
    }
}
