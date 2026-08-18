package com.fer.wavault;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
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

/**
 * Lowest-latency capture path for voice/audio attachments exposed directly by a
 * MessagingStyle notification. The important bit is that the content Uri is OPENED
 * synchronously while the notification is still alive. The already-open descriptor is
 * then copied on a dedicated worker even if WhatsApp revokes/unlinks the Uri moments later.
 */
public final class NotificationAudioCapture {
    private NotificationAudioCapture() {}

    private static final ExecutorService COPY = VaultExecutors.bounded(
            2, 24, "wa-vault-notif-audio", Thread.NORM_PRIORITY + 1);
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

    /** Link a descriptor opened before DB insertion to the message once its ID exists. */
    public static void linkToMessage(Context context, Uri uri, long messageId) {
        if(context==null||uri==null||messageId<=0)return;
        String key=uri.toString();
        try{VaultDb db=new VaultDb(context.getApplicationContext());VaultDb.Msg msg=db.getMessage(messageId);long ts=msg==null?0L:msg.timestamp;rememberPending(key,ts,messageId);long mediaId=ts>0?db.findMediaId("notif:"+key+"|"+ts):-1L;if(mediaId>0){db.linkMediaToMessageIfFree(mediaId,messageId);clearPendingForMessage(key,messageId);}}catch(Throwable ignored){}
    }

    public static boolean tryCaptureNow(
            Context context,
            Uri uri,
            String mime,
            long linkedMessageId,
            long notificationTime,
            long messageTime
    ) {
        if (context == null || uri == null) return false;
        final Context app = context.getApplicationContext();
        final SharedPreferences diag = app.getSharedPreferences("wa_vault_diag", Context.MODE_PRIVATE);
        final long seenAt = SystemClock.elapsedRealtime();
        final String rawMime = mime == null ? "" : mime.trim();
        diag.edit()
                .putLong("notif_audio_uri_seen_at", System.currentTimeMillis())
                .putString("notif_audio_uri", MetadataPrivacy.token(app,"uri",uri.toString()))
                .putString("notif_audio_mime", rawMime)
                .putString("notif_audio_status", "URI visto · abriendo ahora")
                .apply();

        // OPEN synchronously in onNotificationPosted/parse handling. This minimizes the race
        // against a sender deleting the voice note and WhatsApp revoking the temporary Uri grant.
        final ParcelFileDescriptor pfd;
        try {
            ContentResolver cr = app.getContentResolver();
            pfd = cr.openFileDescriptor(uri, "r");
        } catch (Throwable t) {
            diag.edit()
                    .putString("notif_audio_status", "URI visto pero Android/WhatsApp no permitió abrirlo")
                    .putString("notif_audio_error", t.getClass().getSimpleName())
                    .apply();
            return false;
        }
        if (pfd == null) {
            diag.edit().putString("notif_audio_status", "URI visto pero descriptor nulo").apply();
            return false;
        }

        final long openedInMs = Math.max(0L, SystemClock.elapsedRealtime() - seenAt);
        diag.edit()
                .putLong("notif_audio_open_latency_ms", openedInMs)
                .putString("notif_audio_status", "URI ABIERTO · copiando")
                .apply();

        try {
            COPY.execute(() -> copyOpenedDescriptor(app, pfd, uri, rawMime, linkedMessageId, notificationTime, messageTime, openedInMs));
            return true;
        } catch (RejectedExecutionException saturated) {
            try { pfd.close(); } catch (Throwable ignored) {}
            diag.edit()
                    .putLong("notif_audio_backpressure_at", System.currentTimeMillis())
                    .putString("notif_audio_status", "Descriptor cerrado: cola de captura saturada")
                    .apply();
            CaptureProcessingEngine.recordFailureAsync(app, "notification-audio", "audio", 0L,
                    "CAPTURE_QUEUE_FULL", linkedMessageId,
                    messageTime > 0 ? messageTime : notificationTime, System.currentTimeMillis());
            return false;
        }
    }

    private static void copyOpenedDescriptor(
            Context app,
            ParcelFileDescriptor pfd,
            Uri uri,
            String mime,
            long linkedMessageId,
            long notificationTime,
            long messageTime,
            long openedInMs
    ) {
        SharedPreferences diag = app.getSharedPreferences("wa_vault_diag", Context.MODE_PRIVATE);
        File staged = null;
        long written = 0L;
        try {
            String ext = extensionForMime(mime);
            staged = MediaArchiver.newStagingPart(app, "audio");
            if(staged==null)return;

            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(staged)) {
                byte[] buf = new byte[131072];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                }
                out.flush();
                out.getFD().sync();
            }
        } catch (Throwable t) {
            diag.edit()
                    .putString("notif_audio_status", "Descriptor abierto, pero falló la copia")
                    .putString("notif_audio_error", t.getClass().getSimpleName())
                    .putLong("notif_audio_bytes", written)
                    .apply();
        } finally {
            try { pfd.close(); } catch (Throwable ignored) {}
        }

        try {
            if (staged == null || !staged.exists() || written <= 128L) {
                if (staged != null) try { staged.delete(); } catch (Throwable ignored) {}
                if (written <= 128L) {
                    diag.edit()
                            .putString("notif_audio_status", "URI abierto pero sin audio suficiente")
                            .putLong("notif_audio_bytes", written)
                            .apply();
                }
                return;
            }

            long ts = messageTime > 0 ? messageTime : (notificationTime > 0 ? notificationTime : System.currentTimeMillis());
            long resolvedLink=linkedMessageId;
            if(resolvedLink<=0){Long pending=pendingMessage(uri.toString(),ts);if(pending!=null)resolvedLink=pending;}
            File ready=MediaArchiver.completedPartToReady(app,staged,"audio",resolvedLink,ts,VaultDb.RETENTION_PENDING,System.currentTimeMillis()+30_000L,"NOTIFICATION_DATA_URI","recovered"+extensionForMime(mime));
            if(ready==null){try{staged.delete();}catch(Throwable ignored){}return;}
            staged=ready;
            long id = MediaArchiver.registerNotificationCapturedAudio(
                    app,
                    staged,
                    uri.toString(),
                    mime,
                    resolvedLink,
                    notificationTime,
                    ts
            );
            if (id > 0) {
                if(resolvedLink>0)clearPendingForMessage(uri.toString(),resolvedLink);
                staged = null; // ownership transferred
                diag.edit()
                        .putString("notif_audio_status", "AUDIO COPIADO DESDE NOTIFICACIÓN")
                        .putLong("notif_audio_copied_at", System.currentTimeMillis())
                        .putLong("notif_audio_media_id", id)
                        .putLong("notif_audio_bytes", written)
                        .putLong("notif_audio_open_latency_ms", openedInMs)
                        .apply();
            } else {
                diag.edit()
                        .putString("notif_audio_status", "URI copiado pero no se pudo registrar")
                        .putLong("notif_audio_bytes", written)
                        .apply();
            }
        } catch (Throwable t) {
            diag.edit()
                    .putString("notif_audio_status", "Error registrando audio de notificación")
                    .putString("notif_audio_error", t.getClass().getSimpleName())
                    .apply();
        } finally {
            if (staged != null) try { staged.delete(); } catch (Throwable ignored) {}
        }
    }

    private static String extensionForMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("opus")) return ".opus";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("mpeg") || m.contains("mp3")) return ".mp3";
        if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return ".m4a";
        if (m.contains("amr")) return ".amr";
        if (m.contains("wav") || m.contains("wave")) return ".wav";
        return ".audio";
    }
}
