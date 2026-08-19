package com.fer.wavault;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs upgrade migrations one at a time at low priority, after live capture is available. */
public final class MigrationCoordinator {
    private MigrationCoordinator(){}
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final int MAX_ATTEMPTS=3;
    public static void ensureAsync(Context context){
        if(context==null||!RUNNING.compareAndSet(false,true))return;Context app=context.getApplicationContext();
        Thread t=new Thread(()->{try{
            runLegacyWithRetry(app);
            runPrivacyWithRetry(app);
            runMediaWithRetry(app);
        }finally{RUNNING.set(false);}},"wa-vault-migrations");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);t.start();
    }
    private static void runLegacyWithRetry(Context app){for(int i=0;i<MAX_ATTEMPTS&&!Thread.currentThread().isInterrupted();i++){LegacyPlainMigration.runAsync(app);awaitLegacy();if(LegacyPlainMigration.remainingCount(app)==0)return;backoff(i);}}
    private static void runPrivacyWithRetry(Context app){for(int i=0;i<MAX_ATTEMPTS&&!Thread.currentThread().isInterrupted();i++){MetadataPrivacy.runV0526MigrationAsync(app);awaitPrivacy();int remaining=-1;try{remaining=new VaultDb(app).countLegacyContentHashes();}catch(Throwable ignored){}if(MetadataPrivacy.v0526MigrationComplete(app)&&remaining==0)return;backoff(i);}}
    private static void runMediaWithRetry(Context app){for(int i=0;i<MAX_ATTEMPTS&&!Thread.currentThread().isInterrupted();i++){MediaCrypto.enforceRequiredMode(app);awaitMedia();if(MediaCrypto.migrationRemaining(app)==0)return;backoff(i);}}
    private static void awaitLegacy(){while(LegacyPlainMigration.migrationRunning()&&!Thread.currentThread().isInterrupted())sleep(80L);}
    private static void awaitPrivacy(){while(MetadataPrivacy.migrationRunning()&&!Thread.currentThread().isInterrupted())sleep(80L);}
    private static void awaitMedia(){while(MediaCrypto.migrationRunning()&&!Thread.currentThread().isInterrupted())sleep(80L);}
    private static void backoff(int attempt){if(attempt+1>=MAX_ATTEMPTS)return;sleep(attempt==0?300L:1200L);}
    private static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
