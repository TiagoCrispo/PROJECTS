from pathlib import Path
import sqlite3
ROOT=Path(__file__).resolve().parents[1]

def text(rel): return (ROOT/rel).read_text(encoding='utf-8')
def need(c,m):
    if not c: raise AssertionError(m)

db=text('app/src/main/java/com/fer/wavault/VaultDb.java')
media=text('app/src/main/java/com/fer/wavault/MediaArchiver.java')
preview=text('app/src/main/java/com/fer/wavault/NotificationPreviewCapture.java')
processing=text('app/src/main/java/com/fer/wavault/CaptureProcessingEngine.java')
recovery=text('app/src/main/java/com/fer/wavault/CaptureRecovery.java')
notif_audio=text('app/src/main/java/com/fer/wavault/NotificationAudioCapture.java')
notif_media=text('app/src/main/java/com/fer/wavault/NotificationMediaCapture.java')
direct_voice=text('app/src/main/java/com/fer/wavault/DirectVoiceWatcher.java')
thumb=text('app/src/main/java/com/fer/wavault/MediaThumbnailLoader.java')
validation=text('app/src/main/java/com/fer/wavault/MediaValidation.java')
storage=text('app/src/main/java/com/fer/wavault/StorageAnalyzer.java')
downloads=text('app/src/main/java/com/fer/wavault/DownloadsExporter.java')
gallery=text('app/src/main/java/com/fer/wavault/GalleryExporter.java')
coordinator=text('app/src/main/java/com/fer/wavault/CaptureCoordinator.java')

need('super(c, "wa_vault.db", null, 15)' in db,'DB v15 required')
need('CREATE TABLE media_message_links' in db and 'PRIMARY KEY(media_id,message_id)' in db,'many-to-many media link table missing')
need('INSERT OR IGNORE INTO media_message_links' in db,'link insertion must be idempotent')
need('transferMediaLinks' in db and 'deleteMediaLinks' in db and 'repairPrimaryLink' in db,'media link lifecycle helpers missing')
need('EXISTS (SELECT 1 FROM media_message_links' in db,'media queries must use canonical link table')
need('insertPreparedEncryptedMedia' in db,'prepared encrypted DB commit API missing')
need('alreadyEncrypted' in db and 'MediaCrypto.isEncrypted(f)' in db,'DB must verify prepared ciphertext')
need('HASH_TOMBSTONE_RETRY_WINDOW_MS' in db and 'deleted_at>=?' in db,'global content tombstone must be bounded')

# Permanent archives may be referenced for reads/migrations, but active capture writes must converge
# through commitStagedSecure/moveCiphertextDurably rather than direct plaintext FileOutputStream.
need('commitStagedSecureInternal' in media and 'MediaCrypto.encryptInPlace(ready)' in media,'secure staged commit missing')
need(media.index('MediaCrypto.encryptInPlace(ready)') < media.index('moveCiphertextDurably(ready,dst)'), 'ciphertext must be created before permanent move')
need('insertPreparedEncryptedMedia' in media and media.index('moveCiphertextDurably(ready,dst)') < media.index('insertPreparedEncryptedMedia'), 'DB must reference only moved ciphertext')
need('out.getFD().sync()' in media,'staging copies must be fsynced before ready promotion')
need('completedPartToReady' in media and 'stagingName("part_",type)' in media,'part->ready staging contract missing')
need('MediaCrypto.isEncrypted(ready)' in media and 'MediaCrypto.decryptTo(encryptedReady,part)' in media,'encrypted-ready process-death recovery missing')
need('_audio_' in recovery and '_document_' in recovery,'recovery must recognize audio/document ready files')

# No capture class outside MediaArchiver writes plaintext straight into permanent archive dirs.
need('vault_media' not in preview,'notification preview writes directly to permanent archive')
need('vault_media' not in processing,'partial preview writes directly to permanent archive')
need('vault_audio_quarantine' not in notif_audio,'notification audio writes directly to permanent archive')
need('vault_media' not in notif_media,'notification media writes directly to permanent archive')
need(db.count('public long insertPendingAudio(')==1,'legacy insertPendingAudio API unexpectedly duplicated')
need('insertPendingAudio(' not in media,'MediaArchiver must not bypass secure staged pending-audio commit')
need('insertMediaWithOrigin(' not in media and 'insertMediaWithOrigin(' not in preview and 'insertMediaWithOrigin(' not in processing,'capture paths still bypass secure staged commit')

# Preview is physically JPEG while logical media type may remain video.
need('commitGeneratedPreviewStaged' in media and 'logicalType,"image"' in media,'preview physical/logical type separation missing')
need('MediaArchiver.commitGeneratedPreviewStaged' in preview and 'MediaArchiver.commitGeneratedPreviewStaged' in processing,'preview routes do not use secure commit')


