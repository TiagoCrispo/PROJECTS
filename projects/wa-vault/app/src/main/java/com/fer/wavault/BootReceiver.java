package com.fer.wavault;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms capture after reboot or an app update without doing long work on the broadcast thread. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                CaptureCoordinator.initialize(app);
                WhatsAppNotificationListener.forceRebind(app);
                // Do not open/migrate SQLite inside the broadcast completion window.
                WhatsAppNotificationListener.logEngineEventAsync(app,"PROCESS_REARM",action);
            } catch (Throwable ignored) {
            } finally {
                try { pending.finish(); } catch (Throwable ignored) {}
            }
        }, "wa-vault-rearm");
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
    }
}
