package com.fer.a53performance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        if(!context.getSharedPreferences("a53_ui",Context.MODE_PRIVATE).getBoolean("auto_restore_profile",false))return;
        AutoScheduler.scheduleBootRestore(context);
    }
}
