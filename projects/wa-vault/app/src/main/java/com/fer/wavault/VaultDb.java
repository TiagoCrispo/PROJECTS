package com.fer.wavault;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VaultDb extends SQLiteOpenHelper {
    private static final long HASH_TOMBSTONE_RETRY_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private final CryptoManager crypto;
    private final Context appContext;
    private static final AtomicInteger EVENT_WRITES = new AtomicInteger(0);
    /** Serialize the check->insert critical sections used by the aggressive capture routes. */
    private static final Object MESSAGE_INSERT_LOCK = new Object();
    private static final Object MEDIA_INSERT_LOCK = new Object();
    private static final ScheduledExecutorService DELETE_RETRY = Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"wa-vault-delete-retry");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;});
    private static final Set<Long> DELETE_RETRY_SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final Map<Long,Integer> DELETE_RETRY_ATTEMPTS = new ConcurrentHashMap<>();

    public static final int RETENTION_NORMAL = 0;
    public static final int RETENTION_PENDING = 1;
    public static final int RETENTION_KEPT_DELETED = 2;
    public static final long PROVISIONAL_MEDIA_TTL_MS = 10L * 60L * 1000L;

    public static final int DELETE_NONE = 0;
    public static final int DELETE_PROBABLE = 1;
    public static final int DELETE_CONFIRMED = 2;
    public static final String DELETION_PLACEHOLDER_BODY = "Mensaje borrado · contenido original no disponible";

    public static class Msg {
        public long id, timestamp;
        public int deletionState, messageIndex, identitySlot;
        public String conversation, sender, body, notificationKey;
        public boolean deleted, isGroup;
    }

    public static class Media {
        public long id, size, capturedAt, sourceTime, linkedMessageId, expiresAt, durationMs;
        public int retentionState, width, height;
        public boolean favorite, trashed;
        public long trashedAt;
        public String sourceUri, name, mime, type, path, contentHash, origin, canonicalKey, galleryUri, captureBatchKey;
    }

    public static class Event {
        public long id, timestamp, messageId, mediaId;
        public String code, detail;
    }

    public static class CaptureAttempt {
        public long id, bytes, sourceTime, messageId, createdAt, resolvedMediaId;
        public String familyKey, type, state, reason, localPath;
    }

    public static class RecoveryJob {
        public long id, bytes, sourceTime, messageId, createdAt, updatedAt, resolvedMediaId;
        public String jobKey, type, state, reason, localPath, origin;
    }

    public static class PendingManualMedia {
        public long messageId, armedAt, expiresAt;
        public String type, batchKey;
    }

    public static class Stats {
        public int messages, deletedMessages, media, pendingMedia, keptMedia, unlinkedMedia, favorites;
        public int detectedFiles, savedFiles, recoveryIssues, trashedMedia;
        public long totalBytes, audioBytes, imageBytes, videoBytes, documentBytes, pendingBytes, trashBytes, physicalBytes;
    }

    public static class MediaRef {
        public long id; public String path;
        MediaRef(long id,String path){this.id=id;this.path=path;}
    }

    public VaultDb(Context c) { super(c, "wa_vault.db", null, 15); appContext=c.getApplicationContext(); crypto=new CryptoManager(appContext); }
    private void notifyUi(String kind){ VaultUiNotifier.notifyChanged(appContext,kind); }
    private String sourceKey(String raw){return MetadataPrivacy.sourceKey(appContext,raw);}
    private String[] sourceArgs(String raw){String k=sourceKey(raw);return k.equals(raw)?new String[]{k}:new String[]{k,raw==null?"":raw};}
    private String sourceWhere(String raw){return sourceKey(raw).equals(raw)?"source_uri=?":"source_uri IN (?,?)";}
    private String messageFingerprint(String pkg,String conv,String sender,String body,long ts,boolean isGroup,int identitySlot){int slot=Math.max(1,identitySlot);return MetadataPrivacy.token(appContext,"msgfp",(pkg==null?"":pkg)+"|"+safeText(conv)+"|"+safeText(sender)+"|"+safeText(body)+"|"+ts+"|"+(isGroup?1:0)+"|slot="+slot);}

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT NOT NULL, conversation BLOB NOT NULL, sender BLOB NOT NULL, body BLOB NOT NULL, timestamp INTEGER NOT NULL, notification_key TEXT, fingerprint TEXT UNIQUE, is_deleted INTEGER NOT NULL DEFAULT 0, is_group INTEGER NOT NULL DEFAULT 0, deletion_state INTEGER NOT NULL DEFAULT 0, message_index INTEGER NOT NULL DEFAULT 0, identity_slot INTEGER NOT NULL DEFAULT 1)");
        db.execSQL("CREATE INDEX idx_messages_time ON messages(timestamp DESC)");
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(timestamp DESC, is_deleted)");
        db.execSQL("CREATE INDEX idx_messages_deleted_time ON messages(is_deleted, timestamp DESC)");
        db.execSQL("CREATE INDEX idx_messages_identity_lookup ON messages(package_name, timestamp, is_group, identity_slot, id)");
        db.execSQL("CREATE TABLE media(id INTEGER PRIMARY KEY AUTOINCREMENT, source_uri TEXT UNIQUE NOT NULL, local_path TEXT NOT NULL, display_name BLOB NOT NULL, mime_type TEXT, media_type TEXT, byte_size INTEGER, captured_at INTEGER, source_time INTEGER NOT NULL DEFAULT 0, linked_message_id INTEGER NOT NULL DEFAULT 0, retention_state INTEGER NOT NULL DEFAULT 0, expires_at INTEGER NOT NULL DEFAULT 0, content_hash TEXT NOT NULL DEFAULT '', origin TEXT NOT NULL DEFAULT '', canonical_key TEXT NOT NULL DEFAULT '', width INTEGER NOT NULL DEFAULT 0, height INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, gallery_uri TEXT NOT NULL DEFAULT '', is_favorite INTEGER NOT NULL DEFAULT 0, trash_state INTEGER NOT NULL DEFAULT 0, trashed_at INTEGER NOT NULL DEFAULT 0, capture_batch_key TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_media_time ON media(captured_at DESC)");
        db.execSQL("CREATE INDEX idx_media_link ON media(linked_message_id)");
        db.execSQL("CREATE INDEX idx_media_retention ON media(retention_state, expires_at)");
        db.execSQL("CREATE INDEX idx_media_hash ON media(content_hash)");
        db.execSQL("CREATE UNIQUE INDEX idx_media_hash_type_unique ON media(content_hash,media_type) WHERE content_hash<>''");
        db.execSQL("CREATE INDEX idx_media_canonical ON media(canonical_key)");
        db.execSQL("CREATE INDEX idx_media_type_time ON media(media_type, retention_state, captured_at DESC)");
        db.execSQL("CREATE INDEX idx_media_favorite_time ON media(is_favorite, captured_at DESC)");
        db.execSQL("CREATE INDEX idx_media_trash_time ON media(trash_state, trashed_at DESC)");
        db.execSQL("CREATE INDEX idx_media_batch ON media(capture_batch_key, media_type, retention_state)");
        db.execSQL("CREATE TABLE media_message_links(media_id INTEGER NOT NULL, message_id INTEGER NOT NULL, linked_at INTEGER NOT NULL, link_source TEXT NOT NULL DEFAULT '', PRIMARY KEY(media_id,message_id))");
        db.execSQL("CREATE INDEX idx_media_message_links_message ON media_message_links(message_id,media_id)");
        db.execSQL("CREATE INDEX idx_media_message_links_media ON media_message_links(media_id,message_id)");
        db.execSQL("CREATE TABLE bank_seen(source_uri TEXT PRIMARY KEY, first_seen INTEGER NOT NULL, resolved_state INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE event_log(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, code TEXT NOT NULL, detail BLOB, message_id INTEGER NOT NULL DEFAULT 0, media_id INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_event_time ON event_log(timestamp DESC)");
        db.execSQL("CREATE TABLE deletion_evidence(evidence_key TEXT PRIMARY KEY NOT NULL, consumed_at INTEGER NOT NULL, message_id INTEGER NOT NULL DEFAULT 0, result_state INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_deletion_evidence_time ON deletion_evidence(consumed_at DESC)");
        db.execSQL("CREATE TABLE pending_manual_media(message_id INTEGER PRIMARY KEY, media_type TEXT NOT NULL, armed_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, batch_key TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_pending_manual_expiry ON pending_manual_media(expires_at)");
        db.execSQL("CREATE INDEX idx_pending_manual_type ON pending_manual_media(media_type, expires_at)");
        db.execSQL("CREATE INDEX idx_pending_manual_batch ON pending_manual_media(batch_key, media_type)");
        db.execSQL("CREATE TABLE capture_attempts(id INTEGER PRIMARY KEY AUTOINCREMENT, family_key TEXT NOT NULL DEFAULT '', media_type TEXT NOT NULL DEFAULT '', byte_size INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', local_path TEXT NOT NULL DEFAULT '', source_time INTEGER NOT NULL DEFAULT 0, message_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, resolved_media_id INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_capture_attempt_time ON capture_attempts(created_at DESC)");
        db.execSQL("CREATE INDEX idx_capture_attempt_family ON capture_attempts(family_key, created_at DESC)");
        db.execSQL("CREATE TABLE recovery_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, job_key TEXT UNIQUE NOT NULL, media_type TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', byte_size INTEGER NOT NULL DEFAULT 0, source_time INTEGER NOT NULL DEFAULT 0, message_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, resolved_media_id INTEGER NOT NULL DEFAULT 0, local_path TEXT NOT NULL DEFAULT '', origin TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX idx_recovery_state ON recovery_jobs(state, updated_at DESC)");
        db.execSQL("CREATE INDEX idx_recovery_time ON recovery_jobs(updated_at DESC)");
        db.execSQL("CREATE TABLE media_tombstones(source_uri TEXT PRIMARY KEY NOT NULL, content_hash TEXT NOT NULL DEFAULT '', media_type TEXT NOT NULL DEFAULT '', deleted_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_media_tombstone_hash ON media_tombstones(content_hash, media_type)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE media ADD COLUMN source_time INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN linked_message_id INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_link ON media(linked_message_id)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE media ADD COLUMN retention_state INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_retention ON media(retention_state, expires_at)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS bank_seen(source_uri TEXT PRIMARY KEY, first_seen INTEGER NOT NULL, resolved_state INTEGER NOT NULL DEFAULT 0)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 4) {
            try { db.execSQL("ALTER TABLE messages ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE messages ADD COLUMN deletion_state INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE messages ADD COLUMN message_index INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("UPDATE messages SET deletion_state=CASE WHEN is_deleted=1 THEN 2 ELSE 0 END"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN origin TEXT NOT NULL DEFAULT ''"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_hash ON media(content_hash)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 5) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS event_log(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, code TEXT NOT NULL, detail BLOB, message_id INTEGER NOT NULL DEFAULT 0, media_id INTEGER NOT NULL DEFAULT 0)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_time ON event_log(timestamp DESC)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 6) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS pending_manual_media(message_id INTEGER PRIMARY KEY, media_type TEXT NOT NULL, armed_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_manual_expiry ON pending_manual_media(expires_at)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_manual_type ON pending_manual_media(media_type, expires_at)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 7) {
            try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_media_hash_type_unique ON media(content_hash,media_type) WHERE content_hash<>''"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 8) {
            try { db.execSQL("ALTER TABLE media ADD COLUMN canonical_key TEXT NOT NULL DEFAULT ''"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN width INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN height INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN gallery_uri TEXT NOT NULL DEFAULT ''"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_canonical ON media(canonical_key)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 9) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS capture_attempts(id INTEGER PRIMARY KEY AUTOINCREMENT, family_key TEXT NOT NULL DEFAULT '', media_type TEXT NOT NULL DEFAULT '', byte_size INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', local_path TEXT NOT NULL DEFAULT '', source_time INTEGER NOT NULL DEFAULT 0, message_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, resolved_media_id INTEGER NOT NULL DEFAULT 0)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_capture_attempt_time ON capture_attempts(created_at DESC)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_capture_attempt_family ON capture_attempts(family_key, created_at DESC)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 10) {
            try { db.execSQL("ALTER TABLE media ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS recovery_jobs(id INTEGER PRIMARY KEY AUTOINCREMENT, job_key TEXT UNIQUE NOT NULL, media_type TEXT NOT NULL DEFAULT '', state TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', byte_size INTEGER NOT NULL DEFAULT 0, source_time INTEGER NOT NULL DEFAULT 0, message_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, resolved_media_id INTEGER NOT NULL DEFAULT 0, local_path TEXT NOT NULL DEFAULT '', origin TEXT NOT NULL DEFAULT '')"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_recovery_state ON recovery_jobs(state, updated_at DESC)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_recovery_time ON recovery_jobs(updated_at DESC)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 11) {
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_deleted_time ON messages(is_deleted, timestamp DESC)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_type_time ON media(media_type, retention_state, captured_at DESC)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_favorite_time ON media(is_favorite, captured_at DESC)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_link_time ON media(linked_message_id, captured_at DESC)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 12) {
            try { db.execSQL("ALTER TABLE media ADD COLUMN trash_state INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE media ADD COLUMN trashed_at INTEGER NOT NULL DEFAULT 0"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_trash_time ON media(trash_state, trashed_at DESC)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 13) {
            try { db.execSQL("ALTER TABLE media ADD COLUMN capture_batch_key TEXT NOT NULL DEFAULT ''"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_batch ON media(capture_batch_key, media_type, retention_state)"); } catch (Throwable ignored) {}
            try { db.execSQL("ALTER TABLE pending_manual_media ADD COLUMN batch_key TEXT NOT NULL DEFAULT ''"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_manual_batch ON pending_manual_media(batch_key, media_type)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS media_tombstones(source_uri TEXT PRIMARY KEY NOT NULL, content_hash TEXT NOT NULL DEFAULT '', media_type TEXT NOT NULL DEFAULT '', deleted_at INTEGER NOT NULL)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_tombstone_hash ON media_tombstones(content_hash, media_type)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 14) {
            try { db.execSQL("ALTER TABLE messages ADD COLUMN identity_slot INTEGER NOT NULL DEFAULT 1"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_identity_lookup ON messages(package_name, timestamp, is_group, identity_slot, id)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS deletion_evidence(evidence_key TEXT PRIMARY KEY NOT NULL, consumed_at INTEGER NOT NULL, message_id INTEGER NOT NULL DEFAULT 0, result_state INTEGER NOT NULL DEFAULT 0)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_deletion_evidence_time ON deletion_evidence(consumed_at DESC)"); } catch (Throwable ignored) {}
        }
        if (oldVersion < 15) {
            try { db.execSQL("CREATE TABLE IF NOT EXISTS media_message_links(media_id INTEGER NOT NULL, message_id INTEGER NOT NULL, linked_at INTEGER NOT NULL, link_source TEXT NOT NULL DEFAULT '', PRIMARY KEY(media_id,message_id))"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_message_links_message ON media_message_links(message_id,media_id)"); } catch (Throwable ignored) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_message_links_media ON media_message_links(media_id,message_id)"); } catch (Throwable ignored) {}
            try { db.execSQL("INSERT OR IGNORE INTO media_message_links(media_id,message_id,linked_at,link_source) SELECT id,linked_message_id,CASE WHEN captured_at>0 THEN captured_at ELSE strftime('%s','now')*1000 END,'v14-migration' FROM media WHERE linked_message_id>0"); } catch (Throwable ignored) {}
        }
    }

    private static String sha256(String s) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte x : b) out.append(String.format("%02x", x));
            return out.toString();
        } catch (Exception e) { return Integer.toHexString(s == null ? 0 : s.hashCode()); }
    }

    /** Opaque per-message capture identity. Message ids are unique inside the vault, so
     * different conversations cannot collide even when media arrives in the same millisecond. */
    public static String buildCaptureBatchKey(long messageId, String type, long armedAt) {
        if (messageId <= 0) return "";
        long bucket = Math.max(0L, armedAt) / 250L;
        return sha256("v14|" + messageId + "|" + safeText(type) + "|" + bucket);
    }

    private String pendingBatchKey(long messageId,String type){
        if(messageId<=0)return "";
        Cursor c=getReadableDatabase().query("pending_manual_media",new String[]{"batch_key"},"message_id=? AND media_type=?",new String[]{String.valueOf(messageId),type==null?"":type},null,null,"armed_at DESC","1");
        try{return c.moveToFirst()?(c.getString(0)==null?"":c.getString(0)):"";}finally{c.close();}
    }

    private boolean sourceTombstoned(String source){
        if(source==null||source.isEmpty())return false;
        Cursor c=getReadableDatabase().query("media_tombstones",new String[]{"source_uri"},sourceWhere(source),sourceArgs(source),null,null,null,"1");
        try{return c.moveToFirst();}finally{c.close();}
    }
    private boolean hashTombstoned(String hash,String type){
        if(hash==null||hash.isEmpty())return false;
        long cutoff=System.currentTimeMillis()-HASH_TOMBSTONE_RETRY_WINDOW_MS;
        Cursor c=getReadableDatabase().query("media_tombstones",new String[]{"source_uri"},"content_hash=? AND media_type=? AND deleted_at>=?",new String[]{hash,type==null?"":type,String.valueOf(cutoff)},null,null,null,"1");
        try{return c.moveToFirst();}finally{c.close();}
    }
    private void rememberPermanentDeletion(Media m){
        if(m==null)return;ContentValues v=new ContentValues();v.put("source_uri",sourceKey(m.sourceUri==null?("deleted:"+m.id):m.sourceUri));v.put("content_hash",m.contentHash==null?"":m.contentHash);v.put("media_type",m.type==null?"":m.type);v.put("deleted_at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("media_tombstones",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public static String fileSha256(File f) {
        if (f == null || !f.exists() || !f.isFile()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[131072];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf,0,n);
            StringBuilder out = new StringBuilder();
            for (byte x : md.digest()) out.append(String.format("%02x",x));
            return out.toString();
        } catch (Throwable t) { return ""; }
    }

    public long insertMessage(String pkg, String conv, String sender, String body, long ts, String key) {
        return insertMessage(pkg, conv, sender, body, ts, key, false, 0, 1);
    }

    public long insertMessage(String pkg, String conv, String sender, String body, long ts, String key, boolean isGroup, int messageIndex) {
        return insertMessage(pkg, conv, sender, body, ts, key, isGroup, messageIndex, 1);
    }

    /**
     * Persist one logical notification message. identitySlot disambiguates two genuinely distinct
     * MessagingStyle entries that are otherwise byte-for-byte identical (same chat/sender/body/ms).
     * It is derived from deterministic occurrence order inside the parsed snapshot, never from the
     * volatile Android notification key.
     */
    public long insertMessage(String pkg, String conv, String sender, String body, long ts, String key,
                              boolean isGroup, int messageIndex, int identitySlot) {
        if (body == null || body.trim().isEmpty()) return -1;
        final int slot = Math.max(1, identitySlot);
        synchronized (MESSAGE_INSERT_LOCK) {
            long exact = findExactMessageId(pkg, conv, sender, body, ts, isGroup, slot);
            if (exact > 0) return exact;
            byte[] encConv = crypto.encrypt(conv);
            byte[] encSender = crypto.encrypt(sender);
            byte[] encBody = crypto.encrypt(body);
            if (encConv == null || encSender == null || encBody == null) return -1;
            String stableKey = key == null ? "" : key;
            String fp;
            try{fp=messageFingerprint(pkg,conv,sender,body,ts,isGroup,slot);}catch(Throwable t){return -1L;}
            if(fp==null||fp.isEmpty())return -1L;
            ContentValues v = new ContentValues();
            v.put("package_name", pkg);
            v.put("conversation", encConv);
            v.put("sender", encSender);
            v.put("body", encBody);
            v.put("timestamp", ts);
            v.put("notification_key", stableKey);
            v.put("fingerprint", fp);
            v.put("is_group", isGroup ? 1 : 0);
            v.put("message_index", messageIndex);
            v.put("identity_slot", slot);
            long id = getWritableDatabase().insertWithOnConflict("messages", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (id != -1) { notifyUi("message"); return id; }
            // CONFLICT_IGNORE can mean a concurrent/replayed insert won the race. Resolve the
            // winner by durable fingerprint rather than treating an idempotent replay as failure.
            Cursor c = getReadableDatabase().query("messages", new String[]{"id"}, "fingerprint=?", new String[]{fp}, null,null,null,"1");
            try { return c.moveToFirst() ? c.getLong(0) : -1; } finally { c.close(); }
        }
    }

    private static String safeText(String x){return x==null?"":x.trim();}

    /** Exact logical-message lookup used as a compatibility bridge for rows written before v0.5.30 Block 3. */
    public long findExactMessageId(String pkg,String conv,String sender,String body,long ts,boolean isGroup){
        return findExactMessageId(pkg,conv,sender,body,ts,isGroup,1);
    }

    public long findExactMessageId(String pkg,String conv,String sender,String body,long ts,boolean isGroup,int identitySlot){
        if(ts<=0||body==null)return -1L;
        int slot=Math.max(1,identitySlot);
        String sel="timestamp=? AND is_group=? AND identity_slot=?" + (pkg==null?"":" AND package_name=?");
        String[] args=pkg==null
                ?new String[]{String.valueOf(ts),isGroup?"1":"0",String.valueOf(slot)}
                :new String[]{String.valueOf(ts),isGroup?"1":"0",String.valueOf(slot),pkg};
        Cursor c=getReadableDatabase().query("messages",new String[]{"id","conversation","sender","body","is_deleted"},sel,args,null,null,"id ASC",null);
        try{while(c.moveToNext()){
            String cc=crypto.decrypt(c.getBlob(1)),ss=crypto.decrypt(c.getBlob(2)),bb=crypto.decrypt(c.getBlob(3));
            if(c.getInt(4)==0 && sameText(cc,conv) && sameText(ss,sender) && sameText(bb,body))return c.getLong(0);
        }}finally{c.close();}
        return -1L;
    }
    private static boolean sameText(String a,String b){return safeText(a).equals(safeText(b));}

    /**
     * Deprecated fail-closed compatibility API. Conversation recency is not a deletion signal.
     * New code must identify one concrete message from validated REAL_POST evidence instead.
     */
    @Deprecated public long markLatestDeleted(String conv) { return -1L; }
    @Deprecated public long markLatestDeleted(String conv, int state) {
        try { logEvent("DELETION_REJECTED", "WHY_DETECTED=MARK_LATEST_DISABLED · SOURCE_EVENT=UNKNOWN", 0L, 0L); } catch (Throwable ignored) {}
        return -1L;
    }

    public boolean markDeletedById(long id) { return markDeletedById(id, DELETE_CONFIRMED); }
    public boolean markDeletedById(long id, int state) {
        if (id <= 0) return false;
        final int wanted = Math.max(DELETE_PROBABLE, Math.min(DELETE_CONFIRMED, state));
        ContentValues v = new ContentValues();
        v.put("is_deleted", 1);
        v.put("deletion_state", wanted);
        // Idempotent/atomic state transition: two listener/worker paths cannot both "confirm" the
        // same row and retrigger media promotion. PROBABLE -> CONFIRMED remains allowed.
        boolean changed=getWritableDatabase().update("messages", v,
                "id=? AND (is_deleted=0 OR deletion_state<?)",
                new String[]{String.valueOf(id),String.valueOf(wanted)}) > 0;
        if(changed)notifyUi("message");
        return changed;
    }

    /**
     * Deprecated fail-closed compatibility API. An explicit WhatsApp deletion marker that cannot
     * be correlated to a captured row is UNKNOWN, not a synthetic confirmed deletion.
     */
    @Deprecated
    public long ensureDeletionPlaceholder(String pkg, String conv, String sender, long ts, String key, int state, int ordinal, boolean isGroup) {
        try { logEvent("DELETION_UNKNOWN", "WHY_DETECTED=UNMAPPED_MARKER_PLACEHOLDER_DISABLED · SOURCE_EVENT=REAL_POST · CONFIDENCE=UNKNOWN", 0L, 0L); } catch (Throwable ignored) {}
        return -1L;
    }

    public static final int EVIDENCE_FAILED = -1;
    public static final int EVIDENCE_DUPLICATE = 0;
    public static final int EVIDENCE_RECORDED = 1;
    public static final int EVIDENCE_CONFIRMED = 2;

    public boolean hasDeletionEvidence(String evidenceKey){
        if(evidenceKey==null||evidenceKey.isEmpty())return false;
        Cursor c=getReadableDatabase().query("deletion_evidence",new String[]{"evidence_key"},"evidence_key=?",new String[]{evidenceKey},null,null,null,"1");
        try{return c.moveToFirst();}finally{c.close();}
    }

    /** Persist an UNKNOWN/REJECTED marker idempotently so a process restart cannot replay it forever. */
    public int recordDeletionEvidence(String evidenceKey,long messageId,int resultState){
        if(evidenceKey==null||evidenceKey.isEmpty())return EVIDENCE_FAILED;
        ContentValues v=new ContentValues();v.put("evidence_key",evidenceKey);v.put("consumed_at",System.currentTimeMillis());v.put("message_id",Math.max(0L,messageId));v.put("result_state",Math.max(0,resultState));
        try{
            long inserted=getWritableDatabase().insertWithOnConflict("deletion_evidence",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            if(inserted!=-1L)return EVIDENCE_RECORDED;
            return hasDeletionEvidence(evidenceKey)?EVIDENCE_DUPLICATE:EVIDENCE_FAILED;
        }catch(Throwable t){return EVIDENCE_FAILED;}
    }

    /**
     * Crash-safe deletion commit. The evidence ledger and the message state transition live in the
     * same SQLite transaction: either both are durable or neither is. This closes the process-death
     * window that existed when evidence lived in SharedPreferences.
     */
    public int confirmDeletedWithEvidence(long id,String evidenceKey){
        if(id<=0||evidenceKey==null||evidenceKey.isEmpty())return EVIDENCE_FAILED;
        SQLiteDatabase db=getWritableDatabase();int result=EVIDENCE_FAILED;boolean changed=false;db.beginTransaction();
        try{
            ContentValues ev=new ContentValues();ev.put("evidence_key",evidenceKey);ev.put("consumed_at",System.currentTimeMillis());ev.put("message_id",id);ev.put("result_state",DELETE_CONFIRMED);
            long inserted=db.insertWithOnConflict("deletion_evidence",null,ev,SQLiteDatabase.CONFLICT_IGNORE);
            if(inserted==-1L){
                Cursor d=db.query("deletion_evidence",new String[]{"evidence_key"},"evidence_key=?",new String[]{evidenceKey},null,null,null,"1");
                boolean duplicate;try{duplicate=d.moveToFirst();}finally{d.close();}
                result=duplicate?EVIDENCE_DUPLICATE:EVIDENCE_FAILED;
            }else{
                ContentValues v=new ContentValues();v.put("is_deleted",1);v.put("deletion_state",DELETE_CONFIRMED);
                int n=db.update("messages",v,"id=? AND (is_deleted=0 OR deletion_state<?)",new String[]{String.valueOf(id),String.valueOf(DELETE_CONFIRMED)});
                changed=n>0;
                if(!changed){
                    Cursor c=db.query("messages",new String[]{"is_deleted","deletion_state"},"id=?",new String[]{String.valueOf(id)},null,null,null,"1");
                    boolean already=false;try{already=c.moveToFirst()&&c.getInt(0)==1&&c.getInt(1)==DELETE_CONFIRMED;}finally{c.close();}
                    if(!already)throw new IllegalStateException("deletion target missing or invalid");
                }
                db.setTransactionSuccessful();result=EVIDENCE_CONFIRMED;
            }
        }catch(Throwable t){result=EVIDENCE_FAILED;}
        finally{db.endTransaction();}
        if(changed)notifyUi("message");
        return result;
    }

    public boolean unmarkProbableDeletedById(long id) {
        if (id <= 0) return false;
        ContentValues v = new ContentValues(); v.put("is_deleted",0); v.put("deletion_state",DELETE_NONE);
        boolean changed=getWritableDatabase().update("messages",v,"id=? AND deletion_state=?",new String[]{String.valueOf(id),String.valueOf(DELETE_PROBABLE)})>0;
        if(changed)notifyUi("message");
        return changed;
    }

    public List<Msg> listRecentUndeletedForConversation(String conversation, int limit, long notBefore) {
        List<Msg> out=new ArrayList<>();
        if(conversation==null)return out;
        int cap=Math.max(1,Math.min(200,limit));
        for(Msg m:listMessages(500)){
            if(m==null||m.deleted||m.timestamp<notBefore||m.conversation==null||!m.conversation.equalsIgnoreCase(conversation))continue;
            out.add(m); if(out.size()>=cap)break;
        }
        return out;
    }

    /** Recent compact-burst candidates. Probable rows are included because an explicit WhatsApp
     * marker may arrive milliseconds after the optimistic delete path marked them probable.
     * Confirmed rows from older delete batches remain excluded. */
    public List<Msg> listRecentBurstCandidatesForConversation(String conversation, int limit, long notBefore) {
        List<Msg> out=new ArrayList<>();
        if(conversation==null)return out;
        int cap=Math.max(1,Math.min(256,limit));
        for(Msg m:listMessages(800)){
            if(m==null||m.timestamp<notBefore||m.conversation==null||!m.conversation.equalsIgnoreCase(conversation))continue;
            if(m.deleted && m.deletionState==DELETE_CONFIRMED)continue;
            out.add(m); if(out.size()>=cap)break;
        }
        return out;
    }

    public boolean unmarkDeletedById(long id) {
        if (id <= 0) return false;
        ContentValues v = new ContentValues();
        v.put("is_deleted", 0); v.put("deletion_state", DELETE_NONE);
        boolean changed=getWritableDatabase().update("messages", v, "id=?", new String[]{String.valueOf(id)}) > 0;
        if(changed)notifyUi("message");
        return changed;
    }

    public boolean isMessageDeleted(long id) {
        if (id <= 0) return false;
        Cursor c = getReadableDatabase().query("messages", new String[]{"is_deleted"}, "id=?", new String[]{String.valueOf(id)}, null,null,null,"1");
        try { return c.moveToFirst() && c.getInt(0) == 1; } finally { c.close(); }
    }

    /** True only after the deletion passed the confirmed-only verifier. */
    public boolean isMessageConfirmedDeleted(long id) {
        if (id <= 0) return false;
        Cursor c = getReadableDatabase().query("messages", new String[]{"is_deleted","deletion_state"}, "id=?", new String[]{String.valueOf(id)}, null,null,null,"1");
        try { return c.moveToFirst() && c.getInt(0) == 1 && c.getInt(1) == DELETE_CONFIRMED; } finally { c.close(); }
    }

    public long messageTimestamp(long id) {
        if (id <= 0) return 0L;
        Cursor c = getReadableDatabase().query("messages", new String[]{"timestamp"}, "id=?", new String[]{String.valueOf(id)}, null,null,null,"1");
        try { return c.moveToFirst() ? c.getLong(0) : 0L; } finally { c.close(); }
    }

    public List<Msg> listMessages(int limit) { return queryMessages(null, null, limit); }
    /** Ascending-id page used by maintenance so no old rows are skipped by UI-style limits. */
    private List<Msg> listMessagesAfterId(long afterId,int limit) {
        List<Msg> out=new ArrayList<>();
        Cursor c=getReadableDatabase().query("messages",new String[]{"id","conversation","sender","body","timestamp","is_deleted","is_group","deletion_state","notification_key","message_index","identity_slot"},"id>?",new String[]{String.valueOf(Math.max(0L,afterId))},null,null,"id ASC",String.valueOf(Math.max(1,limit)));
        try{while(c.moveToNext()){Msg m=new Msg();m.id=c.getLong(0);m.conversation=crypto.decrypt(c.getBlob(1));m.sender=crypto.decrypt(c.getBlob(2));m.body=crypto.decrypt(c.getBlob(3));m.timestamp=c.getLong(4);m.deleted=c.getInt(5)==1;m.isGroup=c.getInt(6)==1;m.deletionState=c.getInt(7);m.notificationKey=c.getString(8);m.messageIndex=c.getInt(9);m.identitySlot=c.getInt(10);out.add(m);}}finally{c.close();}
        return out;
    }
    public List<Msg> listDeletedMessages(int limit) { return queryMessages("is_deleted=1", null, limit); }
    /** Visible message history: only deletions confirmed by a reliable WhatsApp/Android signal.
     * Probable candidates remain internal so they can later be confirmed or reverted, but they
     * must never leak into the user-facing deleted-messages feed. */
    public List<Msg> listConfirmedDeletedMessages(int limit) {
        return queryMessages("is_deleted=1 AND deletion_state=?", new String[]{String.valueOf(DELETE_CONFIRMED)}, limit);
    }
    public Msg getMessage(long id) { List<Msg> x=queryMessages("id=?",new String[]{String.valueOf(id)},1); return x.isEmpty()?null:x.get(0); }

    private List<Msg> queryMessages(String selection, String[] args, int limit) {
        List<Msg> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("messages", new String[]{"id","conversation","sender","body","timestamp","is_deleted","is_group","deletion_state","notification_key","message_index","identity_slot"}, selection,args,null,null,"timestamp DESC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id=c.getLong(0); m.conversation=crypto.decrypt(c.getBlob(1)); m.sender=crypto.decrypt(c.getBlob(2)); m.body=crypto.decrypt(c.getBlob(3));
                m.timestamp=c.getLong(4); m.deleted=c.getInt(5)==1; m.isGroup=c.getInt(6)==1; m.deletionState=c.getInt(7); m.notificationKey=c.getString(8); m.messageIndex=c.getInt(9); m.identitySlot=c.getInt(10);
                out.add(m);
            }
        } finally { c.close(); }
        return out;
    }

    public String findMediaPath(String uri) {
        Cursor c = getReadableDatabase().query("media", new String[]{"local_path"}, sourceWhere(uri), sourceArgs(uri), null,null,null,"1");
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }
    public long findMediaId(String uri) {
        Cursor c = getReadableDatabase().query("media", new String[]{"id"}, sourceWhere(uri), sourceArgs(uri), null,null,null,"1");
        try { return c.moveToFirst() ? c.getLong(0) : -1; } finally { c.close(); }
    }
    public long findMediaIdByHash(String hash, String type) {
        if (hash == null || hash.isEmpty()) return -1;
        Cursor c = getReadableDatabase().query("media", new String[]{"id"}, "content_hash=? AND media_type=?", new String[]{hash,type==null?"":type}, null,null,"captured_at DESC","1");
        try { return c.moveToFirst() ? c.getLong(0) : -1; } finally { c.close(); }
    }

    public boolean bankSourceKnown(String uri) {
        Cursor c = getReadableDatabase().query("bank_seen", new String[]{"source_uri"}, sourceWhere(uri), sourceArgs(uri), null,null,null,"1");
        try { return c.moveToFirst(); } finally { c.close(); }
    }
    public void markBankSourceSeen(String uri, int resolvedState) {
        if (uri == null || uri.isEmpty()) return;String key=sourceKey(uri);
        ContentValues v = new ContentValues(); v.put("source_uri", key); v.put("first_seen", System.currentTimeMillis()); v.put("resolved_state", resolvedState);
        getWritableDatabase().insertWithOnConflict("bank_seen", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues u = new ContentValues(); u.put("resolved_state", resolvedState); getWritableDatabase().update("bank_seen", u, "source_uri=?", new String[]{key});
        if(!key.equals(uri))getWritableDatabase().delete("bank_seen","source_uri=?",new String[]{uri});
    }

    public void deleteMediaBySource(String uri) { SQLiteDatabase w=getWritableDatabase();Cursor c=w.query("media",new String[]{"id"},sourceWhere(uri),sourceArgs(uri),null,null,null);ArrayList<Long> ids=new ArrayList<>();try{while(c.moveToNext())ids.add(c.getLong(0));}finally{c.close();}for(Long id:ids)if(id!=null)deleteMediaLinks(w,id);if(w.delete("media", sourceWhere(uri), sourceArgs(uri))>0)notifyUi("media"); }
    public void deleteMedia(long id) { SQLiteDatabase w=getWritableDatabase();deleteMediaLinks(w,id);if(w.delete("media", "id=?", new String[]{String.valueOf(id)})>0)notifyUi("media"); }

    public void insertMedia(String uri, String localPath, String name, String mime, String type, long size, long sourceTime, long linkedMessageId) {
        insertMediaInternal(uri, localPath, name, mime, type, size, sourceTime, linkedMessageId, RETENTION_NORMAL, 0L, "scan");
    }
    public long insertPendingAudio(String uri, String localPath, String name, String mime, long size, long sourceTime, long linkedMessageId, long expiresAt) {
        return insertMediaInternal(uri, localPath, name, mime, "audio", size, sourceTime, linkedMessageId, RETENTION_PENDING, expiresAt, "audio-capture");
    }
    public long insertMediaWithOrigin(String uri, String localPath, String name, String mime, String type, long size, long sourceTime, long linkedMessageId, int retentionState, long expiresAt, String origin) {
        return insertMediaInternal(uri, localPath, name, mime, type, size, sourceTime, linkedMessageId, retentionState, expiresAt, origin);
    }

    /** Commit a file that has already been hashed/validated in plaintext and encrypted at rest.
     * This closes the process-death window where plaintext could otherwise live in vault_media. */
    public long insertPreparedEncryptedMedia(String uri,String localPath,String name,String mime,String type,long size,long sourceTime,long linkedMessageId,int retentionState,long expiresAt,String origin,String rawSha256,long width,long height,long durationMs){
        long[] meta=new long[]{Math.max(0L,width),Math.max(0L,height),Math.max(0L,durationMs)};
        synchronized(MEDIA_INSERT_LOCK){return insertMediaInternalLocked(uri,localPath,name,mime,type,size,sourceTime,linkedMessageId,retentionState,expiresAt,origin,rawSha256,meta,true);}
    }

    /** Visible zero-byte video row used while WhatsApp has exposed the message but not the file.
     * A real video capture for the same message automatically replaces this placeholder. */
    public long ensureVideoPlaceholder(long messageId,long sourceTime,String label,String origin){
        if(messageId<=0)return -1L;
        Media linked=findLinkedMedia(messageId,"video");
        if(linked!=null&&linked.id>0)return linked.id;
        String source="video-placeholder:"+messageId;
        long existing=findMediaId(source);
        if(existing>0)return existing;
        return insertMediaInternal(source,null,label==null?"Video detectado":label,"video/*","video",0L,sourceTime,messageId,RETENTION_NORMAL,0L,origin==null?"VIDEO_PENDING_PLACEHOLDER":origin);
    }

    public void markVideoPlaceholderLimited(long messageId,long observedBytes,long limitBytes){
        if(messageId<=0)return;
        long mb=Math.max(1L,limitBytes/(1024L*1024L));
        byte[] enc=crypto.encrypt("Video no guardado · supera el límite actual de "+mb+" MB");
        if(enc==null)return;
        ContentValues v=new ContentValues();v.put("display_name",enc);v.put("origin","VIDEO_LIMIT_PLACEHOLDER");
        getWritableDatabase().update("media",v,"linked_message_id=? AND media_type='video' AND origin LIKE 'VIDEO_%PLACEHOLDER'",new String[]{String.valueOf(messageId)});
        try{logEvent("VIDEO_LIMIT_EXCEEDED","Video omitido por límite "+mb+" MB · observado="+Math.max(0L,observedBytes)+" bytes",messageId,0L);}catch(Throwable ignored){}
        notifyUi("media");
    }

    private long insertMediaInternal(String uri, String localPath, String name, String mime, String type, long size, long sourceTime, long linkedMessageId, int retentionState, long expiresAt, String origin) {
        synchronized (MEDIA_INSERT_LOCK) {
            return insertMediaInternalLocked(uri,localPath,name,mime,type,size,sourceTime,linkedMessageId,retentionState,expiresAt,origin,null,null,false);
        }
    }

    private long insertMediaInternalLocked(String uri, String localPath, String name, String mime, String type, long size, long sourceTime, long linkedMessageId, int retentionState, long expiresAt, String origin, String preparedRawHash, long[] preparedMeta, boolean alreadyEncrypted) {
        File f = localPath == null ? null : new File(localPath);
        // v0.5.12: capture first, expose later. Automatic WhatsApp media is quarantined
        // internally until the linked message is CONFIRMED deleted. This prevents normal
        // photos/audio/video from leaking into Archivos merely because a provisional delete
        // candidate was observed. Historical explicit imports remain normal.
        String originLow = origin == null ? "" : origin.toLowerCase(Locale.ROOT);
        boolean automaticCapture = !originLow.equals("manual_import");
        String captureBatchKey = linkedMessageId>0 ? pendingBatchKey(linkedMessageId,type) : "";
        if (retentionState == RETENTION_NORMAL && automaticCapture) {
            retentionState = RETENTION_PENDING;
            if (expiresAt <= 0L) expiresAt = System.currentTimeMillis() + PROVISIONAL_MEDIA_TTL_MS;
        }

        // Cheapest dedupe first: if this exact source was already archived, avoid hashing and
        // re-encrypting the staged copy. This matters for repeated FileObserver/ContentObserver events.
        if (uri != null && !uri.isEmpty()) {
            if(automaticCapture && sourceTombstoned(uri)){
                try{logEvent("USER_DELETE_BLOCKED","source · "+(type==null?"":type),linkedMessageId,0L);}catch(Throwable ignored){}
                if(f!=null)try{f.delete();}catch(Throwable ignored){}return -2L;
            }
            long bySource = findMediaId(uri);
            if (bySource > 0) {
                Media existingMedia = getMedia(bySource);
                if(existingMedia!=null && existingMedia.trashed && automaticCapture){
                    try { logEvent("TRASH_RESTORE_BLOCKED","source · "+(type==null?"":type),linkedMessageId,bySource); } catch(Throwable ignored) {}
                    if(f!=null)try{f.delete();}catch(Throwable ignored){}return bySource;
                }
                if(existingMedia!=null && existingMedia.trashed) restoreMedia(bySource);
                if (linkedMessageId > 0) linkMediaToMessageIfFree(bySource, linkedMessageId);
                existingMedia = getMedia(bySource);
                try { logEvent("DUPLICATE_AVOIDED","source · "+(type==null?"":type),linkedMessageId,bySource); } catch(Throwable ignored) {}
                if (f != null) try { f.delete(); } catch (Throwable ignored) {}
                return bySource;
            }
        }

        Media replaceCandidate = null;
        // Multiple capture routes can see the same attachment. For one message+media type,
        // reject a smaller/equal copy before doing a full SHA-256 pass. If the new copy is
        // bigger, keep the old one until the new encrypted record is safely inserted.
        if (linkedMessageId > 0 && type != null && !type.isEmpty()) {
            Media linked = findLinkedMedia(linkedMessageId,type);
            if (linked != null && linked.id > 0) {
                File oldFile = linked.path == null ? null : new File(linked.path);
                boolean oldUsable = oldFile != null && oldFile.exists() && oldFile.length() > 0;
                if (oldUsable && linked.size >= Math.max(1L,size)) {
                    if (f != null) try { f.delete(); } catch (Throwable ignored) {}
                    return linked.id;
                }
                if (size > linked.size) replaceCandidate = linked;
            }
        }

        String rawHash = preparedRawHash==null?fileSha256(f):preparedRawHash;
        String hash = MetadataPrivacy.contentHash(appContext,rawHash);
        if (!hash.isEmpty()) {
            // During the one-time v0.5.26 migration, old rows may still contain the raw SHA.
            // Compare against both forms without ever storing a new raw hash.
            if(automaticCapture && (hashTombstoned(hash,type)||(!rawHash.isEmpty()&&hashTombstoned(rawHash,type)))){
                try{logEvent("USER_DELETE_BLOCKED","sha256 · "+(type==null?"":type),linkedMessageId,0L);}catch(Throwable ignored){}
                if(f!=null)try{f.delete();}catch(Throwable ignored){}return -2L;
            }
            long dupId = findMediaIdByHash(hash, type);
            if(dupId<=0&&!rawHash.isEmpty())dupId=findMediaIdByHash(rawHash,type);
            if (dupId > 0) {
                Media existingMedia = getMedia(dupId);
                if(existingMedia!=null && existingMedia.trashed && automaticCapture){
                    try { logEvent("TRASH_RESTORE_BLOCKED","sha256 · "+(type==null?"":type),linkedMessageId,dupId); } catch(Throwable ignored) {}
                    if(f!=null)try{f.delete();}catch(Throwable ignored){}return dupId;
                }
                if(existingMedia!=null && existingMedia.trashed) restoreMedia(dupId);
                if (linkedMessageId > 0) linkMediaToMessageIfFree(dupId, linkedMessageId);
                existingMedia = getMedia(dupId);
                try { logEvent("DUPLICATE_AVOIDED","sha256 · "+(type==null?"":type),linkedMessageId,dupId); } catch(Throwable ignored) {}
                if (f != null) { try { f.delete(); } catch (Throwable ignored) {} }
                return dupId;
            }
        }
        long[] meta=preparedMeta==null?probeMediaMetadata(f,type):preparedMeta;
        int width=(int)Math.min(Integer.MAX_VALUE,Math.max(0L,meta[0]));
        int height=(int)Math.min(Integer.MAX_VALUE,Math.max(0L,meta[1]));
        long durationMs=Math.max(0L,meta[2]);
        String canonical=(hash==null?"":hash)+"|"+(type==null?"":type)+"|"+Math.max(0L,size)+"|"+(mime==null?"":mime)+"|"+width+"x"+height+"|"+durationMs;
        // File contents are encrypted only after the plaintext hash/dedup decision, so
        // content_hash is a Keystore-backed HMAC token; the plaintext SHA-256 never reaches SQLite.
        if (f != null && f.exists() && MediaCrypto.shouldEncrypt(appContext,type,origin)) {
            if(alreadyEncrypted){if(!MediaCrypto.isEncrypted(f)){try{f.delete();}catch(Throwable ignored){}return -1L;}}
            else if(!MediaCrypto.encryptInPlace(f)){try { f.delete(); } catch (Throwable ignored) {} return -1L;}
        }
        // v0.5.22: permanent private archive names are opaque; the human name remains only
        // in display_name, which is AES-GCM encrypted by CryptoManager.
        if(f!=null&&f.exists()){
            File opaque=VaultFileNames.ensureNewArchiveOpaque(appContext,f,name,mime,type);
            if(opaque==null){try{f.delete();}catch(Throwable ignored){}return -1L;}
            f=opaque;localPath=f.getAbsolutePath();
        }
        byte[] encName = crypto.encrypt(name == null ? "" : name);
        if (encName == null) { if(f!=null)try{f.delete();}catch(Throwable ignored){} return -1L; }
        ContentValues v = new ContentValues();
        v.put("source_uri", sourceKey(uri == null ? ("local:"+System.nanoTime()) : uri)); v.put("local_path", localPath == null ? "" : localPath); v.put("display_name", encName);
        v.put("mime_type", mime); v.put("media_type", type); v.put("byte_size", size); v.put("captured_at", System.currentTimeMillis()); v.put("source_time", sourceTime);
        v.put("linked_message_id", linkedMessageId); v.put("retention_state", retentionState); v.put("expires_at", expiresAt); v.put("content_hash", hash); v.put("origin", origin==null?"":origin);
        v.put("canonical_key",canonical);v.put("width",width);v.put("height",height);v.put("duration_ms",durationMs);v.put("gallery_uri","");v.put("is_favorite",0);v.put("capture_batch_key",captureBatchKey);
        SQLiteDatabase mediaDb=getWritableDatabase();long inserted=-1L;
        mediaDb.beginTransaction();
        try{
            inserted=mediaDb.insertWithOnConflict("media", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if(inserted!=-1L&&linkedMessageId>0)addMediaLink(mediaDb,inserted,linkedMessageId,"insert");
            if(inserted!=-1L&&replaceCandidate!=null&&replaceCandidate.id!=inserted)transferMediaLinks(mediaDb,replaceCandidate.id,inserted);
            if(inserted!=-1L)mediaDb.setTransactionSuccessful();
        }finally{mediaDb.endTransaction();}
        if(inserted==-1L){if(f!=null)try{f.delete();}catch(Throwable ignored){}return -1L;}
        if(replaceCandidate!=null&&replaceCandidate.id!=inserted){
            if(replaceCandidate.galleryUri!=null&&!replaceCandidate.galleryUri.isEmpty())updateGalleryUri(inserted,replaceCandidate.galleryUri);
            retireSupersededMedia(replaceCandidate.id,true);
        }
        notifyUi("media");
        if (retentionState == RETENTION_PENDING) {
            try { MediaArchiver.monitorPendingMedia(appContext, inserted); } catch (Throwable ignored) {}
        }
        return inserted;
    }


    /** Internal migration helper; does not alter user-visible metadata. */
    public boolean updateMediaLocalPath(long mediaId,String localPath){
        if(mediaId<=0||localPath==null||localPath.isEmpty())return false;
        ContentValues v=new ContentValues();v.put("local_path",localPath);
        return getWritableDatabase().update("media",v,"id=?",new String[]{String.valueOf(mediaId)})>0;
    }

    private static long[] probeMediaMetadata(File f,String type){
        long[] out=new long[]{0L,0L,0L};if(f==null||!f.exists())return out;
        if("image".equals(type)){try{BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getAbsolutePath(),o);out[0]=Math.max(0,o.outWidth);out[1]=Math.max(0,o.outHeight);}catch(Throwable ignored){}}
        else if("video".equals(type)||"audio".equals(type)){MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(f.getAbsolutePath());String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(d!=null)out[2]=Long.parseLong(d);if("video".equals(type)){String w=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),h=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);if(w!=null)out[0]=Long.parseLong(w);if(h!=null)out[1]=Long.parseLong(h);}}catch(Throwable ignored){}finally{try{r.release();}catch(Throwable ignored){}}}
        return out;
    }

    private static final String CONFIRMED_MEDIA_SQL = "EXISTS (SELECT 1 FROM media_message_links ml JOIN messages msg ON msg.id=ml.message_id WHERE ml.media_id=media.id AND msg.is_deleted=1 AND msg.deletion_state=2)";
    private static final String HAS_LINK_SQL = "EXISTS (SELECT 1 FROM media_message_links ml WHERE ml.media_id=media.id)";
    private static final String NO_LINK_SQL = "NOT EXISTS (SELECT 1 FROM media_message_links ml WHERE ml.media_id=media.id)";
    /** User-facing recovered media is derived from confirmed deletion state, not merely from a
     * historical retention flag. This makes old/probable/normal captures invisible by design. */
    public List<Media> listMedia(int limit) { return queryMedia("trash_state=0 AND retention_state=? AND "+CONFIRMED_MEDIA_SQL,new String[]{String.valueOf(RETENTION_KEPT_DELETED)},"byte_size DESC, captured_at DESC",limit); }

    /** Database-side filtering/sorting keeps the Media screen off the main-thread hot path. */
    public List<Media> listMediaFiltered(String type, boolean pendingOnly, String sort, int limit) {
        return listMediaFilteredPage(type,pendingOnly,sort,limit,0);
    }

    public List<Media> listMediaFilteredPage(String type, boolean pendingOnly, String sort, int limit, int offset) {
        StringBuilder sel = new StringBuilder();
        ArrayList<String> args = new ArrayList<>();
        sel.append("trash_state=0 AND ");
        if (pendingOnly) sel.append("retention_state=?");
        else sel.append("retention_state<>?");
        args.add(String.valueOf(RETENTION_PENDING));
        if (type != null && !type.isEmpty() && !"all".equals(type)) {
            sel.append(" AND media_type=?");
            args.add(type);
        }
        String order;
        if ("old".equals(sort)) order="captured_at ASC";
        else if ("name".equals(sort)) order="captured_at DESC";
        else if ("size_asc".equals(sort)) order="byte_size ASC, captured_at DESC";
        else if ("size_desc".equals(sort)) order="byte_size DESC, captured_at DESC";
        else order="captured_at DESC";
        List<Media> out=queryMediaPage(sel.toString(),args.toArray(new String[0]),order,Math.max(1,limit),Math.max(0,offset));
        if ("name".equals(sort)) out.sort((a,b)->(a.name==null?"":a.name).compareToIgnoreCase(b.name==null?"":b.name));
        return out;
    }
    public List<Media> listRecoveryCenterPage(String type,boolean pendingOnly,String sort,boolean favoritesOnly,boolean unlinkedOnly,long capturedAfter,int limit,int offset){
        StringBuilder sel=new StringBuilder("trash_state=0 AND ");ArrayList<String> args=new ArrayList<>();
        if(pendingOnly){sel.append("retention_state=?");args.add(String.valueOf(RETENTION_PENDING));}
        else{sel.append("retention_state=? AND ").append(CONFIRMED_MEDIA_SQL);args.add(String.valueOf(RETENTION_KEPT_DELETED));}
        if(type!=null&&!type.isEmpty()&&!"all".equals(type)){sel.append(" AND media_type=?");args.add(type);}
        if(favoritesOnly)sel.append(" AND is_favorite=1");if(unlinkedOnly)sel.append(" AND ").append(NO_LINK_SQL);
        if(capturedAfter>0){sel.append(" AND captured_at>=?");args.add(String.valueOf(capturedAfter));}
        String order;if("old".equals(sort))order="captured_at ASC";else if("size_asc".equals(sort))order="byte_size ASC, captured_at DESC";else if("size_desc".equals(sort))order="byte_size DESC, captured_at DESC";else order="captured_at DESC";
        List<Media> out=queryMediaPage(sel.toString(),args.toArray(new String[0]),order,Math.max(1,limit),Math.max(0,offset));if("name".equals(sort))out.sort((a,b)->(a.name==null?"":a.name).compareToIgnoreCase(b.name==null?"":b.name));return out;
    }
    public List<Media> listPendingAudio(int limit) { return queryMedia("retention_state=? AND media_type='audio'",new String[]{String.valueOf(RETENTION_PENDING)},"expires_at ASC",limit); }
    public List<Media> listPendingMedia(int limit) { return listPendingMediaPage(limit,0); }
    public List<Media> listPendingMediaPage(int limit,int offset){return queryMediaPage("trash_state=0 AND retention_state=?",new String[]{String.valueOf(RETENTION_PENDING)},"expires_at ASC",Math.max(1,limit),Math.max(0,offset));}
    public List<Media> listPendingMediaAfterId(long afterId,int limit){return queryMedia("trash_state=0 AND retention_state=? AND id>?",new String[]{String.valueOf(RETENTION_PENDING),String.valueOf(Math.max(0L,afterId))},"id ASC",Math.max(1,limit));}
    public Media getMedia(long id) { List<Media> m=queryMedia("id=?",new String[]{String.valueOf(id)},null,1); return m.isEmpty()?null:m.get(0); }
    public Media findMediaByPath(String path){if(path==null||path.isEmpty())return null;List<Media> m=queryMedia("local_path=?",new String[]{path},null,1);return m.isEmpty()?null:m.get(0);}
    public Media findLinkedMedia(long messageId,String type){
        if(messageId<=0||type==null||type.isEmpty())return null;
        List<Media> m=queryMedia("trash_state=0 AND media_type=? AND EXISTS (SELECT 1 FROM media_message_links ml WHERE ml.media_id=media.id AND ml.message_id=?)",new String[]{type,String.valueOf(messageId)},"byte_size DESC, captured_at DESC",1);
        return m.isEmpty()?null:m.get(0);
    }

    public List<Media> listMediaForMessage(long messageId, long messageTime, int limit) {
        // Exact persisted links only. One physical blob may legitimately belong to more than
        // one WhatsApp message, so association is many-to-many in media_message_links.
        return queryMedia("trash_state=0 AND retention_state<>? AND EXISTS (SELECT 1 FROM media_message_links ml WHERE ml.media_id=media.id AND ml.message_id=?)",
                new String[]{String.valueOf(RETENTION_PENDING),String.valueOf(messageId)},
                "captured_at DESC",Math.max(1,limit));
    }

    private Media findUnlinkedPendingByBatch(String batchKey,String type){
        if(batchKey==null||batchKey.isEmpty())return null;
        List<Media> ms=queryMedia("trash_state=0 AND retention_state=? AND "+NO_LINK_SQL+" AND capture_batch_key=? AND media_type=?",new String[]{String.valueOf(RETENTION_PENDING),batchKey,type==null?"":type},"captured_at ASC",2);
        return ms.size()==1?ms.get(0):null;
    }
    private boolean hasCompetingPendingArm(long messageId,String type,long when,long windowMs){
        List<PendingManualMedia> ps=listPendingManualMedia(type,20);int n=0;for(PendingManualMedia x:ps){if(x.messageId==messageId)continue;if(Math.abs(x.armedAt-when)<=windowMs)n++;}return n>0;
    }
    private String messageConversationFingerprint(long messageId){Msg m=getMessage(messageId);return m==null?"":MetadataPrivacy.token(appContext,"convfp",(m.isGroup?"g|":"p|")+safeText(m.conversation));}

    /** Attach preserved media without nearest-timestamp guessing. Exact batch identity wins;
     * otherwise the type-level FIFO reconciler may pair only when message/media counts match. */
    public int reconcileRecentUnlinkedMedia(long messageId,long messageTime,String type){
        if(messageId<=0||messageTime<=0||type==null||type.isEmpty())return 0;
        if(findLinkedMedia(messageId,type)!=null)return 0;
        String batch=pendingBatchKey(messageId,type);Media m=findUnlinkedPendingByBatch(batch,type);
        if(m!=null){
            linkMediaToMessageIfFree(m.id,messageId);
            return hasMediaLink(m.id,messageId)?1:0;
        }
        reconcilePendingMediaByOrder(type);
        Media linked=findLinkedMedia(messageId,type);
        return linked!=null?1:0;
    }

    private List<Media> queryMedia(String selection,String[] args,String order,int limit) {
        return queryMediaPage(selection,args,order,limit,0);
    }
    private List<Media> queryMediaPage(String selection,String[] args,String order,int limit,int offset) {
        List<Media> out=new ArrayList<>();
        String lim=String.valueOf(Math.max(1,limit))+(offset>0?(" OFFSET "+offset):"");
        Cursor c=getReadableDatabase().query("media",mediaColumns(),selection,args,null,null,order,lim);
        try { while(c.moveToNext()) out.add(readMedia(c)); } finally { c.close(); }
        return out;
    }
    private String[] mediaColumns(){return new String[]{"id","source_uri","local_path","display_name","mime_type","media_type","byte_size","captured_at","source_time","linked_message_id","retention_state","expires_at","content_hash","origin","canonical_key","width","height","duration_ms","gallery_uri","is_favorite","trash_state","trashed_at","capture_batch_key"};}
    private Media readMedia(Cursor c){Media m=new Media();m.id=c.getLong(0);m.sourceUri=c.getString(1);m.path=c.getString(2);m.name=crypto.decrypt(c.getBlob(3));m.mime=c.getString(4);m.type=c.getString(5);m.size=c.getLong(6);m.capturedAt=c.getLong(7);m.sourceTime=c.getLong(8);m.linkedMessageId=c.getLong(9);m.retentionState=c.getInt(10);m.expiresAt=c.getLong(11);m.contentHash=c.getString(12);m.origin=c.getString(13);m.canonicalKey=c.getString(14);m.width=c.getInt(15);m.height=c.getInt(16);m.durationMs=c.getLong(17);m.galleryUri=MetadataPrivacy.open(appContext,c.getString(18));m.favorite=c.getInt(19)!=0;m.trashed=c.getInt(20)!=0;m.trashedAt=c.getLong(21);m.captureBatchKey=c.getString(22);return m;}

    public List<Media> listUnlinkedMedia(int limit){return listUnlinkedMediaPage(limit,0);}
    public List<Media> listUnlinkedMediaPage(int limit,int offset){return queryMediaPage("trash_state=0 AND "+NO_LINK_SQL+" AND retention_state<>?",new String[]{String.valueOf(RETENTION_PENDING)},"captured_at DESC",Math.max(1,limit),Math.max(0,offset));}
    public List<Media> listMediaAfterId(long afterId,int limit){return queryMedia("id>?",new String[]{String.valueOf(Math.max(0L,afterId))},"id ASC",Math.max(1,limit));}

    public Map<Long,String> listMessageContextsForMedia(List<Media> media){
        LinkedHashMap<Long,String> out=new LinkedHashMap<>();
        if(media==null||media.isEmpty())return out;
        ArrayList<Long> msgIds=new ArrayList<>();
        LinkedHashMap<Long,Long> mediaToMsg=new LinkedHashMap<>();
        for(Media m:media){if(m==null||m.linkedMessageId<=0)continue;mediaToMsg.put(m.id,m.linkedMessageId);if(!msgIds.contains(m.linkedMessageId))msgIds.add(m.linkedMessageId);}
        if(msgIds.isEmpty())return out;
        StringBuilder qs=new StringBuilder();String[] args=new String[msgIds.size()];
        for(int i=0;i<msgIds.size();i++){if(i>0)qs.append(',');qs.append('?');args[i]=String.valueOf(msgIds.get(i));}
        LinkedHashMap<Long,String> byMsg=new LinkedHashMap<>();
        Cursor c=getReadableDatabase().query("messages",new String[]{"id","conversation","sender","is_group"},"id IN ("+qs+")",args,null,null,null);
        try{while(c.moveToNext()){long id=c.getLong(0);String conv=crypto.decrypt(c.getBlob(1));String sender=crypto.decrypt(c.getBlob(2));boolean group=c.getInt(3)!=0;String who=sender==null||sender.isEmpty()?"WhatsApp":sender;String ctx=group?("Grupo: "+(conv==null?"":conv)):"Privado";byMsg.put(id,who+" · "+ctx);}}finally{c.close();}
        for(Map.Entry<Long,Long> e:mediaToMsg.entrySet()){String x=byMsg.get(e.getValue());if(x!=null)out.put(e.getKey(),x);}
        return out;
    }

    public void updateGalleryUri(long mediaId,String uri){if(mediaId<=0)return;ContentValues v=new ContentValues();v.put("gallery_uri",uri==null||uri.isEmpty()?"":MetadataPrivacy.seal(appContext,uri));getWritableDatabase().update("media",v,"id=?",new String[]{String.valueOf(mediaId)});}
    public boolean updateGalleryUriForPath(String path,String uri){if(path==null||path.isEmpty()||uri==null||uri.isEmpty())return false;String sealed=MetadataPrivacy.seal(appContext,uri);if(sealed.isEmpty())return false;ContentValues v=new ContentValues();v.put("gallery_uri",sealed);int n=getWritableDatabase().update("media",v,"local_path=?",new String[]{path});return n==1;}
    public boolean clearGalleryUriForPath(String path){if(path==null||path.isEmpty())return false;ContentValues v=new ContentValues();v.put("gallery_uri","");return getWritableDatabase().update("media",v,"local_path=?",new String[]{path})==1;}

    /** Merge user-visible state before retiring a provable duplicate. Distinct live Gallery copies are never orphaned. */
    private boolean mergeDuplicateState(Media keep,Media drop){
        if(keep==null||drop==null)return false;
        String kg=keep.galleryUri==null?"":keep.galleryUri.trim(),dg=drop.galleryUri==null?"":drop.galleryUri.trim();
        boolean kLive=!kg.isEmpty()&&GalleryExporter.exportedCopyExists(appContext,kg);
        boolean dLive=!dg.isEmpty()&&GalleryExporter.exportedCopyExists(appContext,dg);
        if(kLive&&dLive&&!kg.equals(dg))return false;
        ContentValues u=new ContentValues();if(keep.favorite||drop.favorite)u.put("is_favorite",1);u.put("retention_state",Math.max(keep.retentionState,drop.retentionState));
        if(!kLive&&dLive){String sealed=MetadataPrivacy.seal(appContext,dg);if(sealed.isEmpty())return false;u.put("gallery_uri",sealed);}
        else if(!kLive&&!kg.isEmpty())u.put("gallery_uri","");
        return getWritableDatabase().update("media",u,"id=?",new String[]{String.valueOf(keep.id)})==1;
    }
    public void setMediaOriginalTimes(long mediaId,long capturedAt,long sourceTime){if(mediaId<=0)return;ContentValues v=new ContentValues();v.put("captured_at",Math.max(0L,capturedAt));v.put("source_time",Math.max(0L,sourceTime));getWritableDatabase().update("media",v,"id=?",new String[]{String.valueOf(mediaId)});}
    public boolean setFavorite(long mediaId,boolean favorite){if(mediaId<=0)return false;ContentValues v=new ContentValues();v.put("is_favorite",favorite?1:0);int n=getWritableDatabase().update("media",v,"id=?",new String[]{String.valueOf(mediaId)});if(n>0)notifyUi("media");return n>0;}
    public boolean toggleFavorite(long mediaId){Media m=getMedia(mediaId);return m!=null&&setFavorite(mediaId,!m.favorite);}

    public int reconcileAllPendingMedia(){int n=0;for(String type:new String[]{"image","video","audio","document"}){List<PendingManualMedia> p=listPendingManualMedia(type,40);for(PendingManualMedia x:p){if(reconcileRecentUnlinkedMedia(x.messageId,x.armedAt,type)>0){consumePendingManualMedia(x.messageId);n++;}}n+=reconcilePendingMediaByOrder(type);}return n;}

    /** Conservative one-to-one/FIFO repair. No "nearest" winner is ever selected. v0.5.16
     * resolves independent micro-cohorts instead of forcing every still-live arm of a media type
     * into one giant batch. Each cohort must belong to one conversation, be compact in time, and
     * have an exact 1:1 arm/file count. Ambiguous cohorts remain hidden. */
    public int reconcilePendingMediaByOrder(String type){
        if(type==null||type.isEmpty())return 0;
        List<PendingManualMedia> pending=listPendingManualMedia(type,60);if(pending.isEmpty())return 0;
        java.util.Collections.reverse(pending); // chronological
        final long cohortGapMs=9_000L;
        int total=0,start=0;
        while(start<pending.size()){
            PendingManualMedia first=pending.get(start);String conv=messageConversationFingerprint(first.messageId);
            if(conv.isEmpty()){start++;continue;}
            int end=start+1;long prev=first.armedAt;
            while(end<pending.size()){
                PendingManualMedia x=pending.get(end);String c=messageConversationFingerprint(x.messageId);
                if(!conv.equals(c)||x.armedAt-prev>cohortGapMs)break;
                prev=x.armedAt;end++;
            }
            List<PendingManualMedia> cohort=new ArrayList<>(pending.subList(start,end));
            total+=reconcilePendingMediaCohort(type,cohort,conv);
            start=end;
        }
        if(total>0)logEvent("CORRELATION_REPAIRED","FIFO micro-cohort · "+type+" · "+total+" asociaciones",0L,0L);
        return total;
    }

    private int reconcilePendingMediaCohort(String type,List<PendingManualMedia> cohort,String conv){
        if(cohort==null||cohort.isEmpty()||conv==null||conv.isEmpty())return 0;
        for(PendingManualMedia x:cohort)if(x==null||!conv.equals(messageConversationFingerprint(x.messageId)))return 0;
        long min=cohort.get(0).armedAt-1500L,max=cohort.get(cohort.size()-1).armedAt+8_000L;
        List<Media> media=queryMedia("trash_state=0 AND retention_state=? AND "+NO_LINK_SQL+" AND media_type=? AND (CASE WHEN source_time>0 THEN source_time ELSE captured_at END) BETWEEN ? AND ?",new String[]{String.valueOf(RETENTION_PENDING),type,String.valueOf(min),String.valueOf(max)},"CASE WHEN source_time>0 THEN source_time ELSE captured_at END ASC",Math.max(12,cohort.size()+4));
        if(media.size()!=cohort.size())return 0;
        for(int i=0;i<cohort.size();i++){
            long mt=media.get(i).sourceTime>0?media.get(i).sourceTime:media.get(i).capturedAt;
            long age=mt-cohort.get(i).armedAt;
            if(age<-1500L||age>8_000L)return 0;
        }
        int n=0;
        for(int i=0;i<cohort.size();i++){
            linkMediaToMessageIfFree(media.get(i).id,cohort.get(i).messageId);
            Media linked=getMedia(media.get(i).id);
            if(linked!=null&&linked.linkedMessageId==cohort.get(i).messageId){consumePendingManualMedia(cohort.get(i).messageId);n++;}
        }
        return n;
    }

    public int performConsistencyMaintenance(){
        int changes=0;SQLiteDatabase w=getWritableDatabase();
        changes+=repairProvableMessageDuplicates(6000);
        changes+=repairLinkedMediaDuplicates(6000);
        Set<String> referenced=new HashSet<>();long after=0L;while(true){List<Media> page=listMediaAfterId(after,600);if(page.isEmpty())break;for(Media m:page){after=Math.max(after,m.id);if(m.path!=null&&!m.path.isEmpty())referenced.add(m.path);File f=m.path==null?null:new File(m.path);if(f==null||!f.exists()){deleteMediaLinks(w,m.id);changes+=w.delete("media","id=?",new String[]{String.valueOf(m.id)});}}if(page.size()<600)break;}
        for(String folder:new String[]{"vault_media","vault_audio_quarantine"}){File dir=new File(appContext.getFilesDir(),folder);File[] fs=dir.listFiles();if(fs!=null)for(File f:fs){if(f.isFile()&&!referenced.contains(f.getAbsolutePath())&&System.currentTimeMillis()-Math.max(0L,f.lastModified())>10*60_000L){if(f.delete())changes++;}}}
        changes+=StorageAnalyzer.cleanupTemporary(appContext);
        prunePendingManualMedia(System.currentTimeMillis());
        try{w.execSQL("DELETE FROM event_log WHERE timestamp<?",new Object[]{System.currentTimeMillis()-14L*24L*60L*60L*1000L});}catch(Throwable ignored){}
        try{w.execSQL("DELETE FROM deletion_evidence WHERE consumed_at<?",new Object[]{System.currentTimeMillis()-30L*24L*60L*60L*1000L});}catch(Throwable ignored){}
        if(changes>0)notifyUi("media");return changes;
    }

    /**
     * Deprecated safety shim. Equality of chat/sender/body/timestamp is no longer proof of a
     * duplicate because two legitimate MessagingStyle entries can share all those fields. Durable
     * dedupe happens at insert time through fingerprint + identity_slot, so maintenance must never
     * collapse historical rows heuristically.
     */
    @Deprecated
    public int repairProvableMessageDuplicates(int ignoredLimit){
        return 0;
    }

    /** Collapse capture-route copies for every linked message/type, without a global row ceiling. */
    public int repairLinkedMediaDuplicates(int ignoredLimit){
        int n=0;SQLiteDatabase w=getWritableDatabase();
        Cursor groups=w.rawQuery("SELECT linked_message_id,media_type FROM media WHERE linked_message_id>0 GROUP BY linked_message_id,media_type HAVING COUNT(*)>1",null);
        try{while(groups.moveToNext()){
            long messageId=groups.getLong(0);String type=groups.getString(1);List<Media> rows=queryMedia("linked_message_id=? AND media_type=?",new String[]{String.valueOf(messageId),type==null?"":type},"byte_size DESC, captured_at DESC",256);if(rows.size()<2)continue;
            Media winner=rows.get(0);for(int i=1;i<rows.size();i++){Media loser=rows.get(i);if(loser==null||winner==null)continue;if(!mergeDuplicateState(winner,loser))continue;if(retireSupersededMedia(loser.id,true))n++;winner=getMedia(winner.id);if(winner==null)break;}
        }}finally{groups.close();}
        if(n>0)try{logEvent("DUPLICATE_MEDIA_REPAIRED","vinculados="+n,0L,0L);}catch(Throwable ignored){}return n;
    }

    public List<Event> listEventsSince(long since,int limit){List<Event> out=new ArrayList<>();Cursor c=getReadableDatabase().query("event_log",new String[]{"id","timestamp","code","detail","message_id","media_id"},"timestamp>=?",new String[]{String.valueOf(since)},null,null,"timestamp DESC",String.valueOf(Math.max(1,limit)));try{while(c.moveToNext()){Event e=new Event();e.id=c.getLong(0);e.timestamp=c.getLong(1);e.code=c.getString(2);e.detail=crypto.decrypt(c.getBlob(3));e.messageId=c.getLong(4);e.mediaId=c.getLong(5);out.add(e);}}finally{c.close();}return out;}

    private boolean addMediaLink(SQLiteDatabase db,long mediaId,long messageId,String source){
        if(db==null||mediaId<=0||messageId<=0)return false;ContentValues l=new ContentValues();l.put("media_id",mediaId);l.put("message_id",messageId);l.put("linked_at",System.currentTimeMillis());l.put("link_source",source==null?"":source);
        long r=db.insertWithOnConflict("media_message_links",null,l,SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues primary=new ContentValues();primary.put("linked_message_id",messageId);db.update("media",primary,"id=? AND linked_message_id=0",new String[]{String.valueOf(mediaId)});
        return r!=-1L || hasMediaLink(mediaId,messageId);
    }
    private void transferMediaLinks(SQLiteDatabase db,long fromMediaId,long toMediaId){
        if(db==null||fromMediaId<=0||toMediaId<=0||fromMediaId==toMediaId)return;
        try{db.execSQL("INSERT OR IGNORE INTO media_message_links(media_id,message_id,linked_at,link_source) SELECT ?,message_id,linked_at,'dedupe-transfer' FROM media_message_links WHERE media_id=?",new Object[]{toMediaId,fromMediaId});}catch(Throwable ignored){}
    }
    private void deleteMediaLinks(SQLiteDatabase db,long mediaId){if(db!=null&&mediaId>0)try{db.delete("media_message_links","media_id=?",new String[]{String.valueOf(mediaId)});}catch(Throwable ignored){}}
    private void repairPrimaryLink(SQLiteDatabase db,long mediaId){if(db==null||mediaId<=0)return;Cursor c=db.rawQuery("SELECT message_id FROM media_message_links WHERE media_id=? ORDER BY linked_at ASC,message_id ASC LIMIT 1",new String[]{String.valueOf(mediaId)});long msg=0L;try{if(c.moveToFirst())msg=c.getLong(0);}finally{c.close();}ContentValues v=new ContentValues();v.put("linked_message_id",msg);db.update("media",v,"id=?",new String[]{String.valueOf(mediaId)});}
    public boolean hasMediaLink(long mediaId,long messageId){if(mediaId<=0||messageId<=0)return false;Cursor c=getReadableDatabase().query("media_message_links",new String[]{"media_id"},"media_id=? AND message_id=?",new String[]{String.valueOf(mediaId),String.valueOf(messageId)},null,null,null,"1");try{return c.moveToFirst();}finally{c.close();}}
    public boolean hasConfirmedDeletedLink(long mediaId){if(mediaId<=0)return false;Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM media_message_links ml JOIN messages m ON m.id=ml.message_id WHERE ml.media_id=? AND m.is_deleted=1 AND m.deletion_state=? LIMIT 1",new String[]{String.valueOf(mediaId),String.valueOf(DELETE_CONFIRMED)});try{return c.moveToFirst();}finally{c.close();}}

    public void deleteMessage(long id) {
        if(id<=0)return;SQLiteDatabase w=getWritableDatabase();w.beginTransaction();try{ArrayList<Long> affected=new ArrayList<>();Cursor c=w.query("media_message_links",new String[]{"media_id"},"message_id=?",new String[]{String.valueOf(id)},null,null,null);try{while(c.moveToNext())affected.add(c.getLong(0));}finally{c.close();}w.delete("media_message_links","message_id=?",new String[]{String.valueOf(id)});int n=w.delete("messages","id=?",new String[]{String.valueOf(id)});for(Long mediaId:affected)if(mediaId!=null)repairPrimaryLink(w,mediaId);w.setTransactionSuccessful();if(n>0)notifyUi("message");}finally{w.endTransaction();}
    }
    public void linkMediaToMessage(long mediaId,long messageId){ if(mediaId<=0||messageId<=0)return; synchronized(MEDIA_INSERT_LOCK){mergeOrLinkMedia(mediaId,messageId,false);} }
    public void linkMediaToMessageIfFree(long mediaId,long messageId){ if(mediaId<=0||messageId<=0)return; synchronized(MEDIA_INSERT_LOCK){mergeOrLinkMedia(mediaId,messageId,true);} }

    /** One physical blob may be referenced by several distinct WhatsApp messages. */
    private void mergeOrLinkMedia(long mediaId,long messageId,boolean conservative){
        Media candidate=getMedia(mediaId);if(candidate==null)return;
        Media existing=findLinkedMedia(messageId,candidate.type);
        String resolvedBatch=pendingBatchKey(messageId,candidate.type);SQLiteDatabase w=getWritableDatabase();
        if(existing!=null&&existing.id!=candidate.id){
            Media keep=(candidate.size>existing.size)?candidate:existing;Media drop=keep.id==candidate.id?existing:candidate;
            if(mergeDuplicateState(keep,drop)){transferMediaLinks(w,drop.id,keep.id);addMediaLink(w,keep.id,messageId,conservative?"link-if-free":"link");if(!resolvedBatch.isEmpty()){ContentValues kv=new ContentValues();kv.put("capture_batch_key",resolvedBatch);w.update("media",kv,"id=?",new String[]{String.valueOf(keep.id)});}if(!retireSupersededMedia(drop.id,true))try{logEvent("DUPLICATE_MEDIA_DELETE_PENDING",candidate.type+" · mensaje="+messageId,messageId,keep.id);}catch(Throwable ignored){}else try{logEvent("DUPLICATE_MEDIA_COLLAPSED",candidate.type+" · mensaje="+messageId,messageId,keep.id);}catch(Throwable ignored){}notifyUi("media");return;}
        }
        if(addMediaLink(w,mediaId,messageId,conservative?"link-if-free":"link")){if(!resolvedBatch.isEmpty()){ContentValues v=new ContentValues();v.put("capture_batch_key",resolvedBatch);w.update("media",v,"id=?",new String[]{String.valueOf(mediaId)});}notifyUi("media");}
    }

    public int promotePendingForMessage(long messageId){ if(messageId<=0)return 0; ContentValues v=new ContentValues();v.put("retention_state",RETENTION_KEPT_DELETED);v.put("expires_at",0L);int n=getWritableDatabase().update("media",v,"retention_state=? AND EXISTS (SELECT 1 FROM media_message_links ml WHERE ml.media_id=media.id AND ml.message_id=?)",new String[]{String.valueOf(RETENTION_PENDING),String.valueOf(messageId)});if(n>0)notifyUi("media");return n; }

    /**
     * v0.5.13 final confirmed-delete reconciliation. Automatic media stays hidden while the
     * deletion is only probable. Once the message is CONFIRMED, first attach any preserved but
     * still-unlinked media using exact persisted batch identity or an unambiguous FIFO mapping,
     * then promote only media linked to this confirmed message.
     */
    public int finalizePendingForConfirmedMessage(long messageId,long messageTime){
        if(messageId<=0 || !isMessageConfirmedDeleted(messageId)) return 0;
        int changes=0;
        // Existing exact links are always safest.
        changes += promotePendingForMessage(messageId);

        // Persisted batch identity is the strongest late-association signal. If the file arrived
        // unlinked, only the exact batch key may attach it directly.
        Cursor pc=getReadableDatabase().query("pending_manual_media",new String[]{"media_type","batch_key"},"message_id=?",new String[]{String.valueOf(messageId)},null,null,"armed_at ASC");
        try{while(pc.moveToNext()){String type=pc.getString(0);String batch=pc.getString(1);if(type==null||type.isEmpty()||findLinkedMedia(messageId,type)!=null)continue;Media exact=findUnlinkedPendingByBatch(batch,type);if(exact!=null){linkMediaToMessageIfFree(exact.id,messageId);if(hasMediaLink(exact.id,messageId))changes++;}}}finally{pc.close();}

        // For files that had no batch id, FIFO is the only fallback: exact counts, one conversation,
        // chronological order. Ambiguous media remains hidden rather than being guessed.
        for(String type:new String[]{"audio","image","video","document"}) changes+=reconcilePendingMediaByOrder(type);

        changes += promotePendingForMessage(messageId);
        for(String type:new String[]{"image","video","audio","document"}) if(findLinkedMedia(messageId,type)!=null) consumePendingManualMedia(messageId);
        if(changes>0) try{logEvent("CONFIRMED_MEDIA_RECONCILED","message="+messageId+" · cambios="+changes,messageId,0L);}catch(Throwable ignored){}
        return changes;
    }

    public boolean promotePendingById(long mediaId){ if(mediaId<=0)return false;ContentValues v=new ContentValues();v.put("retention_state",RETENTION_KEPT_DELETED);v.put("expires_at",0L);boolean ok=getWritableDatabase().update("media",v,"id=? AND retention_state=?",new String[]{String.valueOf(mediaId),String.valueOf(RETENTION_PENDING)})>0;if(ok)notifyUi("media");return ok; }

    public void logEvent(String code,String detail,long messageId,long mediaId){
        byte[] enc=crypto.encrypt(detail==null?"":detail); if(enc==null)return; ContentValues v=new ContentValues();v.put("timestamp",System.currentTimeMillis());v.put("code",code==null?"EVENT":code);v.put("detail",enc);v.put("message_id",messageId);v.put("media_id",mediaId);getWritableDatabase().insert("event_log",null,v);
        // Pruning on every low-latency event creates unnecessary SQLite work during bursts.
        // Keep the diagnostic log bounded, but prune only once every 64 writes.
        if((EVENT_WRITES.incrementAndGet() & 63)==0)try { getWritableDatabase().execSQL("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY id DESC LIMIT 600)"); } catch(Throwable ignored){}
    }
    public void logInternalError(String scope,Throwable t){
        try{String msg=t==null?"sin detalle":String.valueOf(t.getClass().getSimpleName()+": "+t.getMessage());if(msg.length()>320)msg=msg.substring(0,320);logEvent("ERROR_"+(scope==null?"UNKNOWN":scope),msg,0L,0L);}catch(Throwable ignored){}
    }
    public void logCaptureFailure(String stage,String type,long messageId,Throwable t){
        try{String detail=(type==null?"":type)+" · "+(t==null?"sin detalle":t.getClass().getSimpleName()+": "+String.valueOf(t.getMessage()));if(detail.length()>280)detail=detail.substring(0,280);logEvent("CAPTURE_FAIL_"+(stage==null?"UNKNOWN":stage),detail,messageId,0L);}catch(Throwable ignored){}
    }
    public List<Event> listEvents(int limit){List<Event> out=new ArrayList<>();Cursor c=getReadableDatabase().query("event_log",new String[]{"id","timestamp","code","detail","message_id","media_id"},null,null,null,null,"timestamp DESC",String.valueOf(limit));try{while(c.moveToNext()){Event e=new Event();e.id=c.getLong(0);e.timestamp=c.getLong(1);e.code=c.getString(2);e.detail=crypto.decrypt(c.getBlob(3));e.messageId=c.getLong(4);e.mediaId=c.getLong(5);out.add(e);}}finally{c.close();}return out;}

    public String armPendingManualMedia(long messageId, String type, long armedAt, long ttlMs) {
        if (messageId <= 0 || type == null || !("image".equals(type) || "video".equals(type) || "audio".equals(type) || "document".equals(type))) return "";
        long now = armedAt > 0 ? armedAt : System.currentTimeMillis();
        String batch=buildCaptureBatchKey(messageId,type,now);
        ContentValues v=new ContentValues();
        v.put("message_id",messageId); v.put("media_type",type); v.put("armed_at",now); v.put("expires_at",now+Math.max(30_000L,ttlMs));v.put("batch_key",batch);
        getWritableDatabase().insertWithOnConflict("pending_manual_media",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        prunePendingManualMedia(System.currentTimeMillis());
        return batch;
    }

    public void consumePendingManualMedia(long messageId) {
        if(messageId<=0)return;
        getWritableDatabase().delete("pending_manual_media","message_id=?",new String[]{String.valueOf(messageId)});
    }

    public void prunePendingManualMedia(long now) {
        getWritableDatabase().delete("pending_manual_media","expires_at<?",new String[]{String.valueOf(now)});
    }

    public List<PendingManualMedia> listPendingManualMedia(String type, int limit) {
        prunePendingManualMedia(System.currentTimeMillis());
        String sel=(type==null||type.isEmpty())?null:"media_type=?";
        String[] args=sel==null?null:new String[]{type};
        ArrayList<PendingManualMedia> out=new ArrayList<>();
        Cursor c=getReadableDatabase().query("pending_manual_media",new String[]{"message_id","media_type","armed_at","expires_at","batch_key"},sel,args,null,null,"armed_at DESC",String.valueOf(Math.max(1,limit)));
        try{while(c.moveToNext()){PendingManualMedia x=new PendingManualMedia();x.messageId=c.getLong(0);x.type=c.getString(1);x.armedAt=c.getLong(2);x.expiresAt=c.getLong(3);x.batchKey=c.getString(4);out.add(x);}}finally{c.close();}
        return out;
    }

    /** Return a persisted arm only when exactly one typed capture context is active. This is a
     * uniqueness test, not a nearest-timestamp contest. */
    public long findUniquePendingManualMessage(String type,long eventTime,long windowMs){
        if(type==null||type.isEmpty())return 0L;long now=eventTime>0?eventTime:System.currentTimeMillis();long window=Math.max(1000L,Math.min(windowMs,30_000L));
        List<PendingManualMedia> list=listPendingManualMedia(type,30);PendingManualMedia only=null;int count=0;
        for(PendingManualMedia x:list){long age=now-x.armedAt;if(age<-1500L||age>window)continue;only=x;if(++count>1)return 0L;}
        return count==1&&only!=null?only.messageId:0L;
    }

    /** Upgrade/startup normalization: legacy visible media not backed by a confirmed deleted
     * message is moved back into hidden quarantine. Confirmed links are normalized to KEPT. */
    /** v0.5.16 evidence migration. v0.5.15 could confirm directly-observed rows from an
     * APP_CANCEL lifecycle event even when WhatsApp never emitted a deletion marker. Those rows
     * do not meet the new evidence standard. Downgrade only rows that can be tied to the bounded
     * v0.5.15 DELETE_VERIFIED_SEQUENCE audit trail; explicit-marker deletions are untouched. */
    public int repairLegacyAppCancelConfirmations(){
        android.content.SharedPreferences sp=appContext.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE);
        if(sp.getBoolean("v0516_app_cancel_cleanup_done",false))return 0;
        class Seq{long ts;int count;String conv;Seq(long t,int c,String v){ts=t;count=c;conv=v;}}
        ArrayList<Seq> seqs=new ArrayList<>();Cursor c=null;
        try{
            c=getReadableDatabase().query("event_log",new String[]{"timestamp","detail"},"code=?",new String[]{"DELETE_VERIFIED_SEQUENCE"},null,null,"timestamp DESC","600");
            while(c.moveToNext()){
                long ts=c.getLong(0);String d=crypto.decrypt(c.getBlob(1));if(d==null||!d.startsWith("APP_CANCEL directo="))continue;
                int count=0;String conv="";
                try{int a="APP_CANCEL directo=".length(),b=d.indexOf(' ',a);if(b<0)b=d.indexOf('·',a);String raw=(b>a?d.substring(a,b):d.substring(a)).trim();count=Integer.parseInt(raw);}catch(Throwable ignored){}
                int cut=d.lastIndexOf(" · ");if(cut>=0&&cut+3<d.length())conv=d.substring(cut+3).trim();
                if(count>0&&!conv.isEmpty())seqs.add(new Seq(ts,Math.min(count,64),conv));
            }
        }catch(Throwable ignored){}finally{if(c!=null)c.close();}
        int reverted=0;HashSet<Long> done=new HashSet<>();SQLiteDatabase w=getWritableDatabase();long now=System.currentTimeMillis();
        for(Seq seq:seqs){
            Cursor e=null;int left=seq.count;
            try{
                e=getReadableDatabase().query("event_log",new String[]{"message_id"},"code=? AND message_id>0 AND timestamp BETWEEN ? AND ?",new String[]{"DELETE_CONFIRMED",String.valueOf(seq.ts-5_000L),String.valueOf(seq.ts+250L)},null,null,"timestamp DESC","96");
                while(e.moveToNext()&&left>0){long id=e.getLong(0);if(id<=0||done.contains(id))continue;Msg m=getMessage(id);if(m==null||!seq.conv.equalsIgnoreCase(safeText(m.conversation))||m.deletionState!=DELETE_CONFIRMED)continue;
                    ContentValues mv=new ContentValues();mv.put("is_deleted",0);mv.put("deletion_state",DELETE_NONE);
                    if(w.update("messages",mv,"id=?",new String[]{String.valueOf(id)})>0){
                        ContentValues med=new ContentValues();med.put("retention_state",RETENTION_PENDING);med.put("expires_at",now+PROVISIONAL_MEDIA_TTL_MS);
                        w.update("media",med,"linked_message_id=? AND trash_state=0",new String[]{String.valueOf(id)});
                        done.add(id);reverted++;left--;
                    }
                }
            }catch(Throwable ignored){}finally{if(e!=null)e.close();}
        }
        sp.edit().putBoolean("v0516_app_cancel_cleanup_done",true).putInt("v0516_app_cancel_cleanup_count",reverted).apply();
        if(reverted>0){try{logEvent("V0516_APP_CANCEL_LEGACY_ROLLBACK","Confirmaciones APP_CANCEL sin prueba degradadas="+reverted,0L,0L);}catch(Throwable ignored){}notifyUi("message");notifyUi("media");}
        return reverted;
    }

    public int normalizeConfirmedMediaVisibility(){
        SQLiteDatabase w=getWritableDatabase();long now=System.currentTimeMillis();int n=0;ContentValues kept=new ContentValues();kept.put("retention_state",RETENTION_KEPT_DELETED);kept.put("expires_at",0L);
        n+=w.update("media",kept,"trash_state=0 AND "+CONFIRMED_MEDIA_SQL+" AND (retention_state<>"+RETENTION_KEPT_DELETED+" OR expires_at<>0)",null);
        ContentValues hidden=new ContentValues();hidden.put("retention_state",RETENTION_PENDING);hidden.put("expires_at",now+PROVISIONAL_MEDIA_TTL_MS);
        n+=w.update("media",hidden,"trash_state=0 AND NOT ("+CONFIRMED_MEDIA_SQL+") AND retention_state<>?",new String[]{String.valueOf(RETENTION_PENDING)});
        if(n>0)try{logEvent("VISIBILITY_NORMALIZED","filas="+n,0L,0L);}catch(Throwable ignored){}return n;
    }

    public Stats getStats(){
        Stats st=new Stats();
        Cursor c=null;
        try{c=getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(CASE WHEN is_deleted=1 AND deletion_state=2 THEN 1 ELSE 0 END),0) FROM messages",null);if(c.moveToFirst()){st.messages=c.getInt(0);st.deletedMessages=c.getInt(1);}}finally{if(c!=null)c.close();}
        try{c=getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size),0), COALESCE(SUM(CASE WHEN media_type='audio' THEN byte_size ELSE 0 END),0), COALESCE(SUM(CASE WHEN media_type='image' THEN byte_size ELSE 0 END),0), COALESCE(SUM(CASE WHEN media_type='video' THEN byte_size ELSE 0 END),0), COALESCE(SUM(CASE WHEN media_type='document' THEN byte_size ELSE 0 END),0), COUNT(*), 0, COALESCE(SUM(CASE WHEN is_favorite=1 THEN 1 ELSE 0 END),0) FROM media WHERE trash_state=0 AND retention_state="+RETENTION_KEPT_DELETED+" AND "+CONFIRMED_MEDIA_SQL,null);if(c.moveToFirst()){st.media=c.getInt(0);st.totalBytes=c.getLong(1);st.audioBytes=c.getLong(2);st.imageBytes=c.getLong(3);st.videoBytes=c.getLong(4);st.documentBytes=c.getLong(5);st.keptMedia=c.getInt(6);st.unlinkedMedia=c.getInt(7);st.favorites=c.getInt(8);}}finally{if(c!=null)c.close();}
        try{c=getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size),0) FROM media WHERE trash_state=0 AND retention_state="+RETENTION_PENDING,null);if(c.moveToFirst()){st.pendingMedia=c.getInt(0);st.pendingBytes=c.getLong(1);}}finally{if(c!=null)c.close();}
        try{c=getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(byte_size),0) FROM media WHERE trash_state=1",null);if(c.moveToFirst()){st.trashedMedia=c.getInt(0);st.trashBytes=c.getLong(1);}}finally{if(c!=null)c.close();}
        try{c=getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(CASE WHEN state IN ('LOST','CORRUPT') THEN 1 ELSE 0 END),0) FROM recovery_jobs WHERE state NOT IN ('SAVED','DUPLICATE')",null);if(c.moveToFirst()){st.detectedFiles=st.media+c.getInt(0);st.recoveryIssues=c.getInt(1);}}finally{if(c!=null)c.close();}
        st.savedFiles=st.media;st.physicalBytes=st.totalBytes+st.pendingBytes+st.trashBytes;
        return st;
    }

    public List<MediaRef> listMediaRefsOlderThan(long cutoff,int limit){
        ArrayList<MediaRef> out=new ArrayList<>();Cursor c=getReadableDatabase().query("media",new String[]{"id","local_path"},"trash_state=0 AND captured_at<?",new String[]{String.valueOf(cutoff)},null,null,"captured_at ASC",String.valueOf(Math.max(1,limit)));try{while(c.moveToNext())out.add(new MediaRef(c.getLong(0),c.getString(1)));}finally{c.close();}return out;
    }
    public List<MediaRef> listUnlinkedMediaRefs(int limit){
        ArrayList<MediaRef> out=new ArrayList<>();Cursor c=getReadableDatabase().query("media",new String[]{"id","local_path"},"trash_state=0 AND "+NO_LINK_SQL,null,null,null,"captured_at DESC",String.valueOf(Math.max(1,limit)));try{while(c.moveToNext())out.add(new MediaRef(c.getLong(0),c.getString(1)));}finally{c.close();}return out;
    }

    public long recordCaptureAttempt(String family,String type,long bytes,String state,String reason,long messageId,long sourceTime,long resolvedMediaId){
        return recordCaptureAttempt(family,type,bytes,state,reason,"",messageId,sourceTime,resolvedMediaId);
    }
    public long recordCaptureAttempt(String family,String type,long bytes,String state,String reason,String localPath,long messageId,long sourceTime,long resolvedMediaId){
        ContentValues v=new ContentValues();v.put("family_key",family==null?"":family);v.put("media_type",type==null?"":type);v.put("byte_size",Math.max(0L,bytes));v.put("state",state==null?"":state);v.put("reason",reason==null?"":reason);v.put("local_path",localPath==null||localPath.isEmpty()?"":MetadataPrivacy.seal(appContext,localPath));v.put("source_time",sourceTime);v.put("message_id",messageId);v.put("created_at",System.currentTimeMillis());v.put("resolved_media_id",resolvedMediaId);
        long id=getWritableDatabase().insert("capture_attempts",null,v);
        try{getWritableDatabase().execSQL("DELETE FROM capture_attempts WHERE state<>'PARTIAL' AND id NOT IN (SELECT id FROM capture_attempts ORDER BY id DESC LIMIT 800)");}catch(Throwable ignored){}
        return id;
    }
    public List<CaptureAttempt> listCaptureAttempts(int limit){
        ArrayList<CaptureAttempt> out=new ArrayList<>();Cursor c=getReadableDatabase().query("capture_attempts",new String[]{"id","family_key","media_type","byte_size","state","reason","local_path","source_time","message_id","created_at","resolved_media_id"},null,null,null,null,"created_at DESC",String.valueOf(Math.max(1,limit)));
        try{while(c.moveToNext()){CaptureAttempt a=new CaptureAttempt();a.id=c.getLong(0);a.familyKey=c.getString(1);a.type=c.getString(2);a.bytes=c.getLong(3);a.state=c.getString(4);a.reason=c.getString(5);a.localPath=MetadataPrivacy.open(appContext,c.getString(6));a.sourceTime=c.getLong(7);a.messageId=c.getLong(8);a.createdAt=c.getLong(9);a.resolvedMediaId=c.getLong(10);out.add(a);}}finally{c.close();}return out;
    }
    public int countCaptureAttempts(String state){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM capture_attempts WHERE state=?",new String[]{state==null?"":state});try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}}
    public void resolveCaptureFamily(String family,long mediaId){
        if(family==null||family.isEmpty()||mediaId<=0)return;Cursor c=getReadableDatabase().query("capture_attempts",new String[]{"local_path"},"family_key=? AND state='PARTIAL'",new String[]{family},null,null,null);try{while(c.moveToNext()){String p=MetadataPrivacy.open(appContext,c.getString(0));if(p!=null&&!p.isEmpty())try{new File(p).delete();}catch(Throwable ignored){}}}finally{c.close();}
        ContentValues v=new ContentValues();v.put("state","RESOLVED");v.put("resolved_media_id",mediaId);getWritableDatabase().update("capture_attempts",v,"family_key=? AND state IN ('PARTIAL','FAILED','WAITING')",new String[]{family});
    }
    public int removeFamilyPreviews(String family,long keepMediaId){
        if(family==null||family.isEmpty())return 0;ArrayList<Media> victims=new ArrayList<>();Cursor c=getReadableDatabase().query("media",mediaColumns(),"source_uri LIKE ? AND id<>?",new String[]{"partial-preview:"+family+"%",String.valueOf(keepMediaId)},null,null,null);try{while(c.moveToNext())victims.add(readMedia(c));}finally{c.close();}
        int n=0;SQLiteDatabase w=getWritableDatabase();for(Media m:victims){if(m.path!=null&&!m.path.isEmpty())try{new File(m.path).delete();}catch(Throwable ignored){}deleteMediaLinks(w,m.id);n+=w.delete("media","id=?",new String[]{String.valueOf(m.id)});}if(n>0)notifyUi("media");return n;
    }
    public int pruneCaptureAttempts(long partialCutoff,long historyCutoff){
        int n=0;Cursor c=getReadableDatabase().query("capture_attempts",new String[]{"id","local_path"},"state='PARTIAL' AND created_at<?",new String[]{String.valueOf(partialCutoff)},null,null,null);try{while(c.moveToNext()){String p=MetadataPrivacy.open(appContext,c.getString(1));if(p!=null&&!p.isEmpty())try{new File(p).delete();}catch(Throwable ignored){}}}finally{c.close();}
        n+=getWritableDatabase().delete("capture_attempts","state='PARTIAL' AND created_at<?",new String[]{String.valueOf(partialCutoff)});n+=getWritableDatabase().delete("capture_attempts","created_at<?",new String[]{String.valueOf(historyCutoff)});return n;
    }
    public int clearPartialCaptureAttempts(){
        Cursor c=getReadableDatabase().query("capture_attempts",new String[]{"local_path"},"state='PARTIAL'",null,null,null,null);try{while(c.moveToNext()){String p=MetadataPrivacy.open(appContext,c.getString(0));if(p!=null&&!p.isEmpty())try{new File(p).delete();}catch(Throwable ignored){}}}finally{c.close();}
        return getWritableDatabase().delete("capture_attempts","state='PARTIAL'",null);
    }

    public void upsertRecoveryJob(String jobKey,String type,String state,String reason,long bytes,long messageId,long sourceTime,String localPath,long resolvedMediaId,String origin){
        if(jobKey==null||jobKey.isEmpty())return;long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("job_key",jobKey);v.put("media_type",type==null?"":type);v.put("state",state==null?"":state);v.put("reason",reason==null?"":reason);v.put("byte_size",Math.max(0L,bytes));v.put("source_time",sourceTime);v.put("message_id",messageId);v.put("updated_at",now);v.put("resolved_media_id",resolvedMediaId);v.put("local_path",localPath==null||localPath.isEmpty()?"":MetadataPrivacy.token(appContext,"path",localPath));v.put("origin",origin==null?"":origin);
        Cursor c=getReadableDatabase().query("recovery_jobs",new String[]{"id","created_at"},"job_key=?",new String[]{jobKey},null,null,null,"1");boolean exists=false;long created=now;try{if(c.moveToFirst()){exists=true;created=c.getLong(1);}}finally{c.close();}v.put("created_at",created);
        if(exists)getWritableDatabase().update("recovery_jobs",v,"job_key=?",new String[]{jobKey});else getWritableDatabase().insertWithOnConflict("recovery_jobs",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        try{getWritableDatabase().execSQL("DELETE FROM recovery_jobs WHERE updated_at<? AND state IN ('SAVED','DUPLICATE','LOST')",new Object[]{now-14L*24L*60L*60L*1000L});}catch(Throwable ignored){}
        notifyUi("recovery");
    }

    public List<RecoveryJob> listRecoveryJobs(int limit){ArrayList<RecoveryJob> out=new ArrayList<>();Cursor c=getReadableDatabase().query("recovery_jobs",new String[]{"id","job_key","media_type","state","reason","byte_size","source_time","message_id","created_at","updated_at","resolved_media_id","local_path","origin"},null,null,null,null,"updated_at DESC",String.valueOf(Math.max(1,limit)));try{while(c.moveToNext()){RecoveryJob r=new RecoveryJob();r.id=c.getLong(0);r.jobKey=c.getString(1);r.type=c.getString(2);r.state=c.getString(3);r.reason=c.getString(4);r.bytes=c.getLong(5);r.sourceTime=c.getLong(6);r.messageId=c.getLong(7);r.createdAt=c.getLong(8);r.updatedAt=c.getLong(9);r.resolvedMediaId=c.getLong(10);r.localPath=c.getString(11);r.origin=c.getString(12);out.add(r);}}finally{c.close();}return out;}

    public boolean moveMediaToTrash(long mediaId){
        if(mediaId<=0)return false;ContentValues v=new ContentValues();v.put("trash_state",1);v.put("trashed_at",System.currentTimeMillis());
        int n=getWritableDatabase().update("media",v,"id=? AND trash_state=0",new String[]{String.valueOf(mediaId)});if(n>0){logEvent("MEDIA_TRASHED","Movido a papelera",0L,mediaId);notifyUi("media");}return n>0;
    }
    public int moveMediaToTrash(List<Long> mediaIds){int n=0;if(mediaIds!=null)for(Long id:mediaIds)if(id!=null&&moveMediaToTrash(id))n++;return n;}
    public boolean restoreMedia(long mediaId){
        if(mediaId<=0)return false;ContentValues v=new ContentValues();v.put("trash_state",0);v.put("trashed_at",0L);
        int n=getWritableDatabase().update("media",v,"id=? AND trash_state<>0",new String[]{String.valueOf(mediaId)});if(n>0){logEvent("MEDIA_RESTORED","Restaurado desde papelera",0L,mediaId);notifyUi("media");}return n>0;
    }
    public boolean deleteMediaPermanently(long mediaId){return deleteMediaPermanently(mediaId,false);}
    public boolean deleteMediaPermanently(long mediaId,boolean deleteGalleryCopy){return deleteMediaPermanentlyInternal(mediaId,deleteGalleryCopy,true);}

    private boolean deleteMediaPermanentlyInternal(long mediaId,boolean deleteGalleryCopy,boolean scheduleRetry){
        Media m=getMedia(mediaId);if(m==null){clearPendingPhysicalDelete(mediaId);return true;}
        // If the user requested both copies, keep the DB row (and therefore the encrypted Gallery URI)
        // until the external copy is confirmed gone. This makes the operation safely retryable.
        if(deleteGalleryCopy){
            boolean galleryGone=false;try{galleryGone=GalleryExporter.deleteExportedCopy(appContext,m);}catch(Throwable t){logCaptureFailure("GALLERY_DELETE",m.type,m.linkedMessageId,t);}
            if(!galleryGone){
                markPendingPhysicalDelete(mediaId,true);
                try{logEvent("GALLERY_DELETE_PENDING","Copia de Galería pendiente",0L,mediaId);}catch(Throwable ignored){}
                if(scheduleRetry)schedulePhysicalDeleteRetry(mediaId,true);
                return false;
            }
        }
        File privateFile=(m.path==null||m.path.isEmpty())?null:new File(m.path);
        boolean physicalGone=privateFile==null||!privateFile.exists();
        if(!physicalGone){
            try{physicalGone=privateFile.delete()&&!privateFile.exists();}catch(Throwable ignored){physicalGone=false;}
            if(!physicalGone)physicalGone=!privateFile.exists();
        }
        if(!physicalGone){
            markPendingPhysicalDelete(mediaId,deleteGalleryCopy);
            try{logEvent("MEDIA_DELETE_PENDING","Borrado físico pendiente",0L,mediaId);}catch(Throwable ignored){}
            if(scheduleRetry)schedulePhysicalDeleteRetry(mediaId,deleteGalleryCopy);
            return false;
        }
        rememberPermanentDeletion(m);
        SQLiteDatabase deleteDb=getWritableDatabase();deleteMediaLinks(deleteDb,mediaId);
        int n=deleteDb.delete("media","id=?",new String[]{String.valueOf(mediaId)});
        if(n>0){
            clearPendingPhysicalDelete(mediaId);
            try{logEvent("MEDIA_USER_DELETED","tombstone guardado · galería="+(deleteGalleryCopy?"sí":"no"),0L,mediaId);}catch(Throwable ignored){}
            notifyUi("media");
        }
        return n>0;
    }

    private String pendingDeleteKey(long id){return "pending_physical_delete_"+id;}
    private String pendingDeleteGalleryKey(long id){return "pending_physical_gallery_"+id;}
    private void markPendingPhysicalDelete(long id,boolean gallery){
        try{appContext.getSharedPreferences("wa_vault_delete_retry",Context.MODE_PRIVATE).edit().putBoolean(pendingDeleteKey(id),true).putBoolean(pendingDeleteGalleryKey(id),gallery).apply();
            appContext.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putLong("physical_delete_pending_at",System.currentTimeMillis()).putLong("physical_delete_pending_id",id).apply();}catch(Throwable ignored){}
    }
    private void clearPendingPhysicalDelete(long id){
        try{appContext.getSharedPreferences("wa_vault_delete_retry",Context.MODE_PRIVATE).edit().remove(pendingDeleteKey(id)).remove(pendingDeleteGalleryKey(id)).apply();}catch(Throwable ignored){}
        DELETE_RETRY_ATTEMPTS.remove(id);DELETE_RETRY_SCHEDULED.remove(id);
    }
    private void schedulePhysicalDeleteRetry(long id,boolean gallery){
        int attempt=DELETE_RETRY_ATTEMPTS.getOrDefault(id,0);long[] delays={5L,30L,120L};if(attempt>=delays.length)return;
        if(!DELETE_RETRY_SCHEDULED.add(id))return;
        DELETE_RETRY.schedule(()->{
            DELETE_RETRY_SCHEDULED.remove(id);
            boolean ok=false;try{ok=new VaultDb(appContext).deleteMediaPermanentlyInternal(id,gallery,false);}catch(Throwable ignored){}
            if(ok){DELETE_RETRY_ATTEMPTS.remove(id);return;}
            DELETE_RETRY_ATTEMPTS.put(id,attempt+1);try{new VaultDb(appContext).schedulePhysicalDeleteRetry(id,gallery);}catch(Throwable ignored){}
        },delays[attempt],TimeUnit.SECONDS);
    }
    public int retryPendingPhysicalDeletes(){
        int done=0;SharedPreferences p=appContext.getSharedPreferences("wa_vault_delete_retry",Context.MODE_PRIVATE);
        for(String k:new ArrayList<>(p.getAll().keySet())){
            if(k==null||!k.startsWith("pending_physical_delete_"))continue;
            long id;try{id=Long.parseLong(k.substring("pending_physical_delete_".length()));}catch(Throwable bad){continue;}
            boolean gallery=p.getBoolean(pendingDeleteGalleryKey(id),false);
            if(deleteMediaPermanentlyInternal(id,gallery,false))done++;else schedulePhysicalDeleteRetry(id,gallery);
        }
        return done;
    }
    private static final Set<Long> SUPERSEDED_RETRY_SCHEDULED = ConcurrentHashMap.newKeySet();
    private static final Map<Long,Integer> SUPERSEDED_RETRY_ATTEMPTS = new ConcurrentHashMap<>();
    private String pendingSupersededKey(long id){return "pending_superseded_delete_"+id;}
    private void markPendingSuperseded(long id){try{appContext.getSharedPreferences("wa_vault_delete_retry",Context.MODE_PRIVATE).edit().putBoolean(pendingSupersededKey(id),true).apply();}catch(Throwable ignored){}}
    private void clearPendingSuperseded(long id){try{appContext.getSharedPreferences("wa_vault_delete_retry",Context.MODE_PRIVATE).edit().remove(pendingSupersededKey(id)).apply();}catch(Throwable ignored){}SUPERSEDED_RETRY_ATTEMPTS.remove(id);SUPERSEDED_RETRY_SCHEDULED.remove(id);}
    private boolean retireSupersededMedia(long mediaId,boolean scheduleRetry){
        Media m=getMedia(mediaId);if(m==null){clearPendingSuperseded(mediaId);return true;}File f=(m.path==null||m.path.isEmpty())?null:new File(m.path);boolean gone=f==null||!f.exists();if(!gone){try{gone=f.delete()&&!f.exists();}catch(Throwable ignored){gone=false;}if(!gone)gone=!f.exists();}
        if(!gone){markPendingSuperseded(mediaId);if(scheduleRetry)scheduleSupersededRetry(mediaId);return false;}
        SQLiteDatabase retireDb=getWritableDatabase();transferMediaLinks(retireDb,mediaId,0L);deleteMediaLinks(retireDb,mediaId);int n=retireDb.delete("media","id=?",new String[]{String.valueOf(mediaId)});if(n>0||getMedia(mediaId)==null){clearPendingSuperseded(mediaId);notifyUi("media");return true;}markPendingSuperseded(mediaId);if(scheduleRetry)scheduleSupersededRetry(mediaId);return false;
    }
    private void scheduleSupersededRetry(long id){int attempt=SUPERSEDED_RETRY_ATTEMPTS.getOrDefault(id,0);long[] delays={5L,30L,120L};if(attempt>=delays.length)return;if(!SUPERSEDED_RETRY_SCHEDULED.add(id))return;DELETE_RETRY.schedule(()->{SUPERSEDED_RETRY_SCHEDULED.remove(id);boolean ok=false;try{ok=new VaultDb(appContext).retireSupersededMedia(id,false);}catch(Throwable ignored){}if(ok){SUPERSEDED_RETRY_ATTEMPTS.remove(id);return;}SUPERSEDED_RETRY_ATTEMPTS.put(id,attempt+1);try{new VaultDb(appContext).scheduleSupersededRetry(id);}catch(Throwable ignored){}},delays[attempt],TimeUnit.SECONDS);}
    public int retryPendingSupersededDeletes(){int done=0;SharedPreferences p=appContext.getSharedPreferences("wa_vault_delete_retry",Context.MODE_PRIVATE);for(String k:new ArrayList<>(p.getAll().keySet())){if(k==null||!k.startsWith("pending_superseded_delete_"))continue;long id;try{id=Long.parseLong(k.substring("pending_superseded_delete_".length()));}catch(Throwable bad){continue;}if(retireSupersededMedia(id,false))done++;else scheduleSupersededRetry(id);}return done;}

    public int emptyTrash(){return emptyTrash(false);}
    public int emptyTrash(boolean deleteGalleryCopies){int n=0;while(true){List<Media> all=listTrashPage(250,0);if(all.isEmpty())break;int pass=0;for(Media m:all)if(deleteMediaPermanently(m.id,deleteGalleryCopies)){n++;pass++;}if(pass==0)break;}return n;}
    public int restoreAllTrash(){int n=0;while(true){List<Media> all=listTrashPage(250,0);if(all.isEmpty())break;int pass=0;for(Media m:all)if(restoreMedia(m.id)){n++;pass++;}if(pass==0)break;}return n;}
    /** Permanent cleanup APIs deliberately use deleteMediaPermanently so user intent is
     * remembered in media_tombstones and automatic watchers cannot resurrect the same bytes. */
    public int deleteUnlinkedMediaPermanently(){int n=0;while(true){List<Media> all=queryMedia("trash_state=0 AND "+NO_LINK_SQL,null,"id ASC",250);if(all.isEmpty())break;int pass=0;for(Media m:all)if(deleteMediaPermanently(m.id)){n++;pass++;}if(pass==0)break;}return n;}
    public int deleteMediaOlderThanPermanently(long cutoff){int n=0;while(true){List<Media> all=queryMedia("trash_state=0 AND captured_at<?",new String[]{String.valueOf(cutoff)},"id ASC",250);if(all.isEmpty())break;int pass=0;for(Media m:all)if(deleteMediaPermanently(m.id)){n++;pass++;}if(pass==0)break;}return n;}
    public int deleteAllMediaPermanently(){int n=0;while(true){List<Media> all=queryMedia(null,null,"id ASC",250);if(all.isEmpty())break;int pass=0;for(Media m:all)if(deleteMediaPermanently(m.id)){n++;pass++;}if(pass==0)break;}return n;}

    public int purgeStickerMedia(){
        int n=0;while(true){List<Media> all=queryMedia("LOWER(source_uri) LIKE '%whatsapp stickers%' OR LOWER(origin) LIKE '%sticker%'",null,"id ASC",250);if(all.isEmpty())break;int pass=0;for(Media m:all)if(m!=null&&deleteMediaPermanently(m.id)){n++;pass++;}if(pass==0)break;}return n;
    }
    public int purgeTrashOlderThan(long cutoff){int n=0;while(true){List<Media> all=queryMedia("trash_state=1 AND trashed_at<?",new String[]{String.valueOf(cutoff)},"trashed_at ASC",250);if(all.isEmpty())break;int pass=0;for(Media m:all)if(deleteMediaPermanently(m.id)){n++;pass++;}if(pass==0)break;}return n;}
    public List<Media> listTrashPage(int limit,int offset){return queryMediaPage("trash_state=1",null,"trashed_at DESC",Math.max(1,limit),Math.max(0,offset));}
    public int countTrash(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM media WHERE trash_state=1",null);try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}}
    public List<RecoveryJob> listRecoveryJobsForMedia(long mediaId,int limit){ArrayList<RecoveryJob> out=new ArrayList<>();Cursor c=getReadableDatabase().query("recovery_jobs",new String[]{"id","job_key","media_type","state","reason","byte_size","source_time","message_id","created_at","updated_at","resolved_media_id","local_path","origin"},"resolved_media_id=?",new String[]{String.valueOf(mediaId)},null,null,"updated_at DESC",String.valueOf(Math.max(1,limit)));try{while(c.moveToNext()){RecoveryJob r=new RecoveryJob();r.id=c.getLong(0);r.jobKey=c.getString(1);r.type=c.getString(2);r.state=c.getString(3);r.reason=c.getString(4);r.bytes=c.getLong(5);r.sourceTime=c.getLong(6);r.messageId=c.getLong(7);r.createdAt=c.getLong(8);r.updatedAt=c.getLong(9);r.resolvedMediaId=c.getLong(10);r.localPath=c.getString(11);r.origin=c.getString(12);out.add(r);}}finally{c.close();}return out;}
    public List<Event> listEventsForMedia(long mediaId,int limit){ArrayList<Event> out=new ArrayList<>();Cursor c=getReadableDatabase().query("event_log",new String[]{"id","timestamp","code","detail","message_id","media_id"},"media_id=?",new String[]{String.valueOf(mediaId)},null,null,"timestamp DESC",String.valueOf(Math.max(1,limit)));try{while(c.moveToNext()){Event e=new Event();e.id=c.getLong(0);e.timestamp=c.getLong(1);e.code=c.getString(2);e.detail=crypto.decrypt(c.getBlob(3));e.messageId=c.getLong(4);e.mediaId=c.getLong(5);out.add(e);}}finally{c.close();}return out;}

    /** v0.5.26: converts legacy plaintext SHA-256 equality values to Keystore-backed HMAC tokens. */
    public int migrateContentHashesToHmac(){
        SQLiteDatabase db=getWritableDatabase();int touched=0;
        while(true){Cursor c=db.query("media",new String[]{"id","content_hash","canonical_key"},"content_hash<>'' AND content_hash NOT LIKE 'mh1_%'",null,null,null,"id ASC","128");ArrayList<Long> ids=new ArrayList<>();ArrayList<String> hashes=new ArrayList<>();ArrayList<String> canonicals=new ArrayList<>();try{while(c.moveToNext()){ids.add(c.getLong(0));hashes.add(c.getString(1));canonicals.add(c.getString(2));}}finally{c.close();}if(ids.isEmpty())break;for(int i=0;i<ids.size();i++){String old=hashes.get(i);String h=MetadataPrivacy.contentHash(appContext,old);String canonical=canonicals.get(i);if(canonical==null)canonical="";if(canonical.startsWith(old+"|"))canonical=h+canonical.substring(old.length());ContentValues x=new ContentValues();x.put("content_hash",h);x.put("canonical_key",canonical);if(db.update("media",x,"id=?",new String[]{String.valueOf(ids.get(i))})>0)touched++;}if(ids.size()<128)break;try{Thread.sleep(12L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        while(!Thread.currentThread().isInterrupted()){Cursor t=db.query("media_tombstones",new String[]{"source_uri","content_hash"},"content_hash<>'' AND content_hash NOT LIKE 'mh1_%'",null,null,null,"deleted_at ASC","128");ArrayList<String> sources=new ArrayList<>();ArrayList<String> hashes=new ArrayList<>();try{while(t.moveToNext()){sources.add(t.getString(0));hashes.add(t.getString(1));}}finally{t.close();}if(sources.isEmpty())break;for(int i=0;i<sources.size();i++){ContentValues x=new ContentValues();x.put("content_hash",MetadataPrivacy.contentHash(appContext,hashes.get(i)));db.update("media_tombstones",x,"source_uri=?",new String[]{sources.get(i)});}if(sources.size()<128)break;try{Thread.sleep(12L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
        return countLegacyContentHashes();
    }
    public int countLegacyContentHashes(){int n=0;Cursor c=getReadableDatabase().query("media",new String[]{"content_hash"},"content_hash<>''",null,null,null,null);try{while(c.moveToNext())if(!MetadataPrivacy.isProtectedContentHash(c.getString(0)))n++;}finally{c.close();}Cursor t=getReadableDatabase().query("media_tombstones",new String[]{"content_hash"},"content_hash<>''",null,null,null,null);try{while(t.moveToNext())if(!MetadataPrivacy.isProtectedContentHash(t.getString(0)))n++;}finally{t.close();}return n;}

    /** v0.5.23: replaces path/URI-bearing source identifiers with stable HMAC tokens. */
    public int migrateSensitiveSourceMetadata(){
        SQLiteDatabase db=getWritableDatabase();int changed=0,remaining=0;db.beginTransaction();
        try{changed+=migrateSourceTable(db,"media",true);changed+=migrateSourceTable(db,"bank_seen",false);changed+=migrateSourceTable(db,"media_tombstones",false);changed+=scrubRecoveryJobPaths(db);changed+=encryptCaptureAttemptPaths(db);changed+=encryptGalleryUris(db);db.setTransactionSuccessful();}
        finally{db.endTransaction();}
        remaining=countSensitiveSourceMetadata();
        appContext.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("metadata_source_migrated",changed).putInt("metadata_source_remaining",remaining).putLong("metadata_source_migration_at",System.currentTimeMillis()).apply();
        return remaining;
    }
    private int migrateSourceTable(SQLiteDatabase db,String table,boolean keepRow){
        int changed=0;Cursor c=db.query(table,new String[]{"source_uri"},null,null,null,null,null);ArrayList<String> old=new ArrayList<>();try{while(c.moveToNext()){String raw=c.getString(0);if(MetadataPrivacy.isSensitiveSource(raw))old.add(raw);}}finally{c.close();}
        for(String raw:old){String key=sourceKey(raw);if(key.equals(raw))continue;ContentValues v=new ContentValues();v.put("source_uri",key);int n=0;try{n=db.updateWithOnConflict(table,v,"source_uri=?",new String[]{raw},SQLiteDatabase.CONFLICT_IGNORE);}catch(Throwable ignored){}
            if(n>0){changed++;continue;}
            if(!keepRow){try{if(db.delete(table,"source_uri=?",new String[]{raw})>0)changed++;}catch(Throwable ignored){}}
            else{String orphan=MetadataPrivacy.token(appContext,"src","orphan|"+raw);v.put("source_uri",orphan);try{if(db.update(table,v,"source_uri=?",new String[]{raw})>0)changed++;}catch(Throwable ignored){}}
        }return changed;
    }
    private int encryptGalleryUris(SQLiteDatabase db){int changed=0;Cursor c=db.query("media",new String[]{"id","gallery_uri"},"gallery_uri<>''",null,null,null,null);ArrayList<Long> ids=new ArrayList<>();ArrayList<String> vals=new ArrayList<>();try{while(c.moveToNext()){ids.add(c.getLong(0));vals.add(c.getString(1));}}finally{c.close();}for(int i=0;i<ids.size();i++){String raw=vals.get(i);if(raw==null||raw.isEmpty()||raw.startsWith("enc1:"))continue;String sealed=MetadataPrivacy.seal(appContext,raw);if(sealed.isEmpty())continue;ContentValues v=new ContentValues();v.put("gallery_uri",sealed);if(db.update("media",v,"id=?",new String[]{String.valueOf(ids.get(i))})>0)changed++;}return changed;}
    private int scrubRecoveryJobPaths(SQLiteDatabase db){int changed=0;Cursor c=db.query("recovery_jobs",new String[]{"id","local_path"},"local_path<>''",null,null,null,null);ArrayList<Long> ids=new ArrayList<>();ArrayList<String> paths=new ArrayList<>();try{while(c.moveToNext()){ids.add(c.getLong(0));paths.add(c.getString(1));}}finally{c.close();}for(int i=0;i<ids.size();i++){String raw=paths.get(i);if(raw==null||raw.isEmpty()||raw.startsWith("path_"))continue;ContentValues v=new ContentValues();v.put("local_path",MetadataPrivacy.token(appContext,"path",raw));if(db.update("recovery_jobs",v,"id=?",new String[]{String.valueOf(ids.get(i))})>0)changed++;}return changed;}
    private int encryptCaptureAttemptPaths(SQLiteDatabase db){int changed=0;Cursor c=db.query("capture_attempts",new String[]{"id","local_path"},"local_path<>''",null,null,null,null);ArrayList<Long> ids=new ArrayList<>();ArrayList<String> paths=new ArrayList<>();try{while(c.moveToNext()){ids.add(c.getLong(0));paths.add(c.getString(1));}}finally{c.close();}for(int i=0;i<ids.size();i++){String raw=paths.get(i);if(raw==null||raw.isEmpty()||raw.startsWith("enc1:"))continue;String sealed=MetadataPrivacy.seal(appContext,raw);if(sealed.isEmpty())continue;ContentValues v=new ContentValues();v.put("local_path",sealed);if(db.update("capture_attempts",v,"id=?",new String[]{String.valueOf(ids.get(i))})>0)changed++;}return changed;}
    public int migrateMessageFingerprintsToHmac(){
        synchronized(MESSAGE_INSERT_LOCK){SQLiteDatabase db=getWritableDatabase();int failures=0,migrated=0;Cursor c=db.query("messages",new String[]{"id","package_name","conversation","sender","body","timestamp","is_group","fingerprint","identity_slot"},"fingerprint IS NULL OR fingerprint NOT LIKE 'msgfp_%'",null,null,null,"id ASC");
            try{while(c.moveToNext()){long id=c.getLong(0);String pkg=c.getString(1),conv=crypto.decrypt(c.getBlob(2)),sender=crypto.decrypt(c.getBlob(3)),body=crypto.decrypt(c.getBlob(4));long ts=c.getLong(5);boolean group=c.getInt(6)!=0;int slot=Math.max(1,c.getInt(8));if(conv==null||sender==null||body==null){failures++;continue;}String fp;try{fp=messageFingerprint(pkg,conv,sender,body,ts,group,slot);}catch(Throwable t){failures++;continue;}ContentValues v=new ContentValues();v.put("fingerprint",fp);try{if(db.update("messages",v,"id=?",new String[]{String.valueOf(id)})==1)migrated++;else failures++;}catch(Throwable t){failures++;}}}finally{c.close();}
            appContext.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putInt("message_fingerprint_migrated",migrated).putInt("message_fingerprint_failures",failures).putLong("message_fingerprint_migration_at",System.currentTimeMillis()).apply();return failures;}
    }
    public int countLegacyMessageFingerprints(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE fingerprint IS NULL OR fingerprint NOT LIKE 'msgfp_%'",null);try{return c.moveToFirst()?c.getInt(0):0;}finally{c.close();}}

    public int countSensitiveSourceMetadata(){int n=0;n+=countSensitiveIn("media");n+=countSensitiveIn("bank_seen");n+=countSensitiveIn("media_tombstones");Cursor g=getReadableDatabase().query("media",new String[]{"gallery_uri"},"gallery_uri<>''",null,null,null,null);try{while(g.moveToNext()){String x=g.getString(0);if(x!=null&&!x.isEmpty()&&!x.startsWith("enc1:"))n++;}}finally{g.close();}Cursor r=getReadableDatabase().query("recovery_jobs",new String[]{"local_path"},"local_path<>''",null,null,null,null);try{while(r.moveToNext()){String x=r.getString(0);if(x!=null&&!x.isEmpty()&&!x.startsWith("path_"))n++;}}finally{r.close();}Cursor a=getReadableDatabase().query("capture_attempts",new String[]{"local_path"},"local_path<>''",null,null,null,null);try{while(a.moveToNext()){String x=a.getString(0);if(x!=null&&!x.isEmpty()&&!x.startsWith("enc1:"))n++;}}finally{a.close();}return n;}
    private int countSensitiveIn(String table){int n=0;Cursor c=getReadableDatabase().query(table,new String[]{"source_uri"},null,null,null,null,null);try{while(c.moveToNext())if(MetadataPrivacy.isSensitiveSource(c.getString(0)))n++;}finally{c.close();}return n;}
    public boolean isMediaPathReferenced(String path){if(path==null||path.isEmpty())return false;Cursor c=getReadableDatabase().query("media",new String[]{"id"},"local_path=?",new String[]{path},null,null,null,"1");try{return c.moveToFirst();}finally{c.close();}}

    public void clearMessages(){ SQLiteDatabase w=getWritableDatabase();w.beginTransaction();int n=0;try{w.delete("media_message_links",null,null);ContentValues u=new ContentValues();u.put("linked_message_id",0);w.update("media",u,null,null);n=w.delete("messages",null,null);w.setTransactionSuccessful();}finally{w.endTransaction();}if(n>0)notifyUi("message"); }
    public void clearMedia(){ SQLiteDatabase w=getWritableDatabase();w.delete("media_message_links",null,null);if(w.delete("media",null,null)>0)notifyUi("media"); }
    public void clearBankSeen(){ getWritableDatabase().delete("bank_seen",null,null); }
    public void clearEvents(){ getWritableDatabase().delete("event_log",null,null); }
}
