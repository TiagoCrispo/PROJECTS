#!/usr/bin/env python3
from pathlib import Path
import re, sqlite3
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
limits=read('app/src/main/java/com/fer/wavault/MediaLimits.java')
selftest=read('app/src/main/java/com/fer/wavault/CaptureIntegritySelfTest.java')
fast=read('app/src/main/java/com/fer/wavault/FastCaptureEngine.java')
notifmedia=read('app/src/main/java/com/fer/wavault/NotificationMediaCapture.java')

need('versionCode = 66' in gradle and 'versionName = "0.5.16"' in gradle,'version 0.5.16 / code 66')
need('super(c, "wa_vault.db", null, 13)' in db,'SQLite schema remains v13')
need('android:versionCode' not in manifest and 'android:versionName' not in manifest,'manifest has no stale version attributes')

# False-positive invariant: APP_CANCEL alone never reaches DELETE_CONFIRMED.
need('appCancelIsConfirmable(boolean explicitDeleteMarker){return explicitDeleteMarker;}' in listener,'APP_CANCEL confirmability equals explicit marker evidence')
need('APP_CANCEL_UNPROVEN_ROLLBACK' in listener and 'DELETE_APP_CANCEL_UNPROVEN' in listener,'unproven APP_CANCEL is rolled back')
need('APP_CANCEL directo=' not in listener,'old APP_CANCEL direct-confirm path removed')
need('APP_CANCEL + marcador explícito=' in listener,'explicit APP_CANCEL marker path remains available')
need('APP_CANCEL sin marcador nunca confirma un borrado' in selftest,'integrity self-test covers normal APP_CANCEL false positive')
marker_body=listener[listener.index('private boolean isExactDeleteText'):listener.index('private int pluralDeleteCount')]
need('t.equals("mensaje eliminado")' not in marker_body and 't.equals("message deleted")' not in marker_body and 't.equals("mensagem apagada")' not in marker_body,'generic human chat phrases are not strong delete markers')
need('repairLegacyAppCancelConfirmations' in db and 'v0516_app_cancel_cleanup_done' in db,'bounded legacy APP_CANCEL cleanup exists')
need('db.repairLegacyAppCancelConfirmations()' in main,'legacy cleanup runs before visibility normalization')
need(main.index('repairLegacyAppCancelConfirmations') < main.index('normalizeConfirmedMediaVisibility'),'legacy cleanup precedes confirmed-only normalization')

# Polling/backoff.
need('POLL_HOT_MS = 180L' in listener and 'POLL_IDLE_MS = 4_000L' in listener and 'HOT_WINDOW_MS = 9_000L' in listener,'notification fallback polling is short-hot / long-idle')

# Correlation must remain no-nearest.
joined='\n'.join([main,db,direct,voice,ms,listener])
for forbidden in ['findNearestUnlinkedMedia','findBestPendingManualMessage','reconcileRecentUnlinkedPendingMedia','promotePendingNear','nearestArm(']:
    need(forbidden not in joined, f'no nearest-winner API: {forbidden}')
need('media.size()!=cohort.size()' in db and 'reconcilePendingMediaCohort' in db,'micro-cohort FIFO still requires exact arm/media counts')
need('cohortGapMs=9_000L' in db and 'messageConversationFingerprint' in db,'micro-cohorts split by conversation and burst gap')
need('age<-1500L||age>8_000L' in db,'micro-cohort temporal envelope remains strict')

# Documents are first-class persisted pending media.
need('"document".equals(type)' in db[db.index('public String armPendingManualMedia'):db.index('public void consumePendingManualMedia')],'documents accepted by persisted pending-media table')
need('"image".equals(type)||"video".equals(type)||"document".equals(type)' in direct,'DirectMediaWatcher persists document arms')
need('"image".equals(type)||"video".equals(type)||"document".equals(type)' in ms,'MediaStoreWatcher persists document arms')
need('new String[]{"image","video","document"}' in ms,'MediaStore recovery includes documents')
need('MediaStore.Files.getContentUri("external"), "document"' in media,'MediaStore Files fallback scans WhatsApp documents')
need('isDocumentAttachment' in media and 'captureMediaStoreUri(context,uri,"document"' in media,'exact MediaStore document URI can be preserved')
need('"audio","image","video","document"' in db,'confirmed-delete finalizer reconciles documents too')
need('Documento exacto conserva su tipo' in selftest,'integrity self-test covers document placeholder')

