package com.fer.wavault;

import android.content.Context;
import android.os.Environment;
import android.os.FileObserver;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-latency watcher for photos/videos that the user downloads manually in WhatsApp.
 * The parent Media directory is watched too, so new WhatsApp Images/Video folders created
 * after WA Vault starts are discovered automatically.
 */
public final class DirectMediaWatcher {
    private DirectMediaWatcher() {}
    private static final long ACTIVE_ASSOCIATION_WINDOW_MS=12_000L;
    private static final long MANUAL_DOWNLOAD_WINDOW_MS=10*60_000L;
    private static final Map<String, FileObserver> OBS = new ConcurrentHashMap<>();
    private static final Map<String, Long> CAPTURE_STARTED = new ConcurrentHashMap<>();
    private static final ArrayDeque<Arm> ARMS = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static final ExecutorService IO = VaultExecutors.bounded(2,64,"wa-vault-media-watch",Thread.NORM_PRIORITY+1);
    private static final ScheduledExecutorService RETRY = Executors.newScheduledThreadPool(2, r->{Thread t=new Thread(r,"wa-vault-media-retry");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY+1);return t;});
    private static final AtomicBoolean ATTACH_QUEUED = new AtomicBoolean(false);
    private static final AtomicBoolean RECOVERY_QUEUED = new AtomicBoolean(false);
    private static final AtomicBoolean RECOVERING = new AtomicBoolean(false);
    private static final AtomicBoolean SAFETY_SCHEDULED = new AtomicBoolean(false);
    /** Latest notification-driven sparse recovery burst. New messages supersede older bursts because
     * recoverRecentFiles() sees the whole current arm set; this prevents N messages from creating N×6 scans. */
    private static final AtomicLong RECOVERY_BURST_GENERATION = new AtomicLong(0L);
    private static final long SAFETY_RETRY_MS = 15_000L;
    private static volatile Context app;

    private static class Arm { final long id, ts; final String type; Arm(long i,long t,String x){id=i;ts=t;type=x==null?"":x;} }

    public static void start(Context c){
        if(c==null)return;
        app=c.getApplicationContext();
        requestAttach();
        if(hasPendingMedia()){requestRecovery();requestSafetyPass(SAFETY_RETRY_MS);}
    }

    private static void requestAttach(){
        if(app==null||!ATTACH_QUEUED.compareAndSet(false,true))return;
        try{IO.execute(()->{try{attachAll();}finally{ATTACH_QUEUED.set(false);}});}
        catch(RejectedExecutionException saturated){ATTACH_QUEUED.set(false);RETRY.schedule(DirectMediaWatcher::requestAttach,500L,TimeUnit.MILLISECONDS);}
    }

    private static void requestRecovery(){
        if(app==null||!RECOVERY_QUEUED.compareAndSet(false,true))return;
        try{IO.execute(()->{try{recoverRecentFiles();}finally{RECOVERY_QUEUED.set(false);}});}
        catch(RejectedExecutionException saturated){RECOVERY_QUEUED.set(false);RETRY.schedule(DirectMediaWatcher::requestRecovery,750L,TimeUnit.MILLISECONDS);}
    }

    /** One-shot safety pass. It exists only while unresolved media work exists. */
    private static void requestSafetyPass(long delayMs){
        if(app==null||!hasPendingMedia()||!SAFETY_SCHEDULED.compareAndSet(false,true))return;
        RETRY.schedule(()->{
            SAFETY_SCHEDULED.set(false);
            requestRecovery();
            if(hasPendingMedia())requestSafetyPass(SAFETY_RETRY_MS);
        },Math.max(250L,delayMs),TimeUnit.MILLISECONDS);
    }

    public static boolean ensureHealthy(Context c){
        if(c==null)return false; app=c.getApplicationContext();
        boolean needs=OBS.isEmpty() || !app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).getBoolean("direct_media_active",false);
        if(needs) requestAttach();
        if(hasPendingMedia()){requestRecovery();requestSafetyPass(SAFETY_RETRY_MS);}
        return needs;
    }

    public static boolean isAvailable(Context c){
        if(c==null)return false;
        for(File parent:mediaParents()){
            try{if(parent.exists()&&parent.isDirectory()&&parent.canRead())return true;}catch(Throwable ignored){}
        }
        return false;
    }

    public static boolean isHealthy(){return !OBS.isEmpty();}

    public static void scanPendingNow(Context c){
        if(c==null)return;app=c.getApplicationContext();requestRecovery();
    }

    /** Explicit user-requested fallback scan. Unlike Inicio, this may walk the visible WhatsApp media tree. */
    public static void scanAllAvailableNow(Context c){
        if(c==null)return;app=c.getApplicationContext();try{IO.execute(()->{
            int[] budget={1500};
            int before=0;try{before=new VaultDb(app).getStats().savedFiles;}catch(Throwable ignored){}
            for(File parent:mediaParents()){
                if(budget[0]<=0)break;
                if(!parent.exists()||!parent.canRead())continue;File[] roots=parent.listFiles(File::isDirectory);if(roots==null)continue;
                for(File root:roots){if(budget[0]<=0)break;if(relevantTopDir(root.getName()))scanAvailableDir(root,0,budget);}
            }
            try{VaultDb db=new VaultDb(app);int after=db.getStats().savedFiles;db.logEvent("FALLBACK_MEDIA_SCAN","revisados="+(1500-budget[0])+" · nuevos="+Math.max(0,after-before),0L,0L);}catch(Throwable ignored){}
        });}catch(RejectedExecutionException saturated){app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putLong("manual_scan_backpressure_at",System.currentTimeMillis()).apply();}
    }

    /** Direct import for an explicit historical scan. No retry fan-out: each visible file is considered once. */
    private static void scanAvailableDir(File dir,int depth,int[] budget){
        if(dir==null||depth>5||budget==null||budget[0]<=0)return;
        File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){
            if(budget[0]<=0)return;
            if(f.isDirectory()){if(!f.getName().equalsIgnoreCase("Sent"))scanAvailableDir(f,depth+1,budget);continue;}
            String type=typeOfFile(f);if(type.isEmpty()||f.length()<=0)continue;
            budget[0]--;
            long ts=f.lastModified()>0?f.lastModified():System.currentTimeMillis();
            try{MediaArchiver.registerDirectDownloadedMedia(app,f,type,0L,ts);}catch(Throwable ignored){}
        }
    }

    public static void armForMessage(Context c,long messageId,long notificationTime){armForMessage(c,messageId,notificationTime,"");}
    public static void armForMessage(Context c,long messageId,long notificationTime,String type){
        if(c==null)return;app=c.getApplicationContext();
        long when=notificationTime>0?notificationTime:System.currentTimeMillis();
        if(messageId>0){
            synchronized(LOCK){ARMS.addFirst(new Arm(messageId,when,type));while(ARMS.size()>120)ARMS.removeLast();pruneLocked(System.currentTimeMillis());}
            if("image".equals(type)||"video".equals(type)||"document".equals(type)) {
                try { FastCaptureEngine.enterHotMode(type,"video".equals(type)?25_000L:15_000L); } catch(Throwable ignored) {}
                try {
                    VaultDb db = new VaultDb(app);
                    db.armPendingManualMedia(messageId,type,when,MANUAL_DOWNLOAD_WINDOW_MS);
                    // The filesystem event can beat notification parsing by a few milliseconds.
                    // If we already preserved a fresh unlinked file, attach it now instead of
                    // waiting for another MODIFY/CLOSE_WRITE event.
                    db.reconcileRecentUnlinkedMedia(messageId,when,type);
                } catch(Throwable t){try{new VaultDb(app).logCaptureFailure("DIRECT_MEDIA_ARM_DB",type,messageId,t);}catch(Throwable ignored){}}
            }
        }
        start(app);
        requestSafetyPass(SAFETY_RETRY_MS);
        // FileObserver is the primary instant path. These sparse rescans are only a safety net
        // for OEMs that occasionally coalesce/drop filesystem events.
        long generation=RECOVERY_BURST_GENERATION.incrementAndGet();
        long[] safety="video".equals(type)
                ? new long[]{0L,500L,2_000L,6_000L,15_000L}
                : new long[]{0L,500L,2_000L,6_000L,12_000L};
        for(long d:safety)RETRY.schedule(()->{
            if(RECOVERY_BURST_GENERATION.get()!=generation)return;
            requestRecovery();
        },d,TimeUnit.MILLISECONDS);
    }

    /** Hot-path correlation from RAM only. Never guesses by nearest timestamp.
     * A file may be assigned immediately only when exactly one typed message arm is active. */
    private static Arm uniqueTypedArmMemoryOnly(long eventTime,String type){
        synchronized(LOCK){
            pruneLocked(eventTime);
            Arm only=null;int count=0;
            for(Arm a:ARMS){
                if(a==null||a.type.isEmpty()||!a.type.equals(type))continue;
                long age=eventTime-a.ts;if(age<-1500L||age>ACTIVE_ASSOCIATION_WINDOW_MS)continue;
                only=a;if(++count>1)return null;
            }
            return count==1?only:null;
        }
    }

    private static Arm uniqueTypedArm(long eventTime,String type){
        Arm ram=uniqueTypedArmMemoryOnly(eventTime,type);
        if(ram!=null)return ram;
        try {
            VaultDb db=new VaultDb(app);
            long id=db.findUniquePendingManualMessage(type,eventTime,ACTIVE_ASSOCIATION_WINDOW_MS);
            if(id>0){VaultDb.Msg m=db.getMessage(id);long ts=m==null?eventTime:m.timestamp;return new Arm(id,ts,type);}
        } catch(Throwable t){logFailure("DIRECT_ARM_LOOKUP",type,0L,t);}
        return null;
    }

    private static void consume(long messageId,String type){
        if(messageId<=0)return;
        synchronized(LOCK){ARMS.removeIf(a->a.id==messageId&&(type==null||type.isEmpty()||a.type.isEmpty()||type.equals(a.type)));}
        try { new VaultDb(app).consumePendingManualMedia(messageId); } catch(Throwable ignored){}
    }
    private static void pruneLocked(long now){while(!ARMS.isEmpty()&&now-ARMS.peekLast().ts>MANUAL_DOWNLOAD_WINDOW_MS)ARMS.removeLast();}

    private static List<File> mediaParents(){
        File b=Environment.getExternalStorageDirectory();List<File> r=new ArrayList<>();
        r.add(new File(b,"Android/media/com.whatsapp/WhatsApp/Media"));
        r.add(new File(b,"Android/media/com.whatsapp.w4b/WhatsApp Business/Media"));
        r.add(new File(b,"Android/media/com.whatsapp.w4b/WhatsApp/Media"));
        return r;
    }

    private static boolean relevantTopDir(String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return n.equals("whatsapp images")||n.equals("whatsapp video")||n.equals("whatsapp animated gifs")||n.equals("whatsapp documents");
    }

    private static void attachAll(){
        if(app==null)return;int n=0;
        for(File parent:mediaParents()){
            if(!parent.exists()||!parent.isDirectory()||!parent.canRead())continue;
            attach(parent,true);n++;
            File[] roots=parent.listFiles(File::isDirectory);
            if(roots==null)continue;
            for(File root:roots){
                if(!relevantTopDir(root.getName()))continue;
                n += attachRecursive(root,3);
            }
        }
        app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("direct_media_dirs",n).putBoolean("direct_media_active",n>0).apply();
    }


    private static int attachRecursive(File dir,int depth){
        if(dir==null||depth<0||!dir.exists()||!dir.isDirectory())return 0;
        if(dir.getName().equalsIgnoreCase("Sent"))return 0;
        int n=0;
        if(!OBS.containsKey(dir.getAbsolutePath())){attach(dir,false);n++;}
        if(depth==0)return n;
        File[] subs=dir.listFiles(File::isDirectory);
        if(subs!=null)for(File d:subs)n+=attachRecursive(d,depth-1);
        return n;
    }

    private static boolean hasPendingMedia(){
        if(app==null)return false;
        try{return !new VaultDb(app).listPendingManualMedia("",1).isEmpty();}catch(Throwable t){return false;}
    }

    private static void attach(File dir, boolean isMediaParent){
        if(dir==null||OBS.containsKey(dir.getAbsolutePath()))return;
        String base=dir.getAbsolutePath();
        int mask=FileObserver.CREATE|FileObserver.MOVED_TO|FileObserver.CLOSE_WRITE|FileObserver.MODIFY|FileObserver.DELETE|FileObserver.MOVED_FROM|FileObserver.MOVE_SELF|FileObserver.DELETE_SELF;
        FileObserver o=new FileObserver(base,mask){@Override public void onEvent(int event,String path){
            if(app==null)return;
            if((event&(FileObserver.MOVE_SELF|FileObserver.DELETE_SELF))!=0){OBS.remove(base);requestAttach();return;}
            if(path==null)return;
            File f=new File(base,path);
            if(f.isDirectory()){
                if(isMediaParent && relevantTopDir(f.getName())) submitIoBestEffort(()->{attach(f,false);File[] subs=f.listFiles(File::isDirectory);if(subs!=null)for(File d:subs)if(!d.getName().equalsIgnoreCase("Sent"))attach(d,false);});
                else if(!isMediaParent&&!f.getName().equalsIgnoreCase("Sent")) submitIoBestEffort(()->attach(f,false));
                return;
            }
            String type=typeOfFile(f);if(type.isEmpty())return;
            long eventTime=System.currentTimeMillis();
            // v0.3.8: open the descriptor synchronously from the FileObserver callback.
            // This is deliberately before any executor hop/DB work. It also catches temporary
            // WhatsApp files whose final .mp4/.jpg name has not been assigned yet.
            boolean fastOpened=false;
            if((event&(FileObserver.CREATE|FileObserver.MOVED_TO|FileObserver.MODIFY))!=0 && ("image".equals(type)||"video".equals(type))){
                Arm early=uniqueTypedArmMemoryOnly(eventTime,type);
                try{fastOpened=FastCaptureEngine.captureFromFileEvent(app,f,type,eventTime,early==null?0L:early.id,early==null?eventTime:early.ts);}catch(Throwable t){try{new VaultDb(app).logCaptureFailure("DIRECT_FAST_OPEN",type,early==null?0L:early.id,t);}catch(Throwable ignored){}}
                if(fastOpened){
                    app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putString("last_direct_media","FastCapture · "+type+" · descriptor abierto").putLong("last_direct_media_at",eventTime).apply();
                }
            }
            if((event&(FileObserver.DELETE|FileObserver.MOVED_FROM))!=0){
                try{new VaultDb(app).logEvent("MEDIA_PATH_DISAPPEARED",type+" · "+f.getName(),0L,0L);}catch(Throwable ignored){}
                return;
            }
            // The legacy growing-file path is now fallback only. Give FastCaptureEngine first
            // chance to secure the descriptor without competing reads/SQLite work.
            if(fastOpened) RETRY.schedule(()->scheduleCapture(f,type,eventTime),2500L,TimeUnit.MILLISECONDS);
            else scheduleCapture(f,type,eventTime);
        }};
        try{o.startWatching();OBS.put(base,o);}catch(Throwable t){logFailure("DIRECT_MEDIA_OBSERVER_START","engine",0L,t);}
    }

    private static void scheduleCapture(File f,String type,long eventTime){
        if(f==null||type.isEmpty()||app==null)return;
        // Preserve first, correlate second. A FileObserver callback means the file is being
        // created/modified NOW inside WhatsApp's incoming media directories. Even if notification
        // parsing has not produced an arm yet, copy the bytes immediately as unlinked media; the
        // a later exact batch / unambiguous FIFO reconciliation can attach it safely. This closes
        // the race where opening the photo was
        // previously the only action that made capture succeed.
        Arm arm=uniqueTypedArm(eventTime,type);
        String key=f.getAbsolutePath();
        if(FastCaptureEngine.isCapturingPath(key))return;
        long now=System.currentTimeMillis();
        Long old=CAPTURE_STARTED.putIfAbsent(key,now);
        if(old!=null&&now-old<15_000L)return;
        CAPTURE_STARTED.put(key,now);
        final long msg=arm==null?0L:arm.id;
        final long sourceTime=arm==null?eventTime:arm.ts;
        final String metricKey="file:"+key;
        CaptureMetrics.markStart(app,metricKey);
        scheduleCaptureAttempt(f,type,key,msg,sourceTime,metricKey,0);
        RETRY.schedule(()->CAPTURE_STARTED.remove(key),35,TimeUnit.SECONDS);
    }

    // Relative gaps add up to about the same 15s rescue window as the old 11-way fan-out,
    // but only one delayed attempt per path exists at any time.
    private static final long[] CAPTURE_RETRY_GAPS_MS={0L,12L,23L,45L,80L,160L,380L,800L,1500L,4000L,8000L};

    private static void scheduleCaptureAttempt(File f,String type,String key,long msg,long sourceTime,String metricKey,int attempt){
        if(app==null||attempt>=CAPTURE_RETRY_GAPS_MS.length||!CAPTURE_STARTED.containsKey(key))return;
        RETRY.schedule(()->{
            if(!CAPTURE_STARTED.containsKey(key))return;
            if(!f.exists()||!f.isFile()){scheduleCaptureAttempt(f,type,key,msg,sourceTime,metricKey,attempt+1);return;}
            try{
                IO.execute(()->{
                    if(!CAPTURE_STARTED.containsKey(key))return;
                    boolean saved=false;
                    try{
                        long id=MediaArchiver.ingestGrowingDirectMedia(app,f,type,msg,sourceTime);
                        if(id>0){
                            saved=true;
                            CaptureMetrics.finish(app,metricKey,"file_capture");
                            CAPTURE_STARTED.remove(key);
                            if(msg>0)consume(msg,type);
                            new VaultDb(app).logEvent("FILE_OBSERVER_MEDIA",type+" · "+f.getName()+" · captura inmediata"+(msg>0?"":" · pendiente de asociar"),msg,id);
                        }
                    }catch(Throwable t){logFailure("DIRECT_MEDIA_CAPTURE",type,msg,t);}
                    if(!saved&&CAPTURE_STARTED.containsKey(key))scheduleCaptureAttempt(f,type,key,msg,sourceTime,metricKey,attempt+1);
                });
            }catch(RejectedExecutionException saturated){
                scheduleCaptureAttempt(f,type,key,msg,sourceTime,metricKey,attempt+1);
            }
        },CAPTURE_RETRY_GAPS_MS[attempt],TimeUnit.MILLISECONDS);
    }

    private static boolean submitIoBestEffort(Runnable task){
        if(task==null)return false;
        try{IO.execute(task);return true;}catch(RejectedExecutionException saturated){return false;}
    }

    /** Recover a manual download that happened while Android had killed the WA Vault process. */
    private static void recoverRecentFiles(){
        if(app==null||!RECOVERING.compareAndSet(false,true))return;
        try{
            long now=System.currentTimeMillis();long cutoff=now-MANUAL_DOWNLOAD_WINDOW_MS;
            try{
                List<VaultDb.PendingManualMedia> pending=new VaultDb(app).listPendingManualMedia("",40);
                if(pending.isEmpty())return;
                long earliest=now;for(VaultDb.PendingManualMedia x:pending)earliest=Math.min(earliest,x.armedAt);
                cutoff=Math.max(cutoff,earliest-5000L);
            }catch(Throwable ignored){}
            for(File parent:mediaParents()){
                if(!parent.exists()||!parent.canRead())continue;
                File[] roots=parent.listFiles(File::isDirectory);if(roots==null)continue;
                for(File root:roots){
                    if(!relevantTopDir(root.getName()))continue;
                    scanRecentDir(root,cutoff,0);
                }
            }
        }finally{RECOVERING.set(false);}
    }

    private static void scanRecentDir(File dir,long cutoff,int depth){
        if(dir==null||depth>4)return;
        File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){
            if(f.isDirectory()){
                if(!f.getName().equalsIgnoreCase("Sent"))scanRecentDir(f,cutoff,depth+1);
                continue;
            }
            if(f.lastModified()>0&&f.lastModified()<cutoff)continue;
            String type=typeOfFile(f);if(!type.isEmpty())scheduleCapture(f,type,f.lastModified()>0?f.lastModified():System.currentTimeMillis());
        }
    }


    private static void logFailure(String stage,String type,long messageId,Throwable t){
        try{if(app!=null)new VaultDb(app).logCaptureFailure(stage,type,messageId,t);}catch(Throwable ignored){}
    }
    private static String typeOfFile(File f){
        if(f==null)return "";
        String full=f.getAbsolutePath()==null?"":f.getAbsolutePath().toLowerCase(Locale.ROOT);
        if(full.contains(File.separator+"whatsapp stickers".toLowerCase(Locale.ROOT)+File.separator))return "";
        String byName=typeOf(f.getName());if(!byName.isEmpty())return byName;
        String n=f.getName()==null?"":f.getName().toLowerCase(Locale.ROOT);
        if(n.equals(".nomedia")||n.endsWith(".json")||n.endsWith(".db")||n.endsWith(".xml")||n.endsWith(".txt")||n.endsWith(".crypt14")||n.endsWith(".crypt15"))return "";
        String path=f.getAbsolutePath().toLowerCase(Locale.ROOT);
        if(path.contains(File.separator+"whatsapp video".toLowerCase(Locale.ROOT)+File.separator))return "video";
        if(path.contains(File.separator+"whatsapp images".toLowerCase(Locale.ROOT)+File.separator))return "image";
        if(path.contains(File.separator+"whatsapp animated gifs".toLowerCase(Locale.ROOT)+File.separator))return "image";
        if(path.contains(File.separator+"whatsapp stickers".toLowerCase(Locale.ROOT)+File.separator))return "";
        if(path.contains(File.separator+"whatsapp documents".toLowerCase(Locale.ROOT)+File.separator))return "document";
        return "";
    }

    private static String typeOf(String name){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".png")||n.endsWith(".webp")||n.endsWith(".gif")||n.endsWith(".heic")||n.endsWith(".heif"))return "image";
        if(n.endsWith(".mp4")||n.endsWith(".3gp")||n.endsWith(".webm")||n.endsWith(".mkv")||n.endsWith(".mov"))return "video";
        if(n.endsWith(".pdf")||n.endsWith(".doc")||n.endsWith(".docx")||n.endsWith(".xls")||n.endsWith(".xlsx")||n.endsWith(".ppt")||n.endsWith(".pptx")||n.endsWith(".zip")||n.endsWith(".rar")||n.endsWith(".7z")||n.endsWith(".csv")||n.endsWith(".rtf")||n.endsWith(".epub"))return "document";
        return "";
    }
}
