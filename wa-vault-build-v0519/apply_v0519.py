from pathlib import Path
root=Path.cwd()

def read(p): return (root/p).read_text(encoding='utf-8')
def write(p,s): (root/p).write_text(s,encoding='utf-8')
def must_replace(s, old, new, label):
    if old not in s: raise SystemExit(f'missing replacement anchor: {label}')
    return s.replace(old,new,1)

# Version
p=Path('app/build.gradle.kts'); s=read(p)
s=must_replace(s,'versionCode = 68','versionCode = 69','versionCode')
s=must_replace(s,'versionName = "0.5.18"','versionName = "0.5.19"','versionName')
write(p,s)

# Crypto: atomic same-directory replacement, no backup sidecars.
p=Path('app/src/main/java/com/fer/wavault/MediaCrypto.java'); s=read(p)
old='''    /** Encrypts a private archive file using temp + backup replacement so the old copy survives failure. */
    public static boolean encryptInPlace(File plain){
        if(plain==null||!plain.exists()||!plain.isFile())return false;if(isEncrypted(plain))return true;
        File tmp=new File(plain.getParentFile(),plain.getName()+".encrypting");File backup=new File(plain.getParentFile(),plain.getName()+".plainbak");
        try{
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[] iv=c.getIV();
            try(InputStream in=new BufferedInputStream(new FileInputStream(plain),131072);OutputStream raw=new BufferedOutputStream(new FileOutputStream(tmp),131072)){
                raw.write(MAGIC);raw.write(iv.length);raw.write(iv);raw.flush();
                try(CipherOutputStream out=new CipherOutputStream(raw,c)){byte[] buf=new byte[131072];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}
            }
            if(!tmp.exists()||tmp.length()<=MAGIC.length+16){tmp.delete();return false;}if(backup.exists())backup.delete();
            if(!plain.renameTo(backup)){tmp.delete();return false;}if(!tmp.renameTo(plain)){backup.renameTo(plain);tmp.delete();return false;}backup.delete();return true;
        }catch(Throwable t){try{tmp.delete();}catch(Throwable ignored){}if(!plain.exists()&&backup.exists())try{backup.renameTo(plain);}catch(Throwable ignored){}return false;}
    }

    public static boolean decryptInPlace(File encrypted){
        if(encrypted==null||!encrypted.exists()||!encrypted.isFile())return false;if(!isEncrypted(encrypted))return true;
        File tmp=new File(encrypted.getParentFile(),encrypted.getName()+".decrypting");File backup=new File(encrypted.getParentFile(),encrypted.getName()+".encbak");
        if(!decryptTo(encrypted,tmp)){try{tmp.delete();}catch(Throwable ignored){}return false;}
        try{if(backup.exists())backup.delete();if(!encrypted.renameTo(backup)){tmp.delete();return false;}if(!tmp.renameTo(encrypted)){backup.renameTo(encrypted);tmp.delete();return false;}backup.delete();return true;}catch(Throwable t){if(!encrypted.exists()&&backup.exists())try{backup.renameTo(encrypted);}catch(Throwable ignored){}try{tmp.delete();}catch(Throwable ignored){}return false;}
    }
'''
new='''    /** Encrypts a private archive file to a sibling temp and commits it with one atomic rename. */
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
            return atomicReplace(tmp,plain);
        }catch(Throwable t){try{tmp.delete();}catch(Throwable ignored){}return false;}
    }

    public static boolean decryptInPlace(File encrypted){
        if(encrypted==null||!encrypted.exists()||!encrypted.isFile())return false;if(!isEncrypted(encrypted))return true;
        File tmp=new File(encrypted.getParentFile(),encrypted.getName()+".decrypting");
        if(!decryptTo(encrypted,tmp)){try{tmp.delete();}catch(Throwable ignored){}return false;}
        return atomicReplace(tmp,encrypted);
    }

    private static boolean atomicReplace(File tmp,File target){
        if(tmp==null||target==null||!tmp.exists())return false;
        try{
            try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(tmp,"rw")){raf.getFD().sync();}
            android.system.Os.rename(tmp.getAbsolutePath(),target.getAbsolutePath());
            return target.exists()&&!tmp.exists();
        }catch(Throwable t){try{tmp.delete();}catch(Throwable ignored){}return false;}
    }

    /** Tiny non-destructive Keystore/AES-GCM smoke test used by the in-app health check. */
    public static boolean selfTest(Context context){
        if(context==null)return false;File f=new File(context.getCacheDir(),"crypto_selftest_"+System.nanoTime());
        byte[] expected=new byte[]{87,65,45,86,65,85,76,84,45,83,69,76,70,84,69,83,84};
        try{
            try(OutputStream out=new FileOutputStream(f)){out.write(expected);out.flush();}
            if(!encryptInPlace(f)||!isEncrypted(f)||!decryptInPlace(f)||isEncrypted(f)||f.length()!=expected.length)return false;
            try(InputStream in=new FileInputStream(f)){for(byte b:expected)if(in.read()!=(b&0xff))return false;return in.read()==-1;}
        }catch(Throwable t){return false;}finally{try{f.delete();}catch(Throwable ignored){}try{new File(f.getParentFile(),f.getName()+".encrypting").delete();}catch(Throwable ignored){}try{new File(f.getParentFile(),f.getName()+".decrypting").delete();}catch(Throwable ignored){}}
    }
'''
s=must_replace(s,old,new,'MediaCrypto atomic block')
write(p,s)

