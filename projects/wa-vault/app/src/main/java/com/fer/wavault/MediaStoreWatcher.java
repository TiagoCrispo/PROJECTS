package com.fer.wavault;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Event-driven fallback for photos/videos downloaded manually from WhatsApp. */
public final class MediaStoreWatcher {
    private MediaStoreWatcher() {}
    private static final long ARM_WINDOW_MS = 10*60_000L;
    private static final Object LOCK = new Object();
    private static final Deque<Arm> ARMS = new ArrayDeque<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String,Long> CHANGE_GENERATIONS = new ConcurrentHashMap<>();
    private static final AtomicLong CHANGE_SEQUENCE = new AtomicLong(0L);
    private static final ExecutorService IO = VaultExecutors.bounded(
            1,128,"wa-vault-mediastore",Thread.NORM_PRIORITY+1);
    private static final ScheduledExecutorService RETRY = Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"wa-vault-mediastore-retry");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY+1);return t;});
    private static Context app; private static HandlerThread handlerThread; private static Handler handler; private static ContentObserver observer;
    private static final class Arm {final long messageId,time;final String type;Arm(long id,long t,String x){messageId=id;time=t;type=x==null?"":x;}}

    public static boolean ensureHealthy(Context context){
        if(context==null)return false;
        boolean broken=!STARTED.get() || handlerThread==null || !handlerThread.isAlive() || observer==null;
        if(broken){stopInternal();start(context);}
        return broken;
    }

    private static synchronized void stopInternal(){
        try{if(app!=null&&observer!=null)app.getContentResolver().unregisterContentObserver(observer);}catch(Throwable ignored){}
        try{if(handlerThread!=null)handlerThread.quitSafely();}catch(Throwable ignored){}
        STARTED.set(false);handlerThread=null;handler=null;observer=null;
        CHANGE_GENERATIONS.clear();
    }

    public static void start(Context context){
        if(context==null)return;app=context.getApplicationContext();if(!STARTED.compareAndSet(false,true))return;
        try{
            handlerThread=new HandlerThread("wa-vault-mediastore-events");handlerThread.start();handler=new Handler(handlerThread.getLooper());
            observer=new ContentObserver(handler){
                @Override public void onChange(boolean selfChange){onChange(selfChange,(Uri)null);}
                @Override public void onChange(boolean selfChange,Uri uri){scheduleRetries(uri,0);}
                @Override public void onChange(boolean selfChange,Uri uri,int flags){scheduleRetries(uri,flags);}
                @Override public void onChange(boolean selfChange, Collection<Uri> uris,int flags){
                    if(uris==null||uris.isEmpty()){scheduleRetries(null,flags);return;}
                    int n=0;for(Uri u:uris){scheduleRetries(u,flags);if(++n>=12)break;}
                }
            };
            ContentResolver cr=app.getContentResolver();
            cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer);
            cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,true,observer);
            try{cr.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,true,observer);}catch(Throwable ignored){}
            // Some OEM media scanners report the insert on MediaStore.Files instead of the
            // typed collection. Observing Files closes that gap; exact MIME/path filtering is
            // still done before anything is copied.
            try{cr.registerContentObserver(MediaStore.Files.getContentUri("external"),true,observer);}catch(Throwable ignored){}
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putBoolean("mediastore_active",true).putLong("mediastore_started_at",System.currentTimeMillis()).apply();
            record("MEDIASTORE_WATCHER_START","Observando imágenes/videos/audio y MediaStore.Files");
            submitIo(MediaStoreWatcher::recoverPersistedPending);
        }catch(Throwable t){stopInternal();app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putBoolean("mediastore_active",false).apply();record("MEDIASTORE_WATCHER_ERROR",t.getClass().getSimpleName());}
    }

    public static void armForMessage(Context context,long messageId,long notificationTime){armForMessage(context,messageId,notificationTime,"");}
    public static void armForMessage(Context context,long messageId,long notificationTime,String type){
        if(messageId<=0)return;start(context);long t=notificationTime>0?notificationTime:System.currentTimeMillis();
        synchronized(LOCK){ARMS.addFirst(new Arm(messageId,t,type));while(ARMS.size()>120)ARMS.removeLast();pruneLocked(System.currentTimeMillis());}
        if("image".equals(type)||"video".equals(type)||"document".equals(type))try{
            VaultDb db=new VaultDb(app);db.armPendingManualMedia(messageId,type,t,ARM_WINDOW_MS);
            db.reconcileRecentUnlinkedMedia(messageId,t,type);
        }catch(Throwable err){record("MEDIASTORE_ARM_DB_ERROR",err.getClass().getSimpleName());}
    }

    private static void scheduleRetries(Uri changed,int flags){
        if(app==null)return;
        String type=changedType(changed);
        boolean exact=isItemUri(changed);
        Arm active=uniqueArm(type,System.currentTimeMillis());
        // A generic MediaStore collection change with no WhatsApp capture context is ignored.
        // Exact item URIs are cheap to inspect because MediaArchiver rejects non-WhatsApp paths.
        if(!exact&&active==null)return;
        final String key=changed==null?"collection:"+type:changed.toString();
        final long generation=CHANGE_SEQUENCE.incrementAndGet();
        CHANGE_GENERATIONS.put(key,generation);
        long[] delays=exact?new long[]{0L,300L,1200L}:new long[]{0L,250L,1000L,3000L};
        for(int i=0;i<delays.length;i++){
            final long d=delays[i];final boolean last=i==delays.length-1;
            RETRY.schedule(()->{
                Long current=CHANGE_GENERATIONS.get(key);if(current==null||current.longValue()!=generation)return;
                if(!submitIo(()->{
                    Long still=CHANGE_GENERATIONS.get(key);if(still==null||still.longValue()!=generation)return;
                    try{captureChange(changed,flags);}finally{if(last)CHANGE_GENERATIONS.remove(key,generation);}
                }) && last){CHANGE_GENERATIONS.remove(key,generation);}
            },d,TimeUnit.MILLISECONDS);
        }
    }

    private static boolean submitIo(Runnable task){
        if(task==null)return false;
        try{IO.execute(task);return true;}
        catch(RejectedExecutionException saturated){
            if(app!=null)app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("mediastore_backpressure_at",System.currentTimeMillis()).apply();
            return false;
        }
    }

    private static void captureChange(Uri changed,int flags){
        String changedType=changedType(changed);
        Arm arm=uniqueArm(changedType,System.currentTimeMillis());
        // Preserve exact fresh WhatsApp MediaStore events even if notification correlation has
        // not arrived yet. They are stored unlinked and can be attached a moment later.
        long id=arm==null?0L:arm.messageId;
        long t=arm==null?System.currentTimeMillis():arm.time;
        int n=0;
        try{
            if(changed!=null&&isItemUri(changed)){
                long mediaId=MediaArchiver.captureMediaStoreAnyUri(app,changed,id,t);
                if(mediaId>0)n=1;
            }
            if(n==0){
                if("audio".equals(changedType) || (arm!=null && "audio".equals(arm.type))) {
                    n=MediaArchiver.scanRecentAudioAggressive(app,id,t);
                } else if(changedType.isEmpty()){
                    // Files notifications can hide the typed URI. If an audio arm is hot, prefer
                    // the aggressive audio path; otherwise scan image/video windows.
                    if(arm!=null && "audio".equals(arm.type)) n=MediaArchiver.scanRecentAudioAggressive(app,id,t);
                    else n=MediaArchiver.scanRecentDownloadedMedia(app,id,t,"");
                } else n=MediaArchiver.scanRecentDownloadedMedia(app,id,t,changedType);
            }
        }catch(Throwable err){record("MEDIASTORE_CAPTURE_ERROR",err.getClass().getSimpleName());}
        if(n>0&&arm!=null)consume(arm.messageId,changedType);
        app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                .putString("last_mediastore_event",(changed==null?"change":MetadataPrivacy.token(app,"uri",changed.toString()))+" · copiados="+n+" · flags="+flags)
                .putLong("last_mediastore_at",System.currentTimeMillis()).apply();
        if(n>0)record("MEDIASTORE_CAPTURE","Copiados="+n);
    }

    private static String changedType(Uri uri){
        if(uri==null)return "";String s=uri.toString().toLowerCase();
        if(s.contains("/images/")||s.contains("/images/media"))return "image";
        if(s.contains("/video/")||s.contains("/video/media"))return "video";
        if(s.contains("/audio/")||s.contains("/audio/media"))return "audio";
        return "";
    }

    private static Arm uniqueArm(String type,long eventTime){
        String wanted=type==null?"":type;
        synchronized(LOCK){
            pruneLocked(eventTime);
            Arm only=null;int count=0;
            for(Arm a:ARMS){
                long age=eventTime-a.time;if(age<-1500L||age>12_000L)continue;
                if(!wanted.isEmpty()&&!wanted.equals(a.type))continue;
                if(a.type==null||a.type.isEmpty())continue;
                only=a;if(++count>1)return null;
            }
            if(count==1)return only;
        }
        try{
            VaultDb db=new VaultDb(app);
            if(!wanted.isEmpty()){long id=db.findUniquePendingManualMessage(wanted,eventTime,12_000L);if(id>0){VaultDb.Msg m=db.getMessage(id);return new Arm(id,m==null?eventTime:m.timestamp,wanted);}}
            else{java.util.List<VaultDb.PendingManualMedia> ps=db.listPendingManualMedia("",30);VaultDb.PendingManualMedia only=null;int n=0;for(VaultDb.PendingManualMedia x:ps){long age=eventTime-x.armedAt;if(age<-1500L||age>12_000L)continue;only=x;if(++n>1)return null;}if(n==1&&only!=null)return new Arm(only.messageId,only.armedAt,only.type);}
        }catch(Throwable t){record("MEDIASTORE_ARM_ERROR",t.getClass().getSimpleName());}
        return null;
    }

    public static boolean isHealthy(){return STARTED.get()&&handlerThread!=null&&handlerThread.isAlive()&&observer!=null;}

    private static boolean isItemUri(Uri uri){
        if(uri==null)return false;String x=uri.getLastPathSegment();if(x==null||x.isEmpty())return false;
        for(int i=0;i<x.length();i++)if(!Character.isDigit(x.charAt(i)))return false;return true;
    }

    private static void consume(long id,String type){
        synchronized(LOCK){ARMS.removeIf(a->a.messageId==id&&(type==null||type.isEmpty()||a.type.isEmpty()||type.equals(a.type)));}
        try{new VaultDb(app).consumePendingManualMedia(id);}catch(Throwable ignored){}
    }
    private static void pruneLocked(long now){while(!ARMS.isEmpty()&&now-ARMS.peekLast().time>ARM_WINDOW_MS)ARMS.removeLast();}

    private static void recoverPersistedPending(){
        if(app==null)return;
        try{
            VaultDb db=new VaultDb(app);
            for(String type:new String[]{"image","video","document"}){
                java.util.List<VaultDb.PendingManualMedia> pending=db.listPendingManualMedia(type,8);
                if(pending.size()!=1)continue; // do not guess when several downloads are pending
                VaultDb.PendingManualMedia p=pending.get(0);
                int n=MediaArchiver.scanRecentDownloadedMedia(app,0L,p.armedAt,type);
                if(n>0)db.reconcilePendingMediaByOrder(type);
            }
        }catch(Throwable t){record("MEDIASTORE_RECOVERY_ERROR",t.getClass().getSimpleName());}
    }

    private static void record(String code,String detail){try{if(app!=null)new VaultDb(app).logEvent(code,detail,0L,0L);}catch(Throwable ignored){}}
}
