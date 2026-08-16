package com.fer.a53performance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StorageIndexDb extends SQLiteOpenHelper {
    private static final String DB="storage_index.db";
    private static final int VERSION=2;
    private static final String META_SIGNATURE="media_signature";

    public StorageIndexDb(Context context){super(context.getApplicationContext(),DB,null,VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE files(k TEXT PRIMARY KEY,id INTEGER NOT NULL,volume TEXT NOT NULL,uri TEXT,name TEXT,path TEXT,mime TEXT,size INTEGER NOT NULL,modified INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_files_modified ON files(modified)");
        db.execSQL("CREATE INDEX idx_files_volume ON files(volume,id)");
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        if(oldVersion<2){
            try{db.execSQL("ALTER TABLE files ADD COLUMN volume TEXT NOT NULL DEFAULT 'external'");}catch(Throwable ignored){}
            try{db.execSQL("CREATE INDEX idx_files_volume ON files(volume,id)");}catch(Throwable ignored){}
        }
    }

    public synchronized List<StorageItem> load(){return loadWhere(null,null);}
    public synchronized List<StorageItem> loadVolume(String volume){return loadWhere("volume=?",new String[]{volume});}
    private List<StorageItem> loadWhere(String where,String[] args){
        ArrayList<StorageItem> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("files",new String[]{"id","volume","uri","name","path","mime","size","modified"},where,args,null,null,"modified DESC")){
            while(c.moveToNext()){
                long id=c.getLong(0);String volume=c.isNull(1)?"external":c.getString(1);String uri=c.isNull(2)?"":c.getString(2);String name=c.isNull(3)?"":c.getString(3);String path=c.isNull(4)?"":c.getString(4);String mime=c.isNull(5)?"":c.getString(5);long size=c.getLong(6),modified=c.getLong(7);
                out.add(new StorageItem(id,volume,uri.isBlank()?null:Uri.parse(uri),name,path,mime,size,modified));
            }
        }
        return out;
    }

    public synchronized void replaceAll(List<StorageItem> items,String signature){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{db.delete("files",null,null);insertAll(db,items);putSignature(db,signature);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public synchronized void replaceVolume(String volume,List<StorageItem> items,String signature){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{db.delete("files","volume=?",new String[]{volume});insertAll(db,items);putSignature(db,signature);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    private static void insertAll(SQLiteDatabase db,List<StorageItem> items){
        for(StorageItem x:items){
            ContentValues v=new ContentValues();v.put("k",x.stableKey());v.put("id",x.id);v.put("volume",x.volume);if(x.uri!=null)v.put("uri",x.uri.toString());else v.putNull("uri");v.put("name",x.name);v.put("path",x.path);v.put("mime",x.mime);v.put("size",x.size);v.put("modified",x.modified);
            db.insertWithOnConflict("files",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
    private static void putSignature(SQLiteDatabase db,String signature){ContentValues m=new ContentValues();m.put("k",META_SIGNATURE);m.put("v",signature==null?"":signature);db.insertWithOnConflict("meta",null,m,SQLiteDatabase.CONFLICT_REPLACE);}

    public synchronized void remove(Collection<StorageItem> items){
        if(items==null||items.isEmpty())return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{for(StorageItem x:items)db.delete("files","k=?",new String[]{x.stableKey()});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public synchronized Set<String> keys(){HashSet<String> out=new HashSet<>();try(Cursor c=getReadableDatabase().query("files",new String[]{"k"},null,null,null,null,null)){while(c.moveToNext())out.add(c.getString(0));}return out;}
    public synchronized String signature(){try(Cursor c=getReadableDatabase().query("meta",new String[]{"v"},"k=?",new String[]{META_SIGNATURE},null,null,null)){return c.moveToFirst()?c.getString(0):"";}}
}
