package com.fer.a53performance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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

    @Test public void analysisCacheStoresDualVisualSignature(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AnalysisCacheDb db=new AnalysisCacheDb(context);try{StorageItem item=new StorageItem(991L,"external_primary",Uri.parse("content://media/external_primary/file/991"),"cache.jpg","/sdcard/Pictures/cache.jpg","image/jpeg",123456L,1700000000991L);db.putQuick(item,"quick-test");db.putSha(item,"sha-test");db.putVisual(item,123456789L,987654321L,177);assertEquals("quick-test",db.getQuick(item));assertEquals("sha-test",db.getSha(item));assertEquals(Long.valueOf(123456789L),db.getDHash(item));assertEquals(Long.valueOf(987654321L),db.getAHash(item));assertEquals(Integer.valueOf(177),db.getAspect(item));assertTrue(db.estimatedLiveBytes()>0);}finally{db.close();}}

    @Test public void shizukuOfflineHealthNeverCrashes(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();ShizukuShell shell=new ShizukuShell(context);try{assertNotNull(shell.health());assertNotNull(shell.listRunningUserPackages());assertNotNull(shell.listSensitiveUserPackages());}finally{shell.shutdown();}}

    @Test public void duplicateAnalyzerKeepsOriginalNamedCopy()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File a=new File(context.getCacheDir(),"photo.jpg"),b=new File(context.getCacheDir(),"photo_copy.jpg"),c=new File(context.getCacheDir(),"different.bin");byte[] same=new byte[80*1024],different=new byte[80*1024];for(int i=0;i<same.length;i++){same[i]=(byte)(i*31);different[i]=(byte)(i*17+7);}try(FileOutputStream out=new FileOutputStream(a)){out.write(same);}try(FileOutputStream out=new FileOutputStream(b)){out.write(same);}try(FileOutputStream out=new FileOutputStream(c)){out.write(different);}
        StorageAnalyzer analyzer=new StorageAnalyzer(context);try{ArrayList<StorageItem> items=new ArrayList<>();StorageItem original=new StorageItem(0,null,"photo.jpg",a.getAbsolutePath(),"application/octet-stream",a.length(),a.lastModified()+2);StorageItem copy=new StorageItem(0,null,"photo (1).jpg",b.getAbsolutePath(),"application/octet-stream",b.length(),b.lastModified()+1);items.add(original);items.add(copy);items.add(new StorageItem(0,null,"different.bin",c.getAbsolutePath(),"application/octet-stream",c.length(),c.lastModified()));CountDownLatch latch=new CountDownLatch(1);AtomicReference<StorageAnalyzer.DuplicateResult> ref=new AtomicReference<>();analyzer.analyzeDuplicatesAsync(items,new StorageAnalyzer.Callback<>(){public void onPhase(String p){}public void onDone(StorageAnalyzer.DuplicateResult r){ref.set(r);latch.countDown();}});assertTrue(latch.await(15,TimeUnit.SECONDS));StorageAnalyzer.DuplicateResult r=ref.get();assertNotNull(r);assertEquals(1,r.groups);assertEquals(2,r.keys.size());assertEquals(1,r.safeDeleteKeys.size());assertEquals(a.length(),r.recoverableBytes);assertEquals(original.stableKey(),r.groupList.get(0).keeperKey);assertFalse(r.safeDeleteKeys.contains(r.groupList.get(0).keeperKey));}finally{analyzer.shutdown();a.delete();b.delete();c.delete();}
    }

    @Test public void persistentIndexPreservesVolumeAndCustomProtection(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();StorageIndexDb db=new StorageIndexDb(context);String custom="com.example.keepme";try{StorageItem item=new StorageItem(77L,"1234-5678",Uri.parse("content://media/1234-5678/file/77"),"persist.bin","/storage/1234-5678/Download/persist.bin","application/octet-stream",777L,1700000000777L);db.replaceAll(List.of(item),"1234-5678=22;");assertEquals("1234-5678=22;",db.signature());List<StorageItem> loaded=db.load();assertEquals(1,loaded.size());assertEquals("1234-5678",loaded.get(0).volume);assertTrue(db.keys().contains(item.stableKey()));db.remove(List.of(item));assertTrue(db.load().isEmpty());AppProtection.setUserProtected(context,Set.of(custom));assertTrue(AppProtection.userProtected(context).contains(custom));AppProtection.setUserProtected(context,Set.of());assertFalse(AppProtection.userProtected(context).contains(custom));}finally{db.close();}}

    @Test public void similarResultCanRepresentReviewGroups(){StorageItem a=new StorageItem(1,null,"a.jpg","/a.jpg","image/jpeg",1,1),b=new StorageItem(2,null,"b.jpg","/b.jpg","image/jpeg",1,1);StorageAnalyzer.SimilarGroup g=new StorageAnalyzer.SimilarGroup(List.of(a,b));assertEquals(2,g.items.size());}
}
