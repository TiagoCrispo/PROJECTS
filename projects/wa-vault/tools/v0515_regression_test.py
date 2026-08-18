#!/usr/bin/env python3
from pathlib import Path
import re, sqlite3, sys
ROOT=Path(__file__).resolve().parents[1]

def read(rel): return (ROOT/rel).read_text(encoding='utf-8')
def need(cond,msg):
    if not cond:
        print('FAIL:',msg); raise SystemExit(1)
    print('PASS:',msg)

gradle=read('app/build.gradle.kts')
manifest=read('app/src/main/AndroidManifest.xml')
main=read('app/src/main/java/com/fer/wavault/MainActivity.java')
db=read('app/src/main/java/com/fer/wavault/VaultDb.java')
listener=read('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java')
media=read('app/src/main/java/com/fer/wavault/MediaArchiver.java')
direct=read('app/src/main/java/com/fer/wavault/DirectMediaWatcher.java')
voice=read('app/src/main/java/com/fer/wavault/DirectVoiceWatcher.java')
ms=read('app/src/main/java/com/fer/wavault/MediaStoreWatcher.java')
coord=read('app/src/main/java/com/fer/wavault/CaptureCoordinator.java')
watch=read('app/src/main/java/com/fer/wavault/CaptureWatchdog.java')
gallery=read('app/src/main/java/com/fer/wavault/GalleryExporter.java')
selftest=read('app/src/main/java/com/fer/wavault/CaptureIntegritySelfTest.java')

need('versionCode = 65' in gradle and 'versionName = "0.5.15"' in gradle,'version 0.5.15 / code 65')
need('super(c, "wa_vault.db", null, 13)' in db,'SQLite schema remains v13')
need('android:versionCode' not in manifest and 'android:versionName' not in manifest,'manifest has no stale version attributes')

# Correlation must never use a nearest/closest winner.
joined='\n'.join([main,db,direct,voice,ms,listener])
for forbidden in ['findNearestUnlinkedMedia','findBestPendingManualMessage','reconcileRecentUnlinkedPendingMedia','promotePendingNear','nearestArm(']:
    need(forbidden not in joined, f'no active nearest-winner API: {forbidden}')
need('uniqueTypedArmMemoryOnly' in direct and 'uniqueTypedArm(' in direct,'direct image/video assignment requires unique typed arm')
need('private static Arm uniqueArm' in voice and 'candidates>1)return null' in voice,'voice assignment rejects multiple active arms')
need('private static Arm uniqueArm' in ms and 'if(++count>1)return null' in ms,'MediaStore assignment rejects multiple active arms')
need('media.size()!=pending.size()' in db and 'FIFO exacto' in db,'FIFO links only exact arm/media counts')
need('messageConversationFingerprint' in db and 'if(conv.isEmpty())conv=c;else if(!conv.equals(c))return 0' in db,'FIFO requires one conversation cohort')
need('capture_batch_key' in db and 'findUnlinkedPendingByBatch' in db and 'buildCaptureBatchKey' in db,'persisted exact batch identity is first-class')
need('FIFO no adivina' in selftest,'integrity self-test includes ambiguous-count refusal')

# APP_CANCEL: database-only cohort must not silently become confirmed.
need('directlyObservedIds' in listener,'APP_CANCEL tracks directly observed message ids')
need('DELETE_BACKFILL_HELD' in listener,'APP_CANCEL records held DB-only backfill')
need('if(!directlyObserved&&!explicitDelete)' in listener and 'unmarkProbableDeletedById' in listener,'DB-only APP_CANCEL backfill stays hidden without explicit marker')
need(listener.count('removalCandidateGenerations.put(candidateKey, generation);')==1,'single removal generation registration')

# Background work / battery safeguards.
need('scheduleWithFixedDelay(CaptureWatchdog::tick,10,45' in watch,'watchdog uses light 45-second health cadence')
need('hasPending&&(ticks%4L)==0L' in watch,'pending monitor DB rebind is throttled')
need('(ticks%12L)==0L' in watch and '(ticks%960L)==0L' in watch,'recovery and deep maintenance are throttled')
need('capture_recovery_requested_at' in coord and '15L*60L*1000L' in coord,'startup recovery gated to ~15 minutes')
need('maintenance_requested_at' in coord and '12L*60L*60L*1000L' in coord,'startup maintenance gated to ~12 hours')
# ensure old 2-second attach polling is gone
need('scheduleWithFixedDelay(() ->' in direct and '}, 15,15, TimeUnit.SECONDS)' in direct,'direct media safety pass is low-frequency')
need('attachAll();' not in re.sub(r'private static void attachAll\(\)\{.*?\n    \}', '', direct, flags=re.S),'no unconditional DirectMedia attachAll polling loop')
need('new long[]{0L,250L,1000L}' in ms and 'new long[]{0L,120L,500L,1500L,5000L}' in ms,'MediaStore retry fanout is bounded')
need('if(!exact&&active==null)return' in ms,'generic MediaStore changes without capture context are ignored')

