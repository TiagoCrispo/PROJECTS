package com.fer.a53performance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        if(!context.getSharedPreferences("a53_ui",Context.MODE_PRIVATE).getBoolean("auto_restore_profile",false))return;
        Constraints constraints=new Constraints.Builder().setRequiresBatteryNotLow(true).build();
        Data input=new Data.Builder().putBoolean("restore_profile",true).build();
        OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(AutoWorker.class).setInitialDelay(45,TimeUnit.SECONDS).setInputData(input).setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniqueWork("a53-boot-restore",ExistingWorkPolicy.REPLACE,work);
    }
}