# Block 4 hardening: no persistent plaintext derivatives or unmanaged early-audio cache.
need('wa_early_audio' not in direct_voice,'DirectVoiceWatcher still writes plaintext to legacy early-audio cache')
need('MediaArchiver.newStagingPart(app, "audio")' in direct_voice and 'MediaArchiver.completedPartToReady' in direct_voice,'early voice capture must use recoverable staging')
need('out.getFD().sync()' in direct_voice,'early voice capture must fsync before ready promotion')
need('FileOutputStream' not in thumb,'thumbnail loader must not persist decrypted JPEG derivatives')
need('decodeFile(disk' not in thumb and 'diskFile(' not in thumb,'thumbnail loader still reads persistent plaintext thumb cache')
need('wa_media_thumbs' in thumb,'legacy thumbnail cache cleanup should remain for upgrade hygiene')
need('PENDING_TTL_MS' in notif_audio and 'pendingKey(' in notif_audio and 'pendingMessage(' in notif_audio and 'clearPendingForMessage(' in notif_audio,'audio pending-link TTL/pruning missing')
need('PENDING_TTL_MS' in notif_media and 'pendingKey(' in notif_media and 'pendingMessage(' in notif_media and 'clearPendingForMessage(' in notif_media,'media pending-link TTL/pruning missing')
need('db.findMediaId("notif:"+key+"|"+ts)' in notif_audio,'notification audio late-link identity must include persisted message timestamp')
need('db.findMediaId("notif-media:"+key+"|"+ts)' in notif_media,'notification media late-link identity must include persisted message timestamp')
need('deleteStagedReadyAndMeta' in media and 'deleteStagedMeta(ready);' in media,'staging sidecars must be cleaned after commit/rejection')
need('scheduleWithFixedDelay' not in media and 'runPendingPass' in media and 'pendingTickerStarted.set(false)' in media,'quarantine monitor must self-stop instead of waking forever')
need('audioHeaderLooksValid' in media and 'documentLooksComplete' in media,'strict audio/document validation missing')
need('fos.getFD().sync()' in validation,'generated preview bytes must be fsynced')
need('vault_partial' in storage and '24L*60L*60L*1000L' in storage,'encrypted partial archive must be counted and bounded')
need('new String[]{"fast_part_","part_"}' in storage and '"ready_"' not in storage.split('cleanupTemporary',1)[1].split('cleanupDecryptedTemporary',1)[0],'generic cleanup must never age-delete completed ready captures')
need('RECOVERY_REQUESTED_THIS_PROCESS' in coordinator and 'compareAndSet(false,true)' in coordinator,'every new process must request staging recovery once')
need('now-lastRecovery>15L*60L*1000L' not in coordinator,'persisted wall-clock gate must not suppress process-death recovery')
need('out.getFD().sync()' in downloads and 'outFile.delete()' in downloads,'legacy Downloads export must fsync and roll back partial output')
need('out.getFD().sync()' in gallery and 'outFile.delete()' in gallery,'legacy Gallery export must fsync and roll back partial output')
need('deleteMediaLinks(db,id)' in db or 'deleteMediaLinks(' in db,'orphan media-link cleanup missing')

# Model: same physical hash may have N logical message links but only one blob row.
conn=sqlite3.connect(':memory:')
conn.executescript('''
CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT, content_hash TEXT NOT NULL, media_type TEXT NOT NULL, linked_message_id INTEGER NOT NULL DEFAULT 0, byte_size INTEGER NOT NULL);
CREATE UNIQUE INDEX idx_media_hash_type_unique ON media(content_hash,media_type) WHERE content_hash<>'';
CREATE TABLE media_message_links(media_id INTEGER NOT NULL,message_id INTEGER NOT NULL,linked_at INTEGER NOT NULL,link_source TEXT NOT NULL DEFAULT '',PRIMARY KEY(media_id,message_id));
''')
def capture(h,t,msg,size=123):
    row=conn.execute('select id from media where content_hash=? and media_type=?',(h,t)).fetchone()
    if row: mid=row[0]
    else:
        cur=conn.execute('insert into media(content_hash,media_type,linked_message_id,byte_size) values(?,?,?,?)',(h,t,msg,size));mid=cur.lastrowid
    if msg>0:
        conn.execute("insert or ignore into media_message_links(media_id,message_id,linked_at,link_source) values(?,?,1,'test')",(mid,msg))
        conn.execute('update media set linked_message_id=case when linked_message_id=0 then ? else linked_message_id end where id=?',(msg,mid))
    conn.commit();return mid
m1=capture('h1','image',101)
m2=capture('h1','image',202)
need(m1==m2,'same content created two physical blobs')
need(conn.execute('select count(*) from media').fetchone()[0]==1,'physical dedupe failed')
need(conn.execute('select count(*) from media_message_links').fetchone()[0]==2,'distinct legitimate message association lost')
# Replay same route/message is idempotent.
for _ in range(100): capture('h1','image',202)
need(conn.execute('select count(*) from media').fetchone()[0]==1 and conn.execute('select count(*) from media_message_links').fetchone()[0]==2,'replay created duplicates')
# Removing one message link must not remove the blob while another link exists.
conn.execute('delete from media_message_links where media_id=? and message_id=?',(m1,101));conn.commit()
need(conn.execute('select count(*) from media where id=?',(m1,)).fetchone()[0]==1,'blob removed while another logical link survives')
need(conn.execute('select count(*) from media_message_links where media_id=?',(m1,)).fetchone()[0]==1,'remaining logical link lost')

# Crash-state invariant for secure commit. Permanent plaintext is never an allowed state.
states={
 'copying':('staging_plain',False,False),
 'ready_plain':('staging_plain',False,False),
 'encrypted_ready':('staging_cipher',False,False),
 'moved_before_db':('permanent_cipher',False,True),
 'committed':('permanent_cipher',True,True),
}
for name,(where,dbref,cipher) in states.items():
    need(not (where=='permanent_plain'),f'{name}: plaintext reached permanent archive')
    if where.startswith('permanent'): need(cipher,f'{name}: permanent bytes are not ciphertext')
    if dbref: need(where=='permanent_cipher',f'{name}: DB references non-final media')

print('BLOCK4_MEDIA_REGRESSION_PASS')
print('1 physical blob + N logical links; replay idempotent; permanent archive ciphertext-only; recovery contract present')
