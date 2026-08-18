from pathlib import Path
R=Path(__file__).resolve().parents[1]
def text(p): return (R/p).read_text(encoding='utf-8')

grad=text('app/build.gradle.kts')
assert 'versionCode = 73' in grad and 'versionName = "0.5.23"' in grad
manifest=text('app/src/main/AndroidManifest.xml')
assert 'android:allowBackup="false"' in manifest
vdb=text('app/src/main/java/com/fer/wavault/VaultDb.java')
assert 'super(c, "wa_vault.db", null, 13)' in vdb

privacy=text('app/src/main/java/com/fer/wavault/MetadataPrivacy.java')
assert 'HmacSHA256' in privacy and 'src_' in privacy and 'enc1:' in privacy
assert 'migrateOperationalPreferences' in privacy
assert 'last_delete_conversation' in privacy and '.remove(' in privacy

# Gallery is explicit/manual only.
gallery=text('app/src/main/java/com/fer/wavault/GalleryExporter.java')
assert 'saveFileAsync' in gallery
for banned in ('exportAsync','mirrorSourceAsync','exportMedia('): assert banned not in gallery
java='\n'.join(p.read_text(encoding='utf-8') for p in (R/'app/src/main/java').rglob('*.java'))
assert 'GalleryExporter.exportAsync' not in java and 'GalleryExporter.mirrorSourceAsync' not in java

# New staging/private filenames do not contain original display filenames.
vf=text('app/src/main/java/com/fer/wavault/VaultFileNames.java')
assert 'stagingName' in vf and 'opaqueName' in vf and 'shareName' in vf
fast=text('app/src/main/java/com/fer/wavault/FastCaptureEngine.java')
arch=text('app/src/main/java/com/fer/wavault/MediaArchiver.java')
proc=text('app/src/main/java/com/fer/wavault/CaptureProcessingEngine.java')
assert 'VaultFileNames.stagingName("fast_part_",type)' in fast
assert 'VaultFileNames.stagingName("fast_ready_",type)' in fast
assert 'VaultFileNames.stagingName("part_",type)' in arch
assert 'VaultFileNames.stagingName("ready_",type)' in arch
assert 'VaultFileNames.stagingName("partial_",type)' in proc
assert 'System.currentTimeMillis()+"_"+type+"_partial.bin"' not in proc

# Sensitive source/path metadata is HMAC-opaque or encrypted and migratable.
assert 'migrateSensitiveSourceMetadata' in vdb and 'countSensitiveSourceMetadata' in vdb
assert 'MetadataPrivacy.token(appContext,"path",localPath)' in vdb
assert 'MetadataPrivacy.seal(appContext,localPath)' in vdb
assert 'encryptCaptureAttemptPaths' in vdb
assert 'MetadataPrivacy.open(appContext,c.getString(6))' in vdb
assert 'MetadataPrivacy.seal(appContext,uri)' in vdb or 'MetadataPrivacy.seal(appContext,raw)' in vdb
assert 'MetadataPrivacy.token(app,"uri",uri.toString())' in text('app/src/main/java/com/fer/wavault/NotificationAudioCapture.java')
assert 'MetadataPrivacy.token(app,"uri",changed.toString())' in text('app/src/main/java/com/fer/wavault/MediaStoreWatcher.java')

# Separate metadata/text Keystore self-test + migration-zero checks.
crypto=text('app/src/main/java/com/fer/wavault/CryptoManager.java')
assert 'public boolean selfTest()' in crypto and 'WA_VAULT_METADATA_SELFTEST_V1' in crypto
selftest=text('app/src/main/java/com/fer/wavault/CaptureIntegritySelfTest.java')
assert 'new CryptoManager(context).selfTest()' in selftest
assert 'LegacyPlainMigration.remainingCount' in selftest
assert 'MediaCrypto.migrationRemaining' in selftest
assert 'countSensitiveSourceMetadata' in selftest

# Existing migration safety remains.
mc=text('app/src/main/java/com/fer/wavault/MediaCrypto.java')
assert 'mandatory full verification pass' in mc and 'verifyCursor=0L' in mc
assert 'cleanupRecognizableArchiveOrphans' in mc and 'retireLegacyNamedCopy' in mc
assert 'media_migration_remaining' in mc

main=text('app/src/main/java/com/fer/wavault/MainActivity.java')
assert 'version=0.5.23' in main
assert 'metadata_source_remaining' in main and 'media_migration_remaining' in main
assert 'new CryptoManager(app).selfTest()' in main
assert 'cleanupShareTemporary(getApplicationContext(),15L*60L*1000L)' in main

# No active backup-feature wording; disabling Android backup remains required.
assert 'respaldo' not in java.lower()
assert 'android:allowBackup="false"' in manifest

# Detection/correlation invariants remain frozen.
listener=text('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java')
assert 'structuredPluralDeleteCount' in listener
assert 'isAppDrivenRemovalReason' in listener
for banned in ('findNearestUnlinkedMedia','findBestPendingManualMessage','promotePendingNear','nearestArm('):
    assert banned not in java
print('v0.5.23 regression PASS')
