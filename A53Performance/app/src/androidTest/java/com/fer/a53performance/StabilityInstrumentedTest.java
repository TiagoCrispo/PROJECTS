package com.fer.a53performance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class StabilityInstrumentedTest {
    @Test public void tenThousandRowsKeepUniqueStableIdsAndFilterWithoutUiExplosion(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();ThumbnailLoader thumbs=new ThumbnailLoader(context);
        try{
            FileAdapter adapter=new FileAdapter(thumbs,(count,bytes)->{});ArrayList<StorageItem> items=new ArrayList<>();HashSet<Long> ids=new HashSet<>();
            for(int i=0;i<10000;i++){String mime=(i%4==0)?"image/jpeg":"application/octet-stream";StorageItem item=new StorageItem(i+1L,Uri.parse("content://media/external/file/"+(i+1L)),"file_"+i+".bin","/sdcard/Download/file_"+i+".bin",mime,1024L+i,1700000000000L+i);assertTrue("stable ID collision at "+i,ids.add(item.stableId()));items.add(item);}
            List<StorageItem> filtered=CleanerController.filterAndSort(items,"file_9",0,0,Collections.emptySet(),Collections.emptySet());assertTrue(filtered.size()>100);adapter.replace(new ArrayList<>(items.subList(0,60)));assertEquals(60,adapter.getItemCount());adapter.append(new ArrayList<>(items.subList(60,120)));assertEquals(120,adapter.getItemCount());
        }finally{thumbs.shutdown();}
    }

    @Test public void analysisCacheStoresDualVisualSignature(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();AnalysisCacheDb db=new AnalysisCacheDb(context);
        try{StorageItem item=new StorageItem(991L,Uri.parse("content://media/external/file/991"),"cache.jpg","/sdcard/Pictures/cache.jpg","image/jpeg",123456L,1700000000991L);db.putQuick(item,"quick-test");db.putSha(item,"sha-test");db.putVisual(item,123456789L,987654321L,177);assertEquals("quick-test",db.getQuick(item));assertEquals("sha-test",db.getSha(item));assertEquals(Long.valueOf(123456789L),db.getDHash(item));assertEquals(Long.valueOf(987654321L),db.getAHash(item));assertEquals(Integer.valueOf(177),db.getAspect(item));}finally{db.close();}
    }

    @Test public void shizukuOfflineHealthNeverCrashes(){Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();ShizukuShell shell=new ShizukuShell(context);try{assertNotNull(shell.health());assertNotNull(shell.listRunningUserPackages());}finally{shell.shutdown();}}
}
