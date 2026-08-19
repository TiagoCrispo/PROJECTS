package com.fer.wavault;

import android.content.Context;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores completed staging files after process death/reboot and removes stale partials. */
public final class CaptureRecovery {
    private CaptureRecovery() {}
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"wa-vault-recovery");t.setDaemon(true);return t; });

    public static void runAsync(Context context) {
        if (context == null || !RUNNING.compareAndSet(false,true)) return;
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            int recovered=0, queued=0, cleaned=0;
            try {
                File dir = new File(app.getFilesDir(),"vault_staging");
                File[] files = dir.listFiles();
                long now = System.currentTimeMillis();
                if (files != null) for (File f : files) {
                    if (f == null || !f.isFile()) continue;
                    String n=f.getName();
                    if(n.endsWith(".meta")){
                        File base=new File(f.getAbsolutePath().substring(0,f.getAbsolutePath().length()-5));
                        if(!base.exists()&&now-Math.max(f.lastModified(),0L)>10*60_000L)if(f.delete())cleaned++;
                        continue;
                    }
                    String type=n.contains("_video_")?"video":(n.contains("_image_")?"image":(n.contains("_audio_")?"audio":(n.contains("_document_")?"document":"")));
                    if (n.startsWith("fast_ready_") && !type.isEmpty()) {
                        CaptureProcessingEngine.recoverReady(app,f);queued++;
                    } else if (n.startsWith("ready_") && !type.isEmpty()) {
                        try { if (MediaArchiver.recoverStagedFile(app,f,type)>0) recovered++; } catch(Throwable ignored) {}
                    } else if ((n.startsWith("part_")||n.startsWith("fast_part_")) && now-Math.max(f.lastModified(),0L)>10*60_000L) {
                        if (f.delete()) cleaned++;
                    }
                }
                new VaultDb(app).reconcileAllPendingMedia();
                new VaultDb(app).logEvent("RECOVERY_PASS","staging recuperados="+recovered+" · fast en cola="+queued+" · parciales limpiados="+cleaned,0L,0L);
            } catch(Throwable ignored) {
            } finally { RUNNING.set(false); }
        });
    }
}
