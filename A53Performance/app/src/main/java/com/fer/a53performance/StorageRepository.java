package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageRepository {
    public interface ScanCallback { void onFinished(int generation, List<StorageItem> items, String error); }
    public interface DeleteCallback { void onFinished(List<StorageItem> deleted, List<StorageItem> failed); }

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "a53-storage"));
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private final Object lock = new Object();
    private final ArrayList<StorageItem> master = new ArrayList<>();

    public StorageRepository(Context context) { app = context.getApplicationContext(); }

    public int scanAsync(ScanCallback callback) {
        int generation = scanGeneration.incrementAndGet();
        io.execute(() -> {
            ArrayList<StorageItem> result = new ArrayList<>();
            String error = null;
            try { scanMediaStore(generation, result); }
            catch (Throwable t) { error = t.getClass().getSimpleName(); }
            if (generation != scanGeneration.get()) return;
            synchronized (lock) { master.clear(); master.addAll(result); }
            callback.onFinished(generation, result, error);
        });
        return generation;
    }

    public void cancelScan() { scanGeneration.incrementAndGet(); }

    private void scanMediaStore(int generation, List<StorageItem> out) {
        ContentResolver cr = app.getContentResolver();
        Uri base = MediaStore.Files.getContentUri("external");
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATA
        };
        try (Cursor c = cr.query(base, projection, null, null,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            int idIx = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
            int nameIx = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int sizeIx = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
            int dateIx = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
            int mimeIx = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
            int dataIx = c.getColumnIndex(MediaStore.Files.FileColumns.DATA);
            int n = 0;
            while (c.moveToNext()) {
                if ((++n & 255) == 0 && generation != scanGeneration.get()) return;
                long id = idIx >= 0 ? c.getLong(idIx) : n;
                String name = nameIx >= 0 ? c.getString(nameIx) : "";
                long size = sizeIx >= 0 ? c.getLong(sizeIx) : 0;
                long date = dateIx >= 0 ? c.getLong(dateIx) * 1000L : 0;
                String mime = mimeIx >= 0 ? c.getString(mimeIx) : "";
                String path = dataIx >= 0 ? c.getString(dataIx) : "";
                if (name == null || name.startsWith(".")) continue;
                if (size <= 0) continue;
                out.add(new StorageItem(id, Uri.withAppendedPath(base, Long.toString(id)), name, path, mime, size, date));
            }
        }
    }

    public void deleteAsync(Collection<StorageItem> selected, DeleteCallback callback) {
        ArrayList<StorageItem> copy = new ArrayList<>(selected);
        io.execute(() -> {
            ArrayList<StorageItem> deleted = new ArrayList<>();
            ArrayList<StorageItem> failed = new ArrayList<>();
            ContentResolver cr = app.getContentResolver();
            for (StorageItem item : copy) {
                boolean ok = false;
                try {
                    if (item.uri != null) ok = cr.delete(item.uri, null, null) > 0;
                } catch (Throwable ignored) {}
                if (!ok && item.path != null && !item.path.isBlank()) {
                    try { File f = new File(item.path); ok = !f.exists() || f.delete(); } catch (Throwable ignored) {}
                }
                (ok ? deleted : failed).add(item);
            }
            if (!deleted.isEmpty()) {
                synchronized (lock) {
                    Map<String, Boolean> gone = new HashMap<>();
                    for (StorageItem x : deleted) gone.put(x.stableKey(), true);
                    master.removeIf(x -> gone.containsKey(x.stableKey()));
                }
            }
            callback.onFinished(deleted, failed);
        });
    }

    public List<StorageItem> snapshot() { synchronized (lock) { return new ArrayList<>(master); } }

    public static long freeBytes() {
        try { return new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath()).getAvailableBytes(); }
        catch (Throwable ignored) { return 0; }
    }

    public void shutdown() { cancelScan(); io.shutdownNow(); }
}
