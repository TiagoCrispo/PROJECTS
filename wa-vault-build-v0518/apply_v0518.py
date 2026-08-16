from pathlib import Path

ROOT=Path('.')

def edit(path, fn):
    p=ROOT/path
    s=p.read_text()
    ns=fn(s)
    if ns==s:
        raise SystemExit(f'No change applied to {path}')
    p.write_text(ns)

# Version.
edit(Path('app/build.gradle.kts'), lambda s: s.replace('versionCode = 67','versionCode = 68').replace('versionName = "0.5.17"','versionName = "0.5.18"'))

# Remove portable data-transfer feature and add privacy-safe diagnostics export.
p=ROOT/'app/src/main/java/com/fer/wavault/MainActivity.java'
s=p.read_text()
s=s.replace('    private static final int PORTABLE_BACKUP_CREATE_REQ = 46;\n    private static final int PORTABLE_BACKUP_RESTORE_REQ = 47;\n','    private static final int DIAGNOSTIC_EXPORT_REQ = 46;\n')
s=s.replace('    private char[] pendingPortableBackupPassword;\n    private Uri pendingPortableRestoreUri;\n','    private String pendingDiagnosticExport;\n')
s=s.replace('import java.util.Arrays;\n','')
old='        try { MediaCrypto.cleanupCache(getApplicationContext()); } catch (Throwable ignored) {}\n        try { CaptureCoordinator.initialize(getApplicationContext()); } catch (Throwable ignored) {}'
new='        try { MediaCrypto.cleanupCache(getApplicationContext()); } catch (Throwable ignored) {}\n        new Thread(()->{try{StorageAnalyzer.cleanupTemporary(getApplicationContext());}catch(Throwable ignored){}try{StorageAnalyzer.cleanupLegacyRemovedFeatureArtifacts(getApplicationContext());}catch(Throwable ignored){}} ,"wa-vault-startup-clean").start();\n        try { CaptureCoordinator.initialize(getApplicationContext()); } catch (Throwable ignored) {}'
if old not in s: raise SystemExit('startup anchor missing')
s=s.replace(old,new)
old='''        LinearLayout data=card();
        data.addView(text("Tus datos",18,fg,true));
        data.addView(text("Backup portátil cifrado para mover o guardar tu Vault sin depender de este teléfono.",11,muted,false));
        LinearLayout backupActions=row();
        addWeighted(backupActions,button("Crear backup",v->showCreatePortableBackupDialog()));
        addWeighted(backupActions,button("Restaurar",v->choosePortableBackupToRestore()));
        data.addView(backupActions);content.addView(data);

'''
if old not in s: raise SystemExit('settings data card anchor missing')
s=s.replace(old,'')
start=s.index('    private void showCreatePortableBackupDialog(){')
end=s.index('    private void showEncryptionModeDialog(){',start)
s=s[:start]+s[end:]
needle='''        addWeighted(actions,button("Actualizar",v->showDiagnostics()));
        addWeighted(actions,button("Reparar protección",v->{CaptureCoordinator.restart(getApplicationContext());Toast.makeText(this,"Protección revisada",Toast.LENGTH_SHORT).show();uiRefreshHandler.postDelayed(this::showDiagnostics,500L);}));
        content.addView(actions);
'''
if needle not in s: raise SystemExit('diagnostic actions anchor missing')
s=s.replace(needle,needle+'        content.addView(button("Exportar diagnóstico técnico",v->exportTechnicalDiagnostics()));\n',1)
old='''        if(requestCode==PORTABLE_BACKUP_CREATE_REQ){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null)runPortableBackup(data.getData());
            else {if(pendingPortableBackupPassword!=null)Arrays.fill(pendingPortableBackupPassword,'\\0');pendingPortableBackupPassword=null;}
            return;
        }
        if(requestCode==PORTABLE_BACKUP_RESTORE_REQ){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null)showPortableRestorePassword(data.getData());
            return;
        }
'''
if old not in s: raise SystemExit('activity result portable anchors missing')
s=s.replace(old,'')
needle='        if(requestCode==EXPORT_REQ){\n'
s=s.replace(needle,'''        if(requestCode==DIAGNOSTIC_EXPORT_REQ){
            if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null)finishTechnicalDiagnosticsExport(data.getData());
            else pendingDiagnosticExport=null;
            return;
        }
'''+needle,1)
helpers=r'''    private void exportTechnicalDiagnostics(){
        pendingDiagnosticExport=buildTechnicalDiagnostics();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"WA-Vault-diagnostico-"+DateFormat.format("yyyyMMdd-HHmm",new Date())+".txt");
        try{startActivityForResult(i,DIAGNOSTIC_EXPORT_REQ);}catch(Throwable t){pendingDiagnosticExport=null;Toast.makeText(this,"No se pudo abrir el selector de archivos",Toast.LENGTH_LONG).show();}
    }

    private void finishTechnicalDiagnosticsExport(Uri destination){
        final String report=pendingDiagnosticExport;pendingDiagnosticExport=null;if(destination==null||report==null)return;
        new Thread(()->{boolean ok=false;try(OutputStream out=getContentResolver().openOutputStream(destination,"w")){if(out==null)throw new IllegalStateException();byte[] bytes=report.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.write(bytes);out.flush();ok=true;}catch(Throwable t){recordUiError("DIAG_EXPORT",t);}final boolean saved=ok;runOnUiThread(()->Toast.makeText(this,saved?"Diagnóstico guardado":"No se pudo guardar el diagnóstico",Toast.LENGTH_LONG).show());},"wa-vault-diag-export").start();
    }

    private String buildTechnicalDiagnostics(){
        SharedPreferences d=getSharedPreferences("wa_vault_diag",MODE_PRIVATE);VaultDb.Stats st;try{st=db.getStats();}catch(Throwable t){st=new VaultDb.Stats();}
        StringBuilder b=new StringBuilder(4096);b.append("WA Vault - diagnostico tecnico\\n");b.append("version=0.5.18\\n");b.append("generated_at=").append(System.currentTimeMillis()).append('\\n');
        b.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" sdk=").append(Build.VERSION.SDK_INT).append('\\n');
        b.append("protection=").append(protectionHeadline()).append('\\n');b.append("notification_listener=").append(notificationAccess()).append('\\n');b.append("media_permissions=").append(hasAllMediaPermissions()).append('\\n');b.append("all_files_access=").append(Build.VERSION.SDK_INT<30||Environment.isExternalStorageManager()).append('\\n');b.append("battery_unrestricted=").append(ignoresBatteryOptimizations()).append('\\n');
        b.append("direct_voice=").append(DirectVoiceWatcher.isAvailable(this)&&DirectVoiceWatcher.isHealthy()).append('\\n');b.append("direct_media=").append(DirectMediaWatcher.isAvailable(this)&&DirectMediaWatcher.isHealthy()).append('\\n');b.append("mediastore=").append(MediaStoreWatcher.isHealthy()).append('\\n');
        b.append("free_bytes=").append(StorageAnalyzer.freeBytes(this)).append('\\n');b.append("vault_bytes=").append(StorageAnalyzer.vaultPhysicalBytes(this)).append('\\n');b.append("video_limit_bytes=").append(MediaLimits.maxVideoBytes(this)).append('\\n');b.append("document_limit_bytes=").append(MediaLimits.maxDocumentBytes(this)).append('\\n');
        b.append("media_count=").append(st.media).append('\\n');b.append("saved_files=").append(st.savedFiles).append('\\n');b.append("detected_files=").append(st.detectedFiles).append('\\n');b.append("recovery_issues=").append(st.recoveryIssues).append('\\n');b.append("delete_unverifiable_count=").append(d.getInt("delete_unverifiable_count",0)).append('\\n');
        b.append("last_direct_media_at=").append(d.getLong("last_direct_media_at",0L)).append('\\n');b.append("last_mediastore_at=").append(d.getLong("last_mediastore_at",0L)).append('\\n');b.append("last_audio_at=").append(d.getLong("notif_audio_at",0L)).append('\\n');
        b.append("integrity=").append(CaptureIntegritySelfTest.summary(this).replace('\\n',' ')).append("\\n\\nrecent_event_codes:\\n");
        try{for(VaultDb.Event e:db.listEvents(30)){if(e==null)continue;b.append(e.timestamp).append(' ').append(e.code==null?"EVENT":e.code).append('\\n');}}catch(Throwable ignored){}
        b.append("\\nprivacy=No chat text, sender names, conversation names, media names, paths or event details are included.\\n");return b.toString();
    }

'''
anchor='    private void showPartialAttemptsDialog(){\n'
idx=s.index(anchor)
s=s[:idx]+helpers+s[idx:]
p.write_text(s)

