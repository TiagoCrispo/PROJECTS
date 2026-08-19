package com.fer.wavault;

import android.content.Context;
import java.io.File;

public final class StorageAnalyzer {
    private StorageAnalyzer() {}
    public static long freeBytes(Context c){try{return c==null?0L:c.getFilesDir().getUsableSpace();}catch(Throwable t){return 0L;}}
    public static long vaultPhysicalBytes(Context c){
        if(c==null)return 0L;long n=0L;
        n+=dirBytes(new File(c.getFilesDir(),"vault_media"));
        n+=dirBytes(new File(c.getFilesDir(),"vault_audio_quarantine"));
        n+=dirBytes(new File(c.getFilesDir(),"vault_staging"));
        n+=dirBytes(new File(c.getFilesDir(),"vault_partial"));
        n+=dirBytes(new File(c.getCacheDir(),"wa_early_audio"));
        n+=dirBytes(new File(c.getCacheDir(),"partial_previews"));
        n+=dirBytes(new File(c.getCacheDir(),"vault_share"));
        n+=dirBytes(new File(c.getCacheDir(),"vault_decrypted"));
        return n;
    }
    private static long dirBytes(File d){long n=0L;try{File[] fs=d.listFiles();if(fs==null)return 0L;for(File f:fs){if(f==null)continue;if(f.isDirectory())n+=dirBytes(f);else n+=Math.max(0L,f.length());}}catch(Throwable ignored){}return n;}
    public static int cleanupTemporary(Context c){
        if(c==null)return 0;int n=0;long cutoff=System.currentTimeMillis()-6L*60L*60L*1000L;
        // Never age-delete completed ready_* captures here: CaptureRecovery owns them. Recovery
        // and maintenance run asynchronously at startup, so deleting ready_* by age would race and
        // lose a valid crash-recoverable file before it is committed. Only incomplete parts expire.
        n+=clean(new File(c.getFilesDir(),"vault_staging"),cutoff,new String[]{"fast_part_","part_"});
        // Partial recovery artifacts are encrypted and diagnostic-only. Their DB rows are pruned
        // at the same 24h horizon by VaultMaintenance; this also removes crash-orphans.
        n+=clean(new File(c.getFilesDir(),"vault_partial"),System.currentTimeMillis()-24L*60L*60L*1000L,null);
        n+=clean(new File(c.getCacheDir(),"wa_early_audio"),cutoff,null);
        n+=cleanupShareTemporary(c,15L*60L*1000L);
        n+=cleanupDecryptedTemporary(c);
        n+=clean(new File(c.getCacheDir(),"partial_previews"),cutoff,null);
        n+=cleanCryptoSidecars(new File(c.getFilesDir(),"vault_media"),cutoff);
        return n;
    }
    public static int cleanupDecryptedTemporary(Context c){if(c==null)return 0;return clean(new File(c.getCacheDir(),"vault_decrypted"),System.currentTimeMillis()-5L*60L*1000L,null);}
    public static int cleanupShareTemporary(Context c,long maxAgeMs){if(c==null)return 0;long age=Math.max(60_000L,maxAgeMs);return clean(new File(c.getCacheDir(),"vault_share"),System.currentTimeMillis()-age,null);}
    private static int cleanCryptoSidecars(File d,long cutoff){int n=0;try{File[] fs=d.listFiles();if(fs==null)return 0;for(File f:fs){if(f==null)continue;if(f.isDirectory()){n+=cleanCryptoSidecars(f,cutoff);continue;}String x=f.getName();if(f.lastModified()>=cutoff)continue;if(x.endsWith(".encrypting")||x.endsWith(".decrypting")){try{if(f.delete())n++;}catch(Throwable ignored){}}}}catch(Throwable ignored){}return n;}
    private static int clean(File d,long cutoff,String[] prefixes){int n=0;try{File[] fs=d.listFiles();if(fs==null)return 0;for(File f:fs){if(f==null||!f.isFile()||f.lastModified()>=cutoff)continue;boolean ok=prefixes==null; if(!ok)for(String p:prefixes)if(f.getName().startsWith(p)){ok=true;break;} if(ok&&f.delete())n++;}}catch(Throwable ignored){}return n;}
}
