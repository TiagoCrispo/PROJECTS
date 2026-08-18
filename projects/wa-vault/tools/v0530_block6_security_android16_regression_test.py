#!/usr/bin/env python3
from pathlib import Path
import re
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app'
MANIFEST=APP/'src/main/AndroidManifest.xml'
GRADLE=APP/'build.gradle.kts'
ANDROID='{http://schemas.android.com/apk/res/android}'

def need(cond,msg):
    if not cond: raise AssertionError(msg)

def txt(p): return Path(p).read_text(encoding='utf-8')

gradle=txt(GRADLE)
manifest=txt(MANIFEST)
main=txt(APP/'src/main/java/com/fer/wavault/MainActivity.java')
notifier=txt(APP/'src/main/java/com/fer/wavault/VaultUiNotifier.java')
all_java='\n'.join(p.read_text(encoding='utf-8',errors='replace') for p in (APP/'src/main/java').rglob('*.java'))

# Android 16 readiness.
need('compileSdk = 36' in gradle, 'compileSdk must be 36')
need('targetSdk = 36' in gradle, 'targetSdk must be 36')
need('windowOptOutEdgeToEdgeEnforcement' not in manifest+all_java, 'edge-to-edge opt-out remains')
need('android:enableOnBackInvokedCallback="true"' in manifest, 'predictive back contract not explicit')
need('onBackPressed(' not in all_java and 'KEYCODE_BACK' not in all_java, 'legacy back interception remains')
need('setContentSensitivity(View.CONTENT_SENSITIVITY_SENSITIVE)' in main, 'Android 15 screen-share sensitivity missing')
need('WindowInsets.Type.systemBars()' in main and 'WindowInsets.Type.displayCutout()' in main, 'modern system-bar/cutout insets missing')
need('screenOrientation' not in manifest and 'setRequestedOrientation' not in all_java, 'large-screen orientation restriction remains')

# Permission surface is explicit and minimal for actual features.
root=ET.fromstring(manifest)
uses=[x.get(ANDROID+'name') for x in root.findall('uses-permission')]
expected={
 'com.fer.wavault.permission.INTERNAL_EVENTS',
 'android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VIDEO',
 'android.permission.READ_MEDIA_VISUAL_USER_SELECTED','android.permission.READ_MEDIA_AUDIO',
 'android.permission.READ_EXTERNAL_STORAGE','android.permission.WRITE_EXTERNAL_STORAGE',
 'android.permission.MANAGE_EXTERNAL_STORAGE','android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
 'android.permission.RECEIVE_BOOT_COMPLETED','android.permission.USE_BIOMETRIC'
}
need(set(uses)==expected, f'unexpected permission surface: {set(uses)^expected}')
need('android.permission.INTERNET' not in uses, 'network permission must not be present')
need('android.permission.POST_NOTIFICATIONS' not in uses, 'unused notification-post permission introduced')
need('BiometricPrompt' in all_java and 'android.permission.USE_BIOMETRIC' in uses, 'biometric feature/permission mismatch')
need(root.find('queries') is None, 'unnecessary package-visibility queries remain')

# Storage special access remains optional: degraded capture routes must exist.
need('Environment.isExternalStorageManager()' in main, 'all-files state check missing')
need('fast_storage_prompted_v034' in main, 'all-files prompt must be one-shot')
need('MediaStoreWatcher.start' in all_java and 'ACTION_OPEN_DOCUMENT_TREE' in main, 'privacy-friendly fallback routes missing')
need('DirectMediaWatcher.isAvailable(this)' in main and 'Modo limitado' in main, 'UI does not represent degraded mode')

# Battery exemption is one-shot and has safe fallback; no invented foreground service.
need('battery_exemption_prompted' in main, 'battery special-access prompt not one-shot')
need('ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS' in main and 'ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS' in main, 'battery fallback settings missing')
need('android.permission.FOREGROUND_SERVICE' not in manifest and 'startForeground(' not in all_java, 'unnecessary foreground service introduced')
need('com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY' in main and 'com.samsung.android.lool' in main and 'putExtra("activity_type", 2)' in main, 'Samsung Never sleeping documented deeplink changed')
need('Settings.ACTION_BATTERY_SAVER_SETTINGS' in main, 'Samsung settings fallback missing')

# App-private IPC: signature permission protects dynamic broadcast on every supported API.
perm=root.find('permission')
need(perm is not None and perm.get(ANDROID+'name')=='com.fer.wavault.permission.INTERNAL_EVENTS' and perm.get(ANDROID+'protectionLevel')=='signature', 'signature internal permission missing')
need('INTERNAL_PERMISSION = "com.fer.wavault.permission.INTERNAL_EVENTS"' in notifier, 'notifier permission constant missing')
need('sendBroadcast(i, INTERNAL_PERMISSION)' in notifier, 'internal broadcast sender not permission-protected')
need('registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null,Context.RECEIVER_NOT_EXPORTED)' in main, 'API33+ internal receiver not permission-protected')
need('registerReceiver(dataChangedReceiver,f,VaultUiNotifier.INTERNAL_PERMISSION,null)' in main, 'API26-32 internal receiver not permission-protected')