# Remove database API used only by removed feature.
p=ROOT/'app/src/main/java/com/fer/wavault/VaultDb.java'
s=p.read_text()
s=s.replace('    public List<Media> listMediaForPortableBackup(int limit){return queryMedia("trash_state=0 AND retention_state<>?",new String[]{String.valueOf(RETENTION_PENDING)},"captured_at ASC",Math.max(1,limit));}\n','')
p.write_text(s)

# Remove implementation class.
p=ROOT/'app/src/main/java/com/fer/wavault/PortableBackupManager.java'
if p.exists(): p.unlink()

# Cleanup stale cache files left by the removed feature plus normal startup temporary cleanup.
p=ROOT/'app/src/main/java/com/fer/wavault/StorageAnalyzer.java'
s=p.read_text()
anchor='    private static int clean(File d,long cutoff,String[] prefixes)'
insert='''    /** One-time hygiene for cache artifacts left by features removed from current builds. */
    public static int cleanupLegacyRemovedFeatureArtifacts(Context c){
        if(c==null)return 0;int n=0;try{File root=c.getCacheDir();File[] fs=root.listFiles();if(fs==null)return 0;for(File f:fs){if(f==null)continue;String name=f.getName();if(!name.startsWith("portable_"))continue;n+=deleteTree(f);}}catch(Throwable ignored){}return n;
    }
    private static int deleteTree(File f){if(f==null||!f.exists())return 0;int n=0;if(f.isDirectory()){File[] kids=f.listFiles();if(kids!=null)for(File k:kids)n+=deleteTree(k);}try{if(f.delete())n++;}catch(Throwable ignored){}return n;}
'''
if anchor not in s: raise SystemExit('StorageAnalyzer anchor missing')
s=s.replace(anchor,insert+anchor,1)
p.write_text(s)

