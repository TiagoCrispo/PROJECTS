package com.fer.wavault;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight consistency/cleanup pass. Never deletes valid recovered media by age. */
public final class VaultMaintenance {
    private VaultMaintenance() {}
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"wa-vault-maintenance");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});

    public static void runAsync(Context c){
        if(c==null||!RUNNING.compareAndSet(false,true))return;Context app=c.getApplicationContext();
        IO.execute(()->{try{
            VaultDb db=new VaultDb(app);
            int repaired=db.performConsistencyMaintenance();
            repaired+=db.retryPendingPhysicalDeletes();
            repaired+=db.retryPendingSupersededDeletes();
            repaired+=db.pruneCaptureAttempts(System.currentTimeMillis()-24L*60L*60L*1000L,System.currentTimeMillis()-14L*24L*60L*60L*1000L);
            repaired+=db.purgeTrashOlderThan(System.currentTimeMillis()-7L*24L*60L*60L*1000L);
            db.reconcileAllPendingMedia();
            MediaCrypto.cleanupCache(app);
            StorageAnalyzer.cleanupTemporary(app);
            MediaThumbnailLoader.pruneDiskCache(app,14L*24L*60L*60L*1000L);
            db.logEvent("MAINTENANCE_PASS","reparaciones="+repaired,0L,0L);
        }catch(Throwable ignored){}finally{RUNNING.set(false);}});
    }
}