# Backup/D2D leakage must be explicitly disabled, not only allowBackup=false.
app=root.find('application')
need(app is not None and app.get(ANDROID+'allowBackup')=='false', 'allowBackup false missing')
need(app.get(ANDROID+'fullBackupContent')=='@xml/backup_rules_legacy', 'legacy backup rules not wired')
need(app.get(ANDROID+'dataExtractionRules')=='@xml/data_extraction_rules', 'Android12+ extraction rules not wired')
domains={'root','file','database','sharedpref','external','device_root','device_file','device_database','device_sharedpref'}
legacy=ET.parse(APP/'src/main/res/xml/backup_rules_legacy.xml').getroot()
need({x.get('domain') for x in legacy.findall('exclude')}==domains, 'legacy backup does not exclude every app data domain')
rules=ET.parse(APP/'src/main/res/xml/data_extraction_rules.xml').getroot()
for section in ('cloud-backup','device-transfer'):
    node=rules.find(section); need(node is not None, f'{section} missing')
    need({x.get('domain') for x in node.findall('exclude')}==domains, f'{section} does not exclude every app data domain')

# Network hardening: app has no INTERNET and a declarative cleartext deny as defense in depth.
need(app.get(ANDROID+'usesCleartextTraffic')=='false', 'manifest cleartext deny missing')
need(app.get(ANDROID+'networkSecurityConfig')=='@xml/network_security_config', 'network security config not wired')
net=ET.parse(APP/'src/main/res/xml/network_security_config.xml').getroot()
base=net.find('base-config')
need(base is not None and base.get('cleartextTrafficPermitted')=='false', 'network config cleartext deny missing')
need(not any(x in all_java for x in ['HttpURLConnection','okhttp3.','retrofit2.','java.net.Socket','java.net.URL(']), 'network client code unexpectedly present')

# Component exposure is minimal.
def components(tag): return root.find('application').findall(tag)
activities=components('activity'); services=components('service'); receivers=components('receiver'); providers=components('provider')
need(len(activities)==1 and activities[0].get(ANDROID+'name')=='.MainActivity' and activities[0].get(ANDROID+'exported')=='true', 'launcher exposure unexpected')
need(all(x.get(ANDROID+'exported')=='false' for x in services+receivers+providers), 'non-launcher component exported')
need(services[0].get(ANDROID+'permission')=='android.permission.BIND_NOTIFICATION_LISTENER_SERVICE', 'listener bind signature permission missing')
need(providers[0].get(ANDROID+'grantUriPermissions')=='true', 'share provider temporary URI grants missing')

# File provider implementation remains strict read-only/canonical/cache-only.
provider=txt(APP/'src/main/java/com/fer/wavault/VaultShareProvider.java')
for token in ['ParcelFileDescriptor.MODE_READ_ONLY','getCanonicalPath()','getCacheDir(), "vault_share"','UnsupportedOperationException']:
    need(token in provider, f'share provider hardening missing: {token}')
need('MODE_WRITE' not in provider and 'MODE_READ_WRITE' not in provider, 'share provider gained write access')

# Keystore/AEAD invariants.
media=txt(APP/'src/main/java/com/fer/wavault/MediaCrypto.java')
crypto=txt(APP/'src/main/java/com/fer/wavault/CryptoManager.java')
meta=txt(APP/'src/main/java/com/fer/wavault/MetadataPrivacy.java')
for src,name in [(media,'media'),(crypto,'text')]:
    need('AES/GCM/NoPadding' in src, f'{name} encryption is not AEAD GCM')
    need('AndroidKeyStore' in src, f'{name} key not in Android Keystore')
need('.setKeySize(256)' in media, 'media key must stay AES-256')
need('HmacSHA256' in meta and 'AndroidKeyStore' in meta, 'metadata HMAC Keystore contract missing')
need('setUserAuthenticationRequired(true)' not in media+crypto+meta, 'background capture key accidentally requires interactive auth')

# Android 15 NLS limitation is documented locally so QA does not treat OTP redaction as a WA Vault bug.
notes=txt(ROOT/'SECURITY.md') if (ROOT/'SECURITY.md').exists() else ''
need('OTP' in notes and 'Android 15' in notes, 'Android 15 notification-listener OTP limitation not documented')

# Real-device acceptance must test degraded mode by default and keep special-access mutation opt-in.
device=txt(ROOT/'tools/device_acceptance.sh')
need('WA_VAULT_GRANT_ALL_FILES' in device, 'device acceptance lacks explicit all-files opt-in')
need('WA_VAULT_SPECIAL_ACCESS_NEGATIVE_TEST' in device, 'device acceptance lacks negative special-access test')
need('INSTALLED_PACKAGE_SECURITY_PASS' in device and 'targetSdk(?:Version)?=36' in device, 'installed-package target/security check missing')
need('appops set --uid "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow' in device, 'all-files grant path missing')
# The allow command must be guarded, not unconditional.
allow_pos=device.index('appops set --uid "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow')
guard_pos=device.rfind('WA_VAULT_GRANT_ALL_FILES',0,allow_pos)
need(guard_pos>=0 and allow_pos-guard_pos<500, 'all-files is still auto-granted unconditionally')

# No native code => Android 16 page-size/native ABI migration has no app-owned .so surface.
need(not list((APP/'src/main').rglob('*.so')), 'unexpected native library present')
need(not (APP/'src/main/jniLibs').exists(), 'jniLibs introduced unexpectedly')

print('BLOCK6_SECURITY_ANDROID16_PASS')
print(f'target=36 permissions={len(uses)} exported_non_launcher=0 backup_domains={len(domains)}x2')
print('network=NO_INTERNET+CLEARTEXT_DENY biometric=DECLARED internal_ipc=SIGNATURE_PROTECTED')
