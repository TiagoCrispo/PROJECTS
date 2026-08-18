package com.fer.wavault;

import android.content.Context;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v0.3.9 hot path. Its only job is: FILE EVENT -> OPEN FD -> COPY BYTES TO PRIVATE STAGING.
 * No SQLite, hashing, encryption, correlation or UI work is allowed before the descriptor is open.
 */
public final class FastCaptureEngine {
    private FastCaptureEngine() {}
    private static final Map<String,Long> ACTIVE=new ConcurrentHashMap<>();
    private static final AtomicLong VIDEO_HOT_UNTIL=new AtomicLong(0L);
    private static final AtomicLong IMAGE_HOT_UNTIL=new AtomicLong(0L);
    private static final ExecutorService COPY=VaultExecutors.bounded(
            4,32,"wa-vault-fast-copy",Thread.NORM_PRIORITY + 1);

    public static void enterHotMode(String type,long durationMs){
        long until=System.currentTimeMillis()+Math.max(3000L,durationMs);
        if("video".equals(type))VIDEO_HOT_UNTIL.accumulateAndGet(until,Math::max);
        else if("image".equals(type))IMAGE_HOT_UNTIL.accumulateAndGet(until,Math::max);
    }
    public static boolean isHot(String type){long now=System.currentTimeMillis();return "video".equals(type)?VIDEO_HOT_UNTIL.get()>now:IMAGE_HOT_UNTIL.get()>now;}
    public static boolean isCapturingPath(String path){return path!=null&&ACTIVE.containsKey(path);}

    public static boolean captureFromFileEvent(Context context,File file,String type,long eventTime,long linkedMessageId,long sourceTime){
        if(context==null||file==null||!("image".equals(type)||"video".equals(type)))return false;
        long initialBytes=Math.max(0L,file.length());
        if(MediaLimits.videoTooLarge(context,type,initialBytes)){MediaLimits.recordVideoLimit(context,linkedMessageId,sourceTime,initialBytes,"FILE_OBSERVER_PRECHECK");return false;}
        String path=file.getAbsolutePath();long now=System.currentTimeMillis();
        Long prior=ACTIVE.putIfAbsent(path,now);
        if(prior!=null&&now-prior<30_000L)return false;
        ACTIVE.put(path,now);
        final Context app=context.getApplicationContext();
        final long src=sourceTime>0?sourceTime:eventTime;
        final String family=familyKey(type,linkedMessageId,src,eventTime);
        final String trace="fast:"+Integer.toHexString(path.hashCode())+":"+now;
        CaptureMetrics.traceEvent(app,trace);
        RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.DETECTED,"Evento de archivo recibido",Math.max(0L,file.length()),linkedMessageId,src,path,0L,"FILE_OBSERVER");

