package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.atomic.AtomicBoolean;

/** Privacy helpers for stable opaque identifiers and encrypted operational preference values. */
public final class MetadataPrivacy {
    private MetadataPrivacy() {}
    private static final String PREF="wa_vault_metadata_privacy";
    private static final String SECRET="hmac_secret_v1";
    private static final String SRC="src_";
    private static final String HMAC_ALIAS="wa_vault_metadata_hmac_v1";
    private static final String ENC="enc1:";
    private static final String MIGRATION_0525="privacy_migration_v0525_complete";
    private static final String MIGRATION_0526="privacy_migration_v0526_complete";
    private static final String MEDIA_HASH_PREFIX="mh1_";
    private static final String SNAPSHOT_0525="snapshot_hmac_v0525_prepared";
    private static final AtomicBoolean MIGRATION_RUNNING=new AtomicBoolean(false);

    public static String sourceKey(Context context,String raw){
        if(raw==null||raw.isEmpty())return raw==null?"":raw;
        if(raw.startsWith(SRC)||isSafeSynthetic(raw))return raw;
        return SRC+hmacHex(context,raw);
    }

    public static String token(Context context,String namespace,String raw){
        return (namespace==null?"tok":namespace)+"_"+hmacHex(context,raw==null?"":raw);
    }

    /** Stored media equality token. The plaintext SHA-256 never reaches SQLite. */
    public static String contentHash(Context context,String rawSha256){
        if(rawSha256==null||rawSha256.isEmpty())return "";
        if(rawSha256.startsWith(MEDIA_HASH_PREFIX))return rawSha256;
        return MEDIA_HASH_PREFIX+hmacHex(context,"media-content|"+rawSha256);
    }

    public static boolean isProtectedContentHash(String value){return value==null||value.isEmpty()||value.startsWith(MEDIA_HASH_PREFIX);}

    public static boolean isSensitiveSource(String raw){
        return raw!=null&&!raw.isEmpty()&&!raw.startsWith(SRC)&&!isSafeSynthetic(raw);
    }

    private static boolean isSafeSynthetic(String raw){
        return raw.startsWith("partial-preview:")||raw.startsWith("local:")||raw.startsWith("deleted:")||raw.startsWith("opaque:");
    }

    public static String seal(Context context,String clear){
        if(clear==null||clear.isEmpty())return "";
        if(clear.startsWith(ENC))return clear;
        byte[] blob=new CryptoManager(context).encrypt(clear);if(blob==null)return "";
        return ENC+Base64.encodeToString(blob,Base64.NO_WRAP);
    }

    public static String open(Context context,String stored){
        if(stored==null||stored.isEmpty())return "";
        if(!stored.startsWith(ENC))return stored; // migration-compatible read of old private prefs
        try{return new CryptoManager(context).decrypt(Base64.decode(stored.substring(ENC.length()),Base64.NO_WRAP));}catch(Throwable t){return "";}
    }

