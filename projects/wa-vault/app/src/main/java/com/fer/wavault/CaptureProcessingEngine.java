package com.fer.wavault;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Heavy post-processing that intentionally runs after FastCaptureEngine has secured the bytes. */
public final class CaptureProcessingEngine {
    private CaptureProcessingEngine() {}
    private static final Set<String> INFLIGHT=ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORK=VaultExecutors.bounded(
            2,32,"wa-vault-processing",Thread.NORM_PRIORITY);
    private static final ExecutorService LOG=VaultExecutors.bounded(
            1,128,"wa-vault-capture-log",Thread.NORM_PRIORITY-1);

    public static void enqueue(Context context,File ready){
        if(context==null||ready==null)return;
        String key=ready.getAbsolutePath();if(!INFLIGHT.add(key))return;
        Context app=context.getApplicationContext();
        try{
            WORK.execute(()->{try{process(app,ready);}finally{INFLIGHT.remove(key);}});
        }catch(RejectedExecutionException saturated){
            // The ready file is already durable staging. Leave it untouched for CaptureRecovery.
            INFLIGHT.remove(key);
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("processing_backpressure_at",System.currentTimeMillis())
                    .putString("processing_backpressure","ready_queue_full")
                    .apply();
        }
    }
    public static void recoverReady(Context context,File ready){enqueue(context,ready);}

    public static void recordFailureAsync(Context context,String family,String type,long bytes,String reason,long messageId,long sourceTime,long eventTime){
        if(context==null)return;Context app=context.getApplicationContext();
        try{LOG.execute(()->{try{RecoveryLedger.stageAsync(app,"failure:"+family+":"+eventTime,type,RecoveryLedger.LOST,reason,bytes,messageId,sourceTime,"",0L,"FAST_CAPTURE");VaultDb db=new VaultDb(app);db.recordCaptureAttempt(family,type,bytes,"FAILED",reason,messageId,sourceTime,0L);db.logEvent("FAST_CAPTURE_FAIL",friendly(reason,type,bytes),messageId,0L);app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putString("last_capture_reason",friendly(reason,type,bytes)).putLong("last_capture_reason_at",System.currentTimeMillis()).apply();}catch(Throwable ignored){}});}
        catch(RejectedExecutionException saturated){app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putLong("capture_log_backpressure_at",System.currentTimeMillis()).apply();}
    }

    private static void process(Context app,File ready){
        Meta m=readMeta(app,ready);String type=m.type;
        if(type.isEmpty())type=ready.getName().contains("_video_")?"video":(ready.getName().contains("_image_")?"image":"");
        if(type.isEmpty()){cleanup(ready);return;}
        MediaValidation.Result vr=MediaValidation.validate(ready,type);
        VaultDb db=new VaultDb(app);
        if(vr.full()){
            long id=MediaArchiver.commitFastReadyStaged(app,ready,type,m.messageId,m.sourceTime,m.family,"FAST_CAPTURE_ENGINE",m.displayName);
            if(id>0){
                db.recordCaptureAttempt(m.family,type,m.bytes>0?m.bytes:0L,"FULL",vr.reason,m.messageId,m.sourceTime,id);
                db.resolveCaptureFamily(m.family,id);db.removeFamilyPreviews(m.family,id);
                db.logEvent("FAST_CAPTURE_FULL",type+" · "+friendly(vr.reason,type,m.bytes),m.messageId,id);
                CaptureMetrics.traceStage(app,m.trace,"commit");CaptureMetrics.finishTrace(app,m.trace);
                RecoveryLedger.stageAsync(app,m.trace,type,RecoveryLedger.SAVED,"Validado y guardado",m.bytes,m.messageId,m.sourceTime,ready.getAbsolutePath(),id,"FAST_CAPTURE_ENGINE");
                app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putString("last_capture_reason","Captura completa · "+type).putLong("last_direct_media_at",System.currentTimeMillis()).apply();
            }else db.recordCaptureAttempt(m.family,type,m.bytes,"FAILED","COMMIT_FAILED",m.messageId,m.sourceTime,0L);
            deleteMeta(ready);return;
        }

        if(vr.partial()){
            long bytes=ready.length();
            // Try to salvage a visual frame BEFORE moving/encrypting the partial file.
            if("video".equals(type))tryCreatePartialVideoPreview(app,ready,m);
            // Encrypt while the bytes are still in staging. A crash may leave plaintext only
            // in the temporary staging directory, never in the durable partial archive.
            boolean encrypted=false;try{encrypted=MediaCrypto.encryptInPlace(ready)&&MediaCrypto.isEncrypted(ready);}catch(Throwable ignored){}
            if(encrypted){
                File partialDir=new File(app.getFilesDir(),"vault_partial");if(!partialDir.exists())partialDir.mkdirs();
                File partial=new File(partialDir,VaultFileNames.stagingName("partial_",type));
                boolean moved=ready.renameTo(partial);if(!moved){moved=copy(ready,partial);if(moved)ready.delete();}
                if(moved&&MediaCrypto.isEncrypted(partial)){
                    db.recordCaptureAttempt(m.family,type,bytes,"PARTIAL",vr.reason,partial.getAbsolutePath(),m.messageId,m.sourceTime,0L);
                    db.logEvent("FAST_CAPTURE_PARTIAL",friendly(vr.reason,type,bytes),m.messageId,0L);
                    RecoveryLedger.stageAsync(app,m.trace,type,RecoveryLedger.CORRUPT,vr.reason,bytes,m.messageId,m.sourceTime,partial.getAbsolutePath(),0L,"FAST_CAPTURE_ENGINE");
                    app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putString("last_capture_reason","Recuperación parcial · "+humanType(type)+" · "+bytes+" bytes").putLong("last_capture_reason_at",System.currentTimeMillis()).apply();
                }else{
                    try{partial.delete();}catch(Throwable ignored){}
                    db.recordCaptureAttempt(m.family,type,bytes,"FAILED","PARTIAL_STORE_FAILED",m.messageId,m.sourceTime,0L);
                }
            }else{
                try{ready.delete();}catch(Throwable ignored){}
                db.recordCaptureAttempt(m.family,type,bytes,"FAILED","PARTIAL_ENCRYPTION_FAILED",m.messageId,m.sourceTime,0L);
                db.logEvent("FAST_CAPTURE_FAIL","Cifrado obligatorio no disponible para recuperación parcial",m.messageId,0L);
            }

            CaptureMetrics.traceStage(app,m.trace,"partial");CaptureMetrics.finishTrace(app,m.trace);
            deleteMeta(ready);return;
        }

        RecoveryLedger.stageAsync(app,m.trace,type,RecoveryLedger.LOST,vr.reason,ready.length(),m.messageId,m.sourceTime,ready.getAbsolutePath(),0L,"FAST_CAPTURE_ENGINE");
        db.recordCaptureAttempt(m.family,type,ready.length(),"FAILED",vr.reason,m.messageId,m.sourceTime,0L);
        db.logEvent("FAST_CAPTURE_FAIL",friendly(vr.reason,type,ready.length()),m.messageId,0L);
        app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putString("last_capture_reason",friendly(vr.reason,type,ready.length())).putLong("last_capture_reason_at",System.currentTimeMillis()).apply();
        cleanup(ready);CaptureMetrics.traceStage(app,m.trace,"failed");CaptureMetrics.finishTrace(app,m.trace);
    }

