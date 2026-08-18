package com.fer.wavault;

import android.content.Context;
import android.os.StatFs;

/** Shared adaptive capture limits with a protected storage reserve. */
public final class MediaLimits {
    private MediaLimits() {}

    public static final long VIDEO_LIMIT_LOW = 40L * 1024L * 1024L;
    public static final long VIDEO_LIMIT_NORMAL = 100L * 1024L * 1024L;
    public static final long VIDEO_LIMIT_HIGH = 200L * 1024L * 1024L;

    public static final long DOCUMENT_LIMIT_LOW = 50L * 1024L * 1024L;
    public static final long DOCUMENT_LIMIT_NORMAL = 150L * 1024L * 1024L;
    public static final long DOCUMENT_LIMIT_HIGH = 300L * 1024L * 1024L;

    /** Keep at least this much private storage untouched even during repeated captures. */
    public static final long STORAGE_RESERVE_MIN = 768L * 1024L * 1024L;
    private static final long STORAGE_RESERVE_PERCENT = 8L;

    private static long[] storage(Context context){
        long free=0L,total=0L;
        try{
            if(context!=null){
                StatFs fs=new StatFs(context.getFilesDir().getAbsolutePath());
                free=fs.getAvailableBytes();
                total=fs.getTotalBytes();
            }
        }catch(Throwable ignored){}
        return new long[]{Math.max(0L,free),Math.max(0L,total)};
    }

    public static long reserveBytes(Context context){
        long[] s=storage(context);
        long ratio=s[1]>0L ? (s[1]/100L)*STORAGE_RESERVE_PERCENT : 0L;
        return Math.max(STORAGE_RESERVE_MIN,ratio);
    }

    public static long writableBytes(Context context){
        long[] s=storage(context);
        return Math.max(0L,s[0]-reserveBytes(context));
    }

    private static long clampToWritable(Context context,long tier){
        long writable=writableBytes(context);
        if(writable<=0L)return 0L;
        return Math.max(0L,Math.min(tier,writable));
    }

    /** Adaptive video cap based on free space while preserving the reserve. */
    public static long maxVideoBytes(Context context){
        long free=storage(context)[0];
        long tier;
        if(free<=0L)tier=VIDEO_LIMIT_LOW;
        else if(free<1500L*1024L*1024L)tier=VIDEO_LIMIT_LOW;
        else if(free<4L*1024L*1024L*1024L)tier=VIDEO_LIMIT_NORMAL;
        else tier=VIDEO_LIMIT_HIGH;
        return clampToWritable(context,tier);
    }

    /** Documents use the same streaming/reserve policy, with their own conservative tiers. */
    public static long maxDocumentBytes(Context context){
        long free=storage(context)[0];
        long tier;
        if(free<=0L)tier=DOCUMENT_LIMIT_LOW;
        else if(free<2L*1024L*1024L*1024L)tier=DOCUMENT_LIMIT_LOW;
        else if(free<6L*1024L*1024L*1024L)tier=DOCUMENT_LIMIT_NORMAL;
        else tier=DOCUMENT_LIMIT_HIGH;
        return clampToWritable(context,tier);
    }

    public static long maxBytes(Context context,String type){
        if("video".equals(type))return maxVideoBytes(context);
        if("document".equals(type))return maxDocumentBytes(context);
        return Long.MAX_VALUE;
    }

    public static boolean mediaTooLarge(Context context,String type,long bytes){
        if(!("video".equals(type)||"document".equals(type)))return false;
        long limit=maxBytes(context,type);
        return bytes>=0L && (limit<=0L || bytes>limit);
    }

    public static boolean videoTooLarge(Context context,String type,long bytes){
        return "video".equals(type) && mediaTooLarge(context,type,bytes);
    }

    public static boolean documentTooLarge(Context context,String type,long bytes){
        return "document".equals(type) && mediaTooLarge(context,type,bytes);
    }

    public static String limitLabel(Context context){
        long mb=maxVideoBytes(context)/(1024L*1024L);
        return mb+" MB";
    }

    public static String documentLimitLabel(Context context){
        long mb=maxDocumentBytes(context)/(1024L*1024L);
        return mb+" MB";
    }

    public static void recordVideoLimit(Context context,long messageId,long sourceTime,long observedBytes,String origin){
        recordLimit(context,"video",messageId,sourceTime,observedBytes,origin);
    }

    public static void recordDocumentLimit(Context context,long messageId,long sourceTime,long observedBytes,String origin){
        recordLimit(context,"document",messageId,sourceTime,observedBytes,origin);
    }

    public static void recordLimit(Context context,String type,long messageId,long sourceTime,long observedBytes,String origin){
        if(context==null)return;
        Context app=context.getApplicationContext();
        long limit=maxBytes(app,type);String label=(limit/(1024L*1024L))+" MB";
        try{
            VaultDb db=new VaultDb(app);
            if("video".equals(type) && messageId>0){
                db.ensureVideoPlaceholder(messageId,sourceTime,"Video no guardado · supera el límite actual de "+label,"VIDEO_LIMIT_PLACEHOLDER");
                db.markVideoPlaceholderLimited(messageId,observedBytes,limit);
            }else{
                String code="document".equals(type)?"DOCUMENT_LIMIT_EXCEEDED":"VIDEO_LIMIT_EXCEEDED";
                String name="document".equals(type)?"Documento":"Video";
                db.logEvent(code,name+" omitido por límite "+label+" · observado="+Math.max(0L,observedBytes)+" bytes · "+(origin==null?"":origin),messageId,0L);
            }
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putString("document".equals(type)?"last_document_limit":"last_video_limit","Omitido >"+label+" · observado="+Math.max(0L,observedBytes)+" B · "+(origin==null?"":origin))
                    .putLong("document".equals(type)?"last_document_limit_bytes":"last_video_limit_bytes",limit)
                    .putLong("document".equals(type)?"last_document_limit_at":"last_video_limit_at",System.currentTimeMillis()).apply();
        }catch(Throwable ignored){}
    }
}
