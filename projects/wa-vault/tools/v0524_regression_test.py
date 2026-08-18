from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
def read(p): return (root/p).read_text(encoding='utf-8')
def req(cond,msg):
    if not cond: raise AssertionError(msg)
main=read('app/src/main/java/com/fer/wavault/MainActivity.java')
db=read('app/src/main/java/com/fer/wavault/VaultDb.java')
meta=read('app/src/main/java/com/fer/wavault/MetadataPrivacy.java')
integ=read('app/src/main/java/com/fer/wavault/CaptureIntegritySelfTest.java')
gradle=read('app/build.gradle.kts')
manifest=read('app/src/main/AndroidManifest.xml')
req('versionCode = 74' in gradle and 'versionName = "0.5.24"' in gradle,'version')
req('super(c, "wa_vault.db", null, 13)' in db,'db schema')
req('android:allowBackup="false"' in manifest,'allowBackup')
req('deleteMediaPermanentlyInternal' in db and 'physicalGone' in db and 'MEDIA_DELETE_PENDING' in db,'verified physical deletion')
req('retryPendingPhysicalDeletes' in db and 'pending_physical_delete_' in db,'persistent delete retry')
req('AndroidKeyStore' in meta and 'HMAC_ALIAS' in meta and 'KeyProtection' in meta,'hmac keystore')
req('remove(SECRET)' in meta and 'MessageDigest' not in meta,'no deterministic HMAC fallback')
req('MetadataPrivacy.selfTest' in integ and 'HMAC de identificadores' in integ,'hmac self test')
req('postUiIfAlive' in main and 'MainActivity.this.runOnUiThread' in main,'lifecycle UI guard')
# only helper itself should call raw runOnUiThread
req(main.count('runOnUiThread(')==1,'unguarded runOnUiThread remains')
req('waitVoiceWatcherHealthy' in main and 'waitMediaWatcherHealthy' in main and 'waitMediaStoreHealthy' in main,'watcher settling')
req('wa-vault-home-stats' in main and 'wa-vault-diagnostics-load' in main,'heavy UI data off main')
req('version=0.5.24' in main,'diagnostic version')
# frozen strict-detection invariants
for bad in ['findNearestUnlinkedMedia','findBestPendingManualMessage','promotePendingNear','nearestArm(']: req(bad not in db+main,'forbidden nearest guess '+bad)
listener=read('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java')
req('REASON_APP_CANCEL' in listener and 'DELETE_UNVERIFIABLE' in listener,'delete strictness')
req('structuredPluralDeleteCount' in listener,'plural strict deletion')
req('Sticker' in listener or 'sticker' in listener,'sticker exclusion')
print('v0.5.24 regression PASS')