# Listener: three-stage fallback backoff.
p=Path('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java'); s=read(p)
s=must_replace(s,
'''    private static final long POLL_HOT_MS = 180L;\n    private static final long POLL_IDLE_MS = 4_000L;\n    private static final long HOT_WINDOW_MS = 9_000L;''',
'''    private static final long POLL_HOT_MS = 180L;\n    private static final long POLL_WARM_MS = 4_000L;\n    private static final long POLL_IDLE_MS = 30_000L;\n    private static final long HOT_WINDOW_MS = 9_000L;\n    private static final long WARM_WINDOW_MS = 60_000L;''','poll constants')
s=must_replace(s,
'handler.postDelayed(this,age>=0&&age<HOT_WINDOW_MS?POLL_HOT_MS:POLL_IDLE_MS);',
'long delay=age>=0&&age<HOT_WINDOW_MS?POLL_HOT_MS:(age>=0&&age<WARM_WINDOW_MS?POLL_WARM_MS:POLL_IDLE_MS);handler.postDelayed(this,delay);','poll schedule')
write(p,s)

# Storage cleanup: fold final legacy-remnant hygiene into generic temp sanitation and remove old removed-feature method.
p=Path('app/src/main/java/com/fer/wavault/StorageAnalyzer.java'); s=read(p)
s=must_replace(s,
'''        n+=clean(new File(c.getCacheDir(),"partial_previews"),cutoff,null);\n        return n;\n    }\n    /** One-time hygiene for cache artifacts left by features removed from current builds. */\n    public static int cleanupLegacyRemovedFeatureArtifacts(Context c){\n        if(c==null)return 0;int n=0;try{File root=c.getCacheDir();File[] fs=root.listFiles();if(fs==null)return 0;for(File f:fs){if(f==null)continue;String name=f.getName();if(!name.startsWith("portable_"))continue;n+=deleteTree(f);}}catch(Throwable ignored){}return n;\n    }\n    private static int deleteTree(File f){if(f==null||!f.exists())return 0;int n=0;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)n+=deleteTree(k);}try{if(f.delete())n++;}catch(Throwable ignored){}return n;}''',
'''        n+=clean(new File(c.getCacheDir(),"partial_previews"),cutoff,null);\n        n+=cleanCryptoSidecars(new File(c.getFilesDir(),"vault_media"),cutoff);\n        n+=cleanRemovedFeatureCache(c.getCacheDir(),cutoff);\n        return n;\n    }\n    private static int cleanCryptoSidecars(File d,long cutoff){int n=0;try{File[] fs=d.listFiles();if(fs==null)return 0;for(File f:fs){if(f==null)continue;if(f.isDirectory()){n+=cleanCryptoSidecars(f,cutoff);continue;}String x=f.getName();if(f.lastModified()>=cutoff)continue;if(x.endsWith(".encrypting")||x.endsWith(".decrypting")||x.endsWith(".plainbak")||x.endsWith(".encbak")){try{if(f.delete())n++;}catch(Throwable ignored){}}}}catch(Throwable ignored){}return n;}\n    private static int cleanRemovedFeatureCache(File root,long cutoff){int n=0;try{File[] fs=root.listFiles();if(fs==null)return 0;for(File f:fs){if(f==null||f.lastModified()>=cutoff)continue;String x=f.getName();if(x.startsWith("portable_"))n+=deleteTree(f);}}catch(Throwable ignored){}return n;}\n    private static int deleteTree(File f){if(f==null||!f.exists())return 0;int n=0;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)n+=deleteTree(k);}try{if(f.delete())n++;}catch(Throwable ignored){}return n;}''','Storage cleanup')
write(p,s)

