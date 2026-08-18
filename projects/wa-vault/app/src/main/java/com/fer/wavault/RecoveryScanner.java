package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** On-demand fallback scan. Never runs while opening Inicio. */
public final class RecoveryScanner {
    private RecoveryScanner() {}
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"wa-vault-on-demand-scan");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});

    public static boolean isRunning(){return RUNNING.get();}
    public static void scanAsync(Context context){
        if(context==null||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        IO.execute(()->{
            int found=0;long started=System.currentTimeMillis();
            try{
                SharedPreferences p=app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);
                p.edit().putBoolean("recovery_scan_running",true).putLong("recovery_scan_started",started).apply();
                try{found+=MediaArchiver.scanAll(app);}catch(Throwable ignored){}
                try{DirectMediaWatcher.scanAllAvailableNow(app);}catch(Throwable ignored){}
                try{found+=MediaArchiver.scanVoiceBank(app);}catch(Throwable ignored){}
                try{new VaultDb(app).reconcileAllPendingMedia();}catch(Throwable ignored){}
                p.edit().putInt("recovery_scan_found",found).putLong("recovery_scan_finished",System.currentTimeMillis()).apply();
                try{new VaultDb(app).logEvent("RECOVERY_SCAN_DONE","archivos incorporados="+found,0L,0L);}catch(Throwable ignored){}
            }finally{
                app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putBoolean("recovery_scan_running",false).apply();
                RUNNING.set(false);VaultUiNotifier.notifyChanged(app,"media");
            }
        });
    }
}
