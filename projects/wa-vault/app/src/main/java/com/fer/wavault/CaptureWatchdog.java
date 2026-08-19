package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Self-healing supervisor for OEM process/storage quirks with adaptive idle cadence. */
public final class CaptureWatchdog {
    private CaptureWatchdog() {}
    private static final AtomicBoolean STARTED=new AtomicBoolean(false);
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"wa-vault-watchdog");t.setDaemon(true);return t;});
    private static final long ACTIVE_MS=45_000L;
    private static final long IDLE_MS=15L*60_000L;
    private static final long STAGING_RECOVERY_MIN_MS=2L*60_000L;
    private static Context app;

    public static void start(Context c){
        if(c==null)return;app=c.getApplicationContext();
        if(!STARTED.compareAndSet(false,true))return;
        TIMER.schedule(CaptureWatchdog::tick,10,TimeUnit.SECONDS);
    }

    private static void tick(){
        if(app==null)return;int repairs=0;boolean hasPending=false;boolean hot=false;long next=ACTIVE_MS;long now=System.currentTimeMillis();
        try{
            try{if(DirectMediaWatcher.ensureHealthy(app))repairs++;}catch(Throwable t){recordFailure("DIRECT_MEDIA",t);repairs++;}
            try{if(DirectVoiceWatcher.ensureHealthy(app))repairs++;}catch(Throwable t){recordFailure("DIRECT_AUDIO",t);repairs++;}
            try{if(MediaStoreWatcher.ensureHealthy(app))repairs++;}catch(Throwable t){recordFailure("MEDIASTORE",t);repairs++;}
            try{hot=FastCaptureEngine.isHot("video")||FastCaptureEngine.isHot("image")||FastCaptureEngine.isHot("audio")||FastCaptureEngine.isHot("document");if(hot)DirectMediaWatcher.scanPendingNow(app);}catch(Throwable t){recordFailure("HOT_MEDIA",t);repairs++;}

            SharedPreferences prefs=app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);
            long ticks=prefs.getLong("watchdog_ticks",0L)+1L;
            try{VaultDb db=new VaultDb(app);hasPending=!db.listPendingManualMedia("",1).isEmpty();if(hasPending)db.reconcileAllPendingMedia();}catch(Throwable t){recordFailure("RECONCILE",t);repairs++;}

            long lastRebind=prefs.getLong("watchdog_pending_rebind_at",0L);
            if(hasPending&&now-lastRebind>=3L*60_000L){try{MediaArchiver.resumePendingMonitors(app);prefs.edit().putLong("watchdog_pending_rebind_at",now).apply();}catch(Throwable t){recordFailure("PENDING_MONITORS",t);repairs++;}}
            boolean stagingWork=hasStagingWork();
            long lastRecovery=prefs.getLong("watchdog_recovery_at",0L);
            if(stagingWork&&now-lastRecovery>=STAGING_RECOVERY_MIN_MS){try{CaptureRecovery.runAsync(app);prefs.edit().putLong("watchdog_recovery_at",now).apply();}catch(Throwable t){recordFailure("RECOVERY",t);repairs++;}}
            long lastMaintenance=prefs.getLong("watchdog_maintenance_at",0L);
            if(now-lastMaintenance>=12L*60L*60_000L){try{VaultMaintenance.runAsync(app);prefs.edit().putLong("watchdog_maintenance_at",now).apply();}catch(Throwable t){recordFailure("MAINTENANCE",t);repairs++;}}

            boolean quiet=!hasPending&&!hot&&!stagingWork&&repairs==0;
            next=quiet?IDLE_MS:ACTIVE_MS;
            prefs.edit().putLong("watchdog_last_at",now).putInt("watchdog_last_repairs",repairs)
                    .putInt("watchdog_total_repairs",prefs.getInt("watchdog_total_repairs",0)+repairs)
                    .putLong("watchdog_ticks",ticks).putLong("watchdog_next_ms",next).apply();
        }finally{
            try{TIMER.schedule(CaptureWatchdog::tick,next,TimeUnit.MILLISECONDS);}catch(Throwable ignored){}
        }
    }
    private static boolean hasStagingWork(){
        if(app==null)return false;
        try{File d=new File(app.getFilesDir(),"vault_staging");File[] files=d.listFiles();if(files==null)return false;for(File f:files){if(f==null||!f.isFile())continue;String n=f.getName();if(n.startsWith("ready_")||n.startsWith("fast_ready_")||n.startsWith("part_")||n.startsWith("fast_part_"))return true;}}catch(Throwable ignored){}
        return false;
    }
    private static void recordFailure(String stage,Throwable t){try{if(app!=null)new VaultDb(app).logCaptureFailure("WATCHDOG_"+stage,"engine",0L,t);}catch(Throwable ignored){}}
}
