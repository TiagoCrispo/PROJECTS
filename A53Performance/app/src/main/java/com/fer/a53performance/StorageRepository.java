package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageRepository {
    public interface ScanCallback{void onFinished(int generation,List<StorageItem> items,String error);}
    public interface DeleteCallback{void onFinished(List<StorageItem> deleted,List<StorageItem> failed);}
    public record VolumeStats(String volume,String label,long total,long free,boolean removable){public long used(){return Math.max(0L,total-free);}}

    private static final long FULL_RECONCILE_MS=6L*60L*60L*1000L;
    private static final String STATE_PREFS="a53_storage_state";
    private static final String LAST_FULL="last_full_reconcile_v11510";

    private final Context app;
    private final SharedPreferences state;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"a53-storage"));
    private final AtomicInteger scanGeneration=new AtomicInteger();
    private final Object lock=new Object();
    private final ArrayList<StorageItem> master=new ArrayList<>();
    private final StorageIndexDb indexDb;

    public StorageRepository(Context context){app=context.getApplicationContext();state=app.getSharedPreferences(STATE_PREFS,Context.MODE_PRIVATE);indexDb=new StorageIndexDb(app);try{master.addAll(indexDb.load());}catch(Throwable ignored){}}

    public boolean needsRefresh(){
        if(fullReconcileDue())return true;String current=mediaSignature();if(current.isBlank())return false;try{return !current.equals(indexDb.signature());}catch(Throwable ignored){return true;}
    }

    public int scanAsync(ScanCallback callback){
        int generation=scanGeneration.incrementAndGet();io.execute(()->{
            String signature=mediaSignature(),oldSignature="";List<StorageItem> cached=snapshot();boolean forceFull=fullReconcileDue()||cached.isEmpty();
            try{oldSignature=indexDb.signature();if(oldSignature.isBlank())forceFull=true;if(!forceFull&&!signature.isBlank()&&!cached.isEmpty()&&signature.equals(oldSignature)){callback.onFinished(generation,cached,null);return;}}catch(Throwable ignored){forceFull=true;}
            ArrayList<StorageItem> result=new ArrayList<>();String error=null;
            try{
                if(Build.VERSION.SDK_INT>=30)scanVolumesIncremental(generation,cached,oldSignature,result,forceFull);
                else if(!scanVolumeFull("external",generation,result))throw new IllegalStateException("scan");
            }catch(Throwable t){error=t.getClass().getSimpleName();}
            if(generation!=scanGeneration.get())return;
            result.sort((a,b)->Long.compare(b.modified,a.modified));
            synchronized(lock){master.clear();master.addAll(result);}
            try{indexDb.replaceAll(result,signature);if(forceFull&&error==null)state.edit().putLong(LAST_FULL,System.currentTimeMillis()).apply();}catch(Throwable t){if(error==null)error="Index:"+t.getClass().getSimpleName();}
            callback.onFinished(generation,new ArrayList<>(result),error);
        });return generation;
    }

    private boolean fullReconcileDue(){long last=state.getLong(LAST_FULL,0L);return last<=0L||System.currentTimeMillis()-last>=FULL_RECONCILE_MS;}

    private void scanVolumesIncremental(int generation,List<StorageItem> cached,String oldSignature,List<StorageItem> out,boolean forceFull){
        ArrayList<String> volumes=new ArrayList<>(MediaStore.getExternalVolumeNames(app));Collections.sort(volumes);Map<String,Long> old=parseSignature(oldSignature);Map<String,List<StorageItem>> cachedByVolume=new HashMap<>();
        for(StorageItem x:cached)cachedByVolume.computeIfAbsent(x.volume,k->new ArrayList<>()).add(x);
        for(String volume:volumes){
            if(generation!=scanGeneration.get())return;long currentGen=safeGeneration(volume),previous=old.getOrDefault(volume,-1L);List<StorageItem> prior=cachedByVolume.getOrDefault(volume,Collections.emptyList());
            if(!forceFull&&currentGen>=0&&previous==currentGen&&!prior.isEmpty()){out.addAll(prior);continue;}
            if(currentGen>=0&&previous>=0&&!prior.isEmpty()){
                List<StorageItem> merged=forceFull?scanVolumeReconciled(volume,previous,generation,prior):scanVolumeDelta(volume,previous,generation,prior);
                if(merged!=null){out.addAll(merged);continue;}
            }
            if(!scanVolumeFull(volume,generation,out))throw new IllegalStateException("scan:"+volume);
        }
    }

    private List<StorageItem> scanVolumeDelta(String volume,long previousGeneration,int generation,List<StorageItem> prior){
        LinkedHashMap<Long,StorageItem> merged=new LinkedHashMap<>();for(StorageItem x:prior)merged.put(x.id,x);ArrayList<StorageItem> changed=new ArrayList<>();String alive=MediaStore.MediaColumns.IS_TRASHED+"=0",selection=alive+" AND "+MediaStore.MediaColumns.GENERATION_MODIFIED+">?";
        if(!scanVolumeQuery(volume,generation,changed,selection,new String[]{Long.toString(previousGeneration)}))return null;for(StorageItem x:changed)merged.put(x.id,x);return new ArrayList<>(merged.values());
    }

    private List<StorageItem> scanVolumeReconciled(String volume,long previousGeneration,int generation,List<StorageItem> prior){
        ContentResolver cr=app.getContentResolver();Uri base=MediaStore.Files.getContentUri(volume);HashSet<Long> liveIds=new HashSet<>();String alive=MediaStore.MediaColumns.IS_TRASHED+"=0";
        try(Cursor c=cr.query(base,new String[]{MediaStore.Files.FileColumns._ID},alive,null,MediaStore.Files.FileColumns._ID+" ASC")){
            if(c==null)return null;int idIx=c.getColumnIndex(MediaStore.Files.FileColumns._ID);int n=0;while(c.moveToNext()){if((++n&1023)==0&&generation!=scanGeneration.get())return null;if(idIx>=0)liveIds.add(c.getLong(idIx));}
        }catch(Throwable t){return null;}
        LinkedHashMap<Long,StorageItem> merged=new LinkedHashMap<>();for(StorageItem x:prior)if(liveIds.contains(x.id))merged.put(x.id,x);
        ArrayList<StorageItem> changed=new ArrayList<>();String selection=alive+" AND "+MediaStore.MediaColumns.GENERATION_MODIFIED+">?";if(!scanVolumeQuery(volume,generation,changed,selection,new String[]{Long.toString(previousGeneration)}))return null;for(StorageItem x:changed)merged.put(x.id,x);
        if(merged.size()!=liveIds.size())return null;return new ArrayList<>(merged.values());
    }

    private boolean scanVolumeFull(String volume,int generation,List<StorageItem> out){String selection=Build.VERSION.SDK_INT>=30?MediaStore.MediaColumns.IS_TRASHED+"=0":null;return scanVolumeQuery(volume,generation,out,selection,null);}

    private boolean scanVolumeQuery(String volume,int generation,List<StorageItem> out,String selection,String[] args){
        ContentResolver cr=app.getContentResolver();Uri base=MediaStore.Files.getContentUri(volume);String[] projection={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DISPLAY_NAME,MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_MODIFIED,MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.DATA};
        try(Cursor c=cr.query(base,projection,selection,args,MediaStore.Files.FileColumns.DATE_MODIFIED+" DESC")){
            if(c==null)return false;int idIx=c.getColumnIndex(MediaStore.Files.FileColumns._ID),nameIx=c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME),sizeIx=c.getColumnIndex(MediaStore.Files.FileColumns.SIZE),dateIx=c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED),mimeIx=c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE),dataIx=c.getColumnIndex(MediaStore.Files.FileColumns.DATA);int n=0;
            while(c.moveToNext()){
                if((++n&255)==0&&generation!=scanGeneration.get())return false;long id=idIx>=0?c.getLong(idIx):n;String name=nameIx>=0?c.getString(nameIx):"";long size=sizeIx>=0?c.getLong(sizeIx):0,date=dateIx>=0?c.getLong(dateIx)*1000L:0;String mime=mimeIx>=0?c.getString(mimeIx):"",path=dataIx>=0?c.getString(dataIx):"";
                if(name==null||name.startsWith(".")||size<=0)continue;out.add(new StorageItem(id,volume,Uri.withAppendedPath(base,Long.toString(id)),name,path,mime,size,date));
            }
            return true;
        }catch(Throwable t){return false;}
    }

    private long safeGeneration(String volume){try{return MediaStore.getGeneration(app,volume);}catch(Throwable ignored){return -1L;}}
    private String mediaSignature(){
        if(Build.VERSION.SDK_INT<30)return"";try{ArrayList<String> volumes=new ArrayList<>(MediaStore.getExternalVolumeNames(app));Collections.sort(volumes);StringBuilder sb=new StringBuilder();for(String volume:volumes)sb.append(volume).append('=').append(MediaStore.getGeneration(app,volume)).append(';');return sb.toString();}catch(Throwable ignored){return"";}
    }
    private static Map<String,Long> parseSignature(String signature){HashMap<String,Long> out=new HashMap<>();if(signature==null)return out;for(String part:signature.split(";")){int eq=part.indexOf('=');if(eq<=0)continue;try{out.put(part.substring(0,eq),Long.parseLong(part.substring(eq+1)));}catch(Throwable ignored){}}return out;}

    public List<VolumeStats> volumeStats(){
        ArrayList<VolumeStats> out=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=30){
            try{StorageManager sm=(StorageManager)app.getSystemService(Context.STORAGE_SERVICE);for(StorageVolume sv:sm.getStorageVolumes()){File dir=sv.getDirectory();String volume=sv.getMediaStoreVolumeName();if(dir==null||volume==null||volume.isBlank())continue;String st=sv.getState();if(!Environment.MEDIA_MOUNTED.equals(st)&&!Environment.MEDIA_MOUNTED_READ_ONLY.equals(st))continue;StatFs fs=new StatFs(dir.getAbsolutePath());String label=sv.isPrimary()?"Interno":sv.isRemovable()?"microSD":sv.getDescription(app);out.add(new VolumeStats(volume,label,fs.getTotalBytes(),fs.getAvailableBytes(),sv.isRemovable()));}}catch(Throwable ignored){}
        }
        if(out.isEmpty())try{StatFs fs=new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());out.add(new VolumeStats("external","Interno",fs.getTotalBytes(),fs.getAvailableBytes(),false));}catch(Throwable ignored){}
        out.sort((a,b)->Boolean.compare(a.removable(),b.removable()));return out;
    }

    public String spaceSummary(){List<VolumeStats> stats=volumeStats();if(stats.isEmpty())return"Espacio no disponible";StringBuilder b=new StringBuilder();for(VolumeStats s:stats){if(b.length()>0)b.append("\n");b.append(s.label()).append(": ").append(FileAdapter.formatBytes(s.used())).append(" usados · ").append(FileAdapter.formatBytes(s.free())).append(" libres / ").append(FileAdapter.formatBytes(s.total()));}return b.toString();}
    public static boolean isPrimaryVolumeName(String volume){return volume==null||volume.isBlank()||"external".equals(volume)||MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(volume);}
    public static String volumeLabel(StorageItem item){return item==null||isPrimaryVolumeName(item.volume)?"Interno":"microSD";}
    public static boolean matchesVolume(StorageItem item,int mode){if(mode==1)return isPrimaryVolumeName(item.volume);if(mode==2)return !isPrimaryVolumeName(item.volume);return true;}

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
