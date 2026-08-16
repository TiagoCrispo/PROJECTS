package com.fer.a53performance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        Data input=new Data.Builder().putBoolean("restore_profile",true).build();
        OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(AutoWorker.class).setInitialDelay(45,TimeUnit.SECONDS).setInputData(input).build();
        WorkManager.getInstance(context).enqueueUniqueWork("a53-boot-restore",ExistingWorkPolicy.REPLACE,work);
    }
}
