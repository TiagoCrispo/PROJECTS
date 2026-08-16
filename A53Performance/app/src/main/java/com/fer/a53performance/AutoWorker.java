package com.fer.a53performance;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class AutoWorker extends Worker {
    public AutoWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork(){
        Context app=getApplicationContext();
        boolean maintenance=getInputData().getBoolean("maintenance",false);
        boolean restoreProfile=getInputData().getBoolean("restore_profile",false);

        if(maintenance){
            AnalysisCacheDb cache=new AnalysisCacheDb(app);
            try{cache.prune();}finally{cache.close();}
            app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE).edit().putLong("last_cache_prune",System.currentTimeMillis()).apply();
        }
        if(!restoreProfile)return Result.success();

        SharedPreferences prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);
        if(!prefs.getBoolean("auto_restore_profile",false))return Result.success();
        String saved=prefs.getString("last_profile","");
        if(saved==null||saved.isBlank())return Result.success();

        SystemOptimizer.Profile profile;
        try{profile=SystemOptimizer.Profile.valueOf(saved);}catch(Throwable ignored){return Result.success();}
        ShizukuShell shell=new ShizukuShell(app);
        try{
            if(!shell.available())return Result.retry();
            if(!shell.permissionGranted())return Result.success();
            if(!shell.warmUp(1800))return Result.retry();
            SystemOptimizer.ProfileResult result=SystemOptimizer.applyProfileSync(profile,shell);
            return result.success()?Result.success():Result.retry();
        }finally{shell.shutdown();}
    }
}
