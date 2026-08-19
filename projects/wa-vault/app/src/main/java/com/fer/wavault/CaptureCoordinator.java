package com.fer.wavault;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** Centralized, idempotent startup for every capture path. */
public final class CaptureCoordinator {
    private CaptureCoordinator() {}
    private static final AtomicBoolean RECOVERY_REQUESTED_THIS_PROCESS=new AtomicBoolean(false);

    public static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();int failures=0;
        failures+=run(app,"ADOPT_TREE",()->MediaArchiver.adoptPersistedWhatsAppTree(app));
        failures+=run(app,"VOICE_WATCHER",()->DirectVoiceWatcher.start(app));
        failures+=run(app,"MEDIA_WATCHER",()->DirectMediaWatcher.start(app));
        failures+=run(app,"MEDIASTORE_WATCHER",()->MediaStoreWatcher.start(app));
        failures+=run(app,"PENDING_MONITORS",()->MediaArchiver.resumePendingMonitors(app));
        long now=System.currentTimeMillis();android.content.SharedPreferences diag=app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);
        // Crash recovery is process-scoped, not wall-clock scoped. A freshly-created process must
        // always inspect ready staging once, even if the previous process requested recovery seconds ago.
        if(RECOVERY_REQUESTED_THIS_PROCESS.compareAndSet(false,true)){failures+=run(app,"CAPTURE_RECOVERY",()->CaptureRecovery.runAsync(app));diag.edit().putLong("capture_recovery_requested_at",now).apply();}
        long lastMaintenance=diag.getLong("maintenance_requested_at",0L);
        if(now-lastMaintenance>12L*60L*60L*1000L){failures+=run(app,"MAINTENANCE",()->VaultMaintenance.runAsync(app));diag.edit().putLong("maintenance_requested_at",now).apply();}
        failures+=run(app,"WATCHDOG",()->CaptureWatchdog.start(app));
        app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putLong("capture_coordinator_last_at",System.currentTimeMillis()).putInt("capture_start_failures",failures).apply();
    }

    private interface StartAction{void run() throws Throwable;}
    private static int run(Context app,String stage,StartAction action){try{action.run();return 0;}catch(Throwable t){try{new VaultDb(app).logCaptureFailure("START_"+stage,"engine",0L,t);}catch(Throwable ignored){}return 1;}}

    public static void restart(Context context) {
        if (context == null) return;Context app=context.getApplicationContext();
        initialize(app);
        try{DirectVoiceWatcher.ensureHealthy(app);}catch(Throwable t){run(app,"REPAIR_VOICE",()->{throw t;});}
        try{DirectMediaWatcher.ensureHealthy(app);}catch(Throwable t){run(app,"REPAIR_MEDIA",()->{throw t;});}
        try{MediaStoreWatcher.ensureHealthy(app);}catch(Throwable t){run(app,"REPAIR_MEDIASTORE",()->{throw t;});}
        try { WhatsAppNotificationListener.forceRebind(app); } catch (Throwable t) { run(app,"REPAIR_NOTIFICATION",()->{throw t;}); }
        app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putLong("capture_repair_at",System.currentTimeMillis()).apply();
    }
}
