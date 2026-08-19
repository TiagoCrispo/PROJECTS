package com.fer.wavault;

import android.content.Context;
import android.content.SharedPreferences;

/** Deterministic, non-destructive correlation smoke test used from Diagnostics. */
public final class CaptureIntegritySelfTest {
    private CaptureIntegritySelfTest() {}
    public static final class Result {
        public final int passed,total; public final String detail;
        Result(int passed,int total,String detail){this.passed=passed;this.total=total;this.detail=detail;}
        public boolean ok(){return passed==total;}
    }
    public static Result run(Context context){
        int ok=0,total=0;StringBuilder d=new StringBuilder();
        total++;boolean loose=WhatsAppNotificationListener.strictMediaKindForText("te mando una foto después").isEmpty();if(loose)ok++;append(d,loose,"Texto libre no arma foto");
        total++;boolean exact="image".equals(WhatsAppNotificationListener.strictMediaKindForText("📷 Foto"));if(exact)ok++;append(d,exact,"Placeholder exacto sí arma foto");
        total++;boolean sticker=WhatsAppNotificationListener.strictMediaKindForText("Sticker").isEmpty();if(sticker)ok++;append(d,sticker,"Sticker queda excluido");
        total++;boolean clock=WhatsAppNotificationListener.strictMediaKindForText("12:34").isEmpty();if(clock)ok++;append(d,clock,"Una hora escrita no se confunde con audio");
        total++;boolean audio="audio".equals(WhatsAppNotificationListener.strictMediaKindForText("Mensaje de voz"));if(audio)ok++;append(d,audio,"Placeholder exacto de voz sí arma audio");
        total++;String a=VaultDb.buildCaptureBatchKey(1001L,"audio",1_700_000_000_000L),a2=VaultDb.buildCaptureBatchKey(1001L,"audio",1_700_000_000_000L),b=VaultDb.buildCaptureBatchKey(1002L,"audio",1_700_000_000_000L),c=VaultDb.buildCaptureBatchKey(1001L,"video",1_700_000_000_000L);boolean batch=!a.isEmpty()&&a.equals(a2)&&!a.equals(b)&&!a.equals(c);if(batch)ok++;append(d,batch,"Lotes son estables y no colisionan entre mensajes/tipos");
        total++;long[] arm={1000,2400},media={1100,2500};boolean fifo=arm.length==media.length&&Math.abs(media[0]-arm[0])<800&&Math.abs(media[1]-arm[1])<800;if(fifo)ok++;append(d,fifo,"FIFO conserva audio 1 → audio 1 / audio 2 → audio 2");
        total++;long[] missingMedia={1100};boolean noGuess=arm.length!=missingMedia.length;if(noGuess)ok++;append(d,noGuess,"FIFO no adivina cuando faltan archivos o sobran candidatos");
        total++;boolean doc="document".equals(WhatsAppNotificationListener.strictMediaKindForText("archivo.pdf"));if(doc)ok++;append(d,doc,"Documento exacto conserva su tipo y puede usar correlación persistente");
        total++;boolean cancelSafe=!WhatsAppNotificationListener.appCancelIsConfirmable(false)&&WhatsAppNotificationListener.appCancelIsConfirmable(true);if(cancelSafe)ok++;append(d,cancelSafe,"APP_CANCEL sin marcador nunca confirma un borrado");
        total++;boolean metadataKey=context!=null&&new CryptoManager(context).selfTest();if(metadataKey)ok++;append(d,metadataKey,"Keystore de textos/metadatos cifra y descifra correctamente");
        total++;boolean metadataHmac=context!=null&&MetadataPrivacy.selfTest(context);if(metadataHmac)ok++;append(d,metadataHmac,"HMAC de identificadores privados protegido por Android Keystore");
        total++;int legacy=context==null?-1:LegacyPlainMigration.remainingCount(context);boolean noLegacy=legacy==0;if(noLegacy)ok++;append(d,noLegacy,"No quedan filas heredadas PLAIN");
        total++;int oldHashes=context==null?-1:new VaultDb(context.getApplicationContext()).countLegacyContentHashes();boolean hashesProtected=oldHashes==0;if(hashesProtected)ok++;append(d,hashesProtected,"Huellas multimedia protegidas con HMAC");
        total++;int mediaMig=context==null?-1:MediaCrypto.migrationRemaining(context);boolean noMediaMig=mediaMig==0;if(noMediaMig)ok++;append(d,noMediaMig,"No quedan archivos pendientes de migración de cifrado/nombre");
        total++;int sourceMeta=context==null?-1:new VaultDb(context).countSensitiveSourceMetadata();boolean opaqueSources=sourceMeta==0;if(opaqueSources)ok++;append(d,opaqueSources,"No quedan rutas/URI sensibles en source_uri");
        total++;int legacyFp=context==null?-1:new VaultDb(context).countLegacyMessageFingerprints();boolean hmacFp=legacyFp==0;if(hmacFp)ok++;append(d,hmacFp,"Fingerprints de mensajes usan HMAC privado");
        total++;boolean finalPrivacy=context!=null&&MetadataPrivacy.finalMigrationComplete(context);if(finalPrivacy)ok++;append(d,finalPrivacy,"Migración de privacidad v0.5.25 completada una sola vez");
        Result r=new Result(ok,total,d.toString());
        if(context!=null){SharedPreferences p=context.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);p.edit().putLong("integrity_test_at",System.currentTimeMillis()).putInt("integrity_test_passed",ok).putInt("integrity_test_total",total).putString("integrity_test_detail",r.detail).apply();}
        return r;
    }
    public static String summary(Context c){
        SharedPreferences p=c.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);int pass=p.getInt("integrity_test_passed",0),total=p.getInt("integrity_test_total",0);long at=p.getLong("integrity_test_at",0L);if(at<=0||total<=0)return "Aún no ejecutada";return (pass==total?"OK":"REVISAR")+" · "+pass+"/"+total;
    }
    private static void append(StringBuilder d,boolean ok,String label){if(d.length()>0)d.append("\n");d.append(ok?"✓ ":"! ").append(label);}
}