# Documentation: current product no longer advertises the removed feature.
p=ROOT/'README.md'; s=p.read_text().replace('Recovery Center 2.0, papelera y backup portátil cifrado independiente del Keystore.','Recovery Center 2.0, papelera, cifrado local y diagnóstico técnico exportable.'); p.write_text(s)
p=ROOT/'CHANGELOG.md'; s=p.read_text().replace('- Backup/restore portátil offline con PBKDF2-HMAC-SHA256 + AES-256-GCM, independiente del Keystore local.\n',''); p.write_text(s)
p=ROOT/'V050_NOTES.md'; s=p.read_text(); start=s.find('## Backup portátil')
if start>=0:
    nxt=s.find('\n## ',start+3); s=s[:start]+(s[nxt+1:] if nxt>=0 else '')
p.write_text(s)
p=ROOT/'V052_NOTES.md'; s=p.read_text().replace('- Backup portátil, papelera, Recovery Center 2.0 y cifrado.','- Papelera, Recovery Center 2.0 y cifrado.'); p.write_text(s)

(ROOT/'TEST_MATRIX.md').write_text('''# WA Vault v0.5.18 — Current Test Matrix

## Core invariants
- [ ] Normal WhatsApp messages never appear as deleted without explicit strong deletion evidence.
- [ ] APP_CANCEL alone never confirms deletion; APP_CANCEL_ALL is ignored as deletion evidence.
- [ ] Human text such as “mensaje eliminado” or “2 mensajes eliminados” remains ordinary chat unless structured evidence independently proves deletion.
- [ ] Singular official deletion markers tolerate harmless punctuation/invisible spacing normalization.
- [ ] confirmed-only visibility remains enforced for recovered media.
- [ ] No nearest-timestamp/closest-winner association exists.
- [ ] Ambiguous/unconfirmed media remains hidden.
- [ ] Stickers remain excluded.
- [ ] Tombstones prevent deleted media from resurrecting automatically.

## Media correlation and limits
- [ ] Pending manual arms expire within 10 minutes.
- [ ] FIFO micro-cohorts resolve only exact 1:1 demonstrable matches.
- [ ] Documents use the same exact-correlation model as image/video/audio.
- [ ] Video limits remain adaptive at 40/100/200 MB with storage reserve.
- [ ] Document limits remain adaptive at 50/150/300 MB with storage reserve.
- [ ] Large files are copied by streaming; no whole-file RAM buffering.

## Recovery UI / deletion
- [ ] Live refresh preserves loaded pages, visible anchor and pixel offset.
- [ ] If the anchor disappears, neighbor/index fallback preserves the viewport.
- [ ] Papelera individual restore works.
- [ ] Restaurar todo works.
- [ ] Permanent delete from WA Vault only preserves Gallery copy when requested.
- [ ] Permanent delete with Gallery option removes WA Vault-owned Gallery copy and leaves tombstone.

## Diagnostics / maintenance
- [ ] Home always exposes actionable protection status.
- [ ] Diagnostics show notification listener, FileObserver/MediaStore, battery, storage and unverifiable-delete count.
- [ ] “Exportar diagnóstico técnico” writes a plain-text report without chat bodies, sender/conversation names, media names/paths or event details.
- [ ] Startup cleanup removes stale temporary/cache files and legacy removed-feature cache artifacts without touching Vault media.
- [ ] Integrity self-test passes.
- [ ] No portable data-transfer/create/restore/password UI or manager remains in the application source.

## Device validation
- [ ] Screen unlocked: normal text burst, groups, duplicate text and notification clear do not create false deletions.
- [ ] Screen locked: capture and genuine deletion still work where Android exposes the required notification evidence.
- [ ] WhatsApp foreground/background/force-stop scenarios do not convert lifecycle notification removal into deletion.
- [ ] Battery optimization enabled vs disabled is reflected correctly in Home/Diagnostics.
- [ ] Reboot restores watchers and pending-state safety without surfacing ambiguous media.
''')
(ROOT/'V0518_NOTES.md').write_text('''# WA Vault v0.5.18 — Diagnostics / Hygiene

- Removed the legacy portable data-transfer feature completely: UI, password flow, create/restore request handling, database helper and implementation class.
- Android system backup remains explicitly disabled (`allowBackup=false`) so WA Vault does not silently copy private data through the OS.
- Added a privacy-safe technical diagnostic export. It contains only app/device state, watcher health, storage/counters and event codes; never chat text, contacts, conversation names, media names, paths or event details.
- Added startup temporary cleanup plus cleanup of cache artifacts left by the removed feature.
- Replaced the active TEST_MATRIX with a v0.5.18-only matrix so historical polling/deletion behavior cannot be mistaken for current requirements.
- Core detection/correlation behavior from v0.5.17 is unchanged.
''')

