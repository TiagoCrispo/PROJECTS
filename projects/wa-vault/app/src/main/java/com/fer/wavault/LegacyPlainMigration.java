package com.fer.wavault;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot migration for very old text blobs. General application reads do not retain this format after completion. */
public final class LegacyPlainMigration {
    private LegacyPlainMigration(){}
    private static final String PREF="wa_vault_settings";
    private static final String DONE="legacy_plain_migration_v0521";
    private static final byte[] PREFIX=new byte[]{80,76,65,73,78,58};
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);

    public static boolean isComplete(Context c){return c!=null&&c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getBoolean(DONE,false);}
    static boolean migrationRunning(){return RUNNING.get();}
    public static boolean isLegacy(byte[] blob){if(blob==null||blob.length<=PREFIX.length)return false;for(int i=0;i<PREFIX.length;i++)if(blob[i]!=PREFIX[i])return false;return true;}
    static String decodeForMigration(byte[] blob){
        if(!isLegacy(blob))return "[contenido cifrado]";
        try{String encoded=new String(blob,PREFIX.length,blob.length-PREFIX.length,StandardCharsets.UTF_8);return new String(Base64.decode(encoded,Base64.NO_WRAP),StandardCharsets.UTF_8);}catch(Throwable t){return "[contenido cifrado]";}
    }

    public static void runAsync(Context context){
        if(context==null||isComplete(context)||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        Thread t=new Thread(()->{try{migrate(app);}finally{RUNNING.set(false);}},"wa-vault-legacy-plain-migrate");
        t.setPriority(Thread.MIN_PRIORITY);t.start();
    }

    private static void migrate(Context app){
        VaultDb helper=null;int changed=0,failed=0;
        try{
            helper=new VaultDb(app);SQLiteDatabase db=helper.getWritableDatabase();CryptoManager crypto=new CryptoManager(app);
            int[] r=migrateMessages(db,crypto);changed+=r[0];failed+=r[1];
            r=migrateSingleColumn(db,crypto,"media","display_name");changed+=r[0];failed+=r[1];
            r=migrateSingleColumn(db,crypto,"event_log","detail");changed+=r[0];failed+=r[1];
            int remaining=countRemaining(db);
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("legacy_plain_migrated_count",changed).putInt("legacy_plain_migration_failures",failed).putInt("legacy_plain_remaining",remaining).putLong("legacy_plain_migration_at",System.currentTimeMillis()).apply();
            if(failed==0&&remaining==0){app.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putBoolean(DONE,true).apply();try{helper.logEvent("LEGACY_TEXT_ENCRYPTED","Migración de texto heredado completada · filas="+changed,0L,0L);}catch(Throwable ignored){}}
        }catch(Throwable t){try{app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("legacy_plain_migration_failures",failed+1).putLong("legacy_plain_migration_at",System.currentTimeMillis()).apply();}catch(Throwable ignored){}if(helper!=null)try{helper.logInternalError("LEGACY_TEXT_MIGRATION",t);}catch(Throwable ignored){}
        }
    }

    private static int[] migrateMessages(SQLiteDatabase db,CryptoManager crypto){
        int changed=0,failed=0;long after=0L;
        while(true){Cursor c=db.query("messages",new String[]{"id","conversation","sender","body"},"id>?",new String[]{String.valueOf(after)},null,null,"id ASC","128");int seen=0;try{while(c.moveToNext()){seen++;long id=c.getLong(0);after=id;ContentValues v=new ContentValues();boolean touched=false;for(int i=1;i<=3;i++){byte[] old=c.getBlob(i);if(!isLegacy(old))continue;touched=true;String clear=decodeForMigration(old);if("[contenido cifrado]".equals(clear)){failed++;continue;}byte[] enc=crypto.encrypt(clear);if(enc==null||!clear.equals(crypto.decrypt(enc))){failed++;continue;}v.put(i==1?"conversation":i==2?"sender":"body",enc);}if(touched&&v.size()>0){if(db.update("messages",v,"id=?",new String[]{String.valueOf(id)})>0)changed++;else failed++;}}}finally{c.close();}if(seen<128)break;try{Thread.sleep(8L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        return new int[]{changed,failed};
    }

    private static int[] migrateSingleColumn(SQLiteDatabase db,CryptoManager crypto,String table,String column){
        int changed=0,failed=0;long after=0L;
        while(true){Cursor c=db.query(table,new String[]{"id",column},"id>?",new String[]{String.valueOf(after)},null,null,"id ASC","128");int seen=0;try{while(c.moveToNext()){seen++;long id=c.getLong(0);after=id;byte[] old=c.getBlob(1);if(!isLegacy(old))continue;String clear=decodeForMigration(old);if("[contenido cifrado]".equals(clear)){failed++;continue;}byte[] enc=crypto.encrypt(clear);if(enc==null||!clear.equals(crypto.decrypt(enc))){failed++;continue;}ContentValues v=new ContentValues();v.put(column,enc);if(db.update(table,v,"id=?",new String[]{String.valueOf(id)})>0)changed++;else failed++;}}finally{c.close();}if(seen<128)break;try{Thread.sleep(8L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        return new int[]{changed,failed};
    }

    public static int remainingCount(Context context){if(context==null)return -1;VaultDb h=null;try{h=new VaultDb(context.getApplicationContext());return countRemaining(h.getReadableDatabase());}catch(Throwable t){return -1;}}
    private static int countRemaining(SQLiteDatabase db){int n=0;n+=countLegacyIn(db,"messages",new String[]{"conversation","sender","body"});n+=countLegacyIn(db,"media",new String[]{"display_name"});n+=countLegacyIn(db,"event_log",new String[]{"detail"});return n;}
    private static int countLegacyIn(SQLiteDatabase db,String table,String[] columns){int n=0;Cursor c=db.query(table,columns,null,null,null,null,null);try{while(c.moveToNext())for(int i=0;i<columns.length;i++)if(isLegacy(c.getBlob(i)))n++;}finally{c.close();}return n;}
}
