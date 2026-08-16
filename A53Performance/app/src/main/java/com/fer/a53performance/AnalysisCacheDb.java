package com.fer.a53performance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class AnalysisCacheDb extends SQLiteOpenHelper {
    private static final String DB="analysis_cache.db";
    private static final int VERSION=2;

    public AnalysisCacheDb(Context context){super(context.getApplicationContext(),DB,null,VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY,size INTEGER NOT NULL,modified INTEGER NOT NULL,quick TEXT,sha TEXT,dhash INTEGER,updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_cache_updated ON cache(updated)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2){try{db.execSQL("ALTER TABLE cache ADD COLUMN quick TEXT");}catch(Throwable ignored){}}
    }

    public synchronized String getQuick(StorageItem item){return getText(item,"quick");}
    public synchronized String getSha(StorageItem item){return getText(item,"sha");}
    private String getText(StorageItem item,String column){
        try(Cursor c=getReadableDatabase().query("cache",new String[]{column,"size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(!c.moveToFirst())return null;if(c.getLong(1)!=item.size||c.getLong(2)!=item.modified)return null;return c.isNull(0)?null:c.getString(0);
        }
    }
    public synchronized Long getDHash(StorageItem item){
        try(Cursor c=getReadableDatabase().query("cache",new String[]{"dhash","size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(!c.moveToFirst())return null;if(c.getLong(1)!=item.size||c.getLong(2)!=item.modified)return null;return c.isNull(0)?null:c.getLong(0);
        }
    }

    public synchronized void putQuick(StorageItem item,String quick){upsert(item,quick,null,null,false);}
    public synchronized void putSha(StorageItem item,String sha){upsert(item,null,sha,null,false);}
    public synchronized void putDHash(StorageItem item,long hash){upsert(item,null,null,hash,true);}

    private void upsert(StorageItem item,String quick,String sha,Long dhash,boolean writeDhash){
        SQLiteDatabase db=getWritableDatabase();String existingQuick=null,existingSha=null;Long existingHash=null;
        try(Cursor c=db.query("cache",new String[]{"quick","sha","dhash","size","modified"},"k=?",new String[]{item.stableKey()},null,null,null)){
            if(c.moveToFirst()&&c.getLong(3)==item.size&&c.getLong(4)==item.modified){existingQuick=c.isNull(0)?null:c.getString(0);existingSha=c.isNull(1)?null:c.getString(1);existingHash=c.isNull(2)?null:c.getLong(2);}
        }
        ContentValues v=new ContentValues();v.put("k",item.stableKey());v.put("size",item.size);v.put("modified",item.modified);v.put("updated",System.currentTimeMillis());
        if(quick!=null)v.put("quick",quick);else if(existingQuick!=null)v.put("quick",existingQuick);else v.putNull("quick");
        if(sha!=null)v.put("sha",sha);else if(existingSha!=null)v.put("sha",existingSha);else v.putNull("sha");
        if(writeDhash&&dhash!=null)v.put("dhash",dhash);else if(existingHash!=null)v.put("dhash",existingHash);else v.putNull("dhash");
        db.insertWithOnConflict("cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void prune(){
        SQLiteDatabase db=getWritableDatabase();long cutoff=System.currentTimeMillis()-45L*24L*60L*60L*1000L;
        db.delete("cache","updated<?",new String[]{Long.toString(cutoff)});
        db.execSQL("DELETE FROM cache WHERE k NOT IN (SELECT k FROM cache ORDER BY updated DESC LIMIT 25000)");
    }
}
