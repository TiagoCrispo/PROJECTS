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
main=read('app/src/main/java/com/fer/wavault/MainActivity.java')
db=read('app/src/main/java/com/fer/wavault/VaultDb.java')
listener=read('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java')
media=read('app/src/main/java/com/fer/wavault/MediaArchiver.java')
direct=read('app/src/main/java/com/fer/wavault/DirectMediaWatcher.java')
selftest=read('app/src/main/java/com/fer/wavault/CaptureIntegritySelfTest.java')

need('versionCode = 64' in gradle and 'versionName = "0.5.14"' in gradle,'version 0.5.14 / code 64')
need('super(c, "wa_vault.db", null, 13)' in db,'SQLite schema v13')
need('capture_batch_key' in db and 'batch_key' in db and 'buildCaptureBatchKey' in db,'persistent capture/batch identity')
need('media_tombstones' in db and 'USER_DELETE_BLOCKED' in db and 'TRASH_RESTORE_BLOCKED' in db,'permanent-delete tombstone guards')
need('deleteAllMediaPermanently' in db and 'deleteUnlinkedMediaPermanently' in db and 'deleteMediaOlderThanPermanently' in db,'unbounded permanent cleanup routes')
need('findNearestUnlinkedMedia' not in main,'UI never links media by nearest timestamp')
need('CONFIRMED_MEDIA_SQL' in db and 'messages.deletion_state=2' in db,'Recovered feed derives from confirmed deletion')
need('strictMediaKindForText' in listener and 'isExactStickerPlaceholder' in listener,'strict placeholder parser present')
need('b.matches("^\\\\d{1,2}:\\\\d{2}$")' not in listener,'clock-like text is not treated as voice note')
need('te mando una foto después' in selftest and '12:34' in selftest,'integrity test covers loose-text false positives')
need('whatsapp stickers' in direct.lower() and 'return ""' in direct[direct.lower().find('whatsapp stickers'):direct.lower().find('whatsapp stickers')+180],'sticker directory rejected')
need('listPendingMediaAfterId' in db and 'listPendingMediaAfterId' in media,'pending monitor resume uses stable id pagination')
need('CAPTURE_FAIL_' in db and 'logCaptureFailure' in listener,'critical capture failures are logged')
need(main.count('NotificationAudioCapture.linkToMessage') == 0,'UI has no notification-audio capture side effects')
# Listener may call the early notification audio linker in several logical paths, but never twice consecutively.
need('NotificationAudioCapture.linkToMessage(getApplicationContext(), earlyAudioUri[0], linkId);\n                    NotificationAudioCapture.linkToMessage' not in listener,'no duplicate early-audio link call')
need('12000' not in main and '10000' not in main,'UI selection/cleanup has no 10k/12k ceiling')
need('bottomNav()' in main and 'Solo borrados confirmados' in main and 'Temporal oculto' in main,'minimal confirmed-only UI structure')
need('#5CE0A4' in read('app/src/main/res/values/colors.xml'),'launcher palette matches UI mint accent')

# Validate the correlated SQL shape used by normalization / recovered feed.
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
need(rows==[(1,2),(2,1),(3,1),(4,1)],'confirmed-only SQL hides normal/probable/unlinked legacy media')

# Basic source hygiene: no conflict markers.
for rel in ['app/src/main/java/com/fer/wavault/MainActivity.java','app/src/main/java/com/fer/wavault/VaultDb.java','app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java']:
    s=read(rel); need('<<<<<<<' not in s and '>>>>>>>' not in s and '=======' not in s,rel+' has no merge markers')
print('v0.5.14 regression suite: PASS')
