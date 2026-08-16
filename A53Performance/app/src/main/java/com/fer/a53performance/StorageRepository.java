package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageRepository {
    public interface ScanCallback{void onFinished(int generation,List<StorageItem> items,String error);}
    public interface DeleteCallback{void onFinished(List<StorageItem> deleted,List<StorageItem> failed);}

    private final Context app;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-storage"));
    private final AtomicInteger scanGeneration=new AtomicInteger();
    private final Object lock=new Object();
    private final ArrayList<StorageItem> master=new ArrayList<>();
    private final StorageIndexDb indexDb;

    public StorageRepository(Context context){
        app=context.getApplicationContext();indexDb=new StorageIndexDb(app);
        try{master.addAll(indexDb.load());}catch(Throwable ignored){}
    }

    public boolean needsRefresh(){
        String current=mediaSignature();if(current.isBlank())return false;
        try{return !current.equals(indexDb.signature());}catch(Throwable ignored){return true;}
    }

    public int scanAsync(ScanCallback callback){
        int generation=scanGeneration.incrementAndGet();io.execute(()->{
            String signature=mediaSignature();List<StorageItem> cached=snapshot();
            try{
                if(!signature.isBlank()&&!cached.isEmpty()&&signature.equals(indexDb.signature())){callback.onFinished(generation,cached,null);return;}
            }catch(Throwable ignored){}
            ArrayList<StorageItem> result=new ArrayList<>();String error=null;
            try{scanMediaStore(generation,result);}catch(Throwable t){error=t.getClass().getSimpleName();}
            if(generation!=scanGeneration.get())return;
            synchronized(lock){master.clear();master.addAll(result);}
            try{indexDb.replaceAll(result,signature);}catch(Throwable t){if(error==null)error="Index:"+t.getClass().getSimpleName();}
            callback.onFinished(generation,result,error);
        });return generation;
    }

    public void cancelScan(){scanGeneration.incrementAndGet();}

    private void scanMediaStore(int generation,List<StorageItem> out){
        ContentResolver cr=app.getContentResolver();Uri base=MediaStore.Files.getContentUri("external");
        String[] projection={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_MODIFIED,MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.DATA};
        String selection=Build.VERSION.SDK_INT>=30?MediaStore.MediaColumns.IS_TRASHED+"=0":null;
        try(Cursor c=cr.query(base,projection,selection,null,MediaStore.Files.FileColumns.DATE_MODIFIED+" DESC")){
            if(c==null)return;int idIx=c.getColumnIndex(MediaStore.Files.FileColumns._ID),nameIx=c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME),sizeIx=c.getColumnIndex(MediaStore.Files.FileColumns.SIZE),dateIx=c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED),mimeIx=c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE),dataIx=c.getColumnIndex(MediaStore.Files.FileColumns.DATA);int n=0;
            while(c.moveToNext()){
                if((++n&255)==0&&generation!=scanGeneration.get())return;long id=idIx>=0?c.getLong(idIx):n;String name=nameIx>=0?c.getString(nameIx):"";long size=sizeIx>=0?c.getLong(sizeIx):0,date=dateIx>=0?c.getLong(dateIx)*1000L:0;String mime=mimeIx>=0?c.getString(mimeIx):"",path=dataIx>=0?c.getString(dataIx):"";
                if(name==null||name.startsWith(".")||size<=0)continue;out.add(new StorageItem(id,Uri.withAppendedPath(base,Long.toString(id)),name,path,mime,size,date));
            }
        }
    }

    private String mediaSignature(){
        if(Build.VERSION.SDK_INT<30)return"";
        try{
            ArrayList<String> volumes=new ArrayList<>(MediaStore.getExternalVolumeNames(app));Collections.sort(volumes);StringBuilder sb=new StringBuilder();
            for(String volume:volumes)sb.append(volume).append('=').append(MediaStore.getGeneration(app,volume)).append(';');return sb.toString();
        }catch(Throwable ignored){return"";}
    }

    public void deleteAsync(Collection<StorageItem> selected,DeleteCallback callback){
        ArrayList<StorageItem> copy=new ArrayList<>(selected);io.execute(()->{
            ArrayList<StorageItem> deleted=new ArrayList<>(),failed=new ArrayList<>();ContentResolver cr=app.getContentResolver();
            for(StorageItem item:copy){
                boolean ok=false;try{if(item.uri!=null)ok=cr.delete(item.uri,null,null)>0;}catch(Throwable ignored){}
                if(!ok&&item.path!=null&&!item.path.isBlank()){try{File f=new File(item.path);ok=!f.exists()||f.delete();}catch(Throwable ignored){}}
                (ok?deleted:failed).add(item);
            }
            if(!deleted.isEmpty())removeFromIndex(deleted);callback.onFinished(deleted,failed);
        });
    }

    public void removeFromIndex(Collection<StorageItem> selected){
        if(selected==null||selected.isEmpty())return;Map<String,Boolean> gone=new HashMap<>();for(StorageItem x:selected)gone.put(x.stableKey(),true);
        synchronized(lock){master.removeIf(x->gone.containsKey(x.stableKey()));}
        try{indexDb.remove(selected);}catch(Throwable ignored){}
    }

    public List<StorageItem> snapshot(){synchronized(lock){return new ArrayList<>(master);}}
    public Set<String> indexedKeys(){try{return indexDb.keys();}catch(Throwable ignored){return Set.of();}}
    public static long freeBytes(){try{return new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath()).getAvailableBytes();}catch(Throwable ignored){return 0;}}
    public void shutdown(){cancelScan();io.shutdownNow();indexDb.close();}
}