        final FileInputStream in;
        try{
            in=new FileInputStream(file); // intentionally synchronous on FileObserver callback
            CaptureMetrics.traceStage(app,trace,"fd");
            RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.COPYING,"Descriptor abierto",Math.max(0L,file.length()),linkedMessageId,src,path,0L,"FILE_OBSERVER");
        }catch(Throwable t){
            ACTIVE.remove(path);
            RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.LOST,"OPEN_FAILED",0L,linkedMessageId,src,path,0L,"FILE_OBSERVER");
            CaptureProcessingEngine.recordFailureAsync(app,family,type,0L,"OPEN_FAILED",linkedMessageId,src,eventTime);
            return false;
        }

        final String sourceKey=Integer.toHexString((path+"|"+eventTime).hashCode());
        try {
            COPY.execute(()->copyOpened(app,in,file,type,trace,family,sourceKey,linkedMessageId,src,eventTime));
            return true;
        } catch (RejectedExecutionException saturated) {
            try{in.close();}catch(Throwable ignored){}
            ACTIVE.remove(path);
            RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.LOST,"CAPTURE_QUEUE_FULL",0L,linkedMessageId,src,path,0L,"FILE_OBSERVER");
            CaptureProcessingEngine.recordFailureAsync(app,family,type,0L,"CAPTURE_QUEUE_FULL",linkedMessageId,src,eventTime);
            return false;
        }
    }

    private static void copyOpened(Context app,FileInputStream in,File source,String type,String trace,String family,String sourceKey,long messageId,long sourceTime,long eventTime){
        try{Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);}catch(Throwable ignored){}
        long started=System.currentTimeMillis();long written=0L;long quiet=0L;
        File dir=stagingDir(app);
        File part=new File(dir,VaultFileNames.stagingName("fast_part_",type));
        boolean first=false,overVideoLimit=false;
        final long videoLimit=MediaLimits.maxVideoBytes(app);
        try(FileInputStream src=in;FileOutputStream out=new FileOutputStream(part)){
            byte[] buf=new byte[262144];
            long maxRun="video".equals(type)?30_000L:18_000L;
            while(System.currentTimeMillis()-started<maxRun){
                int n;try{n=src.read(buf);}catch(Throwable t){n=-1;}
                if(n>0){
                    if("video".equals(type) && written+n>videoLimit){overVideoLimit=true;written+=n;break;}
                    if(!first){first=true;CaptureMetrics.traceStage(app,trace,"first_byte");}
                    out.write(buf,0,n);written+=n;quiet=0L;continue;
                }
                long elapsed=System.currentTimeMillis()-started;
                long nap=elapsed<200L?3L:(elapsed<1500L?7L:18L);
                try{Thread.sleep(nap);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                quiet+=nap;
                long target="video".equals(type)?(isHot(type)?1600L:1000L):(isHot(type)?800L:450L);
                if(written>256L&&quiet>=target)break;
            }
            out.flush();
        }catch(Throwable t){
            try{part.delete();}catch(Throwable ignored){}
            CaptureProcessingEngine.recordFailureAsync(app,family,type,written,written<=0?"ZERO_BYTES":"COPY_IO_ERROR",messageId,sourceTime,eventTime);
            ACTIVE.remove(source.getAbsolutePath());return;
        }
        if(overVideoLimit){
            try{part.delete();}catch(Throwable ignored){}
            MediaLimits.recordVideoLimit(app,messageId,sourceTime,written,"FAST_CAPTURE");
            RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.LOST,"VIDEO_LIMIT_ADAPTIVE",written,messageId,sourceTime,"",0L,"FAST_CAPTURE");
            ACTIVE.remove(source.getAbsolutePath());return;
        }
        CaptureMetrics.traceStage(app,trace,"staging_ready");
        RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.VERIFYING,"Bytes asegurados; verificando integridad",written,messageId,sourceTime,part.getAbsolutePath(),0L,"FAST_CAPTURE");
        if(written<=0L){
            try{part.delete();}catch(Throwable ignored){}
            RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.LOST,"ZERO_BYTES",0L,messageId,sourceTime,"",0L,"FAST_CAPTURE");
            CaptureProcessingEngine.recordFailureAsync(app,family,type,0L,"ZERO_BYTES",messageId,sourceTime,eventTime);
            ACTIVE.remove(source.getAbsolutePath());return;
        }
        File ready=new File(dir,VaultFileNames.stagingName("fast_ready_",type));
        if(!part.renameTo(ready)){
            RecoveryLedger.stageAsync(app,trace,type,RecoveryLedger.LOST,"STAGING_RENAME_FAILED",written,messageId,sourceTime,part.getAbsolutePath(),0L,"FAST_CAPTURE");
            CaptureProcessingEngine.recordFailureAsync(app,family,type,written,"STAGING_RENAME_FAILED",messageId,sourceTime,eventTime);
            try{part.delete();}catch(Throwable ignored){}
            ACTIVE.remove(source.getAbsolutePath());return;
        }
        writeMeta(app,ready,family,sourceKey,type,messageId,sourceTime,eventTime,trace,written,source==null?"":source.getName());
        ACTIVE.remove(source.getAbsolutePath());
        CaptureProcessingEngine.enqueue(app,ready);
    }

    private static File stagingDir(Context app){File d=new File(app.getFilesDir(),"vault_staging");if(!d.exists())d.mkdirs();return d;}
    private static String familyKey(String type,long msg,long sourceTime,long eventTime){if(msg>0)return type+":m:"+msg;long t=sourceTime>0?sourceTime:eventTime;return type+":t:"+(t/1000L);}

    private static void writeMeta(Context app,File ready,String family,String sourceKey,String type,long messageId,long sourceTime,long eventTime,String trace,long bytes,String displayName){
        File meta=new File(ready.getAbsolutePath()+".meta");
        try(FileOutputStream out=new FileOutputStream(meta)){
            Properties p=new Properties();p.setProperty("family",family);p.setProperty("source_key",sourceKey);p.setProperty("type",type);p.setProperty("message_id",String.valueOf(messageId));p.setProperty("source_time",String.valueOf(sourceTime));p.setProperty("event_time",String.valueOf(eventTime));p.setProperty("trace",trace);p.setProperty("bytes",String.valueOf(bytes));p.setProperty("display_name",MetadataPrivacy.seal(app,displayName));p.store(out,"WA Vault fast capture metadata");out.flush();
        }catch(Throwable ignored){}
    }
}
