package com.fer.a53performance;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class StorageAnalyzer {
    public interface Callback<T>{void onPhase(String phase);void onDone(T result);}
    public static final class DuplicateGroup{
        public final List<StorageItem> items;public final String keeperKey;public final Set<String> removableKeys;public final long recoverableBytes;
        DuplicateGroup(List<StorageItem> source,String keeperKey,Set<String> removableKeys,long recoverableBytes){this.items=Collections.unmodifiableList(new ArrayList<>(source));this.keeperKey=keeperKey;this.removableKeys=Collections.unmodifiableSet(new HashSet<>(removableKeys));this.recoverableBytes=recoverableBytes;}
    }
    public static final class DuplicateResult{
        public final Set<String> keys;public final int groups;public final List<DuplicateGroup> groupList;public final Set<String> safeDeleteKeys;public final long recoverableBytes;
        DuplicateResult(Set<String> keys,List<DuplicateGroup> groupList,Set<String> safeDeleteKeys,long recoverableBytes){this.keys=Collections.unmodifiableSet(new HashSet<>(keys));this.groupList=Collections.unmodifiableList(new ArrayList<>(groupList));this.groups=groupList.size();this.safeDeleteKeys=Collections.unmodifiableSet(new HashSet<>(safeDeleteKeys));this.recoverableBytes=recoverableBytes;}
    }
    public static final class SimilarGroup{public final List<StorageItem> items;SimilarGroup(List<StorageItem> source){items=Collections.unmodifiableList(new ArrayList<>(source));}}
    public static final class SimilarResult{
        public final Set<String> keys;public final int groups;public final List<SimilarGroup> groupList;
        SimilarResult(Set<String> keys,List<SimilarGroup> groupList){this.keys=Collections.unmodifiableSet(new HashSet<>(keys));this.groupList=Collections.unmodifiableList(new ArrayList<>(groupList));this.groups=groupList.size();}
    }

    private static final int QUICK_BLOCK=32*1024,VISUAL_BATCH=250;
    private final Context app;private final AnalysisCacheDb cache;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"a53-storage-analysis");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final AtomicInteger generation=new AtomicInteger();

    public StorageAnalyzer(Context context){app=context.getApplicationContext();cache=new AnalysisCacheDb(app);}
    public void analyzeDuplicatesAsync(List<StorageItem> input,Callback<DuplicateResult> callback){int g=generation.incrementAndGet();ArrayList<StorageItem> snapshot=new ArrayList<>(input);worker.execute(()->{callback.onPhase("Buscando duplicados…");DuplicateResult result=duplicates(snapshot,g,callback);if(g==generation.get())callback.onDone(result);});}
    public void analyzeSimilarAsync(List<StorageItem> input,Callback<SimilarResult> callback){analyzeSimilarAsync(input,null,callback);}
    public void analyzeSimilarAsync(List<StorageItem> input,DuplicateResult knownDuplicates,Callback<SimilarResult> callback){int g=generation.incrementAndGet();ArrayList<StorageItem> snapshot=new ArrayList<>(input);worker.execute(()->{callback.onPhase("Preparando fotos similares…");DuplicateResult dup=knownDuplicates==null?duplicates(snapshot,g,null):knownDuplicates;if(g!=generation.get())return;SimilarResult result=similar(snapshot,dup.keys,g,callback);if(g==generation.get())callback.onDone(result);});}
    public void cancel(){generation.incrementAndGet();}
    public void shutdown(){cancel();worker.shutdownNow();cache.close();}

    private DuplicateResult duplicates(List<StorageItem> items,int g,Callback<?> callback){
        HashMap<Long,ArrayList<StorageItem>> bySize=new HashMap<>();for(StorageItem x:items)if(x.size>=64*1024L)bySize.computeIfAbsent(x.size,k->new ArrayList<>()).add(x);
        HashSet<String> keys=new HashSet<>(),safeKeys=new HashSet<>();ArrayList<DuplicateGroup> groupList=new ArrayList<>();long recoverable=0L;int checked=0,total=items.size();
        for(ArrayList<StorageItem> sameSize:bySize.values()){
            if(g!=generation.get())break;if(sameSize.size()<2)continue;HashMap<String,ArrayList<StorageItem>> quickGroups=new HashMap<>();
            for(StorageItem x:sameSize){if(g!=generation.get())break;if(!thermalGate(g,callback,checked,total))return new DuplicateResult(keys,groupList,safeKeys,recoverable);String q=quickFingerprintCached(x);if(q==null)q="__fallback__";quickGroups.computeIfAbsent(q,k->new ArrayList<>()).add(x);checked++;}
            for(ArrayList<StorageItem> candidates:quickGroups.values()){
                if(g!=generation.get())break;if(candidates.size()<2)continue;HashMap<String,ArrayList<StorageItem>> hashes=new HashMap<>();
                for(StorageItem x:candidates){if(g!=generation.get())break;if(!thermalGate(g,callback,checked,total))return new DuplicateResult(keys,groupList,safeKeys,recoverable);String h=sha256Cached(x);if(h!=null)hashes.computeIfAbsent(h,k->new ArrayList<>()).add(x);checked++;}
                for(ArrayList<StorageItem> sameHash:hashes.values())if(sameHash.size()>1){
                    sameHash.sort(Comparator.comparingInt(StorageAnalyzer::keeperRank).thenComparingLong(x->x.modified).thenComparing(x->x.path));StorageItem keeper=sameHash.get(0);HashSet<String> removable=new HashSet<>();long groupBytes=0L;
                    for(int i=0;i<sameHash.size();i++){StorageItem x=sameHash.get(i);keys.add(x.stableKey());if(i>0){removable.add(x.stableKey());safeKeys.add(x.stableKey());groupBytes+=x.size;}}
                    recoverable+=groupBytes;groupList.add(new DuplicateGroup(sameHash,keeper.stableKey(),removable,groupBytes));
                }
            }
        }
        groupList.sort((a,b)->Long.compare(b.recoverableBytes,a.recoverableBytes));return new DuplicateResult(keys,groupList,safeKeys,recoverable);
    }

    private static int keeperRank(StorageItem x){
        String p=(x.path==null?"":x.path).replace('\\','/').toLowerCase(Locale.ROOT),n=(x.name==null?"":x.name).toLowerCase(Locale.ROOT);int rank=50;
        if(p.contains("/dcim/camera/"))rank=0;else if(p.contains("/dcim/"))rank=8;else if(p.contains("/pictures/"))rank=15;else if(p.contains("/movies/")||p.contains("/music/"))rank=22;else if(p.contains("/documents/"))rank=28;else if(p.contains("/download/"))rank=70;
        if(n.matches(".*\\([0-9]+\\)(\\.[^.]+)?$")||n.contains(" copy")||n.contains("copia")||n.contains("duplicad"))rank+=25;return rank;
    }

    private SimilarResult similar(List<StorageItem> items,Set<String> duplicates,int g,Callback<?> callback){
        ArrayList<PhotoSig> photos=new ArrayList<>();int totalImages=0;for(StorageItem x:items)if(x.isImage()&&x.size>0&&x.size<=200L*1024L*1024L)totalImages++;
        int done=0;for(StorageItem x:items){if(g!=generation.get())break;if(!x.isImage()||x.size<=0||x.size>200L*1024L*1024L)continue;if(!thermalGate(g,callback,done,totalImages))break;VisualSig sig=visualSigCached(x);if(sig!=null)photos.add(new PhotoSig(x,sig.dhash,sig.ahash,sig.aspect,sig.colorSig));done++;if(callback!=null&&(done%VISUAL_BATCH==0||done==totalImages))callback.onPhase("Fotos similares: "+done+"/"+totalImages+" firmas listas · progreso guardado");}
        if(g!=generation.get())return new SimilarResult(new HashSet<>(),Collections.emptyList());if(callback!=null)callback.onPhase("Agrupando "+photos.size()+" fotos con triple verificación perceptual…");
        ArrayList<Group> groups=new ArrayList<>();HashMap<Long,ArrayList<Integer>> buckets=new HashMap<>();
        for(int i=0;i<photos.size();i++){
            if(g!=generation.get())break;PhotoSig p=photos.get(i);HashSet<Integer> candidates=new HashSet<>();for(int seg=0;seg<8;seg++){int value=(int)((p.dhash>>>(seg*8))&0xffL);for(int a=p.aspect-2;a<=p.aspect+2;a++){ArrayList<Integer> ids=buckets.get(bucketKey(seg,value,a));if(ids!=null)candidates.addAll(ids);}}
            Group best=null;int bestScore=Integer.MAX_VALUE;for(int idx:candidates){Group group=groups.get(idx);int aspectDiff=Math.abs(p.aspect-group.rep.aspect);if(aspectDiff>2)continue;int dd=Long.bitCount(p.dhash^group.rep.dhash);if(dd>7)continue;int ad=Long.bitCount(p.ahash^group.rep.ahash);if(ad>10)continue;if(!colorCompatible(p.colorSig,group.rep.colorSig))continue;int score=dd*2+ad+aspectDiff*2+colorDistance(p.colorSig,group.rep.colorSig)/24;if(score<bestScore){best=group;bestScore=score;}}
            if(best==null){int idx=groups.size();Group ng=new Group(p);groups.add(ng);for(int seg=0;seg<8;seg++){int value=(int)((p.dhash>>>(seg*8))&0xffL);buckets.computeIfAbsent(bucketKey(seg,value,p.aspect),k->new ArrayList<>()).add(idx);}}else best.items.add(p);
            if((i&127)==0&&!thermalGate(g,callback,i,photos.size()))break;
        }
        HashSet<String> similarKeys=new HashSet<>();ArrayList<SimilarGroup> review=new ArrayList<>();
        for(Group group:groups){if(group.items.size()<2)continue;boolean nonExact=false;for(PhotoSig p:group.items)if(!duplicates.contains(p.item.stableKey())){nonExact=true;break;}if(!nonExact)continue;ArrayList<StorageItem> list=new ArrayList<>();for(PhotoSig p:group.items){similarKeys.add(p.item.stableKey());list.add(p.item);}list.sort(Comparator.comparingLong((StorageItem x)->x.modified).reversed());review.add(new SimilarGroup(list));}
        review.sort((a,b)->Integer.compare(b.items.size(),a.items.size()));return new SimilarResult(similarKeys,review);
    }

    private static int colorDistance(long a,long b){int ar=(int)((a>>>24)&255),ag=(int)((a>>>16)&255),ab=(int)((a>>>8)&255),br=(int)((b>>>24)&255),bg=(int)((b>>>16)&255),bb=(int)((b>>>8)&255);return Math.abs(ar-br)+Math.abs(ag-bg)+Math.abs(ab-bb);}
    private static boolean colorCompatible(long a,long b){int stdA=(int)(a&255),stdB=(int)(b&255);return colorDistance(a,b)<=140&&Math.abs(stdA-stdB)<=50;}
    private static long bucketKey(int seg,int value,int aspect){return(((long)aspect&0xffffL)<<16)|((seg&0xffL)<<8)|(value&0xffL);}
    private boolean thermalGate(int g,Callback<?> callback,int done,int total){if(Build.VERSION.SDK_INT<29)return g==generation.get();PowerManager pm=(PowerManager)app.getSystemService(Context.POWER_SERVICE);boolean announced=false;while(g==generation.get()){try{int s=pm.getCurrentThermalStatus();if(s<PowerManager.THERMAL_STATUS_SEVERE){if(s>=PowerManager.THERMAL_STATUS_MODERATE)Thread.sleep(70L);return true;}if(!announced&&callback!=null){callback.onPhase("Pausado por temperatura · "+done+"/"+total+" guardado");announced=true;}Thread.sleep(2500L);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}catch(Throwable ignored){return true;}}return false;}
    private String quickFingerprintCached(StorageItem item){String hit=cache.getQuick(item);if(hit!=null)return hit;String v=quickFingerprint(item);if(v!=null)cache.putQuick(item,v);return v;}
    private String sha256Cached(StorageItem item){String hit=cache.getSha(item);if(hit!=null)return hit;String v=sha256(item);if(v!=null)cache.putSha(item,v);return v;}
    private VisualSig visualSigCached(StorageItem item){Long d=cache.getDHash(item),a=cache.getAHash(item),c=cache.getColorSig(item);Integer aspect=cache.getAspect(item);if(d!=null&&a!=null&&aspect!=null&&c!=null)return new VisualSig(d,a,aspect,c);VisualSig v=visualSig(item);if(v!=null)cache.putVisual(item,v.dhash,v.ahash,v.aspect,v.colorSig);return v;}
    private String quickFingerprint(StorageItem item){try{MessageDigest md=MessageDigest.getInstance("SHA-256");md.update(ByteBuffer.allocate(8).putLong(item.size).array());long[] positions={0L,Math.max(0L,item.size/2L-QUICK_BLOCK/2L),Math.max(0L,item.size-QUICK_BLOCK)};if(item.uri!=null){try(ParcelFileDescriptor pfd=app.getContentResolver().openFileDescriptor(item.uri,"r")){if(pfd==null)return null;try(FileInputStream fis=new FileInputStream(pfd.getFileDescriptor());FileChannel ch=fis.getChannel()){for(long pos:positions)sample(ch,pos,md);}}}else if(item.path!=null&&!item.path.isBlank()){try(FileInputStream fis=new FileInputStream(item.path);FileChannel ch=fis.getChannel()){for(long pos:positions)sample(ch,pos,md);}}else return null;return hex(md.digest());}catch(Throwable ignored){return null;}}
    private static void sample(FileChannel ch,long position,MessageDigest md)throws Exception{ch.position(Math.max(0L,position));ByteBuffer buf=ByteBuffer.allocate(QUICK_BLOCK);int total=0;while(buf.hasRemaining()){int n=ch.read(buf);if(n<=0)break;total+=n;}md.update(ByteBuffer.allocate(8).putLong(position).array());md.update(ByteBuffer.allocate(4).putInt(total).array());md.update(buf.array(),0,total);}
    private String sha256(StorageItem item){try(InputStream raw=open(item);BufferedInputStream in=raw==null?null:new BufferedInputStream(raw,128*1024)){if(in==null)return null;MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[128*1024];int n;while((n=in.read(buffer))>0)md.update(buffer,0,n);return hex(md.digest());}catch(Throwable ignored){return null;}}
    private static String hex(byte[] digest){StringBuilder sb=new StringBuilder(digest.length*2);for(byte b:digest)sb.append(String.format("%02x",b&0xff));return sb.toString();}
    private VisualSig visualSig(StorageItem item){
        Bitmap decoded=null,oriented=null,tiny9=null,tiny8=null;try{
            int orientation=ExifInterface.ORIENTATION_NORMAL;try(InputStream exifIn=open(item)){if(exifIn!=null)orientation=new ExifInterface(exifIn).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(Throwable ignored){}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(InputStream in=open(item)){if(in==null)return null;BitmapFactory.decodeStream(in,null,bounds);}int w=bounds.outWidth,h=bounds.outHeight;if(w<=0||h<=0)return null;boolean swap=orientation==ExifInterface.ORIENTATION_TRANSPOSE||orientation==ExifInterface.ORIENTATION_ROTATE_90||orientation==ExifInterface.ORIENTATION_TRANSVERSE||orientation==ExifInterface.ORIENTATION_ROTATE_270;int ow=swap?h:w,oh=swap?w:h;int aspect=Math.round((ow/(float)oh)*100f);int max=Math.max(w,h),sample=1;while(max/sample>192&&sample<128)sample<<=1;
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=sample;opts.inPreferredConfig=Bitmap.Config.RGB_565;try(InputStream in=open(item)){if(in==null)return null;decoded=BitmapFactory.decodeStream(in,null,opts);}if(decoded==null)return null;oriented=applyOrientation(decoded,orientation);Bitmap src=oriented==null?decoded:oriented;
            tiny9=Bitmap.createScaledBitmap(src,9,8,true);long dh=0L;int bit=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++){if(luminance(tiny9.getPixel(x,y))>luminance(tiny9.getPixel(x+1,y)))dh|=(1L<<bit);bit++;}
            tiny8=Bitmap.createScaledBitmap(src,8,8,true);int[] lum=new int[64];long sum=0,sumSq=0,sumR=0,sumG=0,sumB=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++){int i=y*8+x,c=tiny8.getPixel(x,y),l=luminance(c);lum[i]=l;sum+=l;sumSq+=(long)l*l;sumR+=Color.red(c);sumG+=Color.green(c);sumB+=Color.blue(c);}int avg=(int)(sum/64L);long ah=0L;for(int i=0;i<64;i++)if(lum[i]>=avg)ah|=(1L<<i);double variance=Math.max(0d,sumSq/64d-avg*avg);int std=(int)Math.min(255,Math.round(Math.sqrt(variance)));int ar=(int)(sumR/64L),ag=(int)(sumG/64L),ab=(int)(sumB/64L);long colorSig=((long)ar<<24)|((long)ag<<16)|((long)ab<<8)|(std&255L);return new VisualSig(dh,ah,aspect,colorSig);
        }catch(Throwable ignored){return null;}finally{if(tiny9!=null&&tiny9!=decoded&&tiny9!=oriented&&!tiny9.isRecycled())tiny9.recycle();if(tiny8!=null&&tiny8!=decoded&&tiny8!=oriented&&!tiny8.isRecycled())tiny8.recycle();if(oriented!=null&&oriented!=decoded&&!oriented.isRecycled())oriented.recycle();if(decoded!=null&&!decoded.isRecycled())decoded.recycle();}}
    private static Bitmap applyOrientation(Bitmap src,int orientation){if(src==null)return null;Matrix m=new Matrix();switch(orientation){case ExifInterface.ORIENTATION_FLIP_HORIZONTAL->m.setScale(-1f,1f);case ExifInterface.ORIENTATION_ROTATE_180->m.setRotate(180f);case ExifInterface.ORIENTATION_FLIP_VERTICAL->m.setScale(1f,-1f);case ExifInterface.ORIENTATION_TRANSPOSE->{m.setRotate(90f);m.postScale(-1f,1f);}case ExifInterface.ORIENTATION_ROTATE_90->m.setRotate(90f);case ExifInterface.ORIENTATION_TRANSVERSE->{m.setRotate(-90f);m.postScale(-1f,1f);}case ExifInterface.ORIENTATION_ROTATE_270->m.setRotate(-90f);default->{return src;}}try{return Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);}catch(Throwable ignored){return src;}}
    private InputStream open(StorageItem item){ContentResolver cr=app.getContentResolver();try{if(item.uri!=null)return cr.openInputStream(item.uri);}catch(Throwable ignored){}try{if(item.path!=null&&!item.path.isBlank())return new FileInputStream(item.path);}catch(Throwable ignored){}return null;}
    private static int luminance(int c){return(Color.red(c)*299+Color.green(c)*587+Color.blue(c)*114)/1000;}
    private record VisualSig(long dhash,long ahash,int aspect,long colorSig){}
    private record PhotoSig(StorageItem item,long dhash,long ahash,int aspect,long colorSig){}
    private static final class Group{final PhotoSig rep;final ArrayList<PhotoSig> items=new ArrayList<>();Group(PhotoSig p){rep=p;items.add(p);}}
}
