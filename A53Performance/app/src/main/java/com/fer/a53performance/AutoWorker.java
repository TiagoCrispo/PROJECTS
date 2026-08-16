package com.fer.a53performance;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.List;

public final class AutoWorker extends Worker {
    public AutoWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork(){
        Context app=getApplicationContext();
        AnalysisCacheDb cache=new AnalysisCacheDb(app);
        try{cache.prune();}finally{cache.close();}

        SharedPreferences prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);
        if(!prefs.getBoolean("auto_restore_profile",false))return Result.success();
        String saved=prefs.getString("last_profile","");
        if(saved==null||saved.isBlank())return Result.success();

        SystemOptimizer.Profile profile;
        try{profile=SystemOptimizer.Profile.valueOf(saved);}catch(Throwable ignored){return Result.success();}
        ShizukuShell shell=new ShizukuShell(app);
        try{
            if(!shell.permissionGranted())return Result.retry();
            shell.warmUp(1800);
            List<String> commands=SystemOptimizer.commands(profile);
            for(String command:commands){
                ShizukuShell.Result r=shell.exec(command,1600);
                if(!r.ok())return Result.retry();
            }
            return Result.success();
        }finally{shell.shutdown();}
    }
}
