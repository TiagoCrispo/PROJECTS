package com.fer.wavault;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Ultra-low-latency filesystem capture. The important operation is opening the file descriptor
 * synchronously from the FileObserver callback. Once the descriptor is open, the worker can keep
 * reading the inode even if WhatsApp renames or unlinks the directory entry a moment later.
 */
public final class InstantMediaCapture {
    private InstantMediaCapture() {}
    private static final Map<String,Long> OPEN = new ConcurrentHashMap<>();
    private static final ExecutorService IO = VaultExecutors.bounded(
            3,24,"wa-vault-instant-media",Thread.NORM_PRIORITY + 1);

    public static boolean tryPreOpen(Context context, File file, String type, long eventTime, long linkedMessageId, long sourceTime) {
        if (context == null || file == null || !("image".equals(type) || "video".equals(type))) return false;
        String key = file.getAbsolutePath();
        long now = System.currentTimeMillis();
        Long old = OPEN.putIfAbsent(key, now);
        if (old != null && now - old < 20_000L) return false;
        OPEN.put(key, now);

        final FileInputStream in;
        try {
            // Do this NOW, on the FileObserver callback thread. Do not schedule the open itself.
            in = new FileInputStream(file);
        } catch (Throwable t) {
            OPEN.remove(key);
            return false;
        }

        final Context app = context.getApplicationContext();
        final long srcTime = sourceTime > 0 ? sourceTime : eventTime;
        CaptureMetrics.markStart(app,"preopen:"+key+":"+now);
        try {
            IO.execute(() -> {
                try {
                    long id = MediaArchiver.ingestPreopenedGrowingMedia(app, in, file, type, linkedMessageId, srcTime);
                    if (id > 0) {
                        CaptureMetrics.finish(app,"preopen:"+key+":"+now,"preopen_capture");
                        try { new VaultDb(app).logEvent("PREOPEN_MEDIA_CAPTURE",type+" · "+file.getName(),linkedMessageId,id); } catch (Throwable ignored) {}
                    }
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                    OPEN.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException saturated) {
            try { in.close(); } catch (Throwable ignored) {}
            OPEN.remove(key);
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("instant_media_backpressure_at",System.currentTimeMillis())
                    .apply();
            return false;
        }
    }

    public static boolean isCapturing(String path) { return path != null && OPEN.containsKey(path); }
}