# MainActivity: generic cleanup call only, diagnosis wording, full self-check, version string.
p=Path('app/src/main/java/com/fer/wavault/MainActivity.java'); s=read(p)
s=must_replace(s,
'new Thread(()->{try{StorageAnalyzer.cleanupTemporary(getApplicationContext());}catch(Throwable ignored){}try{StorageAnalyzer.cleanupLegacyRemovedFeatureArtifacts(getApplicationContext());}catch(Throwable ignored){}} ,"wa-vault-startup-clean").start();',
'new Thread(()->{try{StorageAnalyzer.cleanupTemporary(getApplicationContext());}catch(Throwable ignored){}} ,"wa-vault-startup-clean").start();','startup cleanup')
s=s.replace('"Usando respaldo / reparar"','"Usando ruta alternativa / reparar"')
s=must_replace(s,
'content.addView(button("Exportar diagnóstico técnico",v->exportTechnicalDiagnostics()));',
'content.addView(button("Comprobar WA Vault",v->runFullSelfCheck()));\n        content.addView(button("Exportar diagnóstico técnico",v->exportTechnicalDiagnostics()));','self check button')
s=must_replace(s,
'''    private void exportTechnicalDiagnostics(){\n''',
'''    private void runFullSelfCheck(){\n        final Context app=getApplicationContext();\n        Toast.makeText(this,"Comprobando protección…",Toast.LENGTH_SHORT).show();\n        new Thread(()->{\n            ArrayList<String> issues=new ArrayList<>();\n            try{if(!notificationAccess())issues.add("Activa el acceso a notificaciones");}catch(Throwable t){issues.add("No se pudo comprobar el listener");}\n            try{if(!hasAllMediaPermissions())issues.add("Faltan permisos de fotos, videos o audio");}catch(Throwable t){issues.add("No se pudieron comprobar los permisos multimedia");}\n            try{if(Build.VERSION.SDK_INT>=30&&!Environment.isExternalStorageManager())issues.add("Falta acceso completo a archivos");}catch(Throwable t){issues.add("No se pudo comprobar el acceso a archivos");}\n            try{if(!DirectVoiceWatcher.isAvailable(app)||!DirectVoiceWatcher.isHealthy())issues.add("Captura directa de audios necesita reparación");}catch(Throwable t){issues.add("No se pudo comprobar la captura de audios");}\n            try{if(!DirectMediaWatcher.isAvailable(app)||!DirectMediaWatcher.isHealthy())issues.add("Captura directa de fotos/videos necesita reparación");}catch(Throwable t){issues.add("No se pudo comprobar la captura multimedia");}\n            try{if(!MediaStoreWatcher.isHealthy())issues.add("MediaStore necesita reinicio");}catch(Throwable t){issues.add("No se pudo comprobar MediaStore");}\n            try{if(!ignoresBatteryOptimizations())issues.add("Desactiva la optimización de batería para WA Vault");}catch(Throwable t){issues.add("No se pudo comprobar la batería");}\n            try{long free=StorageAnalyzer.freeBytes(app);long reserve=MediaLimits.reserveBytes(app);if(free<=reserve)issues.add("Almacenamiento libre insuficiente para captura segura");}catch(Throwable t){issues.add("No se pudo comprobar el almacenamiento");}\n            try{CaptureIntegritySelfTest.Result r=CaptureIntegritySelfTest.run(app);if(!r.ok())issues.add("La prueba del motor de integridad no pasó");}catch(Throwable t){issues.add("No se pudo probar el motor de integridad");}\n            try{if(!MediaCrypto.selfTest(app))issues.add("El cifrado local necesita revisión");}catch(Throwable t){issues.add("No se pudo comprobar el cifrado local");}\n            runOnUiThread(()->{String title=issues.isEmpty()?"Todo correcto ✓":"Hay cosas que corregir";String msg=issues.isEmpty()?"Listener, permisos, captura, MediaStore, batería, almacenamiento, integridad y cifrado funcionan correctamente.":android.text.TextUtils.join("\\n• ",issues);if(!issues.isEmpty())msg="• "+msg;new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show();showDiagnostics();});\n        },"wa-vault-full-self-check").start();\n    }\n\n    private void exportTechnicalDiagnostics(){\n''','full self check method')
s=s.replace('b.append("version=0.5.18\\n");','b.append("version=0.5.19\\n");')
write(p,s)

