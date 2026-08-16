package com.fer.a53performance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class StabilityInstrumentedTest {
    @Test public void tenThousandRowsKeepUniqueStableIdsAndSelectionAcrossPages(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();ThumbnailLoader thumbs=new ThumbnailLoader(context);
        try{FileAdapter adapter=new FileAdapter(thumbs,(count,bytes)->{});ArrayList<StorageItem> items=new ArrayList<>();HashSet<Long> ids=new HashSet<>();for(int i=0;i<10000;i++){String mime=(i%4==0)?"image/jpeg":"application/octet-stream";StorageItem item=new StorageItem(i+1L,"external_primary",Uri.parse("content://media/external_primary/file/"+(i+1L)),"file_"+i+".bin","/sdcard/Download/file_"+i+".bin",mime,1024L+i,1700000000000L+i);assertTrue("stable ID collision at "+i,ids.add(item.stableId()));items.add(item);}List<StorageItem> filtered=CleanerController.filterAndSort(items,"file_9",0,0,Collections.emptySet(),Collections.emptySet());assertTrue(filtered.size()>100);adapter.setSelection(items.subList(0,120));adapter.replace(new ArrayList<>(items.subList(0,60)));assertEquals(60,adapter.getItemCount());assertEquals(120,adapter.selectedKeys().size());adapter.append(new ArrayList<>(items.subList(60,120)));assertEquals(120,adapter.getItemCount());assertEquals(120,adapter.selectedItems(items).size());}finally{thumbs.shutdown();}
    }

    @Test public void sameMediaIdOnDifferentVolumesNeverSharesStableId(){StorageItem internal=new StorageItem(42L,"external_primary",Uri.parse("content://media/external_primary/file/42"),"a.jpg","/storage/emulated/0/DCIM/a.jpg","image/jpeg",10,1);StorageItem sd=new StorageItem(42L,"1234-5678",Uri.parse("content://media/1234-5678/file/42"),"a.jpg","/storage/1234-5678/DCIM/a.jpg","image/jpeg",10,1);assertNotEquals(internal.stableKey(),sd.stableKey());assertNotEquals(internal.stableId(),sd.stableId());}

    @Test public void volumeFilteringSeparatesInternalAndSd(){StorageItem internal=new StorageItem(1L,"external_primary",Uri.parse("content://media/external_primary/file/1"),"a.jpg","/storage/emulated/0/DCIM/a.jpg","image/jpeg",10,1);StorageItem sd=new StorageItem(2L,"1234-5678",Uri.parse("content://media/1234-5678/file/2"),"b.jpg","/storage/1234-5678/DCIM/b.jpg","image/jpeg",10,1);assertTrue(StorageRepository.matchesVolume(internal,0));assertTrue(StorageRepository.matchesVolume(sd,0));assertTrue(StorageRepository.matchesVolume(internal,1));assertFalse(StorageRepository.matchesVolume(sd,1));assertFalse(StorageRepository.matchesVolume(internal,2));assertTrue(StorageRepository.matchesVolume(sd,2));assertEquals("Interno",StorageRepository.volumeLabel(internal));assertEquals("Externo",StorageRepository.volumeLabel(sd));}

    @Test public void analysisCacheStoresDualVisualSignature(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AnalysisCacheDb db=new AnalysisCacheDb(context);try{StorageItem item=new StorageItem(991L,"external_primary",Uri.parse("content://media/external_primary/file/991"),"cache.jpg","/sdcard/Pictures/cache.jpg","image/jpeg",123456L,1700000000991L);db.putQuick(item,"quick-test");db.putSha(item,"sha-test");db.putVisual(item,123456789L,987654321L,177,0x10203040L);assertEquals("quick-test",db.getQuick(item));assertEquals("sha-test",db.getSha(item));assertEquals(Long.valueOf(123456789L),db.getDHash(item));assertEquals(Long.valueOf(987654321L),db.getAHash(item));assertEquals(Integer.valueOf(177),db.getAspect(item));assertEquals(Long.valueOf(0x10203040L),db.getColorSig(item));assertTrue(db.estimatedLiveBytes()>0);}finally{db.close();}}

    @Test public void shizukuOfflineHealthNeverCrashes(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();ShizukuShell shell=new ShizukuShell(context);try{assertNotNull(shell.health());assertNotNull(shell.listRunningUserPackages());assertNotNull(shell.listSensitiveUserPackages());assertFalse(shell.selfTest());}finally{shell.shutdown();}}

    @Test public void duplicateAnalyzerKeepsOriginalNamedCopy()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File a=new File(context.getCacheDir(),"photo.jpg"),b=new File(context.getCacheDir(),"photo_copy.jpg"),c=new File(context.getCacheDir(),"different.bin");byte[] same=new byte[80*1024],different=new byte[80*1024];for(int i=0;i<same.length;i++){same[i]=(byte)(i*31);different[i]=(byte)(i*17+7);}try(FileOutputStream out=new FileOutputStream(a)){out.write(same);}try(FileOutputStream out=new FileOutputStream(b)){out.write(same);}try(FileOutputStream out=new FileOutputStream(c)){out.write(different);}
        StorageAnalyzer analyzer=new StorageAnalyzer(context);try{ArrayList<StorageItem> items=new ArrayList<>();StorageItem original=new StorageItem(0,null,"photo.jpg",a.getAbsolutePath(),"application/octet-stream",a.length(),a.lastModified()+2);StorageItem copy=new StorageItem(0,null,"photo (1).jpg",b.getAbsolutePath(),"application/octet-stream",b.length(),b.lastModified()+1);items.add(original);items.add(copy);items.add(new StorageItem(0,null,"different.bin",c.getAbsolutePath(),"application/octet-stream",c.length(),c.lastModified()));CountDownLatch latch=new CountDownLatch(1);AtomicReference<StorageAnalyzer.DuplicateResult> ref=new AtomicReference<>();analyzer.analyzeDuplicatesAsync(items,new StorageAnalyzer.Callback<>(){public void onPhase(String p){}public void onDone(StorageAnalyzer.DuplicateResult r){ref.set(r);latch.countDown();}});assertTrue(latch.await(15,TimeUnit.SECONDS));StorageAnalyzer.DuplicateResult r=ref.get();assertNotNull(r);assertEquals(1,r.groups);assertEquals(2,r.keys.size());assertEquals(1,r.safeDeleteKeys.size());assertEquals(a.length(),r.recoverableBytes);assertEquals(original.stableKey(),r.groupList.get(0).keeperKey);assertFalse(r.safeDeleteKeys.contains(r.groupList.get(0).keeperKey));}finally{analyzer.shutdown();a.delete();b.delete();c.delete();}
    }

    @Test public void persistentIndexPreservesVolumeAndCustomProtection(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();StorageIndexDb db=new StorageIndexDb(context);String custom="com.example.keepme";try{StorageItem item=new StorageItem(77L,"1234-5678",Uri.parse("content://media/1234-5678/file/77"),"persist.bin","/storage/1234-5678/Download/persist.bin","application/octet-stream",777L,1700000000777L);db.replaceAll(List.of(item),"1234-5678=22;");assertEquals("1234-5678=22;",db.signature());List<StorageItem> loaded=db.load();assertEquals(1,loaded.size());assertEquals("1234-5678",loaded.get(0).volume);assertTrue(db.keys().contains(item.stableKey()));db.remove(List.of(item));assertTrue(db.load().isEmpty());AppProtection.setUserProtected(context,Set.of(custom));assertTrue(AppProtection.userProtected(context).contains(custom));AppProtection.setUserProtected(context,Set.of());assertFalse(AppProtection.userProtected(context).contains(custom));}finally{db.close();}}

    @Test public void legacyStorageIndexMigrationClearsOldSignature()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();context.deleteDatabase("storage_index.db");SQLiteDatabase legacy=context.openOrCreateDatabase("storage_index.db",Context.MODE_PRIVATE,null);try{legacy.execSQL("CREATE TABLE files(k TEXT PRIMARY KEY,id INTEGER NOT NULL,uri TEXT,name TEXT,path TEXT,mime TEXT,size INTEGER NOT NULL,modified INTEGER NOT NULL)");legacy.execSQL("CREATE INDEX idx_files_modified ON files(modified)");legacy.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)");ContentValues f=new ContentValues();f.put("k","legacy-key");f.put("id",9L);f.put("uri","content://media/external/file/9");f.put("name","legacy.jpg");f.put("path","/sdcard/DCIM/legacy.jpg");f.put("mime","image/jpeg");f.put("size",99L);f.put("modified",123L);legacy.insert("files",null,f);ContentValues m=new ContentValues();m.put("k","media_signature");m.put("v","external=123;");legacy.insert("meta",null,m);legacy.setVersion(1);}finally{legacy.close();}
        StorageIndexDb upgraded=new StorageIndexDb(context);try{List<StorageItem> rows=upgraded.load();assertEquals(1,rows.size());assertEquals("external",rows.get(0).volume);assertEquals("",upgraded.signature());}finally{upgraded.close();context.deleteDatabase("storage_index.db");}
    }

    @Test public void similarResultCanRepresentReviewGroups(){StorageItem a=new StorageItem(1,null,"a.jpg","/a.jpg","image/jpeg",1,1),b=new StorageItem(2,null,"b.jpg","/b.jpg","image/jpeg",1,1);StorageAnalyzer.SimilarGroup g=new StorageAnalyzer.SimilarGroup(List.of(a,b));assertEquals(2,g.items.size());}
    @Test public void similarAnalyzerNormalizesExifAndRecompression()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File exif=new File(context.getCacheDir(),"visual_exif.jpg"),physical=new File(context.getCacheDir(),"visual_physical.jpg"),recompressed=new File(context.getCacheDir(),"visual_recompressed.jpg");Bitmap base=patternBitmap(240,160,false),rotated=null;StorageAnalyzer analyzer=new StorageAnalyzer(context);
        try{writeJpeg(base,exif,92);ExifInterface ei=new ExifInterface(exif.getAbsolutePath());ei.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));ei.saveAttributes();Matrix m=new Matrix();m.setRotate(90f);rotated=Bitmap.createBitmap(base,0,0,base.getWidth(),base.getHeight(),m,true);writeJpeg(rotated,physical,88);writeJpeg(rotated,recompressed,62);ArrayList<StorageItem> items=new ArrayList<>();items.add(localImage(exif,1));items.add(localImage(physical,2));items.add(localImage(recompressed,3));StorageAnalyzer.DuplicateResult none=new StorageAnalyzer.DuplicateResult(Set.of(),List.of(),Set.of(),0L);CountDownLatch latch=new CountDownLatch(1);AtomicReference<StorageAnalyzer.SimilarResult> ref=new AtomicReference<>();analyzer.analyzeSimilarAsync(items,none,new StorageAnalyzer.Callback<>(){public void onPhase(String p){}public void onDone(StorageAnalyzer.SimilarResult r){ref.set(r);latch.countDown();}});assertTrue(latch.await(20,TimeUnit.SECONDS));StorageAnalyzer.SimilarResult r=ref.get();assertNotNull(r);assertTrue("expected EXIF/recompressed images to group",r.groups>=1);assertTrue(r.keys.size()>=2);}finally{analyzer.shutdown();if(rotated!=null&&!rotated.isRecycled())rotated.recycle();if(!base.isRecycled())base.recycle();exif.delete();physical.delete();recompressed.delete();}}

    @Test public void similarAnalyzerColorGateRejectsDifferentFlatImages()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File dark=new File(context.getCacheDir(),"flat_dark.jpg"),bright=new File(context.getCacheDir(),"flat_bright.jpg");Bitmap a=Bitmap.createBitmap(220,220,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(220,220,Bitmap.Config.ARGB_8888);a.eraseColor(Color.rgb(25,28,32));b.eraseColor(Color.rgb(230,225,210));StorageAnalyzer analyzer=new StorageAnalyzer(context);
        try{writeJpeg(a,dark,90);writeJpeg(b,bright,90);List<StorageItem> items=List.of(localImage(dark,11),localImage(bright,12));StorageAnalyzer.DuplicateResult none=new StorageAnalyzer.DuplicateResult(Set.of(),List.of(),Set.of(),0L);CountDownLatch latch=new CountDownLatch(1);AtomicReference<StorageAnalyzer.SimilarResult> ref=new AtomicReference<>();analyzer.analyzeSimilarAsync(items,none,new StorageAnalyzer.Callback<>(){public void onPhase(String p){}public void onDone(StorageAnalyzer.SimilarResult r){ref.set(r);latch.countDown();}});assertTrue(latch.await(20,TimeUnit.SECONDS));assertNotNull(ref.get());assertEquals(0,ref.get().groups);assertTrue(ref.get().keys.isEmpty());}finally{analyzer.shutdown();a.recycle();b.recycle();dark.delete();bright.delete();}}

    private static Bitmap patternBitmap(int w,int h,boolean alternate){Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);c.drawColor(alternate?Color.rgb(22,70,120):Color.rgb(28,34,42));p.setColor(alternate?Color.YELLOW:Color.rgb(225,70,55));c.drawRect(w*0.08f,h*0.12f,w*0.62f,h*0.48f,p);p.setColor(alternate?Color.MAGENTA:Color.rgb(70,190,145));c.drawCircle(w*0.72f,h*0.68f,Math.min(w,h)*0.22f,p);p.setColor(Color.WHITE);p.setStrokeWidth(9f);c.drawLine(12,h-20,w-18,18,p);return b;}
    private static void writeJpeg(Bitmap b,File f,int quality)throws Exception{try(FileOutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.JPEG,quality,out));}}
    private static StorageItem localImage(File f,long id){return new StorageItem(id,null,f.getName(),f.getAbsolutePath(),"image/jpeg",f.length(),f.lastModified());}

}
