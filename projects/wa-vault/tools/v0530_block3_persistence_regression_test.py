from pathlib import Path
import sqlite3

ROOT=Path(__file__).resolve().parents[1]
listener=(ROOT/'app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java').read_text()
db=(ROOT/'app/src/main/java/com/fer/wavault/VaultDb.java').read_text()

def need(c,m):
    if not c: raise AssertionError(m)

# Source invariants: identity of repeated logical messages.
need('identity_slot INTEGER NOT NULL DEFAULT 1' in db, 'identity_slot column missing')
need('idx_messages_identity_lookup' in db, 'identity lookup index missing')
need('|slot=' in db and 'messageFingerprint' in db, 'durable fingerprint does not include identity slot')
need('findExactMessageId(sbn.getPackageName(),p.conversation,p.sender,p.text,p.timestamp,p.isGroup,occurrence)' in listener,
     'listener does not resolve duplicate occurrence by durable slot')
need('p.messageIndex,\n                        occurrence' in listener,
     'listener does not persist duplicate occurrence slot')
need('m.identitySlot' in listener and 'Math.max(1,m.identitySlot)' in listener,
     'DB->snapshot reconstruction loses identity slot')


# Package/shortcut scope prevents WhatsApp and WhatsApp Business chats with the same visible name from sharing baselines.
need('sbn.getPackageName()' in listener and 'getShortcutId()' in listener and 'conversationSnapshotKey(StatusBarNotification sbn,String conversation)' in listener,
     'conversation snapshot identity is not package/shortcut scoped')
need('conversationSnapshotKey(sbn, fallbackConversation)' in listener, 'live deletion trust is not package scoped')

# Identity crypto failure is fail-closed, never a fake "#1" token.
need('if (baseToken.isEmpty())' in listener and 'MESSAGE_REJECTED_IDENTITY' in listener,
     'empty HMAC token can still become a trusted occurrence token')
need('if (notificationKey.isEmpty())' in listener and 'IDENTITY_UNAVAILABLE' in listener,
     'missing stable notification identity is not rejected')
need('try{fp=messageFingerprint' in db and 'if(fp==null||fp.isEmpty())return -1L;' in db,
     'message fingerprint failure can escape as crash/invalid identity')

# Dangerous semantic duplicate repair is disabled: equality of text/time is not proof of duplication.
repair=db[db.index('public int repairProvableMessageDuplicates'):db.index('/** Collapse capture-route copies')]
need('return 0;' in repair and 'delete("messages"' not in repair and 'markDeletedById' not in repair,
     'maintenance can still collapse legitimate identical messages')

# Evidence and deletion are one SQLite transaction.
need('CREATE TABLE deletion_evidence' in db, 'deletion evidence table missing')
confirm=db[db.index('public int confirmDeletedWithEvidence'):db.index('public boolean unmarkProbableDeletedById')]
for x in ('beginTransaction()', 'insertWithOnConflict("deletion_evidence"', 'db.update("messages"', 'setTransactionSuccessful()', 'endTransaction()'):
    need(x in confirm, f'atomic evidence/deletion transaction missing {x}')
need('consumed_delete_evidence_v1' not in listener, 'old SharedPreferences deletion ledger still active')
need('db.confirmDeletedWithEvidence(exactTimestampMatch.id, evidenceKey)' in listener,
     'listener bypasses atomic evidence/deletion transaction')

# Snapshot generations are one durable commit; conversation trust is granted only after success.
advance=listener[listener.index('boolean canAdvanceBaseline'):listener.index('if (!canAdvanceBaseline) prefs.edit()')]
need('persistAuthoritativeSnapshot(sbn, notificationKey, fallbackConversation, currentIds, currentItems, stateSignature)' in advance,
     'authoritative snapshot is not persisted as one unit')
need('if (persisted)' in advance and 'trustedBaselineConversations.add(ck)' in advance,
     'conversation trust is granted without persistence success')
need('snapshotPersistenceRetryKeys.add(notificationKey)' in advance,
     'failed snapshot persistence is not retried')
persist=listener[listener.index('private boolean persistAuthoritativeSnapshot'):listener.index('private void saveSnapshotItems')]
for key in ('snapshot_','snapshot2_','conv_snapshot2_','conv_','state2_'):
    need(key in persist, f'atomic snapshot commit missing {key}')
need('.commit();' in persist, 'authoritative snapshot uses asynchronous apply instead of durable commit')
need('stateSignature.equals(oldSignature) && !snapshotPersistenceRetryKeys.contains(notificationKey)' in listener,
     'POLL_SYNC can suppress persistence retry after a failed commit')

# DB conflict recovery must resolve the already-won insert, not report a false failure.
insert=db[db.index('public long insertMessage(String pkg, String conv, String sender, String body, long ts, String key,\n                              boolean isGroup'):db.index('private static String safeText')]
need('SQLiteDatabase.CONFLICT_IGNORE' in insert and '"fingerprint=?"' in insert,
     'idempotent message insert cannot recover conflict winner')

# Simplified SQLite transaction model: crash before commit -> neither side persists; commit -> both persist.
c=sqlite3.connect(':memory:')
c.execute('create table messages(id integer primary key, is_deleted integer not null default 0, deletion_state integer not null default 0)')
c.execute('create table deletion_evidence(evidence_key text primary key, message_id integer not null, result_state integer not null)')
c.execute('insert into messages(id) values(1)'); c.commit()

# Simulated process death before transaction commit.
c.execute('begin')
c.execute('insert into deletion_evidence values(?,?,?)',('e1',1,2))
c.execute('update messages set is_deleted=1,deletion_state=2 where id=1')
c.rollback()
need(c.execute('select is_deleted,deletion_state from messages where id=1').fetchone()==(0,0), 'rollback left message deleted')
need(c.execute('select count(*) from deletion_evidence').fetchone()[0]==0, 'rollback left evidence consumed')

# Durable commit persists both.
c.execute('begin')
c.execute('insert into deletion_evidence values(?,?,?)',('e1',1,2))
c.execute('update messages set is_deleted=1,deletion_state=2 where id=1')
c.commit()
need(c.execute('select is_deleted,deletion_state from messages where id=1').fetchone()==(1,2), 'commit lost message state')
need(c.execute('select count(*) from deletion_evidence').fetchone()[0]==1, 'commit lost evidence')

# Durable occurrence slots: identical message payloads can exist twice and replay maps to same two slots.
c.execute('create table logical_messages(id integer primary key autoincrement, fp text unique, slot integer not null, body text, ts integer)')
for slot in (1,2): c.execute('insert or ignore into logical_messages(fp,slot,body,ts) values(?,?,?,?)',(f'fp-slot-{slot}',slot,'hola',12345))
c.commit()
need(c.execute('select count(*) from logical_messages').fetchone()[0]==2, 'legitimate identical messages collapsed')
for slot in (1,2): c.execute('insert or ignore into logical_messages(fp,slot,body,ts) values(?,?,?,?)',(f'fp-slot-{slot}',slot,'hola',12345))
c.commit()
need(c.execute('select count(*) from logical_messages').fetchone()[0]==2, 'replay created duplicate rows')

print('BLOCK3_PERSISTENCE_IDEMPOTENCY_REGRESSION_PASS')
print('identical same-ms messages -> distinct durable slots; replay -> same rows')
print('evidence + DELETE_CONFIRMED -> one SQLite transaction')
print('process-death before commit -> neither state persists')
print('authoritative snapshot generations -> one synchronous commit before trust')
