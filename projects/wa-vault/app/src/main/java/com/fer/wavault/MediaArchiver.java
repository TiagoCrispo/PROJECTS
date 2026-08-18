package com.fer.wavault;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MediaArchiver {
    private MediaArchiver() {}

    private static final long BANK_RETENTION_MS = 30_000L;
    private static final long BANK_NOTIFICATION_WINDOW_MS = 15_000L;
    private static final int MAX_INDEX_DOCS = 12000;
    private static final int MAX_HOT_DIRS = 96;
    private static final ConcurrentHashMap<Long, Boolean> pendingMonitors = new ConcurrentHashMap<>();
    /** One shared monitor for all 30s audio quarantines. Avoids one sleeping Thread per message/audio. */
    private static final ScheduledExecutorService PENDING_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wa-vault-pending-monitor");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private static final AtomicBoolean pendingTickerStarted = new AtomicBoolean(false);
    private static final AtomicBoolean pendingResumeQueued = new AtomicBoolean(false);

    public static int scanAll(Context context) { return scanAll(context, 0, 0L); }

    public static int scanAll(Context context, long linkedMessageId, long notificationTime) {
        VaultDb db = new VaultDb(context.getApplicationContext());
        int n = 0;
        try { n += scanCollection(context, db, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", linkedMessageId, notificationTime); } catch (Exception ignored) {}
        try { n += scanCollection(context, db, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", linkedMessageId, notificationTime); } catch (Exception ignored) {}
        if (!hasVoiceBank(context)) {
            try { n += scanCollection(context, db, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", linkedMessageId, notificationTime); } catch (Exception ignored) {}
        }
        try { n += scanVoiceBankFast(context, db, linkedMessageId, notificationTime); } catch (Exception ignored) {}
        return n;
    }

    /** Tiny event-driven MediaStore scan used after a manual photo/video download. */
    public static int scanRecentDownloadedMedia(Context context, long linkedMessageId, long notificationTime) {
        return scanRecentDownloadedMedia(context,linkedMessageId,notificationTime,"");
    }
    public static int scanRecentDownloadedMedia(Context context, long linkedMessageId, long notificationTime, String expectedType) {
        if (context == null) return 0;
        VaultDb db = new VaultDb(context.getApplicationContext());
        int n = 0;String t=expectedType==null?"":expectedType;
        if(t.isEmpty()||"image".equals(t)) try { n += scanCollection(context, db, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image", linkedMessageId, notificationTime, 36, true); } catch (Throwable ignored) {}
        if(t.isEmpty()||"video".equals(t)) try { n += scanCollection(context, db, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", linkedMessageId, notificationTime, 24, true); } catch (Throwable ignored) {}
        if(t.isEmpty()||"document".equals(t)) try { n += scanCollection(context, db, MediaStore.Files.getContentUri("external"), "document", linkedMessageId, notificationTime, 80, true); } catch (Throwable ignored) {}
        return n;
    }

    /** Small, time-bounded audio MediaStore fallback used only after an audio notification. */
    public static int scanRecentAudio(Context context, long linkedMessageId, long notificationTime) {
        if (context == null || linkedMessageId <= 0) return 0;
        try {
            return scanCollection(context, new VaultDb(context.getApplicationContext()), MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", linkedMessageId, notificationTime, 72, true);
        } catch (Throwable ignored) { return 0; }
    }

    /** Maximum-recall audio rescue used only while an audio notification is hot. */
    public static int scanRecentAudioAggressive(Context context, long linkedMessageId, long notificationTime) {
        if (context == null || linkedMessageId <= 0) return 0;
        VaultDb db=new VaultDb(context.getApplicationContext()); int n=0;
        try { n += scanCollection(context, db, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", linkedMessageId, notificationTime, 220, true); } catch(Throwable ignored){}
        // Several Android/OEM builds index WhatsApp .opus only in MediaStore.Files, not Audio.
        try { n += scanCollection(context, db, MediaStore.Files.getContentUri("external"), "audio", linkedMessageId, notificationTime, 420, true); } catch(Throwable ignored){}
        try { n += scanVoiceBankFast(context, db, linkedMessageId, notificationTime); } catch(Throwable ignored){}
        return n;
    }

    /** Captures an exact MediaStore item, including WhatsApp documents exposed through Files. */
    public static long captureMediaStoreAnyUri(Context context, Uri uri, long linkedMessageId, long notificationTime) {
        if (context == null || uri == null) return -1L;
        String mime = "",name="";
        try { mime = context.getContentResolver().getType(uri); } catch (Throwable ignored) {}
        Cursor c=null;
        try {
            c=context.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.DISPLAY_NAME},null,null,null);
            if(c!=null&&c.moveToFirst()){String m=c.getString(0);if(m!=null&&!m.isEmpty())mime=m;name=c.getString(1);}
        } catch(Throwable ignored){} finally {if(c!=null)c.close();}
        String low=mime==null?"":mime.toLowerCase(Locale.ROOT);
        if(low.startsWith("image/"))return captureMediaStoreUri(context,uri,"image",linkedMessageId,notificationTime);
        if(low.startsWith("video/"))return captureMediaStoreUri(context,uri,"video",linkedMessageId,notificationTime);
        if(isDocumentAttachment(name,mime))return captureMediaStoreUri(context,uri,"document",linkedMessageId,notificationTime);
        return -1L;
    }

    /** Captures the exact MediaStore item reported by ContentObserver. */
    public static long captureMediaStoreUri(Context context, Uri uri, String type, long linkedMessageId, long notificationTime) {
        if (context == null || uri == null || type == null || type.isEmpty()) return -1L;
        ContentResolver cr = context.getContentResolver();
        String[] proj;
        boolean modern=Build.VERSION.SDK_INT>=29;
        if (modern) {
            proj = new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING};
        } else {
            proj = new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED};
        }
        Cursor c = null;
        try {
            c = cr.query(uri, proj, null, null, null);
            if (c == null || !c.moveToFirst()) return -1L;
            String name = c.getString(0);
            String mime = c.getString(1);
            long size = c.getLong(2);
            String path = c.getString(3);
            long sourceTime = c.getLong(4) * 1000L;
            String owner = modern && c.getColumnCount()>5 ? c.getString(5) : "";
            int pending = modern && c.getColumnCount()>6 && !c.isNull(6) ? c.getInt(6) : 0;
            if (pending != 0) return -1L; // retry after MediaStore finalizes the item
            if (!isWhatsAppMedia(path,owner,name)) return -1L;
            if (name == null || name.isEmpty()) name = type + "_" + System.nanoTime();
            String low=name.toLowerCase(Locale.ROOT);
            if(low.contains("view once")||low.contains("ver una vez"))return -1L;
            if(size<=0||size>600L*1024L*1024L)return -1L;
            if(MediaLimits.mediaTooLarge(context,type,size)){MediaLimits.recordLimit(context,type,linkedMessageId,sourceTime,size,"MEDIASTORE_EXACT");return -1L;}
            String mimeLow=mime==null?"":mime.toLowerCase(Locale.ROOT);
            if("image".equals(type)&&!mimeLow.isEmpty()&&!mimeLow.startsWith("image/"))return -1L;
            if("video".equals(type)&&!mimeLow.isEmpty()&&!mimeLow.startsWith("video/"))return -1L;
            if("document".equals(type)&&!isDocumentAttachment(name,mime))return -1L;

            VaultDb db=new VaultDb(context.getApplicationContext());
            String uriString=uri.toString();
            String existingPath=db.findMediaPath(uriString);
            if(existingPath!=null){
                File existing=new File(existingPath);
                if(existing.exists()&&existing.length()>0&&(MediaCrypto.isEncrypted(existing)||isUsable(existing,type))){
                    long existingId=db.findMediaId(uriString);
                    if(existingId>0&&linkedMessageId>0)db.linkMediaToMessageIfFree(existingId,linkedMessageId);
                    try { db.logEvent("DOWNLOAD_SKIPPED_ALREADY_EXISTS", type+" · source="+uriString, linkedMessageId, existingId); } catch(Throwable ignored) {}
                    return existingId;
                }
                try{existing.delete();}catch(Throwable ignored){}db.deleteMediaBySource(uriString);
            }

            String safe=name.replaceAll("[^a-zA-Z0-9._-]","_");
            Context app=context.getApplicationContext();
            File part=new File(stagingDir(app),VaultFileNames.stagingName("part_",type));
            try { db.logEvent("DOWNLOAD_STARTED", type+" · source="+uriString, linkedMessageId, 0L); } catch(Throwable ignored) {}
            long written=copyUriLimited(context,uri,part,type,linkedMessageId,sourceTime,"MEDIASTORE_EXACT");
            if(written<=0||(size>0&&written<Math.max(1024,size-4096))||!isUsable(part,type)){try{part.delete();}catch(Throwable ignored){}return -1L;}
            File staged=completedPartToReady(app,part,type,linkedMessageId,sourceTime,VaultDb.RETENTION_NORMAL,0L,"MEDIASTORE_EXACT",safe);
            if(staged==null){try{part.delete();}catch(Throwable ignored){}return -1L;}
            long id=commitReadyStaged(app,staged,type,linkedMessageId,sourceTime,uriString,"MEDIASTORE_EXACT",safe);
            if(id>0){
                db.logEvent("MEDIASTORE_EXACT",type+" · "+name,linkedMessageId,id);
                db.logEvent("DOWNLOAD_COMPLETED",type+" · bytes="+written,linkedMessageId,id);
                if(linkedMessageId>0)db.consumePendingManualMedia(linkedMessageId);
            }
            return id;
        } catch (Throwable t) {
            return -1L;
        } finally { if(c!=null)c.close(); }
    }

    private static int scanCollection(Context context, VaultDb db, Uri base, String type, long linkedMessageId, long notificationTime) {
        return scanCollection(context, db, base, type, linkedMessageId, notificationTime, 1500, false);
    }

    private static int scanCollection(Context context, VaultDb db, Uri base, String type, long linkedMessageId, long notificationTime, int maxRows, boolean trustedManualEvent) {
        int copied = 0;
        ContentResolver cr = context.getContentResolver();
        boolean modern=Build.VERSION.SDK_INT>=29;
        String[] proj;
        if (modern) {
            proj = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING};
        } else {
            proj = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED};
        }
        Cursor c = null;
        try {
            c = cr.query(base, proj, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c == null) return 0;
            int seen = 0;
            long now=System.currentTimeMillis();
            while (c.moveToNext() && seen < Math.max(1,maxRows)) {
                seen++;
                long id = c.getLong(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                String path = c.getString(4);
                long sourceTime = c.getLong(5) * 1000L;
                String owner = modern && c.getColumnCount()>6 ? c.getString(6) : "";
                int pending = modern && c.getColumnCount()>7 && !c.isNull(7) ? c.getInt(7) : 0;
                if(pending!=0)continue;
                if (!isWhatsAppMedia(path,owner,name)) continue;
                if (trustedManualEvent && notificationTime>0 && sourceTime>0) {
                    // Manual download must have been added after the message was armed. This
                    // prevents an unrelated older WhatsApp photo from being linked on a null URI event.
                    if(sourceTime < notificationTime-5_000L || sourceTime > now+10_000L) continue;
                }
                if (name == null) name = type + "_" + id;
                String low = name.toLowerCase(Locale.ROOT);
                if (low.contains("view once") || low.contains("ver una vez")) continue;
                if ("audio".equals(type) && !isAudioDocument(name,mime)) continue;
                if ("document".equals(type) && !isDocumentAttachment(name,mime)) continue;
                if (size <= 0 || size > 600L * 1024L * 1024L) continue;
                if(MediaLimits.mediaTooLarge(context,type,size)){MediaLimits.recordLimit(context,type,linkedMessageId,sourceTime,size,"MEDIASTORE_SCAN");continue;}

                Uri uri = ContentUris.withAppendedId(base, id);
                String uriString = uri.toString();
                String existingPath = db.findMediaPath(uriString);
                if (existingPath != null) {
                    File existing = new File(existingPath);
                    if (existing.exists() && existing.length() > 0 && (MediaCrypto.isEncrypted(existing) || isUsable(existing, type))) {
                        long existingId=db.findMediaId(uriString);if(existingId>0&&linkedMessageId>0)db.linkMediaToMessageIfFree(existingId,linkedMessageId);
                        continue;
                    }
                    try { existing.delete(); } catch (Throwable ignored) {}
                    db.deleteMediaBySource(uriString);
                }

                String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                Context app=context.getApplicationContext();
                File part = new File(stagingDir(app),VaultFileNames.stagingName("part_",type));
                long written = copyUriLimited(context,uri,part,type,linkedMessageId,sourceTime,"MEDIASTORE_SCAN");
                if (written <= 0 || (size > 0 && written < Math.max(1024, size - 4096)) || !isUsable(part, type)) {
                    try{part.delete();}catch(Throwable ignored){}
                    continue;
                }

                long link = 0;
                if (linkedMessageId > 0 && trustedManualEvent) link = linkedMessageId;
                else if (linkedMessageId > 0 && notificationTime > 0 && sourceTime > 0 && Math.abs(sourceTime - notificationTime) <= 15000L) link = linkedMessageId;
                String captureOrigin=trustedManualEvent?"MEDIASTORE_EVENT":"MEDIASTORE_SCAN";
                File staged=completedPartToReady(app,part,type,link,sourceTime,VaultDb.RETENTION_NORMAL,0L,captureOrigin,safe);
                if(staged==null){try{part.delete();}catch(Throwable ignored){}continue;}
                long inserted=commitReadyStaged(app,staged,type,link,sourceTime,uriString,captureOrigin,safe);
                if(inserted>0){copied++;if(linkedMessageId>0)db.consumePendingManualMedia(linkedMessageId);if(trustedManualEvent&&linkedMessageId>0)break;}
            }
        } catch (SecurityException ignored) {
            return copied;
        } finally {
            if (c != null) c.close();
        }
        return copied;
    }

    private static boolean isWhatsAppMedia(String relativePath, String ownerPackage, String name) {
        String p=relativePath==null?"":relativePath.toLowerCase(Locale.ROOT);
        if(p.contains("whatsapp stickers")||p.contains("/stickers/")||p.endsWith("/stickers"))return false;
        String o=ownerPackage==null?"":ownerPackage.toLowerCase(Locale.ROOT);
        if(p.contains("whatsapp")||o.equals("com.whatsapp")||o.equals("com.whatsapp.w4b"))return true;
        // Last-resort naming convention only when it is unmistakably WhatsApp-generated.
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return n.matches(".*-wa[0-9]{4,}.*") || n.startsWith("ptt-");
    }

    /** Reuses a previously granted WhatsApp tree automatically; no picker needed again. */
    public static boolean adoptPersistedWhatsAppTree(Context context) {
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        String current = MetadataPrivacy.open(context,sp.getString("voice_bank_tree_uri", ""));
        if (current != null && !current.trim().isEmpty()) return true;
        try {
            List<UriPermission> grants = context.getContentResolver().getPersistedUriPermissions();
            if (grants == null) return false;
            Uri best = null;
            for (UriPermission grant : grants) {
                if (grant == null || !grant.isReadPermission() || grant.getUri() == null) continue;
                String decoded = Uri.decode(grant.getUri().toString()).toLowerCase(Locale.ROOT);
                if (!decoded.contains("com.whatsapp") && !decoded.contains("whatsapp")) continue;
                if (best == null || decoded.contains("/whatsapp/media") || decoded.contains("whatsapp%2fmedia") || decoded.contains("whatsapp voice notes")) {
                    best = grant.getUri();
                    if (decoded.contains("/whatsapp/media")) break;
                }
            }
            if (best != null) {
                sp.edit().putString("voice_bank_tree_uri", MetadataPrivacy.seal(context,best.toString()))
                        .putLong("voice_bank_index_at", 0L)
                        .putString("voice_bank_hot_dirs", "")
                        .apply();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean hasVoiceBank(Context context) {
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        String raw = MetadataPrivacy.open(context,sp.getString("voice_bank_tree_uri", ""));
        if (raw == null || raw.trim().isEmpty()) {
            adoptPersistedWhatsAppTree(context);
            raw = MetadataPrivacy.open(context,sp.getString("voice_bank_tree_uri", ""));
        }
        return raw != null && !raw.trim().isEmpty();
    }

    /** Full manual preparation: index the directory tree, but do not import old audio history. */
    public static int prepareVoiceBank(Context context) {
        if (!hasVoiceBank(context)) return 0;
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        int dirs = refreshVoiceBankIndex(context);
        VaultDb db = new VaultDb(context.getApplicationContext());
        if (!sp.getBoolean("voice_bank_v2_baselined", false)) {
            baselineCurrentVoiceFiles(context, db);
            sp.edit().putBoolean("voice_bank_v2_baselined", true).putLong("voice_bank_armed_at", System.currentTimeMillis()).apply();
        }
        sp.edit().putInt("voice_bank_indexed_dirs", dirs).apply();
        return 0;
    }

    /** Kept for the existing UI button. */
    public static int scanVoiceBank(Context context) { return prepareVoiceBank(context); }

    public static int scanVoiceBankFast(Context context, long linkedMessageId, long notificationTime) {
        return scanVoiceBankFast(context, new VaultDb(context.getApplicationContext()), linkedMessageId, notificationTime);
    }

    private static int scanVoiceBankFast(Context context, VaultDb db, long linkedMessageId, long notificationTime) {
        return scanVoiceBankFast(context, db, linkedMessageId, notificationTime, true);
    }

    private static int scanVoiceBankFast(Context context, VaultDb db, long linkedMessageId, long notificationTime, boolean allowForcedRefresh) {
        if (!hasVoiceBank(context)) return 0;
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        if (!sp.getBoolean("voice_bank_v2_baselined", false)) {
            refreshVoiceBankIndex(context);
            baselineCurrentVoiceFiles(context, db);
            sp.edit().putBoolean("voice_bank_v2_baselined", true).putLong("voice_bank_armed_at", System.currentTimeMillis()).apply();
            return 0;
        }
        long armedAt = sp.getLong("voice_bank_armed_at", 0L);
        if (armedAt == 0L) {
            armedAt = System.currentTimeMillis();
            sp.edit().putLong("voice_bank_armed_at", armedAt).apply();
        }

        String cached = MetadataPrivacy.open(context,sp.getString("voice_bank_hot_dirs", ""));
        long indexAt = sp.getLong("voice_bank_index_at", 0L);
        long nowForIndex = System.currentTimeMillis();
        // A fresh WhatsApp notification can create a new weekly/monthly Voice Notes
        // subdirectory. Re-index once per notification so the hot-directory cache cannot
        // miss the folder that was just created.
        long lastEventIndexed = sp.getLong("voice_bank_last_event_index", 0L);
        if (allowForcedRefresh && notificationTime > 0L && lastEventIndexed != notificationTime) {
            refreshVoiceBankIndex(context);
            sp.edit().putLong("voice_bank_last_event_index", notificationTime).apply();
            cached = MetadataPrivacy.open(context,sp.getString("voice_bank_hot_dirs", ""));
        } else if (cached == null || cached.isEmpty() || nowForIndex - indexAt > 6 * 60 * 60_000L) {
            refreshVoiceBankIndex(context);
            cached = MetadataPrivacy.open(context,sp.getString("voice_bank_hot_dirs", ""));
        }

        Uri treeUri;
        try { treeUri = Uri.parse(MetadataPrivacy.open(context,sp.getString("voice_bank_tree_uri", ""))); } catch (Throwable t) { return 0; }
        ContentResolver cr = context.getContentResolver();
        int copied = 0;
        List<String> dirs = splitDirs(cached);
        if (dirs.isEmpty()) {
            try { dirs.add(DocumentsContract.getTreeDocumentId(treeUri)); } catch (Throwable ignored) {}
        }

        for (String parentId : dirs) {
            if (parentId == null || parentId.isEmpty()) continue;
            Uri children;
            try { children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId); }
            catch (Throwable t) { continue; }
            Cursor c = null;
            try {
                c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                }, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long size = c.isNull(3) ? 0L : c.getLong(3);
                    long modified = c.isNull(4) ? 0L : c.getLong(4);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;
                    if (docId == null || !isAudioDocument(name, mime)) continue;
                    if (size > 300L * 1024L * 1024L) continue;

                    // Existing files were already recorded in the baseline. Do not trust
                    // COLUMN_LAST_MODIFIED for new WhatsApp voice notes: some document
                    // providers report 0 or stale timestamps. Source identity is the reliable gate.

                    Uri docUri;
                    try { docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId); }
                    catch (Throwable t) { continue; }
                    String source = "bank:" + docUri;
                    if (db.bankSourceKnown(source) || db.findMediaId(source) > 0) continue;

                    long now = System.currentTimeMillis();
                    // The requested 30-second quarantine starts when WA Vault actually sees
                    // the binary file, not when the notification first arrived. This prevents
                    // a late WhatsApp download/playback from expiring before it can be copied.
                    long expiresAt = now + BANK_RETENTION_MS;

                    String safeName = (name == null || name.trim().isEmpty() ? "voice_note_" + now + ".opus" : name).replaceAll("[^a-zA-Z0-9._-]", "_");
                    Context app=context.getApplicationContext();
                    File part = new File(stagingDir(app), VaultFileNames.stagingName("part_", "audio"));
                    long written = copyUri(context, docUri, part);
                    if (written <= 256 || (size > 0 && written < Math.max(256, size - 2048)) || !isUsable(part, "audio")) {
                        part.delete();
                        continue;
                    }

                    long sourceTime = modified > 0 ? modified : now;
                    long link = 0L;
                    if (linkedMessageId > 0 && notificationTime > 0 && Math.abs(sourceTime - notificationTime) <= BANK_NOTIFICATION_WINDOW_MS) link = linkedMessageId;
                    File staged=completedPartToReady(app,part,"audio",link,sourceTime,VaultDb.RETENTION_PENDING,expiresAt,"VOICE_BANK_SAF",safeName);
                    if(staged==null){try{part.delete();}catch(Throwable ignored){}continue;}
                    long mediaId = commitStagedSecure(app, staged, "audio", link, sourceTime, source, "VOICE_BANK_SAF", safeName, VaultDb.RETENTION_PENDING, expiresAt);
                    if (mediaId <= 0) {
                        staged.delete();
                        continue;
                    }
                    db.markBankSourceSeen(source, VaultDb.RETENTION_PENDING);
                    startPendingMonitor(context.getApplicationContext(), mediaId);
                    copied++;
                    sp.edit().putString("voice_bank_last_file", "audio detectado").putLong("voice_bank_last_file_at", now).apply();
                }
            } catch (SecurityException se) {
                sp.edit().putString("voice_bank_last_error", "Permiso de carpeta perdido").apply();
                return copied;
            } catch (Throwable t) {
                sp.edit().putString("voice_bank_last_error", "Error leyendo la carpeta: " + t.getClass().getSimpleName()).apply();
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) {}
            }
        }

        long now = System.currentTimeMillis();
        sp.edit().putLong("voice_bank_last_scan", now).putInt("voice_bank_last_copied", copied).putString("voice_bank_last_error", "").apply();

        // WhatsApp can create the Voice Notes subfolder AFTER posting the notification.
        // During a live notification watch, force a fresh tree discovery roughly every
        // 650 ms when nothing was found, then immediately rescan the newly discovered dirs.
        if (copied == 0 && allowForcedRefresh && notificationTime > 0L) {
            long lastForced = sp.getLong("voice_bank_last_forced_index", 0L);
            if (now - lastForced >= 650L) {
                refreshVoiceBankIndex(context);
                sp.edit().putLong("voice_bank_last_forced_index", now).apply();
                copied += scanVoiceBankFast(context, db, linkedMessageId, notificationTime, false);
            }
        } else if (copied == 0 && now - sp.getLong("voice_bank_index_at", 0L) > 60_000L) {
            refreshVoiceBankIndex(context);
        }
        return copied;
    }

    /** Recursively indexes the user-selected tree and remembers only the most active dirs. */
    public static int refreshVoiceBankIndex(Context context) {
        if (!hasVoiceBank(context)) return 0;
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        Uri treeUri;
        try { treeUri = Uri.parse(MetadataPrivacy.open(context,sp.getString("voice_bank_tree_uri", ""))); } catch (Throwable t) { return 0; }
        String rootId;
        try { rootId = DocumentsContract.getTreeDocumentId(treeUri); } catch (Throwable t) { return 0; }

        ContentResolver cr = context.getContentResolver();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(rootId);
        Map<String, Long> score = new HashMap<>();
        score.put(rootId, Long.MAX_VALUE / 4); // always keep root available
        int visited = 0;
        int audioFiles = 0;
        int voiceNoteFiles = 0;
        int whatsappAudioFiles = 0;
        long newestAudioModified = 0L;
        long newestAudioSize = 0L;
        String newestAudioName = "";
        while (!q.isEmpty() && visited < MAX_INDEX_DOCS) {
            String parent = q.removeFirst();
            Uri children;
            try { children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent); }
            catch (Throwable t) { continue; }
            Cursor c = null;
            try {
                c = cr.query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                }, null, null, null);
                if (c == null) continue;
                while (c.moveToNext() && visited < MAX_INDEX_DOCS) {
                    visited++;
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long modified = c.isNull(3) ? 0L : c.getLong(3);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (id != null && shouldDescendAudioDir(rootId, parent, id, name)) {
                            q.addLast(id);
                            score.putIfAbsent(id, 0L);
                        }
                    } else if (isAudioDocument(name, mime)) {
                        audioFiles++;
                        String lid = id == null ? "" : id.toLowerCase(Locale.ROOT);
                        if (lid.contains("whatsapp voice notes")) voiceNoteFiles++;
                        if (lid.contains("whatsapp audio")) whatsappAudioFiles++;
                        if (modified >= newestAudioModified) {
                            newestAudioModified = modified;
                            newestAudioName = name == null ? "(sin nombre)" : name;
                            // size is probed later when the document is opened; leave 0 here.
                        }
                        long old = score.containsKey(parent) ? score.get(parent) : 0L;
                        if (modified > old) score.put(parent, modified);
                    }
                }
            } catch (Throwable ignored) {
            } finally { if (c != null) try { c.close(); } catch (Throwable ignored) {} }
        }

        List<Map.Entry<String, Long>> entries = new ArrayList<>(score.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Long>>() {
            @Override public int compare(Map.Entry<String, Long> a, Map.Entry<String, Long> b) {
                int t = Long.compare(b.getValue(), a.getValue());
                if (t != 0) return t;
                return b.getKey().compareTo(a.getKey());
            }
        });
        StringBuilder joined = new StringBuilder();
        int kept = 0;
        for (Map.Entry<String, Long> e : entries) {
            if (kept >= MAX_HOT_DIRS) break;
            if (joined.length() > 0) joined.append('\n');
            joined.append(e.getKey());
            kept++;
        }
        String treeId = rootId == null ? "" : rootId;
        SharedPreferences.Editor ed = sp.edit()
                .putString("voice_bank_hot_dirs", MetadataPrivacy.seal(context,joined.toString()))
                .putLong("voice_bank_index_at", System.currentTimeMillis())
                .putInt("voice_bank_indexed_dirs", score.size())
                .putInt("voice_bank_probe_docs", visited)
                .putInt("voice_bank_probe_audio", audioFiles)
                .putInt("voice_bank_probe_voice_notes", voiceNoteFiles)
                .putInt("voice_bank_probe_whatsapp_audio", whatsappAudioFiles)
                .putString("voice_bank_probe_newest_name", newestAudioName==null||newestAudioName.isEmpty()?"":"audio detectado")
                .putLong("voice_bank_probe_newest_modified", newestAudioModified)
                .putString("voice_bank_probe_tree_id", treeId);
        if (audioFiles == 0) {
            ed.putString("voice_bank_last_error", "La carpeta elegida no expone ningún audio. Elige Android/media/com.whatsapp/WhatsApp/Media (la carpeta Media completa) y revisa WhatsApp > Ajustes > Chats > Visibilidad de archivos multimedia.");
        } else {
            ed.putString("voice_bank_last_error", "");
        }
        ed.apply();
        return score.size();
    }

    /** Runs a full tree probe and stores human-readable diagnostics in SharedPreferences. */
    public static int probeVoiceBank(Context context) {
        if (!hasVoiceBank(context)) return 0;
        return refreshVoiceBankIndex(context);
    }

    private static int baselineCurrentVoiceFiles(Context context, VaultDb db) {
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        Uri treeUri;
        try { treeUri = Uri.parse(MetadataPrivacy.open(context,sp.getString("voice_bank_tree_uri", ""))); } catch (Throwable t) { return 0; }
        List<String> dirs = splitDirs(MetadataPrivacy.open(context,sp.getString("voice_bank_hot_dirs", "")));
        if (dirs.isEmpty()) {
            try { dirs.add(DocumentsContract.getTreeDocumentId(treeUri)); } catch (Throwable ignored) {}
        }
        int marked = 0;
        for (String parentId : dirs) {
            Cursor c = null;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
                c = context.getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                }, null,null,null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String docId=c.getString(0), name=c.getString(1), mime=c.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) || docId == null || !isAudioDocument(name,mime)) continue;
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    String source = "bank:" + docUri;
                    if (!db.bankSourceKnown(source)) { db.markBankSourceSeen(source, VaultDb.RETENTION_NORMAL); marked++; }
                }
            } catch (Throwable ignored) {
            } finally { if(c!=null) try{c.close();}catch(Throwable ignored){} }
        }
        sp.edit().putInt("voice_bank_baseline_files", marked).putLong("voice_bank_baseline_at", System.currentTimeMillis()).apply();
        return marked;
    }

    private static List<String> splitDirs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split("\\n")) if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        return out;
    }


    /**
     * Fast direct-path ingest used by DirectVoiceWatcher/FileObserver.
     * This bypasses SAF directory crawling entirely once Android grants direct shared-storage access.
     */
    public static long ingestDirectAudio(Context context, File src, long linkedMessageId, long notificationTime) {
        if (context == null || src == null || !src.exists() || !src.isFile() || src.length() <= 256) return -1L;
        Context app = context.getApplicationContext();
        VaultDb db = new VaultDb(app);
        String source = "direct:" + src.getAbsolutePath() + "|" + src.lastModified() + "|" + src.length();
        if (db.bankSourceKnown(source) || db.findMediaId(source) > 0) return -1L;

        long now = System.currentTimeMillis();
        long sourceTime = src.lastModified() > 0 ? src.lastModified() : now;
        long link = 0L;
        if (linkedMessageId > 0 && notificationTime > 0 && Math.abs(sourceTime - notificationTime) <= BANK_NOTIFICATION_WINDOW_MS) link = linkedMessageId;

        String name = src.getName() == null || src.getName().trim().isEmpty() ? ("voice_note_" + now + ".opus") : src.getName();
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File part = new File(stagingDir(app), VaultFileNames.stagingName("part_", "audio"));

        long written = 0L;
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[131072];
            int z;
            while ((z = in.read(buf)) > 0) { out.write(buf, 0, z); written += z; }
            out.flush();
            out.getFD().sync();
        } catch (Throwable t) {
            try { part.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        if (written <= 256 || !isUsable(part, "audio")) {
            try { part.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        long expiresAt=now+BANK_RETENTION_MS;
        File staged=completedPartToReady(app,part,"audio",link,sourceTime,VaultDb.RETENTION_PENDING,expiresAt,"DIRECT_FILEOBSERVER_AUDIO",safe);
        if(staged==null){try{part.delete();}catch(Throwable ignored){}return -1L;}
        long mediaId = commitStagedSecure(app, staged, "audio", link, sourceTime, source, "DIRECT_FILEOBSERVER_AUDIO", safe, VaultDb.RETENTION_PENDING, expiresAt);
        if (mediaId <= 0) {
            try { staged.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        db.markBankSourceSeen(source, VaultDb.RETENTION_PENDING);
        startPendingMonitor(app, mediaId);
        app.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit()
                .putString("voice_bank_last_file", "audio detectado")
                .putLong("voice_bank_last_file_at", now)
                .putString("voice_bank_last_mode", "DIRECT_FILEOBSERVER")
                .putLong("direct_watcher_last_copy_at", now)
                .apply();
        return mediaId;
    }


    /**
     * Registers an ultra-early audio copy produced by DirectVoiceWatcher while WhatsApp
     * is still writing the source file. The staged file may survive even if WhatsApp
     * unlinks the original path milliseconds later because the watcher held the file
     * descriptor open during capture.
     */
    public static long registerEarlyCapturedAudio(
            Context context,
            File staged,
            String sourcePath,
            String originalName,
            long linkedMessageId,
            long notificationTime,
            long sourceTime
    ) {
        if (context == null || staged == null || !staged.exists() || !staged.isFile() || staged.length() <= 256) return -1L;
        Context app = context.getApplicationContext();
        VaultDb db = new VaultDb(app);
        String source = "direct:" + (sourcePath == null ? "" : sourcePath) + "|" + sourceTime + "|" + staged.length();
        if (sourcePath == null || sourcePath.trim().isEmpty()) source = "direct-staged:" + staged.getName() + "|" + sourceTime + "|" + staged.length();
        if (db.bankSourceKnown(source) || db.findMediaId(source) > 0) {
            try { staged.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        if (!isUsable(staged, "audio")) {
            try { staged.delete(); } catch (Throwable ignored) {}
            return -1L;
        }

        long now = System.currentTimeMillis();
        long link = 0L;
        long effectiveSourceTime = sourceTime > 0 ? sourceTime : now;
        if (linkedMessageId > 0 && notificationTime > 0 && Math.abs(effectiveSourceTime - notificationTime) <= BANK_NOTIFICATION_WINDOW_MS) {
            link = linkedMessageId;
        }

        String name = (originalName == null || originalName.trim().isEmpty()) ? ("voice_note_" + now + ".opus") : originalName;
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        long mediaId = commitStagedSecure(app, staged, "audio", link, effectiveSourceTime, source, "EARLY_FD_CAPTURE", safe, VaultDb.RETENTION_PENDING, now + BANK_RETENTION_MS);
        if (mediaId <= 0) {
            try { staged.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        db.markBankSourceSeen(source, VaultDb.RETENTION_PENDING);
        startPendingMonitor(app, mediaId);
        app.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit()
                .putString("voice_bank_last_file", "audio detectado")
                .putLong("voice_bank_last_file_at", now)
                .putString("voice_bank_last_mode", "EARLY_FD_CAPTURE")
                .putLong("direct_watcher_last_copy_at", now)
                .putLong("direct_watcher_early_capture_at", now)
                .apply();
        return mediaId;
    }


    /**
     * Registers audio copied from a data Uri embedded directly in a WhatsApp notification.
     * The Uri itself may be temporary, so NotificationAudioCapture opens it before the
     * notification can be removed and hands us a completed private staging file.
     */
    public static long registerNotificationCapturedAudio(
            Context context,
            File staged,
            String uriString,
            String mime,
            long linkedMessageId,
            long notificationTime,
            long sourceTime
    ) {
        if (context == null || staged == null || !staged.exists() || !staged.isFile() || staged.length() <= 128) return -1L;
        Context app = context.getApplicationContext();
        VaultDb db = new VaultDb(app);
        long now = System.currentTimeMillis();
        long effectiveSourceTime = sourceTime > 0 ? sourceTime : now;
        String source = "notif:" + (uriString == null ? "" : uriString) + "|" + effectiveSourceTime;
        if (db.findMediaId(source) > 0) {
            try { staged.delete(); } catch (Throwable ignored) {}
            return -1L;
        }

        long link = 0L;
        if (linkedMessageId > 0) link = linkedMessageId;

        String rawMime = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        String ext;
        if (rawMime.contains("opus")) ext = ".opus";
        else if (rawMime.contains("ogg")) ext = ".ogg";
        else if (rawMime.contains("mpeg") || rawMime.contains("mp3")) ext = ".mp3";
        else if (rawMime.contains("mp4") || rawMime.contains("m4a") || rawMime.contains("aac")) ext = ".m4a";
        else if (rawMime.contains("amr")) ext = ".amr";
        else if (rawMime.contains("wav") || rawMime.contains("wave")) ext = ".wav";
        else ext = ".audio";
        String safe = "voice_notification_" + effectiveSourceTime + ext;

        String effectiveMime = rawMime.isEmpty() ? "audio/*" : rawMime;
        long mediaId = commitStagedSecure(app, staged, "audio", link, effectiveSourceTime, source, "NOTIFICATION_DATA_URI", safe, VaultDb.RETENTION_PENDING, now + BANK_RETENTION_MS);
        if (mediaId <= 0) {
            try { staged.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        startPendingMonitor(app, mediaId);
        app.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit()
                .putString("voice_bank_last_file", "audio detectado")
                .putLong("voice_bank_last_file_at", now)
                .putString("voice_bank_last_mode", "NOTIFICATION_DATA_URI")
                .apply();
        return mediaId;
    }


    /** Registers image/video copied directly from a MessagingStyle data Uri. */
    public static long registerNotificationCapturedMedia(Context context, File staged, String uriString, String mime, String type, long linkedMessageId, long sourceTime) {
        if (context == null || staged == null || !staged.exists() || staged.length() <= 128) return -1L;
        if (!("image".equals(type) || "video".equals(type))) return -1L;
        Context app=context.getApplicationContext();
        if(MediaLimits.mediaTooLarge(app,type,staged.length())){MediaLimits.recordLimit(app,type,linkedMessageId,sourceTime,staged.length(),"NOTIFICATION_DATA_URI");try{staged.delete();}catch(Throwable ignored){}return -1L;}
        if (!isUsable(staged,type)) { try{staged.delete();}catch(Throwable ignored){} return -1L; }
        VaultDb db=new VaultDb(app);
        long now=System.currentTimeMillis();
        String source="notif-media:"+(uriString==null?"":uriString)+"|"+(sourceTime>0?sourceTime:now);
        long existing=db.findMediaId(source); if(existing>0){try{staged.delete();}catch(Throwable ignored){}return existing;}
        String ext="image".equals(type)?".jpg":".mp4";
        String m=mime==null?"":mime.toLowerCase(Locale.ROOT);
        if(m.contains("png"))ext=".png"; else if(m.contains("webp"))ext=".webp"; else if(m.contains("gif"))ext=".gif"; else if(m.contains("webm"))ext=".webm";
        String safe=type+"_notification_"+(sourceTime>0?sourceTime:now)+ext;
        long id=commitStagedSecure(app,staged,type,linkedMessageId,sourceTime>0?sourceTime:now,source,"NOTIFICATION_DATA_URI",safe,VaultDb.RETENTION_NORMAL,0L);
        if(id>0)db.logEvent("NOTIFICATION_MEDIA_CAPTURE",type+" · "+safe,linkedMessageId,id);
        return id;
    }

    /** Restart hidden provisional-media monitors after listener/app recreation.
     * Never enumerate an arbitrarily large pending-media table on Android's main callback thread. */
    public static void resumePendingMonitors(Context context) {
        if(context==null)return;Context app=context.getApplicationContext();
        if(!pendingResumeQueued.compareAndSet(false,true))return;
        PENDING_EXECUTOR.execute(()->{try{resumePendingMonitorsNow(app);}finally{pendingResumeQueued.set(false);}});
    }

    private static void resumePendingMonitorsNow(Context app) {
        VaultDb db=new VaultDb(app);long afterId=0L;
        while(true){List<VaultDb.Media> page=db.listPendingMediaAfterId(afterId,250);if(page.isEmpty())break;for(VaultDb.Media m:page)if(m!=null){afterId=Math.max(afterId,m.id);startPendingMonitor(app,m.id);}if(page.size()<250)break;}
    }

    public static void monitorPendingMedia(Context context,long mediaId){
        if(context!=null&&mediaId>0)startPendingMonitor(context.getApplicationContext(),mediaId);
    }

    public static void promotePendingForMessage(Context context, long messageId, long messageTime) {
        if (messageId <= 0) return;
        VaultDb db = new VaultDb(context.getApplicationContext());
        int n = db.finalizePendingForConfirmedMessage(messageId, messageTime);
        if (n > 0) {
            context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit().putLong("voice_bank_last_kept_at", System.currentTimeMillis()).putInt("voice_bank_last_kept_count", n).apply();
        }
    }

    private static void startPendingMonitor(Context context, long mediaId) {
        if (mediaId <= 0) return;
        pendingMonitors.put(mediaId, Boolean.TRUE);
        ensurePendingTicker(context.getApplicationContext());
    }

    /**
     * One process-wide, self-stopping quarantine monitor. Confirmed message deletion promotes
     * media immediately in the listener, so this worker only needs to expire hidden provisional
     * blobs. It does not poll forever when there is no work.
     */
    private static void ensurePendingTicker(Context app) {
        if(app==null||!pendingTickerStarted.compareAndSet(false,true))return;
        PENDING_EXECUTOR.execute(() -> runPendingPass(app));
    }

    private static void runPendingPass(Context app){
        try {
            if (!pendingMonitors.isEmpty()) {
                VaultDb db = new VaultDb(app);
                long now = System.currentTimeMillis();
                for (Long mediaId : new ArrayList<>(pendingMonitors.keySet())) {
                    if (mediaId == null || mediaId <= 0) { pendingMonitors.remove(mediaId); continue; }
                    VaultDb.Media m;
                    try { m = db.getMedia(mediaId); } catch (Throwable t) { continue; }
                    if (m == null || m.retentionState != VaultDb.RETENTION_PENDING) {
                        pendingMonitors.remove(mediaId);
                        continue;
                    }
                    if (db.hasConfirmedDeletedLink(mediaId)) {
                        db.promotePendingById(mediaId);
                        db.logEvent("MEDIA_KEEP", "Borrado confirmado durante cuarentena", m.linkedMessageId, mediaId);
                        recordKept(app, 1);
                        pendingMonitors.remove(mediaId);
                        continue;
                    }
                    // Source disappearance is never deletion evidence. Only confirmed message
                    // deletion promotes the blob; otherwise the hidden quarantine expires.
                    if (m.expiresAt > 0 && now >= m.expiresAt) {
                        File f = m.path == null ? null : new File(m.path);
                        if (f != null) try { f.delete(); } catch (Throwable ignored) {}
                        db.deleteMedia(mediaId);
                        db.markBankSourceSeen(m.sourceUri, VaultDb.RETENTION_NORMAL);
                        db.logEvent("MEDIA_QUARANTINE_EXPIRED", "No hubo borrado confirmado durante la cuarentena", m.linkedMessageId, mediaId);
                        app.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE).edit().putLong("voice_bank_last_expired_at", now).apply();
                        pendingMonitors.remove(mediaId);
                    }
                }
            }
        } catch (Throwable t) {
            try{new VaultDb(app).logCaptureFailure("PENDING_TICK","media",0L,t);}catch(Throwable ignored){}
        } finally {
            if (pendingMonitors.isEmpty()) {
                pendingTickerStarted.set(false);
                // Close the add-vs-stop race without leaving a permanent timer alive.
                if(!pendingMonitors.isEmpty())ensurePendingTicker(app);
            } else {
                PENDING_EXECUTOR.schedule(() -> runPendingPass(app), 1L, TimeUnit.SECONDS);
            }
        }
    }

    private static void recordKept(Context context, int count) {
        SharedPreferences sp = context.getSharedPreferences("wa_vault_settings", Context.MODE_PRIVATE);
        sp.edit().putLong("voice_bank_last_kept_at", System.currentTimeMillis()).putInt("voice_bank_last_kept_count", count).apply();
    }

    private static boolean sourceDocumentExists(Context context, String source) {
        if (source == null) return true;
        if (source.startsWith("direct:")) {
            try { return new File(source.substring(7)).exists(); } catch (Throwable t) { return false; }
        }
        if (!source.startsWith("bank:")) return true;
        Uri uri;
        try { uri = Uri.parse(source.substring(5)); } catch (Throwable t) { return false; }
        Cursor c = null;
        try {
            c = context.getContentResolver().query(uri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            return c != null && c.moveToFirst();
        } catch (Throwable t) {
            // A provider can transiently reject a direct query. Try opening it before
            // declaring the source gone, so we do not keep false positives.
            try (InputStream in = context.getContentResolver().openInputStream(uri)) { return in != null; }
            catch (Throwable ignored) { return false; }
        } finally { if (c != null) try { c.close(); } catch (Throwable ignored) {} }
    }

    private static long copyUri(Context context, Uri uri, File dst) {
        long written = 0L;
        try (InputStream in = context.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) { dst.delete(); return 0L; }
            byte[] buf = new byte[65536];
            int z;
            while ((z = in.read(buf)) > 0) { out.write(buf, 0, z); written += z; }
            out.flush();
            out.getFD().sync();
            return written;
        } catch (Throwable t) {
            try { dst.delete(); } catch (Throwable ignored) {}
            return 0L;
        }
    }

    private static long copyUriLimited(Context context,Uri uri,File dst,String type,long messageId,long sourceTime,String origin){
        if(!("video".equals(type)||"document".equals(type)))return copyUri(context,uri,dst);
        long written=0L;final long adaptiveLimit=MediaLimits.maxBytes(context,type);
        try(InputStream in=context.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(dst)){
            if(in==null){dst.delete();return 0L;}
            byte[] buf=new byte[131072];int z;
            while((z=in.read(buf))>0){
                if(adaptiveLimit<=0L||written+z>adaptiveLimit){
                    written+=z;try{dst.delete();}catch(Throwable ignored){}
                    MediaLimits.recordLimit(context,type,messageId,sourceTime,written,origin);return -2L;
                }
                out.write(buf,0,z);written+=z;
            }
            out.flush();out.getFD().sync();return written;
        }catch(Throwable t){try{dst.delete();}catch(Throwable ignored){}return 0L;}
    }

    private static File stagingDir(Context app){File d=new File(app.getFilesDir(),"vault_staging");if(!d.exists())d.mkdirs();return d;}
    static File newStagingPart(Context context,String type){if(context==null)return null;Context app=context.getApplicationContext();return new File(stagingDir(app),VaultFileNames.stagingName("part_",type));}
    static File completedPartToReady(Context app,File part,String type,long messageId,long sourceTime,int retentionState,long expiresAt,String origin){
        return completedPartToReady(app,part,type,messageId,sourceTime,retentionState,expiresAt,origin,null);
    }
    static File completedPartToReady(Context app,File part,String type,long messageId,long sourceTime,int retentionState,long expiresAt,String origin,String displayHint){
        if(app==null||part==null||!part.exists()||!part.isFile())return null;
        File ready=new File(stagingDir(app),VaultFileNames.stagingName("ready_",type));
        boolean moved=false;try{android.system.Os.rename(part.getAbsolutePath(),ready.getAbsolutePath());moved=true;}catch(Throwable ignored){moved=part.renameTo(ready);}
        if(!moved)return null;
        // Sidecar contains only internal recovery coordinates; no message text, sender, URI or filename.
        File meta=new File(ready.getAbsolutePath()+".meta");File tmp=new File(meta.getAbsolutePath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            Properties p=new Properties();p.setProperty("type",type==null?"":type);p.setProperty("message_id",String.valueOf(Math.max(0L,messageId)));p.setProperty("source_time",String.valueOf(Math.max(0L,sourceTime)));p.setProperty("retention_state",String.valueOf(retentionState));p.setProperty("expires_at",String.valueOf(Math.max(0L,expiresAt)));p.setProperty("origin",origin==null?"STAGING_RECOVERY":origin);p.setProperty("extension",safeExtensionHint(displayHint));p.store(out,null);out.flush();out.getFD().sync();
            if(!tmp.renameTo(meta)){try{tmp.delete();}catch(Throwable ignored){}}
        }catch(Throwable stagingFailure){try{tmp.delete();}catch(Throwable cleanupFailure){}}
        return ready;
    }
    private static String safeExtensionHint(String name){if(name==null)return "";String n=name.trim().toLowerCase(Locale.ROOT);int d=n.lastIndexOf('.');if(d<0||d==n.length()-1)return "";String e=n.substring(d);return e.matches("\\.[a-z0-9]{1,10}")?e:"";}
    private static final class StagingRecoveryMeta{String type="",origin="STAGING_RECOVERY",extension="";long messageId=0L,sourceTime=0L,expiresAt=0L;int retentionState=VaultDb.RETENTION_NORMAL;}
    private static StagingRecoveryMeta readStagingRecoveryMeta(File ready,String fallbackType){
        StagingRecoveryMeta m=new StagingRecoveryMeta();m.type=fallbackType==null?"":fallbackType;if(ready==null)return m;File f=new File(ready.getAbsolutePath()+".meta");if(!f.exists())return m;
        try(FileInputStream in=new FileInputStream(f)){Properties p=new Properties();p.load(in);m.type=p.getProperty("type",m.type);m.origin=p.getProperty("origin",m.origin);try{m.messageId=Long.parseLong(p.getProperty("message_id","0"));}catch(Throwable ignored){}try{m.sourceTime=Long.parseLong(p.getProperty("source_time","0"));}catch(Throwable ignored){}try{m.expiresAt=Long.parseLong(p.getProperty("expires_at","0"));}catch(Throwable ignored){}try{m.retentionState=Integer.parseInt(p.getProperty("retention_state",String.valueOf(VaultDb.RETENTION_NORMAL)));}catch(Throwable ignored){}m.extension=safeExtensionHint("x"+p.getProperty("extension",""));}
        catch(Throwable ignored){}return m;
    }
    private static File vaultMediaDir(Context app){File d=new File(app.getFilesDir(),"vault_media");if(!d.exists())d.mkdirs();return d;}
    private static String safeMediaName(File source,String type){
        String name=source==null?"":source.getName();
        if(name==null||name.trim().isEmpty())name=type+"_"+System.nanoTime()+("video".equals(type)?".mp4":".jpg");
        name=name.replaceAll("[^a-zA-Z0-9._-]","_");
        if(!name.contains("."))name += "video".equals(type)?".mp4":".jpg";
        return name;
    }

    /**
     * v0.3.8 hot path: the caller already opened the file descriptor synchronously from the
     * FileObserver callback. Copy into a transactional staging file before any DB/hash/encryption
     * work. The open descriptor survives a later rename/unlink of the pathname.
     */
    public static long ingestPreopenedGrowingMedia(Context context, FileInputStream in, File sourceFile, String type, long linkedMessageId, long sourceTime){
        if(context==null||in==null||!("image".equals(type)||"video".equals(type)))return -1L;
        Context app=context.getApplicationContext();
        String safe=safeMediaName(sourceFile,type);long now=System.currentTimeMillis();
        File part=new File(stagingDir(app),VaultFileNames.stagingName("part_",type));
        long written=0L;long idleMs=0L;long started=System.currentTimeMillis();boolean overVideoLimit=false;
        final long videoLimit=MediaLimits.maxVideoBytes(app);
        try(FileOutputStream out=new FileOutputStream(part)){
            byte[] buf=new byte[131072];
            while(System.currentTimeMillis()-started<20_000L){
                int z;try{z=in.read(buf);}catch(Throwable t){z=-1;}
                if(z>0){if("video".equals(type)&&written+z>videoLimit){overVideoLimit=true;written+=z;break;}out.write(buf,0,z);written+=z;idleMs=0L;continue;}
                long elapsed=System.currentTimeMillis()-started;
                long nap=elapsed<250L?4L:(elapsed<2000L?9L:24L);
                try{Thread.sleep(nap);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                idleMs+=nap;
                long quiet="video".equals(type)?600L:300L;
                if(written>256&&idleMs>=quiet)break;
            }
            out.flush();out.getFD().sync();
        }catch(Throwable t){try{part.delete();}catch(Throwable ignored){}return -1L;}
        if(overVideoLimit){try{part.delete();}catch(Throwable ignored){}MediaLimits.recordVideoLimit(app,linkedMessageId,sourceTime,written,"FILE_OBSERVER_PREOPEN");return -1L;}
        if(written<=256||written>600L*1024L*1024L||!isUsable(part,type)){
            try{new VaultDb(app).logEvent("STAGING_INCOMPLETE",type+" · "+written+" bytes",linkedMessageId,0L);}catch(Throwable ignored){}
            // v0.5.22 fail-closed: an invalid plaintext staging part is destroyed immediately.
            try{part.delete();}catch(Throwable ignored){}
            return -1L;
        }
        long ts=sourceTime>0?sourceTime:now;
        File ready=completedPartToReady(app,part,type,linkedMessageId,ts,VaultDb.RETENTION_NORMAL,0L,"FILE_OBSERVER_PREOPEN",safe);
        if(ready==null){try{part.delete();}catch(Throwable ignored){}return -1L;}
        String source="preopen:"+(sourceFile==null?safe:sourceFile.getAbsolutePath())+"|"+ts+"|"+written;
        return commitReadyStaged(app,ready,type,linkedMessageId,ts,source,"FILE_OBSERVER_PREOPEN",safe);
    }

    /** v0.3.9 processing entry point. Called only after FastCaptureEngine secured the bytes. */
    static long commitFastReadyStaged(Context context,File ready,String type,long linkedMessageId,long sourceTime,String family,String origin,String displayName){
        if(context==null||ready==null||!ready.exists())return -1L;
        Context app=context.getApplicationContext();
        String source="fast-capture:"+(family==null?"":family)+"|"+ready.getName()+"|"+ready.length();
        return commitReadyStaged(app,ready,type,linkedMessageId,sourceTime>0?sourceTime:System.currentTimeMillis(),source,origin==null?"FAST_CAPTURE_ENGINE":origin,displayName);
    }

    /** Recovers a .ready staging file left behind by process death/reboot. */
    public static long recoverStagedFile(Context context,File ready,String type){
        if(context==null||ready==null||!ready.exists()||!("image".equals(type)||"video".equals(type)||"audio".equals(type)||"document".equals(type)))return -1L;
        Context app=context.getApplicationContext();StagingRecoveryMeta meta=readStagingRecoveryMeta(ready,type);
        // Process death can happen after encryptInPlace() but before the ciphertext move. Recover
        // by decrypting back into staging, never by treating ciphertext as corrupt media.
        if(MediaCrypto.isEncrypted(ready)){
            File encryptedReady=ready;File part=newStagingPart(app,type);
            if(part==null||!MediaCrypto.decryptTo(encryptedReady,part)){if(part!=null)try{part.delete();}catch(Throwable ignored){}return -1L;}
            try{encryptedReady.delete();}catch(Throwable ignored){}
            try{new File(encryptedReady.getAbsolutePath()+".meta").delete();}catch(Throwable ignored){}
            ready=completedPartToReady(app,part,type,meta.messageId,meta.sourceTime,meta.retentionState,meta.expiresAt,meta.origin,"recovered"+meta.extension);
            if(ready==null){try{part.delete();}catch(Throwable ignored){}return -1L;}
        }
        if(!isUsable(ready,type)){try{ready.delete();}catch(Throwable ignored){}try{new File(ready.getAbsolutePath()+".meta").delete();}catch(Throwable ignored){}return -1L;}
        long ts=meta.sourceTime>0?meta.sourceTime:(ready.lastModified()>0?ready.lastModified():System.currentTimeMillis());
        int retention=meta.retentionState;long expires=meta.expiresAt;
        if("audio".equals(type)&&retention==VaultDb.RETENTION_NORMAL){retention=VaultDb.RETENTION_PENDING;if(expires<=0L)expires=System.currentTimeMillis()+BANK_RETENTION_MS;}
        String recoveredName=meta.extension.isEmpty()?null:("Recuperado"+meta.extension);
        long id=commitStagedSecure(app,ready,type,meta.messageId,ts,"staging-recovery:"+ready.getName()+"|"+ready.length(),meta.origin,recoveredName,retention,expires);
        try{new File(ready.getAbsolutePath()+".meta").delete();}catch(Throwable ignored){}
        return id;
    }

    private static long commitReadyStaged(Context app,File ready,String type,long linkedMessageId,long sourceTime,String source,String origin,String displayName){
        return commitStagedSecure(app,ready,type,linkedMessageId,sourceTime,source,origin,displayName,VaultDb.RETENTION_NORMAL,0L);
    }

    /** Package-private entry for generated JPEG previews whose logical media type may be video. */
    static long commitGeneratedPreviewStaged(Context context,File ready,String logicalType,long linkedMessageId,long sourceTime,String source,String origin,String displayName){
        if(context==null)return -1L;
        return commitStagedSecureInternal(context.getApplicationContext(),ready,logicalType,"image",linkedMessageId,sourceTime,source,origin,displayName,VaultDb.RETENTION_NORMAL,0L);
    }

    /** Crash-safe media commit: hash/validate plaintext only in staging, encrypt there, then move
     * ciphertext into the permanent private archive before SQLite ever references it. */
    private static long commitStagedSecure(Context app,File ready,String type,long linkedMessageId,long sourceTime,String source,String origin,String displayName,int retentionState,long expiresAt){
        return commitStagedSecureInternal(app,ready,type,type,linkedMessageId,sourceTime,source,origin,displayName,retentionState,expiresAt);
    }

    private static long commitStagedSecureInternal(Context app,File ready,String type,String validationType,long linkedMessageId,long sourceTime,String source,String origin,String displayName,int retentionState,long expiresAt){
        if(app==null||ready==null||!ready.exists()||!ready.isFile())return -1L;
        long plainBytes=ready.length();
        if(plainBytes<=0L||MediaLimits.mediaTooLarge(app,type,plainBytes)){if(plainBytes>0L)MediaLimits.recordLimit(app,type,linkedMessageId,sourceTime,plainBytes,origin);deleteStagedReadyAndMeta(ready);return -1L;}
        String safe=displayName==null||displayName.trim().isEmpty()?(("video".equals(type)?"Video recuperado.mp4":"image".equals(type)?"Foto recuperada.jpg":"document".equals(type)?"Documento recuperado.bin":"Audio recuperado.opus")):displayName.replaceAll("[^a-zA-Z0-9._-]","_");
        String mime=guessMime(safe,type);
        long width=0L,height=0L,duration=0L;
        if("image".equals(validationType)){
            MediaValidation.Result vr=MediaValidation.validate(ready,validationType);
            if(!vr.full()){deleteStagedReadyAndMeta(ready);try{new VaultDb(app).logEvent("MEDIA_REJECTED_INCOMPLETE",type+" · "+vr.reason,linkedMessageId,0L);}catch(Throwable ignored){}return -1L;}
            width=vr.width;height=vr.height;
        }else if("video".equals(validationType)){
            String low=safe.toLowerCase(Locale.ROOT);boolean mp4Family=low.endsWith(".mp4")||low.endsWith(".3gp")||low.endsWith(".mov");
            if(mp4Family){MediaValidation.Result vr=MediaValidation.validate(ready,validationType);if(!vr.full()){deleteStagedReadyAndMeta(ready);try{new VaultDb(app).logEvent("MEDIA_REJECTED_INCOMPLETE",type+" · "+vr.reason,linkedMessageId,0L);}catch(Throwable ignored){}return -1L;}width=vr.width;height=vr.height;duration=vr.durationMs;}
            else{if(!isUsable(ready,validationType)){deleteStagedReadyAndMeta(ready);return -1L;}boolean probed=false;MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(ready.getAbsolutePath());String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),w=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),h=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);if(d!=null){duration=Long.parseLong(d);probed|=duration>0;}if(w!=null){width=Long.parseLong(w);probed|=width>0;}if(h!=null){height=Long.parseLong(h);probed|=height>0;}}catch(Throwable ignored){}finally{try{r.release();}catch(Throwable ignored){}}if(!probed){deleteStagedReadyAndMeta(ready);try{new VaultDb(app).logEvent("MEDIA_REJECTED_INCOMPLETE","video · container metadata unavailable",linkedMessageId,0L);}catch(Throwable ignored){}return -1L;}}
        }else if("audio".equals(validationType)){
            boolean probed=false;MediaMetadataRetriever r=new MediaMetadataRetriever();try{r.setDataSource(ready.getAbsolutePath());String d=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(d!=null){duration=Long.parseLong(d);probed=duration>0;}}catch(Throwable ignored){}finally{try{r.release();}catch(Throwable ignored){}}
            if(plainBytes<=256L||(!probed&&!audioHeaderLooksValid(ready))){deleteStagedReadyAndMeta(ready);try{new VaultDb(app).logEvent("MEDIA_REJECTED_INCOMPLETE","audio · invalid container/header",linkedMessageId,0L);}catch(Throwable ignored){}return -1L;}
        }else if("document".equals(validationType)){
            if(!documentLooksComplete(ready,safe)){deleteStagedReadyAndMeta(ready);try{new VaultDb(app).logEvent("MEDIA_REJECTED_INCOMPLETE","document · invalid/truncated container",linkedMessageId,0L);}catch(Throwable ignored){}return -1L;}
        }
        String rawHash=VaultDb.fileSha256(ready);if(rawHash.isEmpty()){deleteStagedReadyAndMeta(ready);return -1L;}
        if(!MediaCrypto.encryptInPlace(ready)||!MediaCrypto.isEncrypted(ready)){deleteStagedReadyAndMeta(ready);return -1L;}
        File targetDir="audio".equals(type)?new File(app.getFilesDir(),"vault_audio_quarantine"):vaultMediaDir(app);if(!targetDir.exists())targetDir.mkdirs();
        File dst=new File(targetDir,VaultFileNames.opaqueName(safe,mime,type));
        if(!moveCiphertextDurably(ready,dst)){deleteStagedReadyAndMeta(ready);try{dst.delete();}catch(Throwable ignored){}return -1L;}
        deleteStagedMeta(ready);
        VaultDb db=new VaultDb(app);
        long id=db.insertPreparedEncryptedMedia(source,dst.getAbsolutePath(),safe,mime,type,plainBytes,sourceTime,linkedMessageId,retentionState,expiresAt,origin,rawHash,width,height,duration);
        if(id>0){db.logEvent("STAGING_COMMIT",type+" · encrypted · bytes="+plainBytes,linkedMessageId,id);return id;}
        try{dst.delete();}catch(Throwable ignored){}return id;
    }

    private static void deleteStagedMeta(File ready){
        if(ready==null)return;
        try{new File(ready.getAbsolutePath()+".meta").delete();}catch(Throwable ignored){}
        try{new File(ready.getAbsolutePath()+".meta.tmp").delete();}catch(Throwable ignored){}
    }
    private static void deleteStagedReadyAndMeta(File ready){
        if(ready!=null)try{ready.delete();}catch(Throwable ignored){}
        deleteStagedMeta(ready);
    }

    private static boolean moveCiphertextDurably(File source,File target){
        if(source==null||target==null||!source.exists()||!MediaCrypto.isEncrypted(source))return false;
        try{
            try(java.io.RandomAccessFile raf=new java.io.RandomAccessFile(source,"rw")){raf.getFD().sync();}
            try{android.system.Os.rename(source.getAbsolutePath(),target.getAbsolutePath());}
            catch(Throwable renameFail){
                try(FileInputStream in=new FileInputStream(source);FileOutputStream fos=new FileOutputStream(target)){byte[] b=new byte[131072];int n;while((n=in.read(b))>0)fos.write(b,0,n);fos.flush();fos.getFD().sync();}
                if(target.length()!=source.length()){try{target.delete();}catch(Throwable ignored){}return false;}try{source.delete();}catch(Throwable ignored){}
            }
            java.io.FileDescriptor fd=null;try{fd=android.system.Os.open(target.getParentFile().getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(fd);}catch(Throwable ignored){}finally{if(fd!=null)try{android.system.Os.close(fd);}catch(Throwable ignored){}}
            return target.exists()&&target.length()>0L&&MediaCrypto.isEncrypted(target);
        }catch(Throwable t){try{target.delete();}catch(Throwable ignored){}return false;}
    }

    /**
     * Opens a freshly-created WhatsApp image/video immediately and keeps the descriptor open
     * while WhatsApp is still extending the file. On Linux/Android an already-open descriptor
     * remains readable even if the directory entry is removed afterwards.
     */
    public static long ingestGrowingDirectMedia(Context context, File sourceFile, String type, long linkedMessageId, long sourceTime) {
        if(context==null||sourceFile==null||!("image".equals(type)||"video".equals(type)||"document".equals(type)))return -1L;
        if(!sourceFile.exists()||!sourceFile.isFile())return -1L;
        String lowPath=sourceFile.getAbsolutePath().toLowerCase(Locale.ROOT);
        if(lowPath.contains(File.separator+"sent"+File.separator))return -1L;
        String lowName=sourceFile.getName()==null?"":sourceFile.getName().toLowerCase(Locale.ROOT);
        if(lowName.contains("view once")||lowName.contains("ver una vez"))return -1L;
        Context app=context.getApplicationContext();
        VaultDb db=new VaultDb(app);
        long initialModified=sourceFile.lastModified();
        long initialSize=Math.max(0L,sourceFile.length());
        if(MediaLimits.mediaTooLarge(app,type,initialSize)){MediaLimits.recordLimit(app,type,linkedMessageId,sourceTime,initialSize,"FILE_OBSERVER_EARLY_OPEN_PRECHECK");return -1L;}
        String source="direct-media:"+sourceFile.getAbsolutePath()+"|"+initialModified;
        long existing=db.findMediaId(source);
        if(existing>0){db.linkMediaToMessageIfFree(existing,linkedMessageId);return existing;}
        String safe=safeMediaName(sourceFile,type);
        File dst=new File(stagingDir(app),VaultFileNames.stagingName("part_",type));
        long written=0L;int idle=0;long started=System.currentTimeMillis();boolean overAdaptiveLimit=false;
        final long adaptiveLimit=MediaLimits.maxBytes(app,type);
        try(FileInputStream in=new FileInputStream(sourceFile);FileOutputStream out=new FileOutputStream(dst)){
            byte[] buf=new byte[131072];
            while(System.currentTimeMillis()-started<30_000L){
                int z=in.read(buf);
                if(z>0){if(("video".equals(type)||"document".equals(type))&&(adaptiveLimit<=0L||written+z>adaptiveLimit)){overAdaptiveLimit=true;written+=z;break;}out.write(buf,0,z);written+=z;idle=0;continue;}
                idle++;
                if(written>256&&idle>=6)break;
                try{Thread.sleep(idle<3?18L:38L);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
            }
            out.flush();out.getFD().sync();
        }catch(Throwable t){try{dst.delete();}catch(Throwable ignored){}return -1L;}
        if(overAdaptiveLimit){try{dst.delete();}catch(Throwable ignored){}MediaLimits.recordLimit(app,type,linkedMessageId,sourceTime,written,"FILE_OBSERVER_EARLY_OPEN");return -1L;}
        if(written<=("document".equals(type)?0:256)||written>600L*1024L*1024L||!isUsable(dst,type)){try{dst.delete();}catch(Throwable ignored){}return -1L;}
        long ts=sourceTime>0?sourceTime:(initialModified>0?initialModified:System.currentTimeMillis());
        File ready=completedPartToReady(app,dst,type,linkedMessageId,ts,VaultDb.RETENTION_NORMAL,0L,"FILE_OBSERVER_EARLY_OPEN",safe);
        if(ready==null){try{dst.delete();}catch(Throwable ignored){}return -1L;}
        long id=commitReadyStaged(app,ready,type,linkedMessageId,ts,source,"FILE_OBSERVER_EARLY_OPEN",safe);
        if(id>0){
            db.logEvent("MEDIA_DIRECT_EARLY",type+" · "+safe+" · "+written+" bytes",linkedMessageId,id);
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit().putString("last_direct_media",type+" · "+written+" B").putLong("last_direct_media_at",System.currentTimeMillis()).apply();
        }
        return id;
    }

    /**
     * Direct low-latency import for files that the user downloads manually in WhatsApp.
     * This is intentionally independent from notification timing: FileObserver calls it
     * when the physical file appears under Android/media.
     */
    public static long registerDirectDownloadedMedia(Context context, File sourceFile, String type, long linkedMessageId, long sourceTime) {
        if (context == null || sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) return -1L;
        if (!("image".equals(type) || "video".equals(type) || "audio".equals(type) || "document".equals(type))) return -1L;
        String lowPath = sourceFile.getAbsolutePath().toLowerCase(Locale.ROOT);
        if (lowPath.contains(File.separator.toLowerCase(Locale.ROOT)+"sent"+File.separator.toLowerCase(Locale.ROOT))) return -1L;
        String lowName = sourceFile.getName().toLowerCase(Locale.ROOT);
        if (lowName.contains("view once") || lowName.contains("ver una vez")) return -1L;

        // A newly-created WhatsApp image/video can still be growing. Give it a very short
        // stability window while keeping latency low.
        long previous = -1L;
        int stable = 0;
        for (int i=0;i<14;i++) {
            long n = sourceFile.length();
            if (n > 0 && n == previous) stable++; else stable = 0;
            previous = n;
            if (stable >= 2) break;
            try { Thread.sleep(i < 4 ? 12L : 28L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        if (sourceFile.length() <= 0 || sourceFile.length() > 600L*1024L*1024L) return -1L;

        Context app = context.getApplicationContext();
        if(MediaLimits.mediaTooLarge(app,type,sourceFile.length())){MediaLimits.recordLimit(app,type,linkedMessageId,sourceTime,sourceFile.length(),"FILE_OBSERVER");return -1L;}
        VaultDb db = new VaultDb(app);
        String source = "direct-media:" + sourceFile.getAbsolutePath() + "|" + sourceFile.lastModified() + "|" + sourceFile.length();
        long existing = db.findMediaId(source);
        if (existing > 0) return existing;

        String safe = sourceFile.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        File part = new File(stagingDir(app), VaultFileNames.stagingName("part_", type));
        long written = 0L;boolean overAdaptiveLimit=false;final long adaptiveLimit=MediaLimits.maxBytes(app,type);
        try (FileInputStream in = new FileInputStream(sourceFile); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[131072];
            int z;
            while ((z=in.read(buf))>0) {
                if(("video".equals(type)||"document".equals(type))&&(adaptiveLimit<=0L||written+z>adaptiveLimit)){overAdaptiveLimit=true;written+=z;break;}
                out.write(buf,0,z); written += z;
            }
            out.flush();out.getFD().sync();
        } catch (Throwable t) {
            try { part.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        if(overAdaptiveLimit){try{part.delete();}catch(Throwable ignored){}MediaLimits.recordLimit(app,type,linkedMessageId,sourceTime,written,"FILE_OBSERVER_STREAM");return -1L;}
        if (written <= 0 || !isUsable(part,type)) { try { part.delete(); } catch(Throwable ignored){} return -1L; }
        long ts = sourceTime > 0 ? sourceTime : (sourceFile.lastModified() > 0 ? sourceFile.lastModified() : System.currentTimeMillis());
        File staged=completedPartToReady(app,part,type,linkedMessageId,ts,VaultDb.RETENTION_NORMAL,0L,"FILE_OBSERVER",safe);
        if(staged==null){try{part.delete();}catch(Throwable ignored){}return -1L;}
        long id = commitStagedSecure(app,staged,type,linkedMessageId,ts,source,"FILE_OBSERVER",safe,VaultDb.RETENTION_NORMAL,0L);
        if (id > 0) {
            db.logEvent("MEDIA_DIRECT_CAPTURE", type+" · "+safe+" · "+written+" bytes", linkedMessageId, id);
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putString("last_direct_media", type+" · archivo capturado")
                    .putLong("last_direct_media_at",System.currentTimeMillis()).apply();
        }
        return id;
    }

    private static boolean isDocumentAttachment(String name,String mime){
        String m=mime==null?"":mime.toLowerCase(Locale.ROOT);String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(m.startsWith("image/")||m.startsWith("video/")||m.startsWith("audio/"))return false;
        if(m.equals("application/pdf")||m.equals("application/zip")||m.equals("application/rtf")||m.equals("application/epub+zip")||m.contains("msword")||m.contains("officedocument")||m.contains("ms-excel")||m.contains("ms-powerpoint")||m.contains("rar")||m.contains("7z"))return true;
        return n.matches(".*\\.(pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar|7z|csv|rtf|epub)$");
    }

    private static String guessMime(String name, String type) {
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        // Prefer the physical extension. Generated video previews are JPEG blobs whose logical
        // media_type remains "video" so the UI can replace them with a later full video.
        if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return "image/jpeg";
        if(n.endsWith(".png"))return "image/png"; if(n.endsWith(".webp"))return "image/webp"; if(n.endsWith(".gif"))return "image/gif";
        if(n.endsWith(".webm"))return "video/webm"; if(n.endsWith(".3gp"))return "video/3gpp"; if(n.endsWith(".mp4"))return "video/mp4";
        if(n.endsWith(".opus"))return "audio/opus"; if(n.endsWith(".ogg"))return "audio/ogg"; if(n.endsWith(".mp3"))return "audio/mpeg"; if(n.endsWith(".m4a")||n.endsWith(".aac"))return "audio/mp4"; if(n.endsWith(".wav"))return "audio/wav";
        if(n.endsWith(".pdf"))return "application/pdf"; if(n.endsWith(".doc"))return "application/msword"; if(n.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; if(n.endsWith(".xls"))return "application/vnd.ms-excel"; if(n.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; if(n.endsWith(".ppt"))return "application/vnd.ms-powerpoint"; if(n.endsWith(".pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation"; if(n.endsWith(".zip"))return "application/zip";
        if ("image".equals(type)) return "image/jpeg";
        if ("video".equals(type)) return "video/mp4";
        if ("document".equals(type)) return "application/octet-stream";
        return "audio/*";
    }

    /** Avoid walking tens of thousands of Images/Videos when the user selects WhatsApp/Media. */
    private static boolean shouldDescendAudioDir(String rootId, String parentId, String childId, String childName) {
        String root = rootId == null ? "" : rootId.toLowerCase(Locale.ROOT);
        String parent = parentId == null ? "" : parentId.toLowerCase(Locale.ROOT);
        String child = childId == null ? "" : childId.toLowerCase(Locale.ROOT);
        String name = childName == null ? "" : childName.toLowerCase(Locale.ROOT);
        boolean alreadyInsideAudio = root.contains("whatsapp voice notes") || root.contains("whatsapp audio")
                || parent.contains("whatsapp voice notes") || parent.contains("whatsapp audio");
        if (alreadyInsideAudio) return true;
        if (child.contains("whatsapp voice notes") || child.contains("whatsapp audio")) return true;
        if (name.contains("whatsapp voice notes") || name.contains("whatsapp audio")) return true;
        // Allow one navigation step when the selected tree is an ancestor such as WhatsApp.
        return "media".equals(name) || "whatsapp".equals(name);
    }

    private static boolean isAudioDocument(String name, String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (m.startsWith("audio/")) return true;
        return n.endsWith(".opus") || n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".m4a")
                || n.endsWith(".mp3") || n.endsWith(".aac") || n.endsWith(".amr") || n.endsWith(".wav");
    }

    private static boolean audioHeaderLooksValid(File f){
        if(f==null||!f.exists()||f.length()<4)return false;byte[] h=new byte[16];int n=0;
        try(FileInputStream in=new FileInputStream(f)){n=in.read(h);}catch(Throwable ignored){return false;}
        if(n>=4&&h[0]=='O'&&h[1]=='g'&&h[2]=='g'&&h[3]=='S')return true;
        if(n>=4&&h[0]=='R'&&h[1]=='I'&&h[2]=='F'&&h[3]=='F')return true;
        if(n>=3&&h[0]=='I'&&h[1]=='D'&&h[2]=='3')return true;
        if(n>=2&&(h[0]&0xff)==0xff&&((h[1]&0xe0)==0xe0))return true; // MP3/AAC sync
        if(n>=5&&h[0]=='#'&&h[1]=='!'&&h[2]=='A'&&h[3]=='M'&&h[4]=='R')return true;
        return n>=8&&h[4]=='f'&&h[5]=='t'&&h[6]=='y'&&h[7]=='p';
    }

    private static boolean documentLooksComplete(File f,String displayName){
        if(f==null||!f.exists()||f.length()<=0)return false;String n=displayName==null?"":displayName.toLowerCase(Locale.ROOT);
        if(n.endsWith(".pdf")){
            byte[] head=new byte[5];try(java.io.RandomAccessFile r=new java.io.RandomAccessFile(f,"r")){if(r.read(head)!=5||head[0]!='%'||head[1]!='P'||head[2]!='D'||head[3]!='F'||head[4]!='-')return false;long len=r.length();int tailLen=(int)Math.min(8192L,len);byte[] tail=new byte[tailLen];r.seek(len-tailLen);r.readFully(tail);String t=new String(tail,java.nio.charset.StandardCharsets.ISO_8859_1);return t.contains("%%EOF");}catch(Throwable e){return false;}
        }
        if(n.matches(".*\\.(zip|docx|xlsx|pptx|epub)$")){
            try(java.util.zip.ZipFile z=new java.util.zip.ZipFile(f)){java.util.Enumeration<? extends java.util.zip.ZipEntry> e=z.entries();while(e.hasMoreElements())e.nextElement();return true;}catch(Throwable t){return false;}
        }
        if(n.matches(".*\\.(doc|xls|ppt)$")){
            byte[] h=new byte[8];try(FileInputStream in=new FileInputStream(f)){if(in.read(h)!=8)return false;}catch(Throwable t){return false;}byte[] ole={(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0,(byte)0xA1,(byte)0xB1,0x1A,(byte)0xE1};for(int i=0;i<8;i++)if(h[i]!=ole[i])return false;return true;
        }
        if(n.endsWith(".rtf")){byte[] h=new byte[5];try(FileInputStream in=new FileInputStream(f)){if(in.read(h)<5)return false;}catch(Throwable t){return false;}return h[0]=='{'&&h[1]=='\\'&&h[2]=='r'&&h[3]=='t'&&h[4]=='f';}
        if(n.endsWith(".7z")){byte[] h=new byte[6];try(FileInputStream in=new FileInputStream(f)){if(in.read(h)!=6)return false;}catch(Throwable t){return false;}return (h[0]&255)==0x37&&(h[1]&255)==0x7A&&(h[2]&255)==0xBC&&(h[3]&255)==0xAF&&(h[4]&255)==0x27&&(h[5]&255)==0x1C;}
        if(n.endsWith(".rar")){byte[] h=new byte[7];try(FileInputStream in=new FileInputStream(f)){if(in.read(h)<7)return false;}catch(Throwable t){return false;}return h[0]=='R'&&h[1]=='a'&&h[2]=='r'&&h[3]=='!'&&(h[4]&255)==0x1A&&(h[5]&255)==0x07;}
        return true; // CSV/text/unknown document formats: size and copy-length checks remain authoritative.
    }

    private static boolean isUsable(File file, String type) {
        if (file == null || !file.exists() || file.length() <= 0) return false;
        if ("image".equals(type)) {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, src) -> {
                        int w = Math.max(1, info.getSize().getWidth());
                        int h = Math.max(1, info.getSize().getHeight());
                        float scale = Math.min(1f, 64f / Math.max(w, h));
                        decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                    });
                    if (b != null) { b.recycle(); return true; }
                } catch (Throwable ignored) {}
            }
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
                return opts.outWidth > 0 && opts.outHeight > 0;
            } catch (Throwable ignored) { return false; }
        }

        if ("document".equals(type)) return file.length() > 0;

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            String duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) return true;
            if ("video".equals(type)) {
                try { return r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) != null; }
                catch (Throwable ignored) { return file.length() > 32 * 1024; }
            }
            return "audio".equals(type) && file.length() > 256;
        } catch (Throwable t) {
            return "audio".equals(type) ? file.length() > 256 : file.length() > 32 * 1024;
        } finally {
            try { r.release(); } catch (Throwable ignored) {}
        }
    }
}
