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
    private static final int VERSION=1;
    private static final String META_SIGNATURE="media_signature";

    public StorageIndexDb(Context context){super(context.getApplicationContext(),DB,null,VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE files(k TEXT PRIMARY KEY,id INTEGER NOT NULL,uri TEXT,name TEXT,path TEXT,mime TEXT,size INTEGER NOT NULL,modified INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_files_modified ON files(modified)");
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}

    public synchronized List<StorageItem> load(){
        ArrayList<StorageItem> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("files",new String[]{"id","uri","name","path","mime","size","modified"},null,null,null,null,"modified DESC")){
            while(c.moveToNext()){
                long id=c.getLong(0);String uri=c.isNull(1)?"":c.getString(1);String name=c.isNull(2)?"":c.getString(2);String path=c.isNull(3)?"":c.getString(3);String mime=c.isNull(4)?"":c.getString(4);long size=c.getLong(5),modified=c.getLong(6);
                out.add(new StorageItem(id,uri.isBlank()?null:Uri.parse(uri),name,path,mime,size,modified));
            }
        }
        return out;
    }

    public synchronized void replaceAll(List<StorageItem> items,String signature){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.delete("files",null,null);
            for(StorageItem x:items){
                ContentValues v=new ContentValues();v.put("k",x.stableKey());v.put("id",x.id);if(x.uri!=null)v.put("uri",x.uri.toString());else v.putNull("uri");v.put("name",x.name);v.put("path",x.path);v.put("mime",x.mime);v.put("size",x.size);v.put("modified",x.modified);
                db.insertWithOnConflict("files",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
            ContentValues m=new ContentValues();m.put("k",META_SIGNATURE);m.put("v",signature==null?"":signature);db.insertWithOnConflict("meta",null,m,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }

    public synchronized void remove(Collection<StorageItem> items){
        if(items==null||items.isEmpty())return;SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{for(StorageItem x:items)db.delete("files","k=?",new String[]{x.stableKey()});db.setTransactionSuccessful();}finally{db.endTransaction();}
    }

    public synchronized Set<String> keys(){
        HashSet<String> out=new HashSet<>();try(Cursor c=getReadableDatabase().query("files",new String[]{"k"},null,null,null,null,null)){while(c.moveToNext())out.add(c.getString(0));}return out;
    }

    public synchronized String signature(){
        try(Cursor c=getReadableDatabase().query("meta",new String[]{"v"},"k=?",new String[]{META_SIGNATURE},null,null,null)){return c.moveToFirst()?c.getString(0):"";}
    }
}