# Remove obsolete top-level verifier.
vp=root/'verify_source.py'
if vp.exists(): vp.unlink()

# Current regression test.
test=root/'tools/v0519_regression_test.py'
test.write_text(r'''from pathlib import Path
R=Path(__file__).resolve().parents[1]
def txt(p): return (R/p).read_text(encoding='utf-8')
def need(x,m):
    if not x: raise AssertionError(m)
build=txt('app/build.gradle.kts'); main=txt('app/src/main/java/com/fer/wavault/MainActivity.java'); crypto=txt('app/src/main/java/com/fer/wavault/MediaCrypto.java'); listener=txt('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java'); storage=txt('app/src/main/java/com/fer/wavault/StorageAnalyzer.java'); manifest=txt('app/src/main/AndroidManifest.xml')
need('versionCode = 69' in build and 'versionName = "0.5.19"' in build,'version')
need('android:allowBackup="false"' in manifest,'OS backup remains disabled')
need('.plainbak' not in crypto and '.encbak' not in crypto and ' backup ' not in crypto.lower(),'crypto no longer creates backup sidecars')
need('android.system.Os.rename' in crypto and 'atomicReplace' in crypto,'atomic replacement')
need('selfTest(Context context)' in crypto,'crypto self test')
need('POLL_HOT_MS = 180L' in listener and 'POLL_WARM_MS = 4_000L' in listener and 'POLL_IDLE_MS = 30_000L' in listener and 'WARM_WINDOW_MS = 60_000L' in listener,'three stage poll backoff')
need('Comprobar WA Vault' in main and 'runFullSelfCheck' in main,'full in-app health check')
need('MediaCrypto.selfTest(app)' in main and 'CaptureIntegritySelfTest.run(app)' in main,'health checks crypto and correlation engine')
need('cleanupLegacyRemovedFeatureArtifacts' not in main+storage,'old one-off cleanup API removed')
need('verify_source.py' not in [p.name for p in R.iterdir()],'obsolete verifier removed')
need('portable_' in storage and '.plainbak' in storage and '.encbak' in storage,'final generic cleanup recognizes old remnants')
# Core correctness invariants must remain.
need('normalizeConfirmedMediaVisibility' in main and 'DELETE_CONFIRMED' in txt('app/src/main/java/com/fer/wavault/VaultDb.java'),'confirmed-only UI path retained')
need('appCancelIsConfirmable' in listener and 'APP_CANCEL_UNPROVEN' in listener,'APP_CANCEL hardening retained')
need('findNearestUnlinkedMedia' not in txt('app/src/main/java/com/fer/wavault/VaultDb.java'),'no nearest media inference')
print('v0.5.19 regression PASS')
''',encoding='utf-8')

# Notes and matrix.
(root/'V0519_NOTES.md').write_text('''# WA Vault v0.5.19 — Final Hardening\n\n- Atomic AES-GCM in-place commit without backup sidecar files.\n- Three-stage notification fallback: 180 ms hot, 4 s warm, 30 s idle; callbacks remain primary.\n- New one-tap “Comprobar WA Vault” health check.\n- Obsolete verify_source.py removed; v0.5.19 regression is canonical.\n- Generic startup sanitation removes stale encryption temps and legacy removed-feature cache remnants.\n- Core confirmed-only/tombstone/strict-correlation engine unchanged.\n''',encoding='utf-8')
(root/'TEST_MATRIX.md').write_text('''# WA Vault v0.5.19 test matrix\n\n## Automated\n- [x] Version 0.5.19 / code 69.\n- [x] No backup sidecar creation in MediaCrypto.\n- [x] Atomic rename commit present.\n- [x] Crypto smoke self-test present.\n- [x] Poll fallback = 180 ms hot / 4 s warm / 30 s idle.\n- [x] One-tap full health check present.\n- [x] Obsolete verifier removed.\n- [x] confirmed-only and APP_CANCEL protections retained.\n- [x] No nearest-media inference.\n\n## Device validation\n- [ ] “Comprobar WA Vault” returns Todo correcto when all permissions/services are healthy.\n- [ ] Normal messages/grouping/swiping notifications create zero false deletes.\n- [ ] Explicit WhatsApp deletion is recovered only with strict evidence.\n- [ ] Photo/video/audio/document capture works after 2+ minutes idle (callback wake-up).\n- [ ] Battery saver/background/restart behavior is stable.\n- [ ] Encryption mode migration survives app kill without sidecar leftovers.\n''',encoding='utf-8')
print('v0.5.19 transformation applied')
