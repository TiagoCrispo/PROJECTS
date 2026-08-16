package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Privacy helpers for stable opaque identifiers and encrypted operational preference values. */
public final class MetadataPrivacy {
    private MetadataPrivacy() {}
    private static final String PREF="wa_vault_metadata_privacy";
    private static final String SECRET="hmac_secret_v1";
    private static final String SRC="src_";
    private static final String ENC="enc1:";

    public static String sourceKey(Context context,String raw){
        if(raw==null||raw.isEmpty())return raw==null?"":raw;
        if(raw.startsWith(SRC)||isSafeSynthetic(raw))return raw;
        return SRC+hmacHex(context,raw);
    }

    public static String token(Context context,String namespace,String raw){
        return (namespace==null?"tok":namespace)+"_"+hmacHex(context,raw==null?"":raw);
    }

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
        try{SharedPreferences st=app.getSharedPreferences("wa_vault_settings",Context.MODE_PRIVATE);SharedPreferences.Editor e=st.edit();String tree=st.getString("voice_bank_tree_uri","");if(tree!=null&&!tree.isEmpty()&&!tree.startsWith(ENC))e.putString("voice_bank_tree_uri",seal(app,tree));String dirs=st.getString("voice_bank_hot_dirs","");if(dirs!=null&&!dirs.isEmpty()&&!dirs.startsWith(ENC))e.putString("voice_bank_hot_dirs",seal(app,dirs));e.putString("voice_bank_last_file",st.getLong("voice_bank_last_file_at",0L)>0?"audio detectado":"").putString("voice_bank_probe_newest_name","").apply();}catch(Throwable ignored){}
        try{SharedPreferences d=app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);SharedPreferences.Editor e=d.edit();for(java.util.Map.Entry<String,?> x:d.getAll().entrySet()){String k=x.getKey();Object v=x.getValue();if(k!=null&&k.startsWith("conv_")&&v instanceof String){String raw=(String)v;if(raw!=null&&!raw.isEmpty()&&!raw.startsWith(ENC))e.putString(k,seal(app,raw));}}e.remove("last_delete_conversation").remove("delete_unverifiable_last").remove("last_mediastore_event").remove("notif_audio_uri").putString("last_direct_media",d.getLong("last_direct_media_at",0L)>0?"archivo capturado":"").putLong("metadata_pref_scrubbed_at",System.currentTimeMillis()).apply();}catch(Throwable ignored){}
    }

    private static String hmacHex(Context context,String value){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret(context),"HmacSHA256"));byte[] out=mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder(64);for(byte x:out)b.append(String.format(java.util.Locale.ROOT,"%02x",x));return b.toString();
        }catch(Throwable t){
            // Fail private: never return the source value itself. This fallback is still one-way.
            try{byte[] out=java.security.MessageDigest.getInstance("SHA-256").digest(("WA-Vault|"+value).getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder(64);for(byte x:out)b.append(String.format(java.util.Locale.ROOT,"%02x",x));return b.toString();}catch(Throwable ignored){return Integer.toHexString(value.hashCode());}
        }
    }

    private static byte[] secret(Context context){
        if(context==null)throw new IllegalArgumentException("context");Context app=context.getApplicationContext();SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String existing=p.getString(SECRET,"");if(existing!=null&&!existing.isEmpty())try{return Base64.decode(existing,Base64.NO_WRAP);}catch(Throwable ignored){}
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);String encoded=Base64.encodeToString(key,Base64.NO_WRAP);p.edit().putString(SECRET,encoded).commit();return key;
    }
}
