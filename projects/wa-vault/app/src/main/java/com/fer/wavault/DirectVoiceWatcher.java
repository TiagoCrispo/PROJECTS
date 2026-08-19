package com.fer.wavault;

import android.content.Context;
import android.os.Environment;
import android.os.FileObserver;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lowest-latency voice-note capture path.
 *
 * v0.2.4 strategy:
 *  - FileObserver/inotify is the primary source of truth, not recursive polling.
 *  - On CREATE/MOVED_TO/MODIFY we attempt to OPEN the .opus immediately, before CLOSE_WRITE.
 *  - Once the file descriptor is open, we keep reading while WhatsApp grows the file. On Linux,
 *    unlinking the pathname does not invalidate an already-open descriptor, so a very fast delete
 *    can still be copied if Android delivered CREATE soon enough and the descriptor opened first.
 *  - Directory discovery and file copying use separate executors so scans can never block capture.
 */
public final class DirectVoiceWatcher {
    private DirectVoiceWatcher() {}

    private static final Object LOCK = new Object();
    private static final Map<String, FileObserver> OBSERVERS = new ConcurrentHashMap<>();
    private static final Map<String, CaptureSession> CAPTURES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<Arm> ARMS = new ConcurrentLinkedDeque<>();

    private static final ExecutorService CONTROL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "wa-vault-watch-control");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private static final ExecutorService CAPTURE = VaultExecutors.bounded(
            4, 32, "wa-vault-early-audio", Thread.NORM_PRIORITY + 1);
    private static final AtomicBoolean START_QUEUED = new AtomicBoolean(false);
    private static final AtomicBoolean FAST_SCAN_QUEUED = new AtomicBoolean(false);

    private static volatile Context app;
    private static volatile long armedAt = 0L;
    private static volatile long lastFastScanAt = 0L;
    private static final int MAX_WATCHED_DIRS = 160;
    private static final long OPEN_RETRY_WINDOW_MS = 420L;
    private static final long QUIET_EOF_MS = 110L;
    private static final long MAX_CAPTURE_MS = 120_000L;

    private static final class Arm {
        final long messageId;
        final long notificationTime;
        final long armedAt;
        Arm(long id, long nt, long aa) { messageId=id; notificationTime=nt; armedAt=aa; }
    }

    private static final class CaptureSession {
        final String path;
        final File file;
        final long eventAt;
        volatile boolean closeSeen;
        volatile boolean deleteSeen;
        volatile boolean started;
        CaptureSession(File f, long when) { file=f; path=f.getAbsolutePath(); eventAt=when; }
    }

    public static boolean ensureHealthy(Context context) {
        if(context==null)return false; app=context.getApplicationContext();
        boolean needs=OBSERVERS.isEmpty();
        if(needs) start(app);
        return needs;
    }

    public static boolean isAvailable(Context context) {
        for (File root : roots()) {
            if (root.exists() && root.isDirectory() && root.canRead()) return true;
        }
        return false;
    }

    public static boolean isHealthy(){return !OBSERVERS.isEmpty();}

    public static void start(Context context) {
        if(context==null)return;
        app = context.getApplicationContext();
        if (armedAt == 0L) armedAt = System.currentTimeMillis();
        if(!OBSERVERS.isEmpty()||!START_QUEUED.compareAndSet(false,true))return;
        CONTROL.execute(() -> {
            try{
                attachBestDirectories();
                fastScanInternal(0L, 0L, true);
            }finally{START_QUEUED.set(false);}
        });
    }

    public static void stop() {
        synchronized (LOCK) {
            for (FileObserver o : OBSERVERS.values()) {
                try { o.stopWatching(); } catch (Throwable ignored) {}
            }
            OBSERVERS.clear();
        }
    }

    public static void armForMessage(Context context, long messageId, long notificationTime) {
        app = context.getApplicationContext();
        long nt = notificationTime > 0 ? notificationTime : System.currentTimeMillis();
        addArm(messageId, nt);
        try { new VaultDb(app).armPendingManualMedia(messageId,"audio",nt,10*60_000L); } catch(Throwable t){ try{new VaultDb(app).logCaptureFailure("AUDIO_ARM","audio",messageId,t);}catch(Throwable ignored){} }
        start(app);
        fastScanAsync(app, messageId, nt);
    }

    public static void fastScanAsync(Context context, long messageId, long notificationTime) {
        if(context==null)return;app = context.getApplicationContext();
        if (messageId > 0) addArm(messageId, notificationTime > 0 ? notificationTime : System.currentTimeMillis());
        if(!FAST_SCAN_QUEUED.compareAndSet(false,true))return;
        CONTROL.execute(() -> {try{fastScanInternal(0L,0L,false);}finally{FAST_SCAN_QUEUED.set(false);}});
    }

    private static void addArm(long messageId, long notificationTime) {
        if (messageId <= 0) return;
        long now = System.currentTimeMillis();
        Arm newest = ARMS.peekFirst();
        if (newest == null || newest.messageId != messageId || Math.abs(newest.notificationTime - notificationTime) > 10L) {
            ARMS.addFirst(new Arm(messageId, notificationTime, now));
        }
        while (ARMS.size() > 32) ARMS.pollLast();
        while (true) {
            Arm tail = ARMS.peekLast();
            if (tail == null || now - tail.armedAt < 5 * 60_000L) break;
            ARMS.pollLast();
        }
    }

    private static Arm uniqueArm(long eventTime) {
        Arm only=null;int candidates=0;
        for(Arm a:ARMS){
            long t=a.notificationTime>0?a.notificationTime:a.armedAt;
            long age=eventTime-t;if(age<-1500L||age>12_000L)continue;
            only=a;if(++candidates>1)return null;
        }
        return candidates==1?only:null;
    }

    private static void consumeArm(long messageId){if(messageId>0)ARMS.removeIf(a->a!=null&&a.messageId==messageId);}

    private static List<File> roots() {
        File base = Environment.getExternalStorageDirectory();
        List<File> out = new ArrayList<>();
        out.add(new File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"));
        out.add(new File(base, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"));
        out.add(new File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"));
        out.add(new File(base, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio"));
        out.add(new File(base, "Android/media/com.whatsapp.w4b/WhatsApp/Media/WhatsApp Voice Notes"));
        out.add(new File(base, "Android/media/com.whatsapp.w4b/WhatsApp/Media/WhatsApp Audio"));
        return out;
    }

    private static void attachBestDirectories() {
        if (app == null) return;
        List<File> dirs = new ArrayList<>();
        for (File root : roots()) {
            if (!root.exists() || !root.isDirectory() || !root.canRead()) continue;
            dirs.add(root);
            collectDirs(root, dirs, 3);
        }
        Collections.sort(dirs, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        int cap = Math.min(MAX_WATCHED_DIRS, dirs.size());
        for (int i=0;i<cap;i++) attach(dirs.get(i));
        prefs().edit()
                .putBoolean("direct_watcher_active", cap > 0)
                .putInt("direct_watcher_dirs", cap)
                .putLong("direct_watcher_started_at", System.currentTimeMillis())
                .putString("direct_watcher_engine", "EARLY_FD_V024")
                .apply();
    }

    private static void collectDirs(File parent, List<File> out, int depth) {
        if (depth <= 0 || parent == null) return;
        File[] children;
        try { children = parent.listFiles(File::isDirectory); } catch (Throwable t) { return; }
        if (children == null) return;
        Arrays.sort(children, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        int max = Math.min(children.length, 100);
        for (int i=0;i<max;i++) {
            out.add(children[i]);
            collectDirs(children[i], out, depth-1);
        }
    }

    private static void attach(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        final String key = dir.getAbsolutePath();
        synchronized (LOCK) {
            if (OBSERVERS.containsKey(key)) return;
            int mask = FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE |
                    FileObserver.MODIFY | FileObserver.DELETE | FileObserver.MOVED_FROM |
                    FileObserver.MOVE_SELF | FileObserver.DELETE_SELF;
            FileObserver observer = new FileObserver(key, mask) {
                @Override public void onEvent(int event, String path) {
                    if (app == null) return;
                    final int e = event & FileObserver.ALL_EVENTS;
                    if((e&(FileObserver.MOVE_SELF|FileObserver.DELETE_SELF))!=0){
                        OBSERVERS.remove(key);
                        if(START_QUEUED.compareAndSet(false,true))CONTROL.execute(()->{try{attachBestDirectories();}finally{START_QUEUED.set(false);}});
                        return;
                    }
                    if (path == null) return;
                    final File changed = new File(key, path);
                    final long now = System.currentTimeMillis();
                    try {
                        if (isAudio(changed.getName())) {
                            prefs().edit()
                                    .putLong("direct_watcher_last_event_at", now)
                                    .putString("direct_watcher_last_event", eventName(e) + " · audio")
                                    .apply();
                            if ((e & (FileObserver.DELETE | FileObserver.MOVED_FROM)) != 0) {
                                CaptureSession cs = CAPTURES.get(changed.getAbsolutePath());
                                if (cs != null) cs.deleteSeen = true;
                                return;
                            }
                            CaptureSession cs = CAPTURES.computeIfAbsent(changed.getAbsolutePath(), k -> new CaptureSession(changed, now));
                            if ((e & FileObserver.CLOSE_WRITE) != 0) cs.closeSeen = true;
                            startEarlyCapture(cs);
                            return;
                        }

                        // FileObserver is non-recursive. A new date subdirectory can be created
                        // milliseconds before the .opus inside it, so attach immediately and sweep
                        // that directory at 0/12/35 ms to close the race window.
                        if ((e & (FileObserver.CREATE | FileObserver.MOVED_TO)) != 0) {
                            CONTROL.execute(() -> {
                                if (changed.exists() && changed.isDirectory()) {
                                    attach(changed);
                                    sweepDirectory(changed);
                                    SystemClock.sleep(6L); sweepDirectory(changed);
                                    SystemClock.sleep(8L); sweepDirectory(changed);
                                    SystemClock.sleep(16L); sweepDirectory(changed);
                                    SystemClock.sleep(30L); sweepDirectory(changed);
                                }
                            });
                        }
                    } catch (Throwable t) { logFailure("VOICE_OBSERVER_EVENT",0L,t); }
                }
            };
            try {
                observer.startWatching();
                OBSERVERS.put(key, observer); // keep strong reference or Android stops events
            } catch (Throwable t) { logFailure("VOICE_OBSERVER_START",0L,t); }
        }
    }

    private static void sweepDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] files;
        try { files = dir.listFiles(f -> f.isFile() && isAudio(f.getName())); } catch (Throwable t) { return; }
        if (files == null) return;
        Arrays.sort(files, (a,b)->Long.compare(b.lastModified(), a.lastModified()));
        for (int i=0;i<Math.min(files.length, 12);i++) {
            File f = files[i];
            if (System.currentTimeMillis() - Math.max(f.lastModified(), 0L) < 120_000L) {
                CaptureSession cs = CAPTURES.computeIfAbsent(f.getAbsolutePath(), k -> new CaptureSession(f, System.currentTimeMillis()));
                startEarlyCapture(cs);
            }
        }
    }

    private static void startEarlyCapture(CaptureSession cs) {
        if (cs == null || cs.started) return;
        synchronized (cs) {
            if (cs.started) return;
            cs.started = true;
        }
        try{CAPTURE.execute(() -> captureGrowingFile(cs));}
        catch(RejectedExecutionException saturated){
            synchronized(cs){cs.started=false;}
            CAPTURES.remove(cs.path,cs);
            if(app!=null)app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("voice_capture_backpressure_at",System.currentTimeMillis()).apply();
        }
    }

    private static void captureGrowingFile(CaptureSession cs) {
        if (app == null || cs == null) return;
        long started = System.currentTimeMillis();
        FileInputStream in = null;
        FileOutputStream out = null;
        File staged = null;
        long written = 0L;
        long lastDataAt = started;
        try {
            // OPEN AS EARLY AS POSSIBLE. No size-stability wait.
            while (System.currentTimeMillis() - started <= OPEN_RETRY_WINDOW_MS) {
                try {
                    in = new FileInputStream(cs.file);
                    break;
                } catch (Throwable t) {
                    if (cs.deleteSeen || !cs.file.exists()) {
                        SystemClock.sleep(3L);
                    } else {
                        SystemClock.sleep(2L);
                    }
                }
            }
            if (in == null) return;

            // Keep all plaintext capture bytes inside the controlled recovery staging area.
            // Permanent vault directories only ever receive ciphertext.
            staged = MediaArchiver.newStagingPart(app, "audio");
            if (staged == null) return;
            out = new FileOutputStream(staged);
            byte[] buf = new byte[65536];

            while (System.currentTimeMillis() - started < MAX_CAPTURE_MS) {
                int n;
                try { n = in.read(buf); } catch (Throwable t) { n = -1; }
                if (n > 0) {
                    out.write(buf, 0, n);
                    written += n;
                    lastDataAt = System.currentTimeMillis();
                    continue;
                }

                long now = System.currentTimeMillis();
                // After EOF, keep the descriptor open briefly because WhatsApp may append more
                // bytes to the same inode. If the pathname is deleted, the open fd can still read
                // later writes until WhatsApp closes its writer.
                boolean quietEnough = now - lastDataAt >= QUIET_EOF_MS;
                if (quietEnough && (cs.closeSeen || cs.deleteSeen || !cs.file.exists() || now - started > 600L)) break;
                SystemClock.sleep(5L);
            }
            out.flush();
            out.getFD().sync();
        } catch (Throwable t) {
            try{new VaultDb(app).logCaptureFailure("AUDIO_EARLY_COPY","audio",0L,t);}catch(Throwable ignored){}
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }

        try {
            if (staged != null && staged.exists() && written > 256) {
                long sourceTime = cs.file.lastModified() > 0 ? cs.file.lastModified() : cs.eventAt;
                Arm arm = uniqueArm(sourceTime > 0 ? sourceTime : cs.eventAt);
                long msgId = arm == null ? 0L : arm.messageId;
                long notifTime = arm == null ? 0L : arm.notificationTime;
                File ready = MediaArchiver.completedPartToReady(
                        app, staged, "audio", msgId, sourceTime, VaultDb.RETENTION_PENDING,
                        System.currentTimeMillis() + 30_000L, "EARLY_FD_CAPTURE", cs.file.getName());
                if (ready == null) return;
                staged = ready;
                long id = MediaArchiver.registerEarlyCapturedAudio(app, staged, cs.path, cs.file.getName(), msgId, notifTime, sourceTime);
                if (id > 0) {
                    consumeArm(msgId);
                    prefs().edit()
                            .putLong("direct_watcher_last_copy_at", System.currentTimeMillis())
                            .putLong("direct_watcher_last_open_latency_ms", Math.max(0L, lastDataAt - cs.eventAt))
                            .putString("direct_watcher_last_capture", "EARLY_FD · audio · " + written + " bytes")
                            .apply();
                    staged = null; // ownership moved/consumed by MediaArchiver
                }
            }
        } catch (Throwable t) {
            try{new VaultDb(app).logCaptureFailure("AUDIO_COMMIT","audio",0L,t);}catch(Throwable ignored){}
        } finally {
            if (staged != null) try { staged.delete(); } catch (Throwable ignored) {}
            CAPTURES.remove(cs.path, cs);
            // If the path still exists but early validation failed, conventional ingest remains
            // available through fastScanInternal/CLOSE_WRITE fallback.
            if (cs.file.exists() && cs.file.isFile() && cs.file.length() > 256) {
                CONTROL.execute(() -> {
                    Arm a = uniqueArm(System.currentTimeMillis());
                    long fallbackId=MediaArchiver.ingestDirectAudio(app, cs.file, a == null ? 0L : a.messageId, a == null ? 0L : a.notificationTime);
                    if(fallbackId>0&&a!=null)consumeArm(a.messageId);
                });
            }
        }
    }

    private static void fastScanInternal(long messageId, long notificationTime, boolean startup) {
        if (app == null) return;
        long now = System.currentTimeMillis();
        if (!startup && now - lastFastScanAt < 10L) return;
        lastFastScanAt = now;
        if(OBSERVERS.isEmpty())attachBestDirectories();
        long threshold = startup ? now - 4_000L : now - 45_000L;
        int candidates = 0;
        for (File root : roots()) {
            if (!root.exists() || !root.canRead()) continue;
            List<File> dirs = new ArrayList<>();
            dirs.add(root);
            File[] subs = root.listFiles(File::isDirectory);
            if (subs != null) {
                Arrays.sort(subs, (a,b)->Long.compare(b.lastModified(), a.lastModified()));
                for (int i=0;i<Math.min(subs.length, 120);i++) dirs.add(subs[i]);
            }
            for (File d : dirs) {
                File[] files = d.listFiles(f -> f.isFile() && isAudio(f.getName()));
                if (files == null) continue;
                Arrays.sort(files, (a,b)->Long.compare(b.lastModified(), a.lastModified()));
                for (int i=0;i<Math.min(files.length, 48);i++) {
                    File f = files[i];
                    if (f.lastModified() >= threshold || (!startup && now - armedAt < 120_000L)) {
                        CaptureSession cs = CAPTURES.computeIfAbsent(f.getAbsolutePath(), k -> new CaptureSession(f, System.currentTimeMillis()));
                        startEarlyCapture(cs);
                        if (++candidates >= 180) break;
                    }
                }
                if (candidates >= 180) break;
            }
            if (candidates >= 180) break;
        }
        prefs().edit().putLong("direct_watcher_last_scan", now).putInt("direct_watcher_last_candidates", candidates).apply();
    }

    private static boolean isAudio(String name) {
        if (name == null) return false;
        String s = name.toLowerCase(Locale.ROOT);
        return s.endsWith(".opus") || s.endsWith(".ogg") || s.endsWith(".m4a") || s.endsWith(".mp3") || s.endsWith(".aac") || s.endsWith(".wav") || s.endsWith(".amr");
    }

    private static String eventName(int e) {
        if ((e & FileObserver.CLOSE_WRITE) != 0) return "CLOSE_WRITE";
        if ((e & FileObserver.MOVED_TO) != 0) return "MOVED_TO";
        if ((e & FileObserver.CREATE) != 0) return "CREATE";
        if ((e & FileObserver.MODIFY) != 0) return "MODIFY";
        if ((e & FileObserver.DELETE) != 0) return "DELETE";
        if ((e & FileObserver.MOVED_FROM) != 0) return "MOVED_FROM";
        return "EVENT_" + e;
    }

    private static void logFailure(String stage,long messageId,Throwable t){
        try{if(app!=null)new VaultDb(app).logCaptureFailure(stage,"audio",messageId,t);}catch(Throwable ignored){}
    }

    private static android.content.SharedPreferences prefs() {
        return app.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
    }
}
