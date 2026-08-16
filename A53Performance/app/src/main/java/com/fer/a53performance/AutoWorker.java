package com.fer.a53performance;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class AutoWorker extends Worker {
    public AutoWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork(){
        Context app=getApplicationContext();SharedPreferences prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);
        boolean maintenance=getInputData().getBoolean("maintenance",false),restoreProfile=getInputData().getBoolean("restore_profile",false);
        if(maintenance){AnalysisCacheDb cache=new AnalysisCacheDb(app);try{cache.prune();prefs.edit().putLong("last_cache_prune",System.currentTimeMillis()).putString("last_auto_status","Caché analítica mantenida correctamente").apply();}catch(Throwable t){prefs.edit().putString("last_auto_status","Error de mantenimiento: "+t.getClass().getSimpleName()).apply();return Result.retry();}finally{cache.close();}}
        if(!restoreProfile)return Result.success();
        if(!prefs.getBoolean("auto_restore_profile",false))return Result.success();String saved=prefs.getString("last_profile","");if(saved==null||saved.isBlank())return Result.success();
        SystemOptimizer.Profile profile;try{profile=SystemOptimizer.Profile.valueOf(saved);}catch(Throwable ignored){return Result.success();}
        ShizukuShell shell=new ShizukuShell(app);try{
            if(!shell.available()){prefs.edit().putString("last_auto_status","Auto: Shizuku no disponible").apply();return Result.retry();}
            if(!shell.permissionGranted()){prefs.edit().putString("last_auto_status","Auto: falta permiso Shizuku").apply();return Result.success();}
            if(!shell.warmUp(1800)){prefs.edit().putString("last_auto_status","Auto: UserService no respondió").apply();return Result.retry();}
            SystemOptimizer.ProfileResult result=SystemOptimizer.applyProfileSync(profile,shell);
            prefs.edit().putLong("last_auto_run",System.currentTimeMillis()).putString("last_auto_status","Auto "+SystemOptimizer.label(profile)+": "+result.ok()+"/"+result.total()+" aplicados · "+result.verified()+"/"+result.total()+" verificados").apply();
            return result.success()?Result.success():Result.retry();
        }finally{shell.shutdown();}
    }
}