# Pending-arm lifecycle cap.
need('ARM_WINDOW_MS = 10*60_000L' in ms,'MediaStore arm lifetime capped at 10 minutes')
need('MANUAL_DOWNLOAD_WINDOW_MS=10*60_000L' in direct,'DirectMedia arm lifetime capped at 10 minutes')
need('armPendingManualMedia(messageId,"audio",nt,10*60_000L)' in voice,'audio persisted arm lifetime capped at 10 minutes')

# Recovered refresh preserves user position and loaded window.
need('recoveredRefreshAnchorId' in main and 'recoveredRefreshTargetCount' in main,'Recovered keeps refresh anchor and loaded count')
need('findFirstVisibleItemPosition' in main and 'scrollToPositionWithOffset' in main,'Recovered restores exact visible item and offset')
need('requestedLimit=reset&&recoveredRefreshTargetCount>MEDIA_PAGE_SIZE' in main,'refresh reloads enough rows to keep current position')

# Adaptive video limits, streaming only.
need('VIDEO_LIMIT_LOW = 40L' in limits and 'VIDEO_LIMIT_NORMAL = 100L' in limits and 'VIDEO_LIMIT_HIGH = 200L' in limits,'adaptive video tiers are 40/100/200 MB')
need('StatFs' in limits and 'getAvailableBytes' in limits,'video tier uses real free storage')
need('MAX_VIDEO_BYTES' not in '\n'.join([limits,media,fast,notifmedia]),'no stale fixed 40 MB copy ceiling remains')
need('MediaLimits.maxVideoBytes(app)' in fast and 'MediaLimits.maxVideoBytes(app)' in notifmedia,'hot copy routes snapshot adaptive limit before streaming')
need('límite actual' in main and 'limitLabel' in main,'UI reports current adaptive video limit')

# Home remains simple while exposing actionable status.
need('WA Vault activo' in main and 'Estado · ' in main,'Home keeps active headline plus protection state')
for label in ['Notificaciones','Archivos','Batería','Fotos y videos','Audios','MediaStore']:
    need(label in main,f'Home exposes status for {label}')
need('Reparar protección' in main,'repair action remains visible')

# Existing correctness invariants retained.
need('CONFIRMED_MEDIA_SQL' in db and 'messages.deletion_state=2' in db,'Recovered feed remains confirmed-only')
need('strictMediaKindForText' in listener and 'isExactStickerPlaceholder' in listener,'strict media placeholder parser retained')
need('whatsapp stickers' in direct.lower(),'stickers remain excluded')
need('media_tombstones' in db and 'USER_DELETE_BLOCKED' in db,'permanent-delete tombstones retained')
need('deleteMediaPermanently(long mediaId,boolean deleteGalleryCopy)' in db,'Gallery-aware permanent delete retained')

# Basic SQL confirmed-only behavior.
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
need(c.execute('SELECT id,retention_state FROM media ORDER BY id').fetchall()==[(1,2),(2,1),(3,1),(4,1)],'confirmed-only SQL hides normal/probable/unlinked media')

for rel in ['app/src/main/java/com/fer/wavault/MainActivity.java','app/src/main/java/com/fer/wavault/VaultDb.java','app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java','app/src/main/java/com/fer/wavault/DirectMediaWatcher.java','app/src/main/java/com/fer/wavault/DirectVoiceWatcher.java','app/src/main/java/com/fer/wavault/MediaStoreWatcher.java','app/src/main/java/com/fer/wavault/MediaArchiver.java']:
    x=read(rel);need('<<<<<<<' not in x and '>>>>>>>' not in x and '=======' not in x,rel+' has no merge markers')
need(not any(ROOT.rglob('*.orig')) and not any(ROOT.rglob('*.rej')),'no .orig/.rej artifacts')
print('v0.5.16 regression suite: PASS')
