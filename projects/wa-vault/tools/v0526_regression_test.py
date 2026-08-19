from pathlib import Path
r=Path(__file__).resolve().parents[1]

def text(rel): return (r/rel).read_text()

def need(cond,msg):
    if not cond: raise SystemExit('FAIL: '+msg)

b=text('app/build.gradle.kts'); need('versionCode = 76' in b and 'versionName = "0.5.26"' in b,'version')
v=text('app/src/main/java/com/fer/wavault/VaultDb.java')
m=text('app/src/main/java/com/fer/wavault/MetadataPrivacy.java')
g=text('app/src/main/java/com/fer/wavault/GalleryExporter.java')
w=text('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java')
a=text('app/src/main/java/com/fer/wavault/VaultApp.java')
mc=text('app/src/main/java/com/fer/wavault/MigrationCoordinator.java')
need('galleryGone=GalleryExporter.deleteExportedCopy' in v and 'GALLERY_DELETE_PENDING' in v,'transactional gallery delete')
need('retireSupersededMedia(replaceCandidate.id,true)' in v and 'pending_superseded_delete_' in v,'verified replacement cleanup')
need('MetadataPrivacy.contentHash(appContext,rawHash)' in v and 'migrateContentHashesToHmac' in v,'content hash HMAC')
need('MEDIA_HASH_PREFIX="mh1_"' in m and 'media-content|' in m,'hash namespace')
need('Integer.toHexString(raw.hashCode())' not in w and 'MetadataPrivacy.token(getApplicationContext(),"notif",raw)' in w,'notification HMAC')
need('wa_vault_gallery_exports' not in g,'no Gallery legacy prefs in exporter')
need('CaptureCoordinator.initialize(this)' in a and a.index('CaptureCoordinator.initialize(this)') < a.index('MigrationCoordinator.ensureAsync(this)'),'capture before migrations')
need('LegacyPlainMigration.runAsync(app);awaitLegacy();' in mc and 'MetadataPrivacy.runV0526MigrationAsync(app);awaitPrivacy();' in mc and 'MediaCrypto.enforceRequiredMode(app);awaitMedia();' in mc,'serialized migrations')
# Frozen detection invariants
for bad in ['findNearestUnlinkedMedia','findBestPendingManualMessage','promotePendingNear','reconcileRecentUnlinkedPendingMedia']:
    need(bad not in text('app/src/main/java/com/fer/wavault/VaultDb.java'), 'forbidden inference '+bad)
need('super(c, "wa_vault.db", null, 13)' in v,'SQLite v13')
manifest=text('app/src/main/AndroidManifest.xml');need('android:allowBackup="false"' in manifest,'backup disabled')
print('v0.5.26 regression PASS')
