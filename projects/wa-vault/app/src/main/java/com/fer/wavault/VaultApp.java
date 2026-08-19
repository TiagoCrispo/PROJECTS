package com.fer.wavault;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Starts privacy hardening and lightweight event-driven capture whenever Android creates the process. */
public final class VaultApp extends Application {
    private static final ExecutorService STARTUP_IO=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"wa-vault-process-startup");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    @Override public void onCreate() {
        super.onCreate();
        try { CrashRegistry.capturePreviousExit(this); } catch (Throwable ignored) {}
        try { CrashRegistry.install(this); } catch (Throwable ignored) {}
        // Snapshot-token preparation is deliberately synchronous: the listener must never race a
        // one-time snapshot scrub. Disk cleanup and diagnostic DB writes are not startup-critical.
        try { MetadataPrivacy.prepareV0525(this); } catch (Throwable ignored) {}
        try { CaptureCoordinator.initialize(this); } catch (Throwable ignored) {}
        try { MigrationCoordinator.ensureAsync(this); } catch (Throwable ignored) {}
        final android.content.Context app=getApplicationContext();
        STARTUP_IO.execute(()->{
            try { MediaCrypto.cleanupCache(app); } catch (Throwable ignored) {}
            try {
                VaultDb db = new VaultDb(app);
                db.logEvent("PROCESS_CREATED", "Application process created", 0L, 0L);
                db.logEvent("APP_START", "VaultApp.onCreate", 0L, 0L);
            } catch (Throwable ignored) {}
        });
    }
}
