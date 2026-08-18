package com.fer.wavault;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Persistent lifecycle for each fast recovery. All writes are off the callback thread so
 * NotificationListener/FileObserver can secure a file descriptor before SQLite/UI work.
 */
public final class RecoveryLedger {
    private RecoveryLedger() {}
    public static final String DETECTED="DETECTED";
    public static final String COPYING="COPYING";
    public static final String VERIFYING="VERIFYING";
    public static final String SAVED="SAVED";
    public static final String LOST="LOST";
    public static final String DUPLICATE="DUPLICATE";
    public static final String CORRUPT="CORRUPT";

    private static final ExecutorService IO=VaultExecutors.bounded(
            1,256,"wa-vault-recovery-ledger",Thread.NORM_PRIORITY-1);

    public static void stageAsync(Context context,String jobKey,String type,String state,String reason,long bytes,long messageId,long sourceTime,String localPath,long mediaId,String origin){
        if(context==null||jobKey==null||jobKey.isEmpty())return;
        Context app=context.getApplicationContext();
        try{IO.execute(()->{
            try{
                new VaultDb(app).upsertRecoveryJob(jobKey,type,state,reason,bytes,messageId,sourceTime,localPath,mediaId,origin);
            }catch(Throwable ignored){}
        });}catch(RejectedExecutionException saturated){
            // The staging file/SQLite media rows are authoritative; ledger telemetry is secondary.
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("recovery_ledger_backpressure_at",System.currentTimeMillis()).apply();
        }
    }
}
