package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageAnalyzer {
    public interface Callback {
        void onPhase(String phase);
        void onDone(Result result);
    }

    public static final class Result {
        public final Set<String> duplicateKeys;
        public final Set<String> similarKeys;
        public final int duplicateGroups;
        public final int similarGroups;
        Result(Set<String> duplicateKeys, Set<String> similarKeys, int duplicateGroups, int similarGroups) {
            this.duplicateKeys = Collections.unmodifiableSet(duplicateKeys);
            this.similarKeys = Collections.unmodifiableSet(similarKeys);
            this.duplicateGroups = duplicateGroups;
            this.similarGroups = similarGroups;
        }
        public static Result empty() { return new Result(new HashSet<>(), new HashSet<>(), 0, 0); }
    }

    private static final int SIMILARITY_MAX_IMAGES = 2500;
    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "a53-storage-analysis");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();

    public StorageAnalyzer(Context context) { app = context.getApplicationContext(); }

    public void analyzeAsync(List<StorageItem> input, Callback callback) {
        int g = generation.incrementAndGet();
        ArrayList<StorageItem> snapshot = new ArrayList<>(input);
        worker.execute(() -> {
            callback.onPhase("Buscando duplicados…");
            DuplicateResult dup = duplicates(snapshot, g);
            if (g != generation.get()) return;
            callback.onPhase("Comparando fotos similares…");
            SimilarResult sim = similar(snapshot, dup.keys, g);
            if (g != generation.get()) return;
            callback.onDone(new Result(dup.keys, sim.keys, dup.groups, sim.groups));
        });
    }

    public void cancel() { generation.incrementAndGet(); }
    public void shutdown() { cancel(); worker.shutdownNow(); }

    private DuplicateResult duplicates(List<StorageItem> items, int g) {
        HashMap<Long, ArrayList<StorageItem>> bySize = new HashMap<>();
        for (StorageItem x : items) {
            if (x.size < 64 * 1024L) continue;
            bySize.computeIfAbsent(x.size, k -> new ArrayList<>()).add(x);
        }
        HashSet<String> duplicateKeys = new HashSet<>();
        int groups = 0;
        for (ArrayList<StorageItem> sameSize : bySize.values()) {
            if (g != generation.get()) break;
            if (sameSize.size() < 2) continue;
            HashMap<String, ArrayList<StorageItem>> hashes = new HashMap<>();
            for (StorageItem x : sameSize) {
                if (g != generation.get()) break;
                String hash = sha256(x);
                if (hash != null) hashes.computeIfAbsent(hash, k -> new ArrayList<>()).add(x);
            }
            for (ArrayList<StorageItem> sameHash : hashes.values()) {
                if (sameHash.size() > 1) {
                    groups++;
                    for (StorageItem x : sameHash) duplicateKeys.add(x.stableKey());
                }
            }
        }
        return new DuplicateResult(duplicateKeys, groups);
    }

    private String sha256(StorageItem item) {
        try (InputStream raw = open(item); BufferedInputStream in = raw == null ? null : new BufferedInputStream(raw, 128 * 1024)) {
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) md.update(buffer, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable ignored) { return null; }
    }

    private SimilarResult similar(List<StorageItem> items, Set<String> duplicates, int g) {
        ArrayList<PhotoSig> photos = new ArrayList<>();
        for (StorageItem x : items) {
            if (g != generation.get() || photos.size() >= SIMILARITY_MAX_IMAGES) break;
            if (!x.isImage() || x.size <= 0 || x.size > 200L * 1024L * 1024L) continue;
            Long hash = dHash(x);
            if (hash != null) photos.add(new PhotoSig(x, hash));
        }
        int n = photos.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            if (g != generation.get()) break;
            long a = photos.get(i).hash;
            for (int j = i + 1; j < n; j++) {
                if (Long.bitCount(a ^ photos.get(j).hash) <= 7) union(parent, i, j);
            }
        }
        HashMap<Integer, ArrayList<Integer>> clusters = new HashMap<>();
        for (int i = 0; i < n; i++) clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        HashSet<String> similar = new HashSet<>();
        int groups = 0;
        for (ArrayList<Integer> cluster : clusters.values()) {
            if (cluster.size() < 2) continue;
            boolean hasNonExact = false;
            for (int idx : cluster) if (!duplicates.contains(photos.get(idx).item.stableKey())) { hasNonExact = true; break; }
            if (!hasNonExact) continue;
            groups++;
            for (int idx : cluster) similar.add(photos.get(idx).item.stableKey());
        }
        return new SimilarResult(similar, groups);
    }

    private Long dHash(StorageItem item) {
        Bitmap decoded = null, tiny = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = open(item)) { if (in == null) return null; BitmapFactory.decodeStream(in, null, bounds); }
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (max / sample > 160 && sample < 128) sample <<= 1;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = open(item)) { if (in == null) return null; decoded = BitmapFactory.decodeStream(in, null, opts); }
            if (decoded == null) return null;
            tiny = Bitmap.createScaledBitmap(decoded, 9, 8, true);
            long hash = 0L;
            int bit = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int left = luminance(tiny.getPixel(x, y));
                    int right = luminance(tiny.getPixel(x + 1, y));
                    if (left > right) hash |= (1L << bit);
                    bit++;
                }
            }
            return hash;
        } catch (Throwable ignored) { return null; }
        finally {
            if (tiny != null && tiny != decoded && !tiny.isRecycled()) tiny.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    private InputStream open(StorageItem item) {
        ContentResolver cr = app.getContentResolver();
        try { if (item.uri != null) return cr.openInputStream(item.uri); } catch (Throwable ignored) {}
        try { if (item.path != null && !item.path.isBlank()) return new java.io.FileInputStream(item.path); } catch (Throwable ignored) {}
        return null;
    }

    private static int luminance(int c) { return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; }
    private static int find(int[] p, int x) { while (p[x] != x) { p[x] = p[p[x]]; x = p[x]; } return x; }
    private static void union(int[] p, int a, int b) { int ra = find(p, a), rb = find(p, b); if (ra != rb) p[rb] = ra; }
    private record PhotoSig(StorageItem item, long hash) {}
    private record DuplicateResult(HashSet<String> keys, int groups) {}
    private record SimilarResult(HashSet<String> keys, int groups) {}
}
