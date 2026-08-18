#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
java='\n'.join(p.read_text(errors='replace') for p in (ROOT/'app/src/main/java').rglob('*.java'))
checks=[]
def check(ok,name):
    checks.append((name,ok))
    if not ok: print('FAIL',name)
check('android:allowBackup="false"' in manifest,'backup_disabled')
check('android:usesCleartextTraffic="false"' in manifest,'cleartext_disabled')
check('android:name=".VaultShareProvider"' in manifest and 'android:exported="false"' in manifest,'internal_provider_not_exported')
service=manifest[manifest.index('<service'):manifest.index('</service>')]
check('android:exported="false"' in service and 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in service,'listener_protected_not_exported')
for token in ('HttpURLConnection','okhttp3.','retrofit2.','java.net.Socket','DexClassLoader','PathClassLoader','Runtime.getRuntime().exec','ProcessBuilder(','System.loadLibrary('):
    check(token not in java,'no_'+token.replace('.','_').replace('(','').lower())
listener=(ROOT/'app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java').read_text()
check(listener.count('db.confirmDeletedWithEvidence(')==1 and listener.count('markDeletedAndKeepMedia(')==1,'single_live_deletion_mutation_callsite')
check('ensureDeletionPlaceholder(' not in listener,'no_placeholder_deletion_call')
check('WorkManager' not in java and 'androidx.work.' not in java,'workmanager_not_used_n_a')
failed=[n for n,ok in checks if not ok]
print(f'SECURITY_SOURCE_AUDIT checks={len(checks)} failed={len(failed)}')
if failed: raise SystemExit(1)