# Deletion semantics / Gallery.
need('deleteMediaPermanently(long mediaId,boolean deleteGalleryCopy)' in db,'permanent delete supports explicit Gallery choice')
need('deleteExportedCopy' in gallery and 'FileNotFoundException missing' in gallery,'Gallery deletion treats already-absent export as success')
need('Eliminar solo de WA Vault' in main and 'Eliminar de WA Vault y Galería' in main,'single-item delete UI explains Gallery scope')
need('Vaciar solo WA Vault' in main and 'Vaciar WA Vault y sus copias de Galería' in main,'empty-trash UI explains Gallery scope')
need('media_tombstones' in db and 'USER_DELETE_BLOCKED' in db and 'TRASH_RESTORE_BLOCKED' in db,'permanent-delete tombstones prevent automatic resurrection')

# Protection / diagnostics / UI.
need('private int protectionLevel()' in main and 'COMPLETA' in main and 'LIMITADA' in main and 'ATENCIÓN' in main,'tri-state protection UI present')
need(main.count('Reparar protección')>=2,'repair protection action is exposed consistently')
need('private void showTrashDialog(int offset)' in main and 'pageSize=60' in main and 'Siguientes →' in main and '← Anteriores' in main,'Trash is paginated beyond first page')
need('Borrado confirmado · ' in main and '?"Foto"' in main and '?"Audio"' in main,'Recovered cards use friendly confirmed labels')
need('CAPTURE_FAIL_' in db and 'DIRECT_MEDIA_OBSERVER_START' in direct and 'VOICE_OBSERVER_EVENT' in voice and 'MEDIASTORE_CAPTURE_ERROR' in ms,'critical capture paths produce diagnostics')

# Confirmed-only + stickers retained.
need('CONFIRMED_MEDIA_SQL' in db and 'messages.deletion_state=2' in db,'Recovered feed still derives from confirmed deletion')
need('strictMediaKindForText' in listener and 'isExactStickerPlaceholder' in listener,'strict placeholder parser remains')
need('te mando una foto después' in selftest and '12:34' in selftest,'free text / clock false-positive tests remain')
need('whatsapp stickers' in direct.lower(),'sticker directory remains excluded')

# SQL behavior sanity.
con=sqlite3.connect(':memory:'); c=con.cursor()
c.executescript('''
CREATE TABLE messages(id INTEGER PRIMARY KEY,is_deleted INTEGER,deletion_state INTEGER);
CREATE TABLE media(id INTEGER PRIMARY KEY,linked_message_id INTEGER,trash_state INTEGER,retention_state INTEGER,expires_at INTEGER);
INSERT INTO messages VALUES(1,1,2),(2,0,0),(3,1,1);
INSERT INTO media VALUES(1,1,0,0,0),(2,2,0,2,0),(3,3,0,2,0),(4,0,0,2,0);
''')
cond='linked_message_id>0 AND EXISTS (SELECT 1 FROM messages WHERE messages.id=media.linked_message_id AND messages.is_deleted=1 AND messages.deletion_state=2)'
c.execute('UPDATE media SET retention_state=2,expires_at=0 WHERE trash_state=0 AND '+cond)
c.execute('UPDATE media SET retention_state=1,expires_at=99 WHERE trash_state=0 AND NOT ('+cond+') AND retention_state<>1')
rows=c.execute('SELECT id,retention_state FROM media ORDER BY id').fetchall()
need(rows==[(1,2),(2,1),(3,1),(4,1)],'confirmed-only SQL hides normal/probable/unlinked media')

# Source hygiene.
for rel in ['app/src/main/java/com/fer/wavault/MainActivity.java','app/src/main/java/com/fer/wavault/VaultDb.java','app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java','app/src/main/java/com/fer/wavault/DirectMediaWatcher.java','app/src/main/java/com/fer/wavault/DirectVoiceWatcher.java','app/src/main/java/com/fer/wavault/MediaStoreWatcher.java']:
    s=read(rel); need('<<<<<<<' not in s and '>>>>>>>' not in s and '=======' not in s,rel+' has no merge markers')
need(not any(ROOT.rglob('*.orig')) and not any(ROOT.rglob('*.rej')),'no .orig/.rej artifacts in source')
print('v0.5.15 regression suite: PASS')
