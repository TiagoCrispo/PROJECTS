package com.fer.a53performance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class StabilityInstrumentedTest {
    @Test public void fiveThousandRowsKeepUniqueStableIdsAndRecycleData(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ThumbnailLoader thumbs=new ThumbnailLoader(context);
        try{
            FileAdapter adapter=new FileAdapter(thumbs,(count,bytes)->{});
            ArrayList<StorageItem> items=new ArrayList<>();HashSet<Long> ids=new HashSet<>();
            for(int i=0;i<5000;i++){
                StorageItem item=new StorageItem(i+1L,Uri.parse("content://media/external/file/"+(i+1L)),"file_"+i+".bin","/sdcard/Download/file_"+i+".bin","application/octet-stream",1024L+i,1700000000000L+i);
                assertTrue("stable ID collision at "+i,ids.add(item.stableId()));items.add(item);
            }
            adapter.replace(items);assertEquals(5000,adapter.getItemCount());
            adapter.removeAll(new ArrayList<>(items.subList(0,1000)));assertEquals(4000,adapter.getItemCount());
            adapter.replace(new ArrayList<>(items.subList(0,60)));assertEquals(60,adapter.getItemCount());
        }finally{thumbs.shutdown();}
    }

    @Test public void analysisCacheStoresQuickShaAndDhashTogether(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AnalysisCacheDb db=new AnalysisCacheDb(context);
        try{
            StorageItem item=new StorageItem(991L,Uri.parse("content://media/external/file/991"),"cache.jpg","/sdcard/Pictures/cache.jpg","image/jpeg",123456L,1700000000991L);
            db.putQuick(item,"quick-test");db.putSha(item,"sha-test");db.putDHash(item,123456789L);
            assertEquals("quick-test",db.getQuick(item));assertEquals("sha-test",db.getSha(item));assertEquals(Long.valueOf(123456789L),db.getDHash(item));
        }finally{db.close();}
    }
}
