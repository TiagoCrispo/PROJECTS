package com.fer.wavault;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class CryptoManager {
    private static final String ALIAS = "wa_vault_aes_v1";
    private final Context appContext;
    public CryptoManager(Context context){appContext=context==null?null:context.getApplicationContext();}

    private SecretKey getKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(ALIAS)){KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build();kg.init(spec);kg.generateKey();}
        return ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS,null)).getSecretKey();
    }

    private byte[] encryptOnce(String value) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,getKey());byte[] iv=c.getIV(),enc=c.doFinal(value.getBytes(StandardCharsets.UTF_8));byte[] out=new byte[1+iv.length+enc.length];out[0]=(byte)iv.length;System.arraycopy(iv,0,out,1,iv.length);System.arraycopy(enc,0,out,1+iv.length,enc.length);return out;
    }

    private void recordKeystoreFailure(Throwable t){
        if(appContext==null)return;
        try{android.content.SharedPreferences p=appContext.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);int n=p.getInt("keystore_fail_count",0);String kind=t==null?"Unknown":t.getClass().getSimpleName();p.edit().putInt("keystore_fail_count",n+1).putLong("keystore_fail_at",System.currentTimeMillis()).putString("keystore_fail_last","KEYSTORE_FAIL:"+kind).apply();}catch(Throwable ignored){}
    }

    public byte[] encrypt(String value){
        if(value==null)value="";Throwable last=null;
        for(int attempt=0;attempt<2;attempt++){
            try{return encryptOnce(value);}catch(Throwable e){last=e;if(attempt==0)try{Thread.sleep(35L);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}}
        }
        recordKeystoreFailure(last);
        return null;
    }

    /** Normal reads accept only AES-GCM. Legacy clear rows are readable solely while the one-shot v0.5.21 migration is incomplete. */
    /** Fixed in-memory smoke test for the metadata/text Keystore alias. No user data is touched. */
    public boolean selfTest(){
        final String probe="WA_VAULT_METADATA_SELFTEST_V1";
        try{byte[] enc=encryptOnce(probe);return enc!=null&&probe.equals(decrypt(enc));}catch(Throwable t){recordKeystoreFailure(t);return false;}
    }

    public String decrypt(byte[] blob){
        if(blob==null)return "";
        if(LegacyPlainMigration.isLegacy(blob)){
            if(appContext!=null&&!LegacyPlainMigration.isComplete(appContext))return LegacyPlainMigration.decodeForMigration(blob);
            return "[contenido cifrado]";
        }
        try{int ivLen=blob[0]&0xff;if(ivLen<8||ivLen>32||blob.length<=1+ivLen)return "[contenido cifrado]";byte[] iv=new byte[ivLen],enc=new byte[blob.length-1-ivLen];System.arraycopy(blob,1,iv,0,ivLen);System.arraycopy(blob,1+ivLen,enc,0,enc.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,getKey(),new GCMParameterSpec(128,iv));return new String(c.doFinal(enc),StandardCharsets.UTF_8);}catch(Exception e){return "[contenido cifrado]";}
    }
}