reg=r'''from pathlib import Path
root=Path(__file__).resolve().parents[1]
main=(root/'app/src/main/java/com/fer/wavault/MainActivity.java').read_text()
db=(root/'app/src/main/java/com/fer/wavault/VaultDb.java').read_text()
storage=(root/'app/src/main/java/com/fer/wavault/StorageAnalyzer.java').read_text()
listener=(root/'app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java').read_text()
limits=(root/'app/src/main/java/com/fer/wavault/MediaLimits.java').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
matrix=(root/'TEST_MATRIX.md').read_text()
def need(ok,msg):
    if not ok: raise SystemExit('FAIL: '+msg)
    print('PASS:',msg)
need('versionCode = 68' in gradle and 'versionName = "0.5.18"' in gradle,'v0.5.18 version')
need(not (root/'app/src/main/java/com/fer/wavault/PortableBackupManager.java').exists(),'removed implementation absent')
for token in ['PORTABLE_BACKUP_CREATE_REQ','PORTABLE_BACKUP_RESTORE_REQ','pendingPortableBackupPassword','showCreatePortableBackupDialog','choosePortableBackupToRestore','runPortableBackup','runPortableRestore','listMediaForPortableBackup']:
    need(token not in main+db,token+' removed')
need('Crear backup' not in main and 'Backup portátil' not in main and '.wavb' not in main,'removed UI/format absent')
need('android:allowBackup="false"' in manifest,'OS backup disabled')
need('Exportar diagnóstico técnico' in main and 'buildTechnicalDiagnostics' in main,'technical diagnostic export exists')
section=main[main.index('private String buildTechnicalDiagnostics'):main.index('private void showPartialAttemptsDialog')]
need('e.detail' not in section,'diagnostic export excludes event details')
need('cleanupLegacyRemovedFeatureArtifacts' in storage and 'wa-vault-startup-clean' in main,'startup orphan cleanup exists')
need('return reason == REASON_APP_CANCEL;' in listener,'APP_CANCEL_ALL remains excluded')
need('structuredPluralDeleteCount' in listener,'structured plural deletion hardening retained')
need('DELETE_UNVERIFIABLE' in listener,'unverifiable delete diagnostics retained')
need('VIDEO_LIMIT_LOW' in limits and 'DOCUMENT_LIMIT_LOW' in limits,'adaptive media limits retained')
print('v0.5.18 regression suite PASS')
'''
(ROOT/'tools/v0518_regression_test.py').write_text(reg)

# Hard validation before Gradle.
exec(compile(reg,str(ROOT/'tools/v0518_regression_test.py'),'exec'))
print('v0.5.18 transformation PASS')
