package com.fer.a53performance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CleanerController {
    private CleanerController(){}

    public static List<StorageItem> filterAndSort(List<StorageItem> source,String query,int filter,int sort,Set<String> duplicates,Set<String> similar){
        String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);ArrayList<StorageItem> out=new ArrayList<>();
        for(StorageItem x:source){
            boolean type=switch(filter){case 1->x.isImage();case 2->x.isVideo();case 3->x.isAudio();case 4->x.isLarge();case 5->duplicates.contains(x.stableKey());case 6->similar.contains(x.stableKey());default->true;};
            if(!type)continue;if(!q.isEmpty()&&!x.name.toLowerCase(Locale.ROOT).contains(q)&&!x.path.toLowerCase(Locale.ROOT).contains(q))continue;out.add(x);
        }
        Comparator<StorageItem> cmp=switch(sort){case 1->Comparator.comparingLong((StorageItem x)->x.size).reversed();case 2->Comparator.comparingLong(x->x.size);case 3->Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT));default->Comparator.comparingLong((StorageItem x)->x.modified).reversed();};out.sort(cmp);return out;
    }
}
