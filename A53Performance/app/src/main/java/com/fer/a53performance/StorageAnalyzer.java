package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageAnalyzer {
    public interface Callback<T>{void onPhase(String phase);void onDone(T result);}
    public static final class DuplicateResult{
        public final Set<String> keys;public final int groups;
        DuplicateResult(Set<String> keys,int groups){this.keys=Collections.unmodifiableSet(keys);this.groups=groups;}
    }
    public static final class SimilarResult{
        public final Set<String> keys;public final int groups;
        SimilarResult(Set<String> keys,int groups){this.keys=Collections.unmodifiableSet(keys);this.groups=groups;}
    }

    private static final int SIMILARITY_MAX_IMAGES=2500;
    private static final int QUICK_BLOCK=32*1024;
    private final Context app;
    private final AnalysisCacheDb cache;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"a53-storage-analysis");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final AtomicInteger generation=new AtomicInteger();

    public StorageAnalyzer(Context context){app=context.getApplicationContext();cache=new AnalysisCacheDb(app);}

    public void analyzeDuplicatesAsync(List<StorageItem> input,Callback<DuplicateResult> callback){
        int g=generation.incrementAndGet();ArrayList<StorageItem> snapshot=new ArrayList<>(input);
        worker.execute(()->{callback.onPhase("Buscando duplicados…");DuplicateResult result=duplicates(snapshot,g);if(g==generation.get())callback.onDone(result);});
    }

    public void analyzeSimilarAsync(List<StorageItem> input,Callback<SimilarResult> callback){
        int g=generation.incrementAndGet();ArrayList<StorageItem> snapshot=new ArrayList<>(input);
        worker.execute(()->{
            callback.onPhase("Preparando fotos similares…");DuplicateResult dup=duplicates(snapshot,g);
            if(g!=generation.get())return;callback.onPhase("Comparando fotos similares…");SimilarResult result=similar(snapshot,dup.keys,g);
            if(g==generation.get())callback.onDone(result);
        });
    }

    public void cancel(){generation.incrementAndGet();}
    public void shutdown(){cancel();worker.shutdownNow();cache.close();}

    private DuplicateResult duplicates(List<StorageItem> items,int g){
        HashMap<Long,ArrayList<StorageItem>> bySize=new HashMap<>();
        for(StorageItem x:items){if(x.size<64*1024L)continue;bySize.computeIfAbsent(x.size,k->new ArrayList<>()).add(x);}
        HashSet<String> duplicateKeys=new HashSet<>();int groups=0,checked=0;
        for(ArrayList<StorageItem> sameSize:bySize.values()){
            if(g!=generation.get())break;if(sameSize.size()<2)continue;
            HashMap<String,ArrayList<StorageItem>> quickGroups=new HashMap<>();
            for(StorageItem x:sameSize){
                if(g!=generation.get())break;String quick=quickFingerprintCached(x);if(quick==null)quick="__fallback__";
                quickGroups.computeIfAbsent(quick,k->new ArrayList<>()).add(x);if((++checked&31)==0)yieldForThermals();
            }
            for(ArrayList<StorageItem> candidates:quickGroups.values()){
                if(g!=generation.get())break;if(candidates.size()<2)continue;
                HashMap<String,ArrayList<StorageItem>> hashes=new HashMap<>();
                for(StorageItem x:candidates){String hash=sha256Cached(x);if(hash!=null)hashes.computeIfAbsent(hash,k->new ArrayList<>()).add(x);if((++checked&31)==0)yieldForThermals();}
                for(ArrayList<StorageItem> sameHash:hashes.values())if(sameHash.size()>1){groups++;for(StorageItem x:sameHash)duplicateKeys.add(x.stableKey());}
            }
        }
        return new DuplicateResult(duplicateKeys,groups);
    }

    private String quickFingerprintCached(StorageItem item){String hit=cache.getQuick(item);if(hit!=null)return hit;String value=quickFingerprint(item);if(value!=null)cache.putQuick(item,value);return value;}
    private String sha256Cached(StorageItem item){String hit=cache.getSha(item);if(hit!=null)return hit;String value=sha256(item);if(value!=null)cache.putSha(item,value);return value;}

    private String quickFingerprint(StorageItem item){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");md.update(ByteBuffer.allocate(8).putLong(item.size).array());
            long[] positions={0L,Math.max(0L,item.size/2L-QUICK_BLOCK/2L),Math.max(0L,item.size-QUICK_BLOCK)};
            if(item.uri!=null){
                try(ParcelFileDescriptor pfd=app.getContentResolver().openFileDescriptor(item.uri,"r")){
                    if(pfd==null)return null;try(FileInputStream fis=new FileInputStream(pfd.getFileDescriptor());FileChannel ch=fis.getChannel()){
                        for(long pos:positions)sample(ch,pos,md);
                    }
                }
            }else if(item.path!=null&&!item.path.isBlank()){
                try(FileInputStream fis=new FileInputStream(item.path);FileChannel ch=fis.getChannel()){for(long pos:positions)sample(ch,pos,md);}
            }else return null;
            return hex(md.digest());
        }catch(Throwable ignored){return null;}
    }

    private static void sample(FileChannel ch,long position,MessageDigest md)throws Exception{
        ch.position(Math.max(0L,position));ByteBuffer buf=ByteBuffer.allocate(QUICK_BLOCK);int total=0;
        while(buf.hasRemaining()){int n=ch.read(buf);if(n<=0)break;total+=n;}
        md.update(ByteBuffer.allocate(8).putLong(position).array());md.update(ByteBuffer.allocate(4).putInt(total).array());md.update(buf.array(),0,total);
    }

    private String sha256(StorageItem item){
        try(InputStream raw=open(item);BufferedInputStream in=raw==null?null:new BufferedInputStream(raw,128*1024)){
            if(in==null)return null;MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))>0)md.update(buffer,0,n);return hex(md.digest());
        }catch(Throwable ignored){return null;}
    }
    private static String hex(byte[] digest){StringBuilder sb=new StringBuilder(digest.length*2);for(byte b:digest)sb.append(String.format("%02x",b&0xff));return sb.toString();}

    private SimilarResult similar(List<StorageItem> items,Set<String> duplicates,int g){
        ArrayList<PhotoSig> photos=new ArrayList<>();int decoded=0;
        for(StorageItem x:items){
            if(g!=generation.get()||photos.size()>=SIMILARITY_MAX_IMAGES)break;
            if(!x.isImage()||x.size<=0||x.size>200L*1024L*1024L)continue;
            Long hash=dHashCached(x);if(hash!=null)photos.add(new PhotoSig(x,hash));if((++decoded&15)==0)yieldForThermals();
        }
        ArrayList<Group> groups=new ArrayList<>();HashMap<Integer,ArrayList<Integer>> buckets=new HashMap<>();
        for(int i=0;i<photos.size();i++){
            if(g!=generation.get())break;PhotoSig photo=photos.get(i);HashSet<Integer> candidates=new HashSet<>();
            for(int segment=0;segment<8;segment++){
                int value=(int)((photo.hash>>>(segment*8))&0xffL);ArrayList<Integer> ids=buckets.get((segment<<8)|value);if(ids!=null)candidates.addAll(ids);
            }
            Group best=null;int bestDistance=8;
            for(int groupIndex:candidates){Group group=groups.get(groupIndex);int d=Long.bitCount(photo.hash^group.representative.hash);if(d<=7&&d<bestDistance){best=group;bestDistance=d;if(d==0)break;}}
            if(best==null){
                int newIndex=groups.size();Group group=new Group(photo);groups.add(group);
                for(int segment=0;segment<8;segment++){int value=(int)((photo.hash>>>(segment*8))&0xffL);buckets.computeIfAbsent((segment<<8)|value,k->new ArrayList<>()).add(newIndex);}
            }else best.items.add(photo);
            if((i&31)==0)yieldForThermals();
        }
        HashSet<String> similarKeys=new HashSet<>();int count=0;
        for(Group group:groups){
            if(group.items.size()<2)continue;boolean hasNonExact=false;
            for(PhotoSig p:group.items)if(!duplicates.contains(p.item.stableKey())){hasNonExact=true;break;}
            if(!hasNonExact)continue;count++;for(PhotoSig p:group.items)similarKeys.add(p.item.stableKey());
        }
        return new SimilarResult(similarKeys,count);
    }

    private Long dHashCached(StorageItem item){Long hit=cache.getDHash(item);if(hit!=null)return hit;Long value=dHash(item);if(value!=null)cache.putDHash(item,value);return value;}
    private Long dHash(StorageItem item){
        Bitmap decoded=null,tiny=null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            try(InputStream in=open(item)){if(in==null)return null;BitmapFactory.decodeStream(in,null,bounds);}
            int max=Math.max(bounds.outWidth,bounds.outHeight);if(max<=0)return null;int sample=1;while(max/sample>160&&sample<128)sample<<=1;
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;opts.inPreferredConfig=Bitmap.Config.RGB_565;
            try(InputStream in=open(item)){if(in==null)return null;decoded=BitmapFactory.decodeStream(in,null,opts);}
            if(decoded==null)return null;tiny=Bitmap.createScaledBitmap(decoded,9,8,true);long hash=0L;int bit=0;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++){int left=luminance(tiny.getPixel(x,y)),right=luminance(tiny.getPixel(x+1,y));if(left>right)hash|=(1L<<bit);bit++;}
            return hash;
        }catch(Throwable ignored){return null;}
        finally{if(tiny!=null&&tiny!=decoded&&!tiny.isRecycled())tiny.recycle();if(decoded!=null&&!decoded.isRecycled())decoded.recycle();}
    }

    private void yieldForThermals(){
        if(Build.VERSION.SDK_INT<29)return;
        try{int s=((PowerManager)app.getSystemService(Context.POWER_SERVICE)).getCurrentThermalStatus();if(s>=PowerManager.THERMAL_STATUS_MODERATE)Thread.sleep(s>=PowerManager.THERMAL_STATUS_SEVERE?220L:90L);}catch(Throwable ignored){}
    }

    private InputStream open(StorageItem item){ContentResolver cr=app.getContentResolver();try{if(item.uri!=null)return cr.openInputStream(item.uri);}catch(Throwable ignored){}try{if(item.path!=null&&!item.path.isBlank())return new FileInputStream(item.path);}catch(Throwable ignored){}return null;}
    private static int luminance(int c){return(Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000;}
    private record PhotoSig(StorageItem item,long hash){}
    private static final class Group{final PhotoSig representative;final ArrayList<PhotoSig> items=new ArrayList<>();Group(PhotoSig p){representative=p;items.add(p);}}
}
