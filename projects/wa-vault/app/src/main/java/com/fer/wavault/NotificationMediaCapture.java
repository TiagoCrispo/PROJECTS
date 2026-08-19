package com.fer.wavault;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;

/** Opens notification media URIs immediately, before temporary grants can disappear. */
public final class NotificationMediaCapture {
    private NotificationMediaCapture() {}
    private static final ExecutorService COPY=VaultExecutors.bounded(
            3,24,"wa-vault-notif-media",Thread.NORM_PRIORITY + 1);
    private static final ConcurrentHashMap<String,Long> PENDING_LINKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> PENDING_AT = new ConcurrentHashMap<>();
    private static final long PENDING_TTL_MS = 120_000L;

    private static String pendingKey(String uri,long timestamp){return (uri==null?"":uri)+"|"+Math.max(0L,timestamp);}
    private static void rememberPending(String uri,long timestamp,long messageId){
        if(uri==null||uri.isEmpty()||messageId<=0)return;
        String key=pendingKey(uri,timestamp);long now=System.currentTimeMillis();
        PENDING_LINKS.put(key,messageId);PENDING_AT.put(key,now);prunePending(now);
    }
    private static void prunePending(long now){
        for(java.util.Map.Entry<String,Long> e:PENDING_AT.entrySet()){
            Long at=e.getValue();if(at==null||now-at>PENDING_TTL_MS){String stale=e.getKey();PENDING_AT.remove(stale,at);PENDING_LINKS.remove(stale);}
        }
    }
    /** Exact timestamp wins. If timestamp differs (notification-level URI), accept only one
     * unambiguous pending message for this URI; multiple candidates fail closed. */
    private static Long pendingMessage(String uri,long timestamp){
        if(uri==null)return null;long now=System.currentTimeMillis();prunePending(now);
        String exact=pendingKey(uri,timestamp);Long hit=PENDING_LINKS.get(exact);if(hit!=null)return hit;
        String prefix=uri+"|";Long only=null;int count=0;
        for(java.util.Map.Entry<String,Long> e:PENDING_LINKS.entrySet()){
            if(!e.getKey().startsWith(prefix))continue;Long at=PENDING_AT.get(e.getKey());if(at==null||now-at>PENDING_TTL_MS)continue;
            only=e.getValue();if(++count>1)return null;
        }
        return count==1?only:null;
    }
    private static void clearPendingForMessage(String uri,long messageId){
        if(uri==null||messageId<=0)return;String prefix=uri+"|";
        for(java.util.Map.Entry<String,Long> e:PENDING_LINKS.entrySet()){
            if(e.getKey().startsWith(prefix)&&Long.valueOf(messageId).equals(e.getValue())){String k=e.getKey();PENDING_LINKS.remove(k,e.getValue());PENDING_AT.remove(k);}
        }
    }

    public static void linkToMessage(Context context,Uri uri,long messageId){
        if(context==null||uri==null||messageId<=0)return;
        String key=uri.toString();
        String type="";try{String mime=context.getContentResolver().getType(uri);type=typeForMime(mime);}catch(Throwable ignored){}
        if("audio".equals(type)){NotificationAudioCapture.linkToMessage(context,uri,messageId);return;}
        try{VaultDb db=new VaultDb(context.getApplicationContext());VaultDb.Msg msg=db.getMessage(messageId);long ts=msg==null?0L:msg.timestamp;rememberPending(key,ts,messageId);long mediaId=ts>0?db.findMediaId("notif-media:"+key+"|"+ts):-1L;if(mediaId>0){db.linkMediaToMessageIfFree(mediaId,messageId);clearPendingForMessage(key,messageId);}}catch(Throwable ignored){}
    }

