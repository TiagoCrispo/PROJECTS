from pathlib import Path
import sys
root=Path(sys.argv[1])

# Java lambda capture: keep URI in a final holder because the code is reached from an async callback.
p=root/'app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java'
s=p.read_text()
s=s.replace('Uri earlyAudioUri = null;', 'final Uri[] earlyAudioUri = new Uri[]{null};')
s=s.replace('earlyAudioUri = Uri.parse(audioContents.trim());', 'earlyAudioUri[0] = Uri.parse(audioContents.trim());')
s=s.replace('NotificationAudioCapture.tryCaptureNow(getApplicationContext(), earlyAudioUri, "audio/*", 0L, sbn.getPostTime(), sbn.getPostTime());', 'NotificationAudioCapture.tryCaptureNow(getApplicationContext(), earlyAudioUri[0], "audio/*", 0L, sbn.getPostTime(), sbn.getPostTime());')
s=s.replace('if (earlyAudioUri != null) {', 'if (earlyAudioUri[0] != null) {')
s=s.replace('NotificationAudioCapture.linkToMessage(getApplicationContext(), earlyAudioUri, linkId);', 'NotificationAudioCapture.linkToMessage(getApplicationContext(), earlyAudioUri[0], linkId);')
p.write_text(s)

# Explicit-final implementation for thumbnail worker; avoids OEM/JDK lambda-flow edge cases.
p=root/'app/src/main/java/com/fer/wavault/MediaThumbnailLoader.java'
p.write_text(r'''package com.fer.wavault;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MediaThumbnailLoader {
    private MediaThumbnailLoader() {}
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"wa-vault-thumb");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private static final Set<String> BUSY=ConcurrentHashMap.newKeySet();
    private static final LruCache<String,Bitmap> MEM=new LruCache<String,Bitmap>(20*1024){@Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getAllocationByteCount()/1024);}};

    public static void load(Context context,ImageView view,File encryptedFile,String type,int reqPx){
        if(context==null||view==null||encryptedFile==null||!encryptedFile.exists())return;
        final Context app=context.getApplicationContext();
        final ImageView target=view;
        final File source=encryptedFile;
        final String mediaType=type==null?"":type;
        final int size=Math.max(96,reqPx);
        final String key=source.getAbsolutePath()+"|"+source.length()+"|"+source.lastModified()+"|"+mediaType+"|"+size;
        final File disk=new File(new File(app.getCacheDir(),"wa_media_thumbs"),sha256(key)+".jpg");
        target.setTag(key);
        Bitmap cached=MEM.get(key);
        if(cached!=null&&!cached.isRecycled()){target.setImageBitmap(cached);return;}
        if(disk.exists()&&disk.length()>0){Bitmap b=BitmapFactory.decodeFile(disk.getAbsolutePath());if(b!=null){MEM.put(key,b);target.setImageBitmap(b);return;}}
        if(!BUSY.add(key))return;
        POOL.execute(new Runnable(){@Override public void run(){
            Bitmap built=null;File readable=null;
            try{
                readable=MediaCrypto.materialize(app,source,source.getName());
                if(readable!=null&&readable.exists())built=makeThumb(readable,mediaType,size);
                if(built!=null){
                    File parent=disk.getParentFile();if(parent!=null&&!parent.exists())parent.mkdirs();
                    try(FileOutputStream out=new FileOutputStream(disk)){built.compress(Bitmap.CompressFormat.JPEG,78,out);}catch(Throwable ignored){}
                    MEM.put(key,built);
                }
            }catch(Throwable ignored){}finally{
                if(readable!=null&&!readable.equals(source))try{readable.delete();}catch(Throwable ignored){}
                BUSY.remove(key);
            }
            final Bitmap deliver=built;
            MAIN.post(new Runnable(){@Override public void run(){Object tag=target.getTag();if(deliver!=null&&tag!=null&&key.equals(tag)&&!deliver.isRecycled())target.setImageBitmap(deliver);}});
        }});
    }

    private static Bitmap makeThumb(File f,String type,int req){
        if("video".equals(type)){
            MediaMetadataRetriever r=new MediaMetadataRetriever();
            try{r.setDataSource(f.getAbsolutePath());Bitmap b=r.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);return fit(b,req);}catch(Throwable ignored){return null;}finally{try{r.release();}catch(Throwable ignored){}}
        }
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        int sample=1;int max=Math.max(bounds.outWidth,bounds.outHeight);while(max/sample>req*2)sample*=2;
        BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=Math.max(1,sample);o.inPreferredConfig=Bitmap.Config.RGB_565;
        return fit(BitmapFactory.decodeFile(f.getAbsolutePath(),o),req);
    }

    private static Bitmap fit(Bitmap b,int req){
        if(b==null)return null;int w=Math.max(1,b.getWidth()),h=Math.max(1,b.getHeight());float scale=Math.min(1f,(req*1.4f)/Math.max(w,h));
        if(scale>=.99f)return b;int nw=Math.max(1,Math.round(w*scale)),nh=Math.max(1,Math.round(h*scale));Bitmap out=Bitmap.createScaledBitmap(b,nw,nh,true);if(out!=b)try{b.recycle();}catch(Throwable ignored){}return out;
    }

    private static String sha256(String s){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:d)out.append(String.format("%02x",b));return out.toString();}catch(Throwable t){return Integer.toHexString(s.hashCode());}}
}
''')
print('compile fixes applied')
