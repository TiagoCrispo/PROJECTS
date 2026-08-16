package com.fer.a53performance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Set;

public final class AnalysisCacheDb extends SQLiteOpenHelper {
    private static final String DB="analysis_cache.db";
    private static final int VERSION=3;
    private static final long MAX_LIVE_BYTES=32L*1024L*1024L;
    private static final long TARGET_LIVE_BYTES=24L*1024L*1024L;

    public AnalysisCacheDb(Context context){super(context.getApplicationContext(),DB,null,VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY,size INTEGER NOT NULL,modified INTEGER NOT NULL,quick TEXT,sha TEXT,dhash INTEGER,ahash INTEGER,aspect INTEGER,updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_cache_updated ON cache(updated)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2){try{db.execSQL("ALTER TABLE cache ADD COLUMN quick TEXT");}catch(Throwable ignored){}}
        if(oldVersion<3){try{db.execSQL("ALTER TABLE cache ADD COLUMN ahash INTEGER");}catch(Throwable ignored){}try{db.execSQL("ALTER TABLE cache ADD COLUMN aspect INTEGER");}catch(Throwable ignored){}}
    }

    public synchronized String getQuick(StorageItem item){return getText(item,"quick");}
    public synchronized String getSha(StorageItem item){return getText(item,"sha");}
    private String getText(StorageItem item,String column){
        try(Cursor c=getReadableDatabase().query("cache",new String[]{column,"size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(!c.moveToFirst())return null;if(c.getLong(1)!=item.size||c.getLong(2)!=item.modified)return null;return c.isNull(0)?null:c.getString(0);
        }
    }
    public synchronized Long getDHash(StorageItem item){return getLong(item,"dhash");}
    public synchronized Long getAHash(StorageItem item){return getLong(item,"ahash");}
    public synchronized Integer getAspect(StorageItem item){Long v=getLong(item,"aspect");return v==null?null:v.intValue();}
    private Long getLong(StorageItem item,String column){
        try(Cursor c=getReadableDatabase().query("cache",new String[]{column,"size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(!c.moveToFirst())return null;if(c.getLong(1)!=item.size||c.getLong(2)!=item.modified)return null;return c.isNull(0)?null:c.getLong(0);
        }
    }

    public synchronized void putQuick(StorageItem item,String quick){upsert(item,quick,null,null,null,null);}
    public synchronized void putSha(StorageItem item,String sha){upsert(item,null,sha,null,null,null);}
    public synchronized void putDHash(StorageItem item,long hash){upsert(item,null,null,hash,null,null);}
    public synchronized void putVisual(StorageItem item,long dhash,long ahash,int aspect){upsert(item,null,null,dhash,ahash,aspect);}

    private void upsert(StorageItem item,String quick,String sha,Long dhash,Long ahash,Integer aspect){
        SQLiteDatabase db=getWritableDatabase();String eq=null,es=null;Long ed=null,ea=null,ep=null;
        try(Cursor c=db.query("cache",new String[]{"quick","sha","dhash","ahash","aspect","size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(c.moveToFirst()&&c.getLong(5)==item.size&&c.getLong(6)==item.modified){eq=c.isNull(0)?null:c.getString(0);es=c.isNull(1)?null:c.getString(1);ed=c.isNull(2)?null:c.getLong(2);ea=c.isNull(3)?null:c.getLong(3);ep=c.isNull(4)?null:c.getLong(4);}
        }
        ContentValues v=new ContentValues();v.put("k",item.stableKey());v.put("size",item.size);v.put("modified",item.modified);v.put("updated",System.currentTimeMillis());
        if(quick!=null)v.put("quick",quick);else if(eq!=null)v.put("quick",eq);else v.putNull("quick");
        if(sha!=null)v.put("sha",sha);else if(es!=null)v.put("sha",es);else v.putNull("sha");
        Long d=dhash!=null?dhash:ed;if(d!=null)v.put("dhash",d);else v.putNull("dhash");
        Long a=ahash!=null?ahash:ea;if(a!=null)v.put("ahash",a);else v.putNull("ahash");
        Long p=aspect!=null?Long.valueOf(aspect):ep;if(p!=null)v.put("aspect",p);else v.putNull("aspect");
        db.insertWithOnConflict("cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void prune(){prune(Set.of());}
    public synchronized void prune(Set<String> existingKeys){
        SQLiteDatabase db=getWritableDatabase();long cutoff=System.currentTimeMillis()-75L*24L*60L*60L*1000L;
        db.delete("cache","updated<?",new String[]{Long.toString(cutoff)});
        if(existingKeys!=null&&!existingKeys.isEmpty()){
            ArrayList<String> stale=new ArrayList<>();try(Cursor c=db.query("cache",new String[]{"k"},null,null,null,null,null)){while(c.moveToNext()){String k=c.getString(0);if(!existingKeys.contains(k))stale.add(k);}}
            db.beginTransaction();try{for(String k:stale)db.delete("cache","k=?",new String[]{k});db.setTransactionSuccessful();}finally{db.endTransaction();}
        }
        long used=liveBytes(db);int guard=0;
        while(used>MAX_LIVE_BYTES&&guard++<20){long before=count(db);db.execSQL("DELETE FROM cache WHERE k IN (SELECT k FROM cache ORDER BY updated ASC LIMIT 2000)");long after=count(db);if(after>=before)break;used=liveBytes(db);if(used<=TARGET_LIVE_BYTES)break;}
    }

    public synchronized long estimatedLiveBytes(){return liveBytes(getReadableDatabase());}
    private static long count(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM cache",null)){return c.moveToFirst()?c.getLong(0):0L;}}
    private static long liveBytes(SQLiteDatabase db){long pages=pragma(db,"page_count"),free=pragma(db,"freelist_count"),pageSize=pragma(db,"page_size");return Math.max(0L,pages-free)*Math.max(1L,pageSize);}
    private static long pragma(SQLiteDatabase db,String name){try(Cursor c=db.rawQuery("PRAGMA "+name,null)){return c.moveToFirst()?c.getLong(0):0L;}catch(Throwable ignored){return 0L;}}
}
