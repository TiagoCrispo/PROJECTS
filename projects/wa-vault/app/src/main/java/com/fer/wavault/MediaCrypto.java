package com.fer.wavault;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** AES-256-GCM encryption for archived media at rest, backed by Android Keystore. */
public final class MediaCrypto {
    private MediaCrypto() {}
    public static final int MODE_ALL=2;
    private static final String ALIAS="wa_vault_media_key_v1";
    private static final String PREF="wa_vault_settings";
    private static final String MODE_KEY="media_encryption_mode";
    private static final byte[] MAGIC=new byte[]{'W','A','V','M','1'};
    private static final AtomicBoolean MIGRATION_RUNNING=new AtomicBoolean(false);
    private static final String MIGRATION_CURSOR="media_migration_cursor";
    static boolean migrationRunning(){return MIGRATION_RUNNING.get();}

    /** v0.5.21: media encryption is mandatory and can no longer be disabled. */
    public static int getMode(Context c){return MODE_ALL;}
    public static String modeLabel(Context c){return "Cifrar todo · obligatorio";}
    public static boolean shouldEncrypt(Context c,String type,String origin){return true;}
    public static void enforceRequiredMode(Context c){
        if(c==null)return;
        try{c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putInt(MODE_KEY,MODE_ALL).apply();}catch(Throwable ignored){}
        migrateExistingAsync(c.getApplicationContext());
    }

    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){
            KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            kg.generateKey();
        }
        return (SecretKey)ks.getKey(ALIAS,null);
    }

    public static boolean isEncrypted(File f){
        if(f==null||!f.exists()||f.length()<MAGIC.length+2)return false;
        try(InputStream in=new FileInputStream(f)){for(byte b:MAGIC)if(in.read()!=(b&0xff))return false;return true;}catch(Throwable t){return false;}
    }

    /** Encrypts a private archive file to a sibling temp and commits it with one atomic rename. */
    public static boolean encryptInPlace(File plain){
        if(plain==null||!plain.exists()||!plain.isFile())return false;if(isEncrypted(plain))return true;
        File tmp=new File(plain.getParentFile(),plain.getName()+".encrypting");
        try{
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[] iv=c.getIV();
            try(InputStream in=new BufferedInputStream(new FileInputStream(plain),131072);OutputStream raw=new BufferedOutputStream(new FileOutputStream(tmp),131072)){
                raw.write(MAGIC);raw.write(iv.length);raw.write(iv);raw.flush();
                try(CipherOutputStream out=new CipherOutputStream(raw,c)){byte[] buf=new byte[131072];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}
            }
            if(!tmp.exists()||tmp.length()<=MAGIC.length+16){tmp.delete();return false;}
            return atomicReplace(tmp,plain,true);
        }catch(Throwable t){try{tmp.delete();}catch(Throwable ignored){}return false;}
    }

    private static boolean atomicReplace(File tmp,File target,boolean verifyEncrypted){
        if(tmp==null||target==null||!tmp.exists())return false;
        java.io.FileDescriptor dirFd=null;
        try{
            try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(tmp,"rw")){raf.getFD().sync();}
            android.system.Os.rename(tmp.getAbsolutePath(),target.getAbsolutePath());
            try{File parent=target.getParentFile();if(parent!=null){dirFd=android.system.Os.open(parent.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(dirFd);}}catch(Throwable ignored){}
            boolean committed=target.exists()&&!tmp.exists()&&target.length()>0L;
            if(committed&&verifyEncrypted)committed=isEncrypted(target);
            return committed;
        }catch(Throwable t){try{tmp.delete();}catch(Throwable ignored){}return false;}
        finally{if(dirFd!=null)try{android.system.Os.close(dirFd);}catch(Throwable ignored){}}
    }

    /** Tiny non-destructive Keystore/AES-GCM smoke test used by the in-app health check. */
    public static boolean selfTest(Context context){
        if(context==null)return false;
        File encrypted=new File(context.getCacheDir(),"crypto_selftest_"+System.nanoTime());
        File clear=new File(context.getCacheDir(),"crypto_selftest_clear_"+System.nanoTime());
        byte[] expected=new byte[]{87,65,45,86,65,85,76,84,45,83,69,76,70,84,69,83,84};
        try{
            try(OutputStream out=new FileOutputStream(encrypted)){out.write(expected);out.flush();}
            if(!encryptInPlace(encrypted)||!isEncrypted(encrypted)||!decryptTo(encrypted,clear)||clear.length()!=expected.length)return false;
            try(InputStream in=new FileInputStream(clear)){for(byte b:expected)if(in.read()!=(b&0xff))return false;return in.read()==-1;}
        }catch(Throwable t){return false;}
        finally{try{encrypted.delete();}catch(Throwable ignored){}try{clear.delete();}catch(Throwable ignored){}try{new File(encrypted.getParentFile(),encrypted.getName()+".encrypting").delete();}catch(Throwable ignored){}}
    }

    /** Returns a temporary decrypted cache file for encrypted content. */
    public static File materialize(Context context,File stored,String label){
        if(context==null||stored==null||!stored.exists())return null;
        if(!isEncrypted(stored)){
            // A legacy/plain archive can exist only until the mandatory migration reaches it.
            // Never expose that archive directly: copy to the short-lived cache instead.
            File dir=new File(context.getCacheDir(),"vault_decrypted");if(!dir.exists())dir.mkdirs();
            String ext=extension(label==null?stored.getName():label);File out=new File(dir,"open_"+System.nanoTime()+ext);
            if(copy(stored,out))return out;try{out.delete();}catch(Throwable ignored){}return null;
        }
        File dir=new File(context.getCacheDir(),"vault_decrypted");if(!dir.exists())dir.mkdirs();String ext=extension(label==null?stored.getName():label);
        File out=new File(dir,"open_"+System.nanoTime()+ext);if(decryptTo(stored,out))return out;try{out.delete();}catch(Throwable ignored){}return null;
    }
    private static String extension(String n){if(n==null)return "";int dot=n.lastIndexOf('.');return dot>=0&&dot<n.length()-1?n.substring(dot):"";}

    public static boolean decryptTo(File stored,File out){
        if(stored==null||out==null||!stored.exists())return false;if(!isEncrypted(stored))return copy(stored,out);
        try(InputStream raw=new BufferedInputStream(new FileInputStream(stored),131072)){
            for(byte b:MAGIC)if(raw.read()!=(b&0xff))return false;int ivLen=raw.read();if(ivLen<8||ivLen>32)return false;byte[] iv=new byte[ivLen];int off=0;while(off<ivLen){int n=raw.read(iv,off,ivLen-off);if(n<0)return false;off+=n;}
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            try(CipherInputStream in=new CipherInputStream(raw,c);OutputStream os=new BufferedOutputStream(new FileOutputStream(out),131072)){byte[] buf=new byte[131072];int n;while((n=in.read(buf))>0)os.write(buf,0,n);os.flush();}
            return out.exists()&&out.length()>0;
        }catch(Throwable t){try{out.delete();}catch(Throwable ignored){}return false;}
    }

    private static boolean copy(File inFile,File outFile){try(InputStream in=new FileInputStream(inFile);OutputStream out=new FileOutputStream(outFile)){byte[] b=new byte[131072];int n;while((n=in.read(b))>0)out.write(b,0,n);out.flush();return true;}catch(Throwable t){return false;}}
    public static void cleanupCache(Context context){try{File d=new File(context.getCacheDir(),"vault_decrypted");File[] fs=d.listFiles();if(fs!=null)for(File f:fs)f.delete();}catch(Throwable ignored){}}

    /** v0.5.22: resumable encryption + opaque-name migration with a mandatory full verification pass. */
    public static void migrateExistingAsync(Context context){
        if(context==null||!MIGRATION_RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        Thread worker=new Thread(()->{
            VaultDb db=null;
            try{
                db=new VaultDb(app);
                android.content.SharedPreferences prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
                long cursor=prefs.getLong(MIGRATION_CURSOR,0L);
                int encryptedNow=0,renamedNow=0,failed=0,reviewed=0;
                while(true){
                    List<VaultDb.Media> batch=db.listMediaAfterId(cursor,48);
                    if(batch.isEmpty())break;
                    for(VaultDb.Media m:batch){
                        if(m==null)continue;cursor=Math.max(cursor,m.id);reviewed++;
                        if(m.path==null||m.path.isEmpty())continue;File f=new File(m.path);if(!f.exists())continue;
                        if(!isEncrypted(f)){if(encryptInPlace(f)&&isEncrypted(f))encryptedNow++;else{failed++;continue;}}
                        File normalized=normalizeExistingArchiveName(app,db,m,f);
                        if(normalized==null)failed++;else if(!normalized.equals(f))renamedNow++;
                    }
                    prefs.edit().putLong(MIGRATION_CURSOR,cursor).putInt(MODE_KEY,MODE_ALL).putInt("media_encrypted_migrated",encryptedNow).putInt("media_opaque_renamed",renamedNow).apply();
                    try{Thread.sleep(35L);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                }

                // Critical v0.5.22 fix: never trust the cursor as proof of completion. Scan from ID 0
                // and actively retry anything that remains plaintext or has a recognizable archive name.
                int remaining=0;long verifyCursor=0L;
                if(!Thread.currentThread().isInterrupted())while(true){
                    List<VaultDb.Media> batch=db.listMediaAfterId(verifyCursor,96);if(batch.isEmpty())break;
                    for(VaultDb.Media m:batch){
                        if(m==null)continue;verifyCursor=Math.max(verifyCursor,m.id);
                        if(m.path==null||m.path.isEmpty())continue;File f=new File(m.path);if(!f.exists())continue;
                        if(!isEncrypted(f)){if(encryptInPlace(f)&&isEncrypted(f))encryptedNow++;else{remaining++;continue;}}
                        File normalized=normalizeExistingArchiveName(app,db,m,f);
                        if(normalized==null){remaining++;continue;}
                        if(!normalized.equals(f))renamedNow++;
                        if(VaultFileNames.isProtectedArchive(app,normalized)&&!VaultFileNames.isOpaqueName(normalized.getName()))remaining++;
                    }
                    if(batch.size()<96)break;
                }
                if(Thread.currentThread().isInterrupted())remaining=Math.max(1,remaining);
                remaining+=cleanupRecognizableArchiveOrphans(app,db);
                android.content.SharedPreferences.Editor e=prefs.edit().putInt(MODE_KEY,MODE_ALL).putInt("media_migration_remaining",remaining).putInt("media_encrypted_migrated",encryptedNow).putInt("media_opaque_renamed",renamedNow);
                if(remaining==0){e.putLong(MIGRATION_CURSOR,0L).putLong("media_encrypted_migrated_at",System.currentTimeMillis()).putLong("media_opaque_migrated_at",System.currentTimeMillis());}
                else e.putLong(MIGRATION_CURSOR,0L); // full retry next process/run; failed early IDs can never be skipped
                e.apply();
                db.logEvent("MEDIA_PRIVACY_MIGRATION","revisados="+reviewed+" · cifrados="+encryptedNow+" · nombres opacos="+renamedNow+" · pendientes="+remaining+" · fallos="+failed,0L,0L);
            }catch(Throwable t){if(db!=null)db.logInternalError("MEDIA_MIGRATION",t);}
            finally{MIGRATION_RUNNING.set(false);}
        },"wa-vault-media-migrate");
        worker.setPriority(Thread.MIN_PRIORITY);worker.start();
    }

    private static File normalizeExistingArchiveName(Context app,VaultDb db,VaultDb.Media m,File source){
        if(source==null||!source.exists())return source;
        if(!VaultFileNames.isProtectedArchive(app,source)||VaultFileNames.isOpaqueName(source.getName()))return source;
        File target=VaultFileNames.newOpaqueSibling(source,m==null?null:m.name,m==null?null:m.mime,m==null?null:m.type);if(target==null)return null;
        try{
            if(!copyCiphertext(source,target)){try{target.delete();}catch(Throwable ignored){}return null;}
            if(!isEncrypted(target)||target.length()!=source.length()){try{target.delete();}catch(Throwable ignored){}return null;}
            if(m==null||!db.updateMediaLocalPath(m.id,target.getAbsolutePath())){try{target.delete();}catch(Throwable ignored){}return null;}
            if(!retireLegacyNamedCopy(source)){return null;}
            return target;
        }catch(Throwable t){try{target.delete();}catch(Throwable ignored){}return null;}
    }

    private static int cleanupRecognizableArchiveOrphans(Context app,VaultDb db){
        int remaining=0;File[] dirs=new File[]{new File(app.getFilesDir(),"vault_media"),new File(app.getFilesDir(),"vault_audio_quarantine")};
        for(File dir:dirs){File[] fs=dir.listFiles();if(fs==null)continue;for(File f:fs){if(f==null||!f.isFile()||VaultFileNames.isOpaqueName(f.getName()))continue;String n=f.getName();if(n.endsWith(".encrypting")||n.endsWith(".decrypting"))continue;
            if(db.isMediaPathReferenced(f.getAbsolutePath())){remaining++;continue;}
            try{if(f.delete()||!f.exists())continue;}catch(Throwable ignored){}
            try{File opaque=VaultFileNames.newOpaqueSibling(f,null,null,null);if(opaque!=null&&f.renameTo(opaque)){try{opaque.delete();}catch(Throwable ignored){}if(!f.exists())continue;}}catch(Throwable ignored){}
            remaining++;
        }}return remaining;
    }

    /** Remove the recognizable old filename; if direct deletion is blocked, rename the encrypted orphan to an opaque name. */
    private static boolean retireLegacyNamedCopy(File source){
        if(source==null||!source.exists())return true;
        try{if(source.delete()||!source.exists())return true;}catch(Throwable ignored){}
        try{File opaque=VaultFileNames.newOpaqueSibling(source,null,null,null);if(opaque!=null&&source.renameTo(opaque)){try{opaque.delete();}catch(Throwable ignored){}return !source.exists();}}catch(Throwable ignored){}
        return !source.exists();
    }

    private static boolean copyCiphertext(File source,File target){
        try(InputStream in=new BufferedInputStream(new FileInputStream(source),131072);FileOutputStream fos=new FileOutputStream(target);OutputStream out=new BufferedOutputStream(fos,131072)){byte[] b=new byte[131072];int n;while((n=in.read(b))>0)out.write(b,0,n);out.flush();fos.getFD().sync();return target.exists()&&target.length()==source.length();}catch(Throwable t){try{target.delete();}catch(Throwable ignored){}return false;}
    }

    public static int migrationRemaining(Context context){if(context==null)return -1;return context.getSharedPreferences(PREF,Context.MODE_PRIVATE).getInt("media_migration_remaining",0);}
    public static boolean isMigrationRunning(){return MIGRATION_RUNNING.get();}
}
