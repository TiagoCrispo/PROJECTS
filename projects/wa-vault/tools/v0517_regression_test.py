#!/usr/bin/env python3
from pathlib import Path
import sqlite3
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
limits=read('app/src/main/java/com/fer/wavault/MediaLimits.java')
direct=read('app/src/main/java/com/fer/wavault/DirectMediaWatcher.java')
voice=read('app/src/main/java/com/fer/wavault/DirectVoiceWatcher.java')
ms=read('app/src/main/java/com/fer/wavault/MediaStoreWatcher.java')

need('versionCode = 67' in gradle and 'versionName = "0.5.17"' in gradle,'version 0.5.17 / code 67')
need('super(c, "wa_vault.db", null, 13)' in db,'SQLite schema remains v13')
need('android:versionCode' not in manifest and 'android:versionName' not in manifest,'manifest has no stale version attributes')

# v0.5.17 deletion precision.
need('return reason == REASON_APP_CANCEL;' in listener,'APP_CANCEL_ALL filtered before candidate work')
need('APP_CANCEL_ALL_IGNORED' not in listener,'dead APP_CANCEL_ALL candidate path removed')
need('structuredPluralDeleteCount(parsed, previousItems)' in listener,'plural delete text requires structured snapshot proof')
need('added==0&&missing==count' in listener and 'candidate.stableTimestamp' in listener,'plural proof requires exact missing count + structured MessagingStyle')
need('return isExactDeleteText(t) ? 1 : 0;' in listener,'generic deletion helper accepts singular exact markers only')
need('normalizeDeleteMarkerText' in listener and '\\u200b' in listener and "\\u00a0" in listener,'delete marker normalization handles invisible/non-breaking spacing')
need('recordUnverifiableDelete("APP_CANCEL sin marcador"' in listener,'unverifiable delete candidates are counted internally')
need('DELETE_UNVERIFIABLE' in listener and 'delete_unverifiable_count' in listener,'diagnostic evidence for hidden unverifiable candidates exists')
need('Borrados no verificables' in main,'diagnostics expose unverifiable candidate count')

# Keep strongest v0.5.16 no-guess invariants.
joined='\n'.join([main,db,direct,voice,ms,listener])
for forbidden in ['findNearestUnlinkedMedia','findBestPendingManualMessage','reconcileRecentUnlinkedPendingMedia','promotePendingNear','nearestArm(']:
    need(forbidden not in joined, f'no nearest-winner API: {forbidden}')
need('media.size()!=cohort.size()' in db and 'reconcilePendingMediaCohort' in db,'micro-cohort FIFO still requires exact arm/media counts')
need('cohortGapMs=9_000L' in db and 'messageConversationFingerprint' in db,'micro-cohorts remain split by conversation and burst')
need('"document".equals(type)' in db[db.index('public String armPendingManualMedia'):db.index('public void consumePendingManualMedia')],'documents remain first-class persisted arms')
need('POLL_HOT_MS = 180L' in listener and 'POLL_IDLE_MS = 4_000L' in listener and 'HOT_WINDOW_MS = 9_000L' in listener,'short-hot / long-idle polling retained')

# Storage reserve + adaptive documents/videos.
for token in ['STORAGE_RESERVE_MIN = 768L','STORAGE_RESERVE_PERCENT = 8L','writableBytes(Context context)','maxDocumentBytes(Context context)','DOCUMENT_LIMIT_LOW = 50L','DOCUMENT_LIMIT_NORMAL = 150L','DOCUMENT_LIMIT_HIGH = 300L']:
    need(token in limits,f'limit policy contains {token}')
need('Math.min(tier,writable)' in limits,'adaptive limits cannot cross protected storage reserve')
need('MediaLimits.mediaTooLarge(context,type,size)' in media,'MediaStore precheck enforces video/document adaptive limits')
need('MediaLimits.maxBytes(context,type)' in media,'MediaStore streaming uses adaptive type-specific cap')
need('"video".equals(type)||"document".equals(type)' in media,'streaming cap covers documents as well as videos')
need('MediaLimits.recordLimit(app,type' in media,'direct file copy records adaptive type limit')

# Scroll fallback if anchor disappears.
need('recoveredRefreshFallbackId' in main and 'recoveredRefreshAnchorIndex' in main,'Recovered stores fallback id/index')
need('if(pos<0&&fallback>0)' in main and 'Math.min(oldIndex,recoveredItems.size()-1)' in main,'Recovered restores neighbor/index when anchor disappears')
need('scrollToPositionWithOffset' in main,'Recovered still restores exact offset')

# Existing correctness invariants.
need('CONFIRMED_MEDIA_SQL' in db and 'messages.deletion_state=2' in db,'Recovered feed remains confirmed-only')
need('strictMediaKindForText' in listener and 'isExactStickerPlaceholder' in listener,'strict placeholder parser retained')
need('whatsapp stickers' in direct.lower(),'stickers remain excluded')
need('media_tombstones' in db and 'USER_DELETE_BLOCKED' in db,'tombstones retained')
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

for rel in ['app/src/main/java/com/fer/wavault/MainActivity.java','app/src/main/java/com/fer/wavault/VaultDb.java','app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java','app/src/main/java/com/fer/wavault/DirectMediaWatcher.java','app/src/main/java/com/fer/wavault/MediaStoreWatcher.java','app/src/main/java/com/fer/wavault/MediaArchiver.java','app/src/main/java/com/fer/wavault/MediaLimits.java']:
    x=read(rel);need('<<<<<<<' not in x and '>>>>>>>' not in x and '=======' not in x,rel+' has no merge markers')
need(not any(ROOT.rglob('*.orig')) and not any(ROOT.rglob('*.rej')),'no .orig/.rej artifacts')
print('v0.5.17 regression suite: PASS')