    /** One-time scrubbing/encryption of sensitive values left by older releases. */
    public static void migrateOperationalPreferences(Context context){
        if(context==null)return;Context app=context.getApplicationContext();
        try{SharedPreferences st=app.getSharedPreferences("wa_vault_settings",Context.MODE_PRIVATE);SharedPreferences.Editor e=st.edit();String tree=st.getString("voice_bank_tree_uri","");if(tree!=null&&!tree.isEmpty()&&!tree.startsWith(ENC))e.putString("voice_bank_tree_uri",seal(app,tree));String dirs=st.getString("voice_bank_hot_dirs","");if(dirs!=null&&!dirs.isEmpty()&&!dirs.startsWith(ENC))e.putString("voice_bank_hot_dirs",seal(app,dirs));e.putString("voice_bank_last_file",st.getLong("voice_bank_last_file_at",0L)>0?"audio detectado":"").putString("voice_bank_probe_newest_name","").putString("direct_watcher_last_event",st.getLong("direct_watcher_last_event_at",0L)>0?"evento de audio":"").apply();}catch(Throwable ignored){}
        try{SharedPreferences d=app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);SharedPreferences.Editor e=d.edit();for(java.util.Map.Entry<String,?> x:d.getAll().entrySet()){String k=x.getKey();Object v=x.getValue();if(k!=null&&k.startsWith("conv_")&&v instanceof String){String raw=(String)v;if(raw!=null&&!raw.isEmpty()&&!raw.startsWith(ENC))e.putString(k,seal(app,raw));}}e.remove("last_delete_conversation").remove("delete_unverifiable_last").remove("last_mediastore_event").remove("notif_audio_uri").putString("last_direct_media",d.getLong("last_direct_media_at",0L)>0?"archivo capturado":"").putLong("metadata_pref_scrubbed_at",System.currentTimeMillis()).apply();}catch(Throwable ignored){}
    }

    /** Clears only transient notification snapshots once before v0.5.25 starts using HMAC tokens. */
    public static synchronized void prepareV0525(Context context){
        if(context==null)return;Context app=context.getApplicationContext();SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);if(p.getBoolean(SNAPSHOT_0525,false))return;
        // Ensure the Keystore key exists before discarding old SHA-based transient equality tokens.
        token(app,"prep","v0525");
        SharedPreferences d=app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);SharedPreferences.Editor e=d.edit();
        for(String k:d.getAll().keySet())if(k!=null&&(k.startsWith("snapshot_")||k.startsWith("snapshot2_")||k.startsWith("conv_snapshot2_")||k.startsWith("recent_removed_snapshot2_")||k.startsWith("recent_removed_ids_")||k.startsWith("recent_removed_at_")||k.startsWith("burst2_")||k.startsWith("state2_")))e.remove(k);
        e.apply();p.edit().putBoolean(SNAPSHOT_0525,true).commit();
    }

    /** Versioned, serialized and resumable privacy migration. Repeated callers become no-ops. */
    public static void runFinalMigrationAsync(Context context){
        if(context==null)return;Context app=context.getApplicationContext();prepareV0525(app);SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);if(p.getBoolean(MIGRATION_0525,false))return;if(!MIGRATION_RUNNING.compareAndSet(false,true))return;
        Thread t=new Thread(()->{try{runV0525Pass(app,p);}finally{MIGRATION_RUNNING.set(false);}},"wa-vault-v0525-privacy");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);t.start();
    }

    /** v0.5.26: also hides media content hashes and removes obsolete Gallery-export prefs. */
    public static void runV0526MigrationAsync(Context context){
        if(context==null)return;Context app=context.getApplicationContext();prepareV0525(app);SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);if(p.getBoolean(MIGRATION_0526,false))return;if(!MIGRATION_RUNNING.compareAndSet(false,true))return;
        Thread t=new Thread(()->{try{
            if(!p.getBoolean(MIGRATION_0525,false))runV0525Pass(app,p);
            VaultDb db=new VaultDb(app);int remaining=db.migrateContentHashesToHmac();
            try{app.getSharedPreferences("wa_vault_gallery_exports",Context.MODE_PRIVATE).edit().clear().commit();}catch(Throwable ignored){}
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("privacy_v0526_hash_remaining",remaining).putLong("privacy_v0526_migration_at",System.currentTimeMillis()).apply();
            if(remaining==0)p.edit().putBoolean(MIGRATION_0526,true).commit();
        }catch(Throwable ignored){}finally{MIGRATION_RUNNING.set(false);}},"wa-vault-v0526-privacy");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);t.start();
    }

    private static void runV0525Pass(Context app,SharedPreferences p){
        try{migrateOperationalPreferences(app);VaultDb db=new VaultDb(app);int sourceRemaining=db.migrateSensitiveSourceMetadata();int fpFailures=db.migrateMessageFingerprintsToHmac();int fpRemaining=db.countLegacyMessageFingerprints();boolean complete=sourceRemaining==0&&fpFailures==0&&fpRemaining==0;app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("privacy_v0525_source_remaining",sourceRemaining).putInt("privacy_v0525_fingerprint_remaining",fpRemaining).putInt("privacy_v0525_fingerprint_failures",fpFailures).putLong("privacy_v0525_migration_at",System.currentTimeMillis()).apply();if(complete)p.edit().putBoolean(MIGRATION_0525,true).commit();}catch(Throwable ignored){}
    }

    static boolean migrationRunning(){return MIGRATION_RUNNING.get();}
    public static boolean finalMigrationComplete(Context context){return context!=null&&context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE).getBoolean(MIGRATION_0525,false);}
    public static boolean v0526MigrationComplete(Context context){return context!=null&&context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE).getBoolean(MIGRATION_0526,false);}

    public static boolean selfTest(Context context){
        if(context==null)return false;
        try{
            String a=token(context,"self","wa-vault-hmac-test");
            String b=token(context,"self","wa-vault-hmac-test");
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
            boolean alias=ks.containsAlias(HMAC_ALIAS);
            boolean legacyGone=!context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE).contains(SECRET);
            return alias&&legacyGone&&a.equals(b)&&a.length()>20;
        }catch(Throwable t){return false;}
    }

    private static String hmacHex(Context context,String value){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(hmacKey(context));byte[] out=mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(64);for(byte x:out)b.append(String.format(java.util.Locale.ROOT,"%02x",x));return b.toString();
        }catch(Throwable t){
            // Identifier privacy is fail-closed. A deterministic unkeyed hash would make
            // phone numbers/paths dictionary-testable if private storage were ever exposed.
            throw new IllegalStateException("metadata hmac unavailable",t);
        }
    }

    private static synchronized SecretKey hmacKey(Context context) throws Exception{
        if(context==null)throw new IllegalArgumentException("context");
        Context app=context.getApplicationContext();
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        java.security.Key existing=ks.getKey(HMAC_ALIAS,null);
        if(existing instanceof SecretKey)return (SecretKey)existing;

        SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String legacy=p.getString(SECRET,"");
        if(legacy!=null&&!legacy.isEmpty()){
            byte[] raw=Base64.decode(legacy,Base64.NO_WRAP);
            SecretKeySpec spec=new SecretKeySpec(raw,"HmacSHA256");
            KeyProtection protection=new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).build();
            ks.setEntry(HMAC_ALIAS,new KeyStore.SecretKeyEntry(spec),protection);
            java.security.Key imported=ks.getKey(HMAC_ALIAS,null);
            if(!(imported instanceof SecretKey))throw new IllegalStateException("hmac import failed");
            // Same bytes => existing src_ identifiers remain stable for future captures.
            p.edit().remove(SECRET).commit();
            return (SecretKey)imported;
        }

        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(HMAC_ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256).build());
        return gen.generateKey();
    }

}
