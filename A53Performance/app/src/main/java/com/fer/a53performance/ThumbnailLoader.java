package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class ThumbnailLoader {
    private final ContentResolver resolver;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "a53-thumb"); t.setPriority(Thread.MIN_PRIORITY); return t;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final LruCache<String, Bitmap> cache;

    public ThumbnailLoader(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        int maxKb = Math.min(12 * 1024, (int)(Runtime.getRuntime().maxMemory() / 1024L / 16L));
        cache = new LruCache<>(Math.max(4096, maxKb)) {
            @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024); }
        };
    }

    public void load(StorageItem item, ImageView view) {
        view.setImageDrawable(null);
        if (item == null || item.uri == null || (!item.isImage() && !item.isVideo())) return;
        String key = item.stableKey();
        view.setTag(key);
        Bitmap hit = cache.get(key);
        if (hit != null && !hit.isRecycled()) { view.setImageBitmap(hit); return; }
        int g = generation.get();
        pool.execute(() -> {
            if (g != generation.get()) return;
            Bitmap bmp = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) bmp = resolver.loadThumbnail(item.uri, new Size(160,160), new CancellationSignal());
            } catch (Throwable ignored) {}
            if (bmp == null || g != generation.get()) return;
            cache.put(key, bmp);
            Bitmap finalBmp = bmp;
            view.post(() -> {
                Object tag = view.getTag();
                if (g == generation.get() && key.equals(tag)) view.setImageBitmap(finalBmp);
            });
        });
    }

    public void cancelVisualWork() { generation.incrementAndGet(); }
    public void trim(int level) {
        generation.incrementAndGet();
        if (level >= 10) cache.evictAll();
        else cache.trimToSize(Math.max(2048, cache.maxSize()/2));
    }
    public void shutdown() { generation.incrementAndGet(); pool.shutdownNow(); cache.evictAll(); }
}
