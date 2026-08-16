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
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    public StorageRepository(Context context){app=context.getApplicationContext();indexDb=new StorageIndexDb(app);try{master.addAll(indexDb.load());}catch(Throwable ignored){}}

    public boolean needsRefresh(){String current=mediaSignature();if(current.isBlank())return false;try{return !current.equals(indexDb.signature());}catch(Throwable ignored){return true;}}

    public int scanAsync(ScanCallback callback){
        int generation=scanGeneration.incrementAndGet();io.execute(()->{
            String signature=mediaSignature(),oldSignature="";List<StorageItem> cached=snapshot();
            try{oldSignature=indexDb.signature();if(!signature.isBlank()&&!cached.isEmpty()&&signature.equals(oldSignature)){callback.onFinished(generation,cached,null);return;}}catch(Throwable ignored){}
            ArrayList<StorageItem> result=new ArrayList<>();String error=null;
            try{
                if(Build.VERSION.SDK_INT>=30)scanVolumesIncremental(generation,cached,oldSignature,result);
                else scanVolumeFull("external",generation,result);
            }catch(Throwable t){error=t.getClass().getSimpleName();}
            if(generation!=scanGeneration.get())return;
            result.sort((a,b)->Long.compare(b.modified,a.modified));
            synchronized(lock){master.clear();master.addAll(result);}
            try{indexDb.replaceAll(result,signature);}catch(Throwable t){if(error==null)error="Index:"+t.getClass().getSimpleName();}
            callback.onFinished(generation,new ArrayList<>(result),error);
        });return generation;
    }

    private void scanVolumesIncremental(int generation,List<StorageItem> cached,String oldSignature,List<StorageItem> out){
        ArrayList<String> volumes=new ArrayList<>(MediaStore.getExternalVolumeNames(app));Collections.sort(volumes);Map<String,Long> old=parseSignature(oldSignature);Map<String,List<StorageItem>> cachedByVolume=new HashMap<>();
        for(StorageItem x:cached)cachedByVolume.computeIfAbsent(x.volume,k->new ArrayList<>()).add(x);
        for(String volume:volumes){
            if(generation!=scanGeneration.get())return;long currentGen=safeGeneration(volume),previous=old.getOrDefault(volume,-1L);List<StorageItem> prior=cachedByVolume.getOrDefault(volume,Collections.emptyList());
            if(currentGen>=0&&previous==currentGen&&!prior.isEmpty()){out.addAll(prior);continue;}
            if(currentGen>=0&&previous>=0&&currentGen>previous&&!prior.isEmpty()){
                List<StorageItem> merged=scanVolumeIncremental(volume,previous,generation,prior);
                if(merged!=null){out.addAll(merged);continue;}
            }
            scanVolumeFull(volume,generation,out);
        }
    }

    private List<StorageItem> scanVolumeIncremental(String volume,long previousGeneration,int generation,List<StorageItem> prior){
        ContentResolver cr=app.getContentResolver();Uri base=MediaStore.Files.getContentUri(volume);HashSet<Long> liveIds=new HashSet<>();
        String alive=MediaStore.MediaColumns.IS_TRASHED+"=0";
        try(Cursor c=cr.query(base,new String[]{MediaStore.Files.FileColumns._ID},alive,null,null)){
            if(c==null)return null;int idIx=c.getColumnIndex(MediaStore.Files.FileColumns._ID);int n=0;while(c.moveToNext()){if((++n&511)==0&&generation!=scanGeneration.get())return null;if(idIx>=0)liveIds.add(c.getLong(idIx));}
        }catch(Throwable t){return null;}
        LinkedHashMap<Long,StorageItem> merged=new LinkedHashMap<>();for(StorageItem x:prior)if(liveIds.contains(x.id))merged.put(x.id,x);
        ArrayList<StorageItem> changed=new ArrayList<>();String selection=alive+" AND "+MediaStore.MediaColumns.GENERATION_MODIFIED+">?";scanVolumeQuery(volume,generation,changed,selection,new String[]{Long.toString(previousGeneration)});
        for(StorageItem x:changed)merged.put(x.id,x);
        if(merged.size()!=liveIds.size())return null;
        return new ArrayList<>(merged.values());
    }

    private void scanVolumeFull(String volume,int generation,List<StorageItem> out){String selection=Build.VERSION.SDK_INT>=30?MediaStore.MediaColumns.IS_TRASHED+"=0":null;scanVolumeQuery(volume,generation,out,selection,null);}

    private void scanVolumeQuery(String volume,int generation,List<StorageItem> out,String selection,String[] args){
        ContentResolver cr=app.getContentResolver();Uri base=MediaStore.Files.getContentUri(volume);
        String[] projection={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_MODIFIED,MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.DATA};
        try(Cursor c=cr.query(base,projection,selection,args,MediaStore.Files.FileColumns.DATE_MODIFIED+" DESC")){
            if(c==null)return;int idIx=c.getColumnIndex(MediaStore.Files.FileColumns._ID),nameIx=c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME),sizeIx=c.getColumnIndex(MediaStore.Files.FileColumns.SIZE),dateIx=c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED),mimeIx=c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE),dataIx=c.getColumnIndex(MediaStore.Files.FileColumns.DATA);int n=0;
            while(c.moveToNext()){
                if((++n&255)==0&&generation!=scanGeneration.get())return;long id=idIx>=0?c.getLong(idIx):n;String name=nameIx>=0?c.getString(nameIx):"";long size=sizeIx>=0?c.getLong(sizeIx):0,date=dateIx>=0?c.getLong(dateIx)*1000L:0;String mime=mimeIx>=0?c.getString(mimeIx):"",path=dataIx>=0?c.getString(dataIx):"";
                if(name==null||name.startsWith(".")||size<=0)continue;out.add(new StorageItem(id,volume,Uri.withAppendedPath(base,Long.toString(id)),name,path,mime,size,date));
            }
        }
    }

    private long safeGeneration(String volume){try{return MediaStore.getGeneration(app,volume);}catch(Throwable ignored){return -1L;}}
    private String mediaSignature(){
        if(Build.VERSION.SDK_INT<30)return"";try{ArrayList<String> volumes=new ArrayList<>(MediaStore.getExternalVolumeNames(app));Collections.sort(volumes);StringBuilder sb=new StringBuilder();for(String volume:volumes)sb.append(volume).append('=').append(MediaStore.getGeneration(app,volume)).append(';');return sb.toString();}catch(Throwable ignored){return"";}
    }
    private static Map<String,Long> parseSignature(String signature){HashMap<String,Long> out=new HashMap<>();if(signature==null)return out;for(String part:signature.split(";")){int eq=part.indexOf('=');if(eq<=0)continue;try{out.put(part.substring(0,eq),Long.parseLong(part.substring(eq+1)));}catch(Throwable ignored){}}return out;}

    public void cancelScan(){scanGeneration.incrementAndGet();}
    public void deleteAsync(Collection<StorageItem> selected,DeleteCallback callback){
        ArrayList<StorageItem> copy=new ArrayList<>(selected);io.execute(()->{ArrayList<StorageItem> deleted=new ArrayList<>(),failed=new ArrayList<>();ContentResolver cr=app.getContentResolver();for(StorageItem item:copy){boolean ok=false;try{if(item.uri!=null)ok=cr.delete(item.uri,null,null)>0;}catch(Throwable ignored){}if(!ok&&item.path!=null&&!item.path.isBlank()){try{File f=new File(item.path);ok=!f.exists()||f.delete();}catch(Throwable ignored){}}(ok?deleted:failed).add(item);}if(!deleted.isEmpty())removeFromIndex(deleted);callback.onFinished(deleted,failed);});
    }
    public void removeFromIndex(Collection<StorageItem> selected){if(selected==null||selected.isEmpty())return;Map<String,Boolean> gone=new HashMap<>();for(StorageItem x:selected)gone.put(x.stableKey(),true);synchronized(lock){master.removeIf(x->gone.containsKey(x.stableKey()));}try{indexDb.remove(selected);}catch(Throwable ignored){}}
    public List<StorageItem> snapshot(){synchronized(lock){return new ArrayList<>(master);}}
    public Set<String> indexedKeys(){try{return indexDb.keys();}catch(Throwable ignored){return Collections.emptySet();}}
    public static long freeBytes(){try{return new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath()).getAvailableBytes();}catch(Throwable ignored){return 0;}}
    public void shutdown(){cancelScan();io.shutdownNow();indexDb.close();}
}
