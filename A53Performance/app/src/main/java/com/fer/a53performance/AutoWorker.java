package com.fer.a53performance;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.Set;

public final class AutoWorker extends Worker {
    private static final int MAX_BOOT_RETRIES=2;
    public AutoWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork(){
        Context app=getApplicationContext();SharedPreferences prefs=app.getSharedPreferences("a53_ui",Context.MODE_PRIVATE);
        boolean maintenance=getInputData().getBoolean("maintenance",false),restoreProfile=getInputData().getBoolean("restore_profile",false),fromOpen=getInputData().getBoolean("from_open",false);
        if(maintenance){
            AnalysisCacheDb cache=new AnalysisCacheDb(app);StorageIndexDb index=new StorageIndexDb(app);
            try{Set<String> existing=index.keys();cache.prune(existing);prefs.edit().putLong("last_cache_prune",System.currentTimeMillis()).putString("last_auto_status","Caché analítica mantenida correctamente").apply();}
            catch(Throwable t){prefs.edit().putString("last_auto_status","Error de mantenimiento: "+t.getClass().getSimpleName()).apply();return Result.retry();}
            finally{cache.close();index.close();}
        }
        if(!restoreProfile)return Result.success();
        if(!prefs.getBoolean("auto_restore_profile",false))return Result.success();String saved=prefs.getString("last_profile","");if(saved==null||saved.isBlank())return Result.success();
        SystemOptimizer.Profile profile;try{profile=SystemOptimizer.Profile.valueOf(saved);}catch(Throwable ignored){return Result.success();}
        ShizukuShell shell=new ShizukuShell(app);try{
            if(!shell.available())return deferOrRetry(prefs,"Auto: Shizuku no disponible",fromOpen);
            if(!shell.permissionGranted()){markDeferred(prefs,"Auto: falta permiso Shizuku");return Result.success();}
            if(!shell.warmUp(1800))return deferOrRetry(prefs,"Auto: UserService no respondió",fromOpen);
            SystemOptimizer.ProfileResult result=SystemOptimizer.applyProfileSync(profile,shell);
            String rollback=result.rollbackTotal()>0?" · rollback "+result.rollbackVerified()+"/"+result.rollbackTotal():"";
            prefs.edit().putLong("last_auto_run",System.currentTimeMillis()).putString("last_auto_status","Auto "+SystemOptimizer.label(profile)+": "+result.ok()+"/"+result.total()+" aplicados · "+result.verified()+"/"+result.total()+" verificados"+rollback).apply();
            if(result.success()){prefs.edit().putBoolean("auto_deferred",false).apply();return Result.success();}
            return deferOrRetry(prefs,"Auto: perfil no quedó verificado; el estado previo fue protegido",fromOpen);
        }finally{shell.shutdown();}
    }

    private Result deferOrRetry(SharedPreferences prefs,String status,boolean fromOpen){
        if(!fromOpen&&getRunAttemptCount()<MAX_BOOT_RETRIES){prefs.edit().putString("last_auto_status",status+" · reintento "+(getRunAttemptCount()+1)+"/"+MAX_BOOT_RETRIES).apply();return Result.retry();}
        markDeferred(prefs,status+" · pendiente hasta abrir la app");return Result.success();
    }
    private static void markDeferred(SharedPreferences prefs,String status){prefs.edit().putBoolean("auto_deferred",true).putLong("last_auto_run",System.currentTimeMillis()).putString("last_auto_status",status).apply();}
}