    private static void tryCreatePartialVideoPreview(Context app,File candidate,Meta m){
        try{
            // If ready has already been moved/encrypted there may be no readable candidate; this is best-effort only.
            if(candidate==null||!candidate.exists())return;
            File dir=new File(app.getFilesDir(),"vault_staging");if(!dir.exists())dir.mkdirs();File out=new File(dir,VaultFileNames.stagingName("ready_","image"));
            File preview=MediaValidation.extractVideoPreview(candidate,out);if(preview==null)return;
            long id=MediaArchiver.commitGeneratedPreviewStaged(app,preview,"video",m.messageId,m.sourceTime,
                    "partial-preview:"+m.family+":"+m.sourceTime,"NOTIFICATION_PREVIEW_FAST_PARTIAL","Vista previa de video recuperada.jpg");
            if(id>0)new VaultDb(app).logEvent("VIDEO_PREVIEW_SALVAGED","Vista previa extraída de captura parcial",m.messageId,id);
        }catch(Throwable ignored){}
    }

    private static boolean copy(File a,File b){try(FileInputStream in=new FileInputStream(a);FileOutputStream out=new FileOutputStream(b)){byte[] buf=new byte[131072];int n;while((n=in.read(buf))>0)out.write(buf,0,n);out.flush();out.getFD().sync();return b.length()>0;}catch(Throwable t){try{b.delete();}catch(Throwable ignored){}return false;}}
    private static void cleanup(File f){if(f!=null)try{f.delete();}catch(Throwable ignored){}deleteMeta(f);}
    private static void deleteMeta(File f){if(f!=null)try{new File(f.getAbsolutePath()+".meta").delete();}catch(Throwable ignored){}}

    private static final class Meta{String family="",type="",trace="",displayName="";long messageId=0L,sourceTime=0L,eventTime=0L,bytes=0L;}
    private static Meta readMeta(Context app,File ready){Meta m=new Meta();if(ready==null)return m;File f=new File(ready.getAbsolutePath()+".meta");if(!f.exists())return m;try(FileInputStream in=new FileInputStream(f)){Properties p=new Properties();p.load(in);m.family=p.getProperty("family","");m.type=p.getProperty("type","");m.trace=p.getProperty("trace","");m.displayName=MetadataPrivacy.open(app,p.getProperty("display_name",""));m.messageId=parse(p.getProperty("message_id"));m.sourceTime=parse(p.getProperty("source_time"));m.eventTime=parse(p.getProperty("event_time"));m.bytes=parse(p.getProperty("bytes"));}catch(Throwable ignored){}return m;}
    private static long parse(String s){try{return Long.parseLong(s);}catch(Throwable t){return 0L;}}
    private static String humanType(String t){return "video".equals(t)?"video":"foto";}
    public static String friendly(String reason,String type,long bytes){String r=reason==null?"":reason;String prefix=humanType(type)+" · ";if("OPEN_FAILED".equals(r))return prefix+"Android retiró el archivo antes de que pudiera abrirse";if("ZERO_BYTES".equals(r))return prefix+"se abrió, pero todavía tenía 0 bytes";if(r.contains("TRUNCATED")||r.contains("INCOMPLETE"))return prefix+"recuperación parcial · "+bytes+" bytes";if("FULL_CAPTURE".equals(r)||r.contains("PLAYABLE")||r.contains("DECODE_OK"))return prefix+"captura completa";return prefix+r.replace('_',' ').toLowerCase();}
}