    public static boolean tryCaptureNow(Context context, Uri uri, String mime, long linkedMessageId, long notificationTime, long messageTime) {
        if(context==null||uri==null)return false;
        String type=typeForMime(mime);
        if("audio".equals(type)) return NotificationAudioCapture.tryCaptureNow(context,uri,mime,linkedMessageId,notificationTime,messageTime);
        if(!"image".equals(type)&&!"video".equals(type))return false;
        Context app=context.getApplicationContext();
        long start=SystemClock.elapsedRealtime();
        final ParcelFileDescriptor pfd;
        try{ContentResolver cr=app.getContentResolver();pfd=cr.openFileDescriptor(uri,"r");}
        catch(Throwable t){try{new VaultDb(app).logEvent("NOTIF_MEDIA_OPEN_FAIL",type+" · "+t.getClass().getSimpleName(),linkedMessageId,0L);}catch(Throwable ignored){}return false;}
        if(pfd==null)return false;
        long stat=-1L;try{stat=pfd.getStatSize();}catch(Throwable ignored){}
        if(MediaLimits.videoTooLarge(app,type,stat)){
            MediaLimits.recordVideoLimit(app,linkedMessageId,messageTime>0?messageTime:notificationTime,stat,"NOTIFICATION_URI_PRECHECK");
            try{pfd.close();}catch(Throwable ignored){}
            return true;
        }
        long openMs=Math.max(0L,SystemClock.elapsedRealtime()-start);
        try {
            COPY.execute(()->copy(app,pfd,uri,mime,type,linkedMessageId,notificationTime,messageTime,openMs));
            return true;
        } catch (RejectedExecutionException saturated) {
            try{pfd.close();}catch(Throwable ignored){}
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("notif_media_backpressure_at",System.currentTimeMillis())
                    .putString("notif_media_backpressure",type+":queue_full")
                    .apply();
            CaptureProcessingEngine.recordFailureAsync(app,"notification-media",type,0L,
                    "CAPTURE_QUEUE_FULL",linkedMessageId,
                    messageTime>0?messageTime:notificationTime,System.currentTimeMillis());
            return false;
        }
    }

    private static void copy(Context app,ParcelFileDescriptor pfd,Uri uri,String mime,String type,long messageId,long notificationTime,long messageTime,long openMs){
        File staged=null;long written=0;boolean overVideoLimit=false;
        final long videoLimit=MediaLimits.maxVideoBytes(app);
        try{
            staged=MediaArchiver.newStagingPart(app,type);if(staged==null)return;
            try(FileInputStream in=new FileInputStream(pfd.getFileDescriptor());FileOutputStream out=new FileOutputStream(staged)){
                byte[] buf=new byte[131072];int n;while((n=in.read(buf))>0){
                    if("video".equals(type) && written+n>videoLimit){overVideoLimit=true;written+=n;break;}
                    out.write(buf,0,n);written+=n;
                }out.flush();out.getFD().sync();
            }
        }catch(Throwable t){try{new VaultDb(app).logEvent("NOTIF_MEDIA_COPY_FAIL",type+" · "+t.getClass().getSimpleName(),messageId,0L);}catch(Throwable ignored){} }
        finally{try{pfd.close();}catch(Throwable ignored){}}
        if(overVideoLimit){
            if(staged!=null)try{staged.delete();}catch(Throwable ignored){}
            long ts=messageTime>0?messageTime:(notificationTime>0?notificationTime:System.currentTimeMillis());
            MediaLimits.recordVideoLimit(app,messageId,ts,written,"NOTIFICATION_URI_COPY");
            return;
        }
        if(staged==null||!staged.exists()||written<=128){if(staged!=null)try{staged.delete();}catch(Throwable ignored){}return;}
        long ts=messageTime>0?messageTime:(notificationTime>0?notificationTime:System.currentTimeMillis());
        long resolvedLink=messageId;
        if(resolvedLink<=0){Long pending=pendingMessage(uri.toString(),ts);if(pending!=null)resolvedLink=pending;}
        File ready=MediaArchiver.completedPartToReady(app,staged,type,resolvedLink,ts,VaultDb.RETENTION_NORMAL,0L,"NOTIFICATION_DATA_URI","recovered"+extension(mime,type));
        if(ready==null){try{staged.delete();}catch(Throwable ignored){}return;}
        staged=ready;
        long id=MediaArchiver.registerNotificationCapturedMedia(app,staged,uri.toString(),mime,type,resolvedLink,ts);
        if(id>0){
            if(resolvedLink>0)clearPendingForMessage(uri.toString(),resolvedLink);
            try{new VaultDb(app).logEvent("NOTIF_MEDIA_CAPTURED",type+" · "+written+" bytes · open="+openMs+"ms",resolvedLink,id);}catch(Throwable ignored){}
            staged=null;
        }
        if(staged!=null)try{staged.delete();}catch(Throwable ignored){}
    }

    private static String typeForMime(String mime){String m=mime==null?"":mime.toLowerCase(Locale.ROOT);if(m.startsWith("audio/")||m.contains("opus")||m.contains("ogg"))return "audio";if(m.startsWith("image/"))return "image";if(m.startsWith("video/"))return "video";return "";}
    private static String extension(String mime,String type){String m=mime==null?"":mime.toLowerCase(Locale.ROOT);if("image".equals(type)){if(m.contains("png"))return ".png";if(m.contains("webp"))return ".webp";if(m.contains("gif"))return ".gif";return ".jpg";}if("video".equals(type)){if(m.contains("webm"))return ".webm";return ".mp4";}return ".bin";}
}
