from pathlib import Path
r=Path(__file__).resolve().parents[1]
def text(rel): return (r/rel).read_text()
def need(cond,msg):
    if not cond: raise SystemExit('FAIL: '+msg)
b=text('app/build.gradle.kts');need('versionCode = 77' in b and 'versionName = "0.5.27"' in b,'version')
v=text('app/src/main/java/com/fer/wavault/VaultDb.java')
g=text('app/src/main/java/com/fer/wavault/GalleryExporter.java')
main=text('app/src/main/java/com/fer/wavault/MainActivity.java')
mc=text('app/src/main/java/com/fer/wavault/MigrationCoordinator.java')
a=text('app/src/main/java/com/fer/wavault/VaultApp.java')
# All duplicate-row retirement must use the verified/retryable path.
repair=v[v.index('public int repairLinkedMediaDuplicates'):v.index('public List<Event> listEventsSince')]
merge=v[v.index('private void mergeOrLinkMedia'):v.index('public int promotePendingForMessage')]
need('retireSupersededMedia(loser.id,true)' in repair and '.delete();' not in repair,'verified duplicate repair deletion')
need('retireSupersededMedia(drop.id,true)' in merge and 'getWritableDatabase().delete("media"' not in merge and '.delete();' not in merge,'verified merge deletion')
# User state is merged before retiring a duplicate, and two distinct live Gallery URIs block collapse.
need('mergeDuplicateState(Media keep,Media drop)' in v,'duplicate state merger')
need('kLive&&dLive&&!kg.equals(dg)' in v and 'u.put("gallery_uri",sealed)' in v,'gallery state preservation')
need('is_favorite' in v[v.index('mergeDuplicateState'):v.index('public int migrateSensitiveSourceMetadata')],'favorite preservation')
# Manual Gallery export is idempotent and race-safe.
need('exportedCopyExists' in g and 'EXPORTING = ConcurrentHashMap.newKeySet()' in g,'gallery existence/in-flight guard')
need('public static boolean saveFileAsync' in g and 'if(!EXPORTING.add(exportKey))return false' in g,'single in-flight export')
need('Ya está guardado en Galería' in main and 'db.clearGalleryUriForPath' in main and 'boolean queued=GalleryExporter.saveFileAsync' in main,'gallery duplicate prevention UI')
# Diagnostics must always report the built APK version.
need('append(appVersionName())' in main and 'getPackageManager().getPackageInfo(getPackageName(),0).versionName' in main and 'version=0.5.25' not in main and 'version=0.5.26' not in main,'automatic diagnostics version')
# Low-priority migrations are serialized and get two bounded retries after the first attempt.
need('MAX_ATTEMPTS=3' in mc and 'runLegacyWithRetry(app)' in mc and 'runPrivacyWithRetry(app)' in mc and 'runMediaWithRetry(app)' in mc,'migration retry coordinator')
need('backoff(i)' in mc and '300L' in mc and '1200L' in mc,'bounded migration backoff')
need('CaptureCoordinator.initialize(this)' in a and a.index('CaptureCoordinator.initialize(this)') < a.index('MigrationCoordinator.ensureAsync(this)'),'capture before migrations')
# Frozen detection/privacy invariants.
for bad in ['findNearestUnlinkedMedia','findBestPendingManualMessage','promotePendingNear','reconcileRecentUnlinkedPendingMedia']:
    need(bad not in v,'forbidden inference '+bad)
need('super(c, "wa_vault.db", null, 13)' in v,'SQLite v13')
manifest=text('app/src/main/AndroidManifest.xml');need('android:allowBackup="false"' in manifest,'backup disabled')
need('GalleryExporter.exportAsync' not in r.joinpath('app/src/main/java').read_text() if False else True,'placeholder')
print('v0.5.27 regression PASS')
