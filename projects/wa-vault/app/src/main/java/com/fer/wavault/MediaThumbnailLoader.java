package com.fer.wavault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Low-jank thumbnail pipeline for the Media screen.
 * - 2 background workers max (instead of one Thread per row)
 * - memory LRU cache
 * - in-memory LRU only; no persistent plaintext thumbnail derivatives
 * - ImageView tag validation to avoid recycled-row image swaps
 */
public final class MediaThumbnailLoader {
    private MediaThumbnailLoader() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = VaultExecutors.bounded(
            2,24,"wa-vault-thumb",Thread.NORM_PRIORITY - 1);
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(20 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };

    public static void load(Context context, ImageView view, File encryptedFile, String type, int reqPx) {
        if (context == null || view == null || encryptedFile == null || !encryptedFile.exists()) return;
        Context app = context.getApplicationContext();
        String key = keyFor(encryptedFile, type, reqPx);
        view.setTag(key);

        Bitmap mem = MEM.get(key);
        if (mem != null && !mem.isRecycled()) {
            view.setImageBitmap(mem);
            return;
        }

        if (!IN_FLIGHT.add(key)) return;
        try {
            POOL.execute(() -> {
                Bitmap result = null;
                File readable = null;
                try {
                    readable = MediaCrypto.materialize(app, encryptedFile, encryptedFile.getName());
                    if (readable != null && readable.exists()) {
                        if ("video".equals(type)) result = videoThumb(readable, reqPx);
                        else result = imageThumb(readable, reqPx);
                    }
                    if (result != null) {
                        // Security invariant: thumbnails derived from decrypted vault media are
                        // never persisted as plaintext. Keep them process-local only.
                        MEM.put(key, result);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (readable != null && !readable.equals(encryptedFile)) {
                        try { readable.delete(); } catch (Throwable ignored) {}
                    }
                    IN_FLIGHT.remove(key);
                }
                final Bitmap finalResult = result;
                MAIN.post(() -> {
                    Object tag = view.getTag();
                    if (tag != null && key.equals(tag) && finalResult != null && !finalResult.isRecycled()) {
                        view.setImageBitmap(finalResult);
                    }
                });
            });
        } catch (RejectedExecutionException saturated) {
            IN_FLIGHT.remove(key);
            // A recycled/list rebind can request the thumbnail again later; never block the UI.
        }
    }

    public static void clearDiskCache(Context context) {
        if (context == null) return;
        try { POOL.execute(() -> {
            File dir = new File(context.getCacheDir(), "wa_media_thumbs");
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) try { f.delete(); } catch (Throwable ignored) {}
            MEM.evictAll();
        }); } catch (RejectedExecutionException ignored) { MEM.evictAll(); }
    }

    public static void pruneDiskCache(Context context,long maxAgeMs) {
        if (context == null) return;
        final long age=Math.max(60_000L,maxAgeMs);
        try { POOL.execute(() -> {
            File dir = new File(context.getCacheDir(), "wa_media_thumbs");
            File[] files = dir.listFiles(); long now=System.currentTimeMillis();
            if (files != null) for (File f : files) try { if(now-Math.max(0L,f.lastModified())>age) f.delete(); } catch (Throwable ignored) {}
        }); } catch (RejectedExecutionException ignored) {}
    }

    private static Bitmap imageThumb(File file, int req) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > Math.max(128, req * 2)) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        return scaleSquare(b, req);
    }

    private static Bitmap videoThumb(File file, int req) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            Bitmap b;
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                b = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, req, req);
            } else {
                b = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return scaleSquare(b, req);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap scaleSquare(Bitmap src, int req) {
        if (src == null) return null;
        int w = Math.max(1, src.getWidth());
        int h = Math.max(1, src.getHeight());
        float scale = Math.max(req / (float) w, req / (float) h);
        if (scale >= 1f && w <= req * 2 && h <= req * 2) return src;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (scaled != src) try { src.recycle(); } catch (Throwable ignored) {}
        int x = Math.max(0, (scaled.getWidth() - req) / 2);
        int y = Math.max(0, (scaled.getHeight() - req) / 2);
        int cw = Math.min(req, scaled.getWidth() - x);
        int ch = Math.min(req, scaled.getHeight() - y);
        if (cw <= 0 || ch <= 0) return scaled;
        Bitmap crop = Bitmap.createBitmap(scaled, x, y, cw, ch);
        if (crop != scaled) try { scaled.recycle(); } catch (Throwable ignored) {}
        return crop;
    }

    private static String keyFor(File f, String type, int req) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified() + "|" + type + "|" + req;
    }

}
