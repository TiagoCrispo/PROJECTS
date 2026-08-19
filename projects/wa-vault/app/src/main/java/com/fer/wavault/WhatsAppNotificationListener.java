package com.fer.wavault;

import android.app.Notification;
import android.app.Person;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.net.Uri;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private static volatile WhatsAppNotificationListener liveInstance;
    private static final String DIAG_PREFS = "wa_vault_diag";
    // Polling is only a safety net. Normal capture is callback-driven. v0.5.16 keeps the
    // fallback responsive for a short burst, then backs off hard to avoid waking the app while
    // WhatsApp is idle.
    private static final long POLL_HOT_MS = 750L;
    private static final long POLL_WARM_MS = 5_000L;
    private static final long POLL_IDLE_MS = 45_000L;
    private static final long POLL_DEEP_IDLE_MS = 300_000L;
    private static final long HOT_WINDOW_MS = 6_000L;
    private static final long WARM_WINDOW_MS = 60_000L;
    private static final long DEEP_IDLE_WINDOW_MS = 5L * 60L * 1000L;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean connected = false;
    // v0.5.30 lifecycle gate: persisted history may be restored on start, but deletion detection
    // remains disabled until active WhatsApp notifications have been ingested as a fresh baseline.
    private volatile boolean baselineReady = false;
    private volatile long baselineReadyAt = 0L;
    private volatile DeletionGuard.Phase lifecyclePhase = DeletionGuard.Phase.INITIALIZATION;
    private final AtomicLong baselineGeneration = new AtomicLong(0L);
    private int baselineRetryAttempt = 0;
    // Technical API-recovery backoff only. It never changes deletion classification or ignores events.
    private static final long[] BASELINE_RETRY_MS = new long[]{250L, 1_000L, 4_000L};
    private final Set<String> trustedBaselineConversations = ConcurrentHashMap.newKeySet();
    private final Set<String> snapshotPersistenceRetryKeys = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService mediaScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wa-vault-media-scheduler");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private static final ExecutorService ENGINE_LOG_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"wa-vault-engine-log");t.setDaemon(true);t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final AtomicLong mediaBurstToken = new AtomicLong(0L);
    private final AtomicLong audioRescueToken = new AtomicLong(0L);
    private final AtomicLong deletionCandidateGeneration = new AtomicLong(0L);
    private final Map<String,Long> deletionCandidateGenerations = new ConcurrentHashMap<>();
    private static final long DELETE_VERIFY_MS = 160L;
    private static final long REMOVED_SNAPSHOT_GRACE_MS = 8_000L;
    private static final long AGGRESSIVE_BATCH_WINDOW_MS = 120_000L;
    private static final int AGGRESSIVE_BATCH_MAX = 64;
    private static final long REMOVAL_VERIFY_MS = 220L;
    private static final long EMPTY_VERIFY_MS = 190L;
    // v0.5.11: when WhatsApp caps/slides the visible MessagingStyle window, older members of
    // the same rapid send burst can survive only in SQLite. Backfill only a tight contiguous
    // prefix immediately preceding the active removal candidate.
    private static final long MIXED_BACKFILL_MAX_GAP_MS = 35_000L;
    private static final long MIXED_BACKFILL_MAX_SPAN_MS = 120_000L;
    private final Map<String,Long> removalCandidateGenerations = new ConcurrentHashMap<>();
    private final Map<String,Long> recentDescriptorOpens = new ConcurrentHashMap<>();
    private volatile long latestLinkedMessageId = 0L;
    private volatile long latestNotificationTime = 0L;
    private volatile long lastWhatsAppEventAt = 0L;
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!connected) return;
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification sbn : active) {
                        if (sbn != null && isWhatsApp(sbn.getPackageName())) handleNotification(sbn, DeletionGuard.Source.POLL_SYNC);
                    }
                }
            } catch (Throwable ignored) {}
            if (connected) {
                long age=System.currentTimeMillis()-lastWhatsAppEventAt;
                long delay=age>=0&&age<HOT_WINDOW_MS?POLL_HOT_MS:(age>=0&&age<WARM_WINDOW_MS?POLL_WARM_MS:(age>=0&&age<DEEP_IDLE_WINDOW_MS?POLL_IDLE_MS:POLL_DEEP_IDLE_MS));handler.postDelayed(this,delay);
            }
        }
    };

    private boolean isWhatsApp(String pkg) {
        return "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
    }

    private String s(CharSequence c) { return c == null ? "" : c.toString().trim(); }

    @Override public void onCreate() {
        super.onCreate();
        liveInstance = this;
        prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        baselineReady = false;
        baselineReadyAt = 0L;
        lifecyclePhase = DeletionGuard.Phase.INITIALIZATION;
        try { new VaultDb(getApplicationContext()).logEvent("PROCESS_RECREATED", "Listener creado · deletion gate=CLOSED", 0L, 0L); } catch (Throwable ignored) {}
    }

    /** User-facing manual refresh: re-parse all active WhatsApp notifications immediately. */
    public static boolean refreshNow() {
        final WhatsAppNotificationListener inst = liveInstance;
        if (inst == null || !inst.connected) return false;
        inst.handler.post(() -> {
            try {
                StatusBarNotification[] active = inst.getActiveNotifications();
                if (active != null) for (StatusBarNotification sbn : active) {
                    if (sbn != null && inst.isWhatsApp(sbn.getPackageName())) inst.handleNotification(sbn, DeletionGuard.Source.POLL_SYNC);
                }
                if (inst.prefs != null) inst.prefs.edit().putString("last_event", "Actualización manual completada").putLong("last_whatsapp_at", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {}
        });
        return true;
    }

    /** Hard restart requested from the UI after OEM battery/service glitches. */
    public static void forceRebind(android.content.Context context) {
        if (context == null) return;
        try {
            NotificationListenerService.requestRebind(new ComponentName(context, WhatsAppNotificationListener.class));
        } catch (Throwable ignored) {}
        try { DirectVoiceWatcher.start(context.getApplicationContext()); } catch (Throwable ignored) {}
        try { DirectMediaWatcher.start(context.getApplicationContext()); } catch (Throwable ignored) {}
        try { MediaStoreWatcher.start(context.getApplicationContext()); } catch (Throwable ignored) {}
        try { refreshNow(); } catch (Throwable ignored) {}
        logEngineEventAsync(context,"ENGINE_RESTART","Reinicio manual del motor");
    }

    static void logEngineEventAsync(android.content.Context context,String code,String detail){
        if(context==null)return;final android.content.Context app=context.getApplicationContext();
        ENGINE_LOG_EXECUTOR.execute(()->{try{new VaultDb(app).logEvent(code,detail,0L,0L);}catch(Throwable ignored){}});
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        liveInstance = this;
        connected = true;
        lastWhatsAppEventAt=System.currentTimeMillis();
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        baselineReady = false;
        baselineReadyAt = 0L;
        lifecyclePhase = DeletionGuard.Phase.BASELINE_BUILDING;
        trustedBaselineConversations.clear();
        baselineRetryAttempt = 0;
        deletionCandidateGenerations.clear();
        removalCandidateGenerations.clear();
        final long generation = baselineGeneration.incrementAndGet();
        prefs.edit()
                .putLong("listener_connected_at", System.currentTimeMillis())
                .putBoolean("baseline_ready", false)
                .putLong("baseline_generation", generation)
                .putString("last_event", "BASELINE_LOADING")
                .apply();
        try {
            VaultDb lifecycleDb = new VaultDb(getApplicationContext());
            lifecycleDb.logEvent("SERVICE_CONNECTED", "NotificationListener conectado · generation="+generation, 0L, 0L);
            lifecycleDb.logEvent("SERVICE_RESTART", "NotificationListener conectado · generation="+generation, 0L, 0L);
            lifecycleDb.logEvent("BASELINE_LOADING", "Restaurando estado persistente; deletion gate=CLOSED", 0L, 0L);
        } catch (Throwable ignored) {}
        handler.removeCallbacks(pollRunnable);
        handler.post(() -> establishBaseline(generation));
        try { DirectVoiceWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        try { DirectMediaWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        try { MediaStoreWatcher.start(getApplicationContext()); } catch (Throwable ignored) {}
        try { MediaArchiver.resumePendingMonitors(getApplicationContext()); } catch (Throwable ignored) {}
    }

    private void establishBaseline(long generation) {
        if (!connected || generation != baselineGeneration.get()) return;
        lifecyclePhase = DeletionGuard.Phase.BASELINE_BUILDING;
        int activeCount = 0;
        boolean readSucceeded = false;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            // Android documents this call as returning an array once onListenerConnected() fires.
            // A null result is treated as unavailable, not as an authoritative empty snapshot.
            if (active == null) throw new IllegalStateException("active_notifications_unavailable");
            readSucceeded = true;
            for (StatusBarNotification sbn : active) {
                if (sbn == null || !isWhatsApp(sbn.getPackageName())) continue;
                activeCount++;
                handleNotification(sbn, DeletionGuard.Source.BASELINE_SYNC);
            }
        } catch (Throwable t) {
            baselineReady = false;
            baselineReadyAt = 0L;
            lifecyclePhase = DeletionGuard.Phase.BASELINE_BUILDING;
            if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
            prefs.edit().putBoolean("baseline_ready", false).putString("last_event", "BASELINE_REJECTED")
                    .putString("baseline_error", t.getClass().getSimpleName()).apply();
            try { new VaultDb(getApplicationContext()).logEvent("BASELINE_REJECTED", "WHY_DETECTED=ACTIVE_NOTIFICATION_READ_FAILED · deletion gate=CLOSED · "+t.getClass().getSimpleName(), 0L, 0L); } catch (Throwable ignored) {}
        }
        if (!connected || generation != baselineGeneration.get()) return;
        if (!readSucceeded) {
            scheduleBaselineRetry(generation);
            return;
        }
        baselineRetryAttempt = 0;
        try { new VaultDb(getApplicationContext()).logEvent("BASELINE_RESTORED", "Active notifications read succeeded · active="+activeCount, 0L, 0L); } catch (Throwable ignored) {}
        baselineReadyAt = System.currentTimeMillis();
        baselineReady = true;
        lifecyclePhase = DeletionGuard.Phase.LIVE;
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean("baseline_ready", true).putLong("baseline_ready_at", baselineReadyAt)
                .putInt("baseline_active_notifications", activeCount).putString("last_event", "BASELINE_READY").apply();
        try { new VaultDb(getApplicationContext()).logEvent("BASELINE_READY", "Activas="+activeCount+" · deletion gate=LIVE", 0L, 0L); } catch (Throwable ignored) {}
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_HOT_MS);
    }

    private void scheduleBaselineRetry(long generation) {
        if (!connected || generation != baselineGeneration.get()) return;
        if (baselineRetryAttempt >= BASELINE_RETRY_MS.length) {
            try { new VaultDb(getApplicationContext()).logEvent("BASELINE_WAITING_FOR_VALID_STATE", "Retries agotados · gate permanece CLOSED hasta próximo callback/rebind", 0L, 0L); } catch (Throwable ignored) {}
            return;
        }
        long delay = BASELINE_RETRY_MS[baselineRetryAttempt++];
        handler.postDelayed(() -> establishBaseline(generation), delay);
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        baselineReady = false;
        baselineReadyAt = 0L;
        lifecyclePhase = DeletionGuard.Phase.DISCONNECTED;
        baselineGeneration.incrementAndGet();
        trustedBaselineConversations.clear();
        deletionCandidateGenerations.clear();
        removalCandidateGenerations.clear();
        if (prefs != null) prefs.edit().putBoolean("baseline_ready", false).putString("last_event", "SERVICE_RESTART").apply();
        try {
            VaultDb lifecycleDb = new VaultDb(getApplicationContext());
            lifecycleDb.logEvent("SERVICE_DISCONNECTED", "Listener desconectado · deletion gate=CLOSED", 0L, 0L);
            lifecycleDb.logEvent("SERVICE_RESTART", "Listener desconectado · deletion gate=CLOSED", 0L, 0L);
        } catch (Throwable ignored) {}
        handler.removeCallbacks(pollRunnable);
        try {
            ComponentName cn = new ComponentName(this, WhatsAppNotificationListener.class);
            // Android explicitly permits requestRebind() after onListenerDisconnected(). No
            // arbitrary sleep is needed; the system decides when to reconnect the listener.
            NotificationListenerService.requestRebind(cn);
        } catch (Throwable ignored) {}
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        connected = false;
        baselineReady = false;
        baselineReadyAt = 0L;
        lifecyclePhase = DeletionGuard.Phase.DISCONNECTED;
        baselineGeneration.incrementAndGet();
        trustedBaselineConversations.clear();
        if (liveInstance == this) liveInstance = null;
        handler.removeCallbacks(pollRunnable);
        try { mediaScheduler.shutdownNow(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!baselineReady && connected) {
            final long generation = baselineGeneration.get();
            handler.post(() -> establishBaseline(generation));
        }
        if(sbn!=null&&isWhatsApp(sbn.getPackageName())){
            lastWhatsAppEventAt=System.currentTimeMillis();
            CaptureMetrics.markStart(getApplicationContext(),"notif:"+sbn.getKey()+":"+sbn.getPostTime());
        }
        handleNotification(sbn, DeletionGuard.Source.REAL_POST);
        if(sbn!=null&&isWhatsApp(sbn.getPackageName()))CaptureMetrics.finish(getApplicationContext(),"notif:"+sbn.getKey()+":"+sbn.getPostTime(),"notification_store");
    }

    private void handleNotification(StatusBarNotification sbn, DeletionGuard.Source source) {
        final boolean fromPoll = source != DeletionGuard.Source.REAL_POST;
        if (sbn == null || !isWhatsApp(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            if (!fromPoll) diagnostic("Resumen de grupo ignorado", 0, false);
            return;
        }

        // PRECISION EXTREME: preserve notification-level audio before parsing/DB work.
        // The descriptor is opened synchronously while WhatsApp's temporary grant is alive.
        final Uri[] earlyAudioUri = new Uri[]{null};
        final boolean[] earlyAudioFresh = new boolean[]{false};
        try {
            String audioContents = n.extras.getString(Notification.EXTRA_AUDIO_CONTENTS_URI, "");
            if (audioContents != null && !audioContents.trim().isEmpty()) {
                earlyAudioUri[0] = Uri.parse(audioContents.trim());
                if (shouldOpenDescriptor(earlyAudioUri[0])) {
                    earlyAudioFresh[0] = true;
                    NotificationAudioCapture.tryCaptureNow(getApplicationContext(), earlyAudioUri[0], "audio/*", 0L, sbn.getPostTime(), sbn.getPostTime());
                }
            }
        } catch (Throwable ignored) {}

        List<Parsed> parsed = parseNotification(sbn);
        // Preserve every per-message media URI before encryption, correlation and SQLite writes.
        for (Parsed early : parsed) {
            if (early == null || !early.hasMediaData() || !shouldOpenDescriptor(early.dataUri)) continue;
            try { NotificationMediaCapture.tryCaptureNow(getApplicationContext(), early.dataUri, early.dataMime, 0L, sbn.getPostTime(), early.timestamp); }
            catch (Throwable ignored) {}
        }
        String notificationKey = stableKey(sbn);
        if (notificationKey.isEmpty()) {
            try { new VaultDb(getApplicationContext()).logEvent("IDENTITY_UNAVAILABLE","Stable notification identity unavailable; event rejected fail-closed",0L,0L); } catch(Throwable ignored){}
            return;
        }
        if (parsed.isEmpty()) {
            // Some WhatsApp builds publish an empty replacement. Empty/absence is never deletion
            // evidence: retain the last valid LIVE baseline and wait for positive presence or a
            // fresh explicit REAL_POST marker. No timer can promote this state.
            String conv = conversationHint(sbn);
            List<SnapshotItem> previous = loadConversationSnapshot(sbn, conv);
            if (previous.isEmpty()) previous = loadSnapshotV2(notificationKey);
            // Empty/temporarily unavailable notification state is NOT deletion evidence and is not
            // an authoritative per-conversation baseline either. Never overwrite the last positive
            // persisted identity snapshot and never grant current-session trust from an empty parse.
            // The global listener may still become LIVE after getActiveNotifications() succeeds,
            // but this conversation remains untrusted until positive tokenized presence is observed.
            if (source == DeletionGuard.Source.BASELINE_SYNC) {
                trustedBaselineConversations.remove(conversationSnapshotKey(sbn, conv));
                try { new VaultDb(getApplicationContext()).logEvent("BASELINE_CONVERSATION_UNTRUSTED",
                        "WHY_DETECTED=EMPTY_BASELINE_PARSE · SOURCE_EVENT=BASELINE_SYNC · "+conv, 0L, 0L); } catch (Throwable ignored) {}
            }
            traceDeletion("DELETION_REJECTED_EMPTY_STATE", conv, previous.size(), 0, 0);
            try { new VaultDb(getApplicationContext()).logEvent("DELETION_REJECTED", "WHY_DETECTED=EMPTY_STATE · SOURCE_EVENT="+source+" · "+conv, 0L, 0L); } catch (Throwable ignored) {}
            if (source == DeletionGuard.Source.REAL_POST) diagnostic("Estado vacío de WhatsApp · no se considera borrado", previous.size(), false);
            return;
        }

        VaultDb db = new VaultDb(getApplicationContext());
        String fallbackConversation = firstConversation(parsed);
        List<SnapshotItem> previousItems = loadConversationSnapshot(sbn, fallbackConversation);
        if (previousItems.isEmpty()) previousItems = loadSnapshotV2(notificationKey);
        if (previousItems.isEmpty()) previousItems = loadRecentRemovedSnapshot(sbn, fallbackConversation);
        // Upgrade bridge: v0.5.0 only stored raw DB ids per notification key. Keep those ids
        // available for explicit deletion markers on the first v0.5.1 transition.
        if (previousItems.isEmpty()) {
            for (Long legacyId : loadSnapshot(notificationKey)) {
                if (legacyId != null && legacyId > 0) previousItems.add(new SnapshotItem("", legacyId));
            }
        }
        List<SnapshotItem> currentItems = new ArrayList<>();
        List<Long> currentIds = new ArrayList<>();
        Map<String,Integer> tokenOccurrences = new LinkedHashMap<>();
        int deletionMarkers = 0;
        Parsed firstDeletionMarker = null;
        boolean sawOneTimeMarker = false;
        long newestMessageId = -1;
        long newestMessageTime = 0;
        String newestMessageMediaKind = "";
        long newestAudioMessageId = -1L;
        long newestAudioMessageTime = 0L;
        Map<String,Long> previousTokenIds = new LinkedHashMap<>();
        for (SnapshotItem x : previousItems) if (x != null && !x.token.isEmpty() && x.id > 0) previousTokenIds.put(x.token,x.id);
        // Plural delete-looking text is always ordinary message content. Android/WhatsApp do not
        // expose native per-message IDs that would let us map an N-message batch safely. Treating
        // "2 mensajes eliminados" as a system marker would either guess rows or discard a human
        // message with the same text, so production detection is singular-only and fail-closed.

        for (Parsed p : parsed) {
            fallbackConversation = p.conversation;
            String low = p.text.toLowerCase(Locale.ROOT);
            int markerCount = deletedMarkerCount(low);
            if (markerCount > 0) {
                deletionMarkers += markerCount;
                if (firstDeletionMarker == null) firstDeletionMarker = p;
                continue;
            }
            if (isOneTimeMarker(low)) {
                sawOneTimeMarker = true;
                continue;
            }
            if (shouldSkip(low)) continue;

            if (source == DeletionGuard.Source.REAL_POST) {
                try { db.logEvent("MESSAGE_DETECTED", "SOURCE_EVENT=REAL_POST · "+p.conversation+" · "+p.sender, 0L, 0L); } catch (Throwable ignored) {}
            }
            String baseToken = messageTokenBase(p);
            if (baseToken.isEmpty()) {
                try { db.logEvent("MESSAGE_REJECTED_IDENTITY","Stable message identity unavailable; no persistence/trust",0L,0L); } catch(Throwable ignored){}
                continue;
            }
            int occurrence = tokenOccurrences.containsKey(baseToken) ? tokenOccurrences.get(baseToken) + 1 : 1;
            tokenOccurrences.put(baseToken, occurrence);
            String messageToken = baseToken + "#" + occurrence;

            long id = previousTokenIds.containsKey(messageToken) ? previousTokenIds.get(messageToken) : -1L;
            boolean logicalNew = false;
            if (id <= 0) id = db.findExactMessageId(sbn.getPackageName(),p.conversation,p.sender,p.text,p.timestamp,p.isGroup,occurrence);
            if (id <= 0) {
                id = db.insertMessage(
                        sbn.getPackageName(),
                        p.conversation,
                        p.sender,
                        p.text,
                        p.timestamp,
                        sbn.getKey(),
                        p.isGroup,
                        p.messageIndex,
                        occurrence
                );
                logicalNew = id > 0;
            }
            if (id > 0) {
                if (!currentIds.contains(id)) currentIds.add(id);
                currentItems.add(new SnapshotItem(messageToken, id));
                if (p.timestamp >= newestMessageTime) {
                    newestMessageTime = p.timestamp;
                    newestMessageId = id;
                    newestMessageMediaKind = p.mediaKind();
                }
                String mediaKind=p.mediaKind();
                boolean mediaMissing=!mediaKind.isEmpty() && db.findLinkedMedia(id,mediaKind)==null;
                boolean armCapture=logicalNew || (!fromPoll && mediaMissing);
                if (logicalNew) {
                    try { db.logEvent("MESSAGE_CAPTURED", p.conversation + " · " + p.sender + " · " + p.text, id, 0L); } catch (Throwable ignored) {}
                    try { db.logEvent("MESSAGE_PERSISTED", "SOURCE_EVENT="+source+" · stableId="+messageToken, id, 0L); } catch (Throwable ignored) {}
                    rememberConversationBurst(p.conversation, messageToken, id, p.timestamp);
                } else if (source == DeletionGuard.Source.REAL_POST) {
                    try { db.logEvent("MESSAGE_UPDATED", "SOURCE_EVENT=REAL_POST · stableId="+messageToken, id, 0L); } catch (Throwable ignored) {}
                    try { db.logEvent("DUPLICATE_REJECTED", "No se creó una segunda fila lógica · stableId="+messageToken, id, 0L); } catch (Throwable ignored) {}
                }
                if ("audio".equals(mediaKind) && armCapture) {
                    if (p.timestamp >= newestAudioMessageTime) { newestAudioMessageTime = p.timestamp; newestAudioMessageId = id; }
                    try { DirectVoiceWatcher.armForMessage(getApplicationContext(), id, p.timestamp); } catch (Throwable t) { try{db.logCaptureFailure("VOICE_ARM","audio",id,t);}catch(Throwable ignored){} }
                    try { FastCaptureEngine.enterHotMode("audio", 30_000L); } catch (Throwable ignored) {}
                    scheduleAudioRescue(id, p.timestamp);
                }
                if (("image".equals(mediaKind) || "video".equals(mediaKind)) && armCapture) {
                    try { FastCaptureEngine.enterHotMode(mediaKind,"video".equals(mediaKind)?25_000L:15_000L); } catch(Throwable ignored) {}
                    try { NotificationPreviewCapture.captureBestPreviewAsync(getApplicationContext(), n, id, p.timestamp, mediaKind); } catch (Throwable t) { try{db.logCaptureFailure("NOTIF_PREVIEW",mediaKind,id,t);}catch(Throwable ignored){} }
                    // Placeholder creation is cheap and idempotent. The async preview/full capture
                    // replaces/reconciles it later without blocking NotificationListener main.
                    if ("video".equals(mediaKind)) {
                        try { db.ensureVideoPlaceholder(id, p.timestamp, "Video detectado · esperando archivo (límite actual "+MediaLimits.limitLabel(getApplicationContext())+")", "VIDEO_PENDING_PLACEHOLDER"); } catch (Throwable t) { try{db.logCaptureFailure("VIDEO_PLACEHOLDER","video",id,t);}catch(Throwable ignored){} }
                    }
                    if(!p.hasMediaData())try{db.logEvent("MEDIA_WAITING",mediaKind+" · preview asíncrona / esperando archivo compartido",id,0L);}catch(Throwable ignored){}
                }
                if(armCapture && !mediaKind.isEmpty()){
                    try { DirectMediaWatcher.armForMessage(getApplicationContext(), id, p.timestamp, mediaKind); } catch (Throwable t) { try{db.logCaptureFailure("MEDIA_ARM",mediaKind,id,t);}catch(Throwable ignored){} }
                    try { MediaStoreWatcher.armForMessage(getApplicationContext(), id, p.timestamp, mediaKind); } catch (Throwable t) { try{db.logCaptureFailure("MEDIASTORE_ARM",mediaKind,id,t);}catch(Throwable ignored){} }
                }
                if (p.hasMediaData()) {
                    try { NotificationMediaCapture.linkToMessage(getApplicationContext(), p.dataUri, id); } catch (Throwable t) { try{db.logCaptureFailure("NOTIF_DATA_URI",mediaKind,id,t);}catch(Throwable ignored){} }
                }
            }
        }

        // Link the already-opened notification-level audio descriptor after message insertion.
        try {
            if (earlyAudioUri[0] != null && earlyAudioFresh[0]) {
                // Never attach an audio descriptor to a generic newest text row. If WhatsApp did
                // not identify an audio message yet, keep the bytes hidden/unlinked; confirmed-delete
                // reconciliation can attach them safely by timestamp later.
                long linkId = newestAudioMessageId > 0 ? newestAudioMessageId : 0L;
                if (linkId > 0) {
                    NotificationAudioCapture.linkToMessage(getApplicationContext(), earlyAudioUri[0], linkId);
                    scheduleAudioRescue(linkId, newestAudioMessageTime > 0 ? newestAudioMessageTime : sbn.getPostTime());
                }
            }
        } catch (Throwable t) { try{db.logCaptureFailure("EARLY_AUDIO_LINK","audio",newestAudioMessageId,t);}catch(Throwable ignored){} }

        // Persist the conversation associated with this notification key. WhatsApp can
        // cancel and repost a conversation notification using another key, so the removal
        // fallback also matches by conversation instead of relying only on sbn.getKey().
        prefs.edit().putString("conv_" + notificationKey, MetadataPrivacy.seal(getApplicationContext(),fallbackConversation)).apply();

        String stateSignature = stateSignatureV2(currentItems, deletionMarkers, sawOneTimeMarker);
        String oldSignature = prefs.getString("state2_" + notificationKey, "");
        // Only periodic POLL_SYNC may short-circuit an unchanged state. BASELINE_SYNC must always
        // finish rebuilding current-session trust even when the persisted signature is identical
        // to the previous process/session.
        if (source == DeletionGuard.Source.POLL_SYNC && stateSignature.equals(oldSignature) && !snapshotPersistenceRetryKeys.contains(notificationKey)) return;

        List<SnapshotItem> missing = missingItems(previousItems, currentItems);
        List<SnapshotItem> added = missingItems(currentItems, previousItems);
        prefs.edit()
                .putInt("last_snapshot_previous", previousItems.size())
                .putInt("last_snapshot_current", currentItems.size())
                .putInt("last_snapshot_missing", missing.size())
                .putInt("last_snapshot_added", added.size())
                .putInt("last_delete_markers", deletionMarkers)
                .apply();

        int markedDeleted = 0;
        boolean freshDeletePost = deletionMarkers > 0 && DeletionGuard.canEvaluateDeletionMarker(
                lifecyclePhase, source, baselineReady, sbn.getPostTime(), baselineReadyAt);
        String evidenceKey = freshDeletePost ? deletionEvidenceKey(
                sbn, fallbackConversation, deletionMarkers, firstDeletionMarker, stateSignature) : "";
        boolean evidenceKnown = !evidenceKey.isEmpty() && db.hasDeletionEvidence(evidenceKey);
        boolean evidenceFresh = freshDeletePost && !evidenceKey.isEmpty() && !evidenceKnown;
        if (deletionMarkers > 0 && evidenceFresh) {
            final String ck = conversationSnapshotKey(sbn, fallbackConversation);
            deletionCandidateGenerations.remove(ck);
            removalCandidateGenerations.remove(ck);
            final int markerTarget = Math.min(100, Math.max(1, deletionMarkers));
            final boolean trustedMapping = trustedBaselineConversations.contains(ck);

            // Count equality is NOT enough. For a historical row to become CONFIRMED we require
            // a singular explicit marker carrying a stable MessagingStyle timestamp and exactly
            // one missing row in this trusted conversation with that exact original timestamp.
            SnapshotItem exactTimestampMatch = null;
            int exactTimestampMatches = 0;
            boolean markerHasStableTimestamp = markerTarget == 1 && firstDeletionMarker != null
                    && firstDeletionMarker.stableTimestamp && firstDeletionMarker.timestamp > 0L;
            if (trustedMapping && markerHasStableTimestamp) {
                for (SnapshotItem x : missing) {
                    if (x == null || x.id <= 0) continue;
                    long originalTs = db.messageTimestamp(x.id);
                    if (originalTs == firstDeletionMarker.timestamp) {
                        exactTimestampMatches++;
                        exactTimestampMatch = x;
                    }
                }
            }

            boolean strongCorrelation = DeletionGuard.canConfirmSingularMessage(
                    trustedMapping, markerTarget, markerHasStableTimestamp, exactTimestampMatches);
            if (strongCorrelation && exactTimestampMatch != null) {
                int evidenceResult = db.confirmDeletedWithEvidence(exactTimestampMatch.id, evidenceKey);
                if (evidenceResult == VaultDb.EVIDENCE_CONFIRMED) {
                    markedDeleted = 1;
                    // Message state + evidence are already durable in one DB transaction. Media
                    // promotion is idempotent and recoverable by normalizeConfirmedMediaVisibility().
                    try { MediaArchiver.promotePendingForMessage(getApplicationContext(), exactTimestampMatch.id, db.messageTimestamp(exactTimestampMatch.id)); }
                    catch (Throwable t) { try { db.logEvent("MEDIA_PROMOTION_DEFERRED","Confirmed deletion durable; startup maintenance will retry",exactTimestampMatch.id,0L); } catch(Throwable ignored){} }
                    traceDeletion("DELETION_CONFIRMED_STRONG_MATCH", fallbackConversation, previousItems.size(), currentItems.size(), 1);
                    try { db.logEvent("DELETION_CONFIRMED",
                            "WHY_DETECTED=EXPLICIT_MARKER+EXACT_STABLE_TIMESTAMP · SOURCE_EVENT=REAL_POST · MATCH_METHOD=TIMESTAMP_1_TO_1 · CONFIDENCE=CONFIRMED",
                            exactTimestampMatch.id, 0L); } catch (Throwable ignored) {}
                    try { prefs.edit().remove("burst2_" + ck).apply(); } catch (Throwable ignored) {}
                } else {
                    String why = evidenceResult == VaultDb.EVIDENCE_DUPLICATE ? "DUPLICATE_EVIDENCE" : "EVIDENCE_TRANSACTION_FAILED";
                    traceDeletion("DELETION_REJECTED_"+why, fallbackConversation, previousItems.size(), currentItems.size(), 0);
                    try { db.logEvent("DELETION_REJECTED","WHY_DETECTED="+why+" · SOURCE_EVENT=REAL_POST · CONFIDENCE=REJECTED",exactTimestampMatch.id,0L); } catch(Throwable ignored){}
                }
            } else {
                // Android/WhatsApp do not expose a trustworthy native deleted-message id here.
                // Persist UNKNOWN evidence idempotently, but do not mutate messages or media.
                String why = !trustedMapping ? "UNTRUSTED_BASELINE"
                        : (!markerHasStableTimestamp ? "MARKER_WITHOUT_STABLE_TIMESTAMP"
                        : (markerTarget != 1 ? "PLURAL_OR_BATCH_MARKER_AMBIGUOUS"
                        : (exactTimestampMatches == 0 ? "NO_EXACT_TIMESTAMP_MATCH" : "MULTIPLE_TIMESTAMP_MATCHES")));
                int evidenceResult=db.recordDeletionEvidence(evidenceKey,0L,VaultDb.DELETE_NONE);
                if(evidenceResult==VaultDb.EVIDENCE_FAILED)why="EVIDENCE_PERSIST_FAILED_"+why;
                traceDeletion("DELETION_UNKNOWN_"+why, fallbackConversation, previousItems.size(), currentItems.size(), 0);
                try { db.logEvent("DELETION_UNKNOWN",
                        "WHY_DETECTED="+why+" · SOURCE_EVENT=REAL_POST · MATCH_METHOD=NONE · CONFIDENCE=UNKNOWN · markers="+markerTarget+" · missing="+missing.size(),
                        0L, 0L); } catch (Throwable ignored) {}
                recordUnverifiableDelete(why, fallbackConversation, markerTarget);
            }
        } else if (deletionMarkers > 0) {
            String why = !baselineReady ? "BASELINE_NOT_READY"
                    : (source != DeletionGuard.Source.REAL_POST ? "NON_REAL_EVENT_SOURCE"
                    : (sbn.getPostTime() < baselineReadyAt ? "STALE_REPLAY_POSTTIME"
                    : (evidenceKnown ? "DUPLICATE_EVIDENCE" : "EVIDENCE_KEY_UNAVAILABLE")));
            traceDeletion("DELETION_REJECTED_"+why, fallbackConversation, previousItems.size(), currentItems.size(), 0);
            try { db.logEvent("DELETION_REJECTED", "WHY_DETECTED="+why+" · SOURCE_EVENT="+source+" · markers="+deletionMarkers+" · missing="+missing.size(), 0L, 0L); } catch (Throwable ignored) {}
        } else if (!missing.isEmpty() && added.isEmpty() && !currentItems.isEmpty() && allTokenized(missing)) {
            // v0.5.8: a smaller MessagingStyle snapshot is NOT evidence of deletion. WhatsApp
            // routinely truncates/slides the visible notification window while messages arrive,
            // especially for repeated texts such as "j". Keep the newly parsed snapshot but do
            // not mutate historical rows. Remote-delete evidence must come from an explicit
            // marker or an app-driven removal that survives reconciliation.
            try { db.logEvent("SNAPSHOT_SHRINK_IGNORED", "Normalización de notificación · prev="+previousItems.size()+" · actual="+currentItems.size()+" · faltantes="+missing.size(), 0L, 0L); } catch (Throwable ignored) {}
            traceDeletion("SHRINK_IGNORED", fallbackConversation, previousItems.size(), currentItems.size(), 0);
        }

        // Presence may advance the baseline; absence never may. BASELINE_SYNC is authoritative
        // initialization. REAL_POST may advance only after baseline establishment. POLL_SYNC may
        // refresh a non-empty marker-free visible snapshot while LIVE, which recovers missed post
        // callbacks without ever turning a missing item into deletion evidence.
        boolean positiveIdentity = !currentItems.isEmpty() && allTokenized(currentItems);
        boolean liveRealPost = source == DeletionGuard.Source.REAL_POST
                && baselineReady && lifecyclePhase == DeletionGuard.Phase.LIVE;
        boolean safePollPresence = source == DeletionGuard.Source.POLL_SYNC
                && baselineReady && lifecyclePhase == DeletionGuard.Phase.LIVE
                && deletionMarkers == 0 && positiveIdentity;
        boolean baselinePresence = source == DeletionGuard.Source.BASELINE_SYNC && positiveIdentity;
        boolean normalLivePresence = liveRealPost && deletionMarkers == 0 && positiveIdentity;
        boolean confirmedLiveTransition = liveRealPost && markedDeleted > 0;
        boolean canAdvanceBaseline = baselinePresence || normalLivePresence || safePollPresence || confirmedLiveTransition;
        if (canAdvanceBaseline) {
            // One synchronous SharedPreferences commit persists the mutually dependent snapshot
            // generations together. If disk persistence fails, this conversation does NOT become
            // trusted for deletion decisions even though its messages are already safe in SQLite.
            String ck = conversationSnapshotKey(sbn, fallbackConversation);
            boolean persisted = persistAuthoritativeSnapshot(sbn, notificationKey, fallbackConversation, currentIds, currentItems, stateSignature);
            if (persisted) {
                snapshotPersistenceRetryKeys.remove(notificationKey);
                if (positiveIdentity) trustedBaselineConversations.add(ck);
                else trustedBaselineConversations.remove(ck);
            } else {
                snapshotPersistenceRetryKeys.add(notificationKey);
                trustedBaselineConversations.remove(ck);
                try { db.logEvent("BASELINE_PERSIST_FAILED","SOURCE_EVENT="+source+" · conversationTrust=REVOKED",0L,0L); } catch(Throwable ignored){}
            }
        } else if (source == DeletionGuard.Source.REAL_POST || source == DeletionGuard.Source.POLL_SYNC) {
            String retainWhy = !baselineReady ? "BASELINE_NOT_READY"
                    : (deletionMarkers > 0 ? "DELETE_MARKER_NOT_BASELINE" : "EMPTY_OR_NON_LIVE_STATE");
            try { db.logEvent("BASELINE_RETAINED",
                    "WHY_DETECTED="+retainWhy+" · SOURCE_EVENT="+source+" · previous="+previousItems.size()+" · current="+currentItems.size(),
                    0L, 0L); } catch (Throwable ignored) {}
        }
        if (!canAdvanceBaseline) prefs.edit().putString("state2_" + notificationKey, stateSignature).apply();

        if (!added.isEmpty() && newestMessageId > 0 && "audio".equals(newestMessageMediaKind)) {
            final long linkedId = newestMessageId;
            final long notificationTime = newestMessageTime > 0 ? newestMessageTime : sbn.getPostTime();
            scheduleMediaWatch(linkedId, notificationTime);
        }

        if (markedDeleted > 0) {
            diagnostic("Borrado detectado · " + markedDeleted + " mensaje(s)", parsed.size(), true);
        } else if (deletionMarkers > 0) {
            diagnostic("Señal de borrado no correlacionable · clasificada UNKNOWN", parsed.size(), false);
        } else if (sawOneTimeMarker) {
            if (!fromPoll) diagnostic("Medio Ver una vez detectado (no archivado)", parsed.size(), false);
        } else if (!fromPoll || !added.isEmpty()) {
            diagnostic("Mensaje(s) WhatsApp capturados", parsed.size(), false);
        }
    }

    private String firstConversation(List<Parsed> parsed) {
        if (parsed != null) for (Parsed p : parsed) {
            if (p != null && p.conversation != null && !p.conversation.trim().isEmpty()) return p.conversation.trim();
        }
        return "WhatsApp";
    }

    private static class SnapshotItem {
        final String token;
        final long id;
        SnapshotItem(String token, long id) { this.token = token == null ? "" : token; this.id = id; }
    }

    private String messageTokenBase(Parsed p) {
        if (p == null) return "";
        // MIME/URI availability can change while WhatsApp updates the same notification.
        // They are attachment state, not message identity. Excluding them prevents "Foto"/audio
        // from becoming a second logical message when the media URI appears a moment later.
        String raw = normalizeTokenPart(p.conversation) + "|" + normalizeTokenPart(p.sender) + "|"
                + normalizeTokenPart(p.text) + (p.stableTimestamp ? "|ts=" + p.timestamp : "");
        try { return MetadataPrivacy.token(getApplicationContext(),"msgtok",raw); }
        catch (Throwable t) { return ""; }
    }

    private String messageTokenForMsg(VaultDb.Msg m) {
        if(m==null)return "";
        String raw=normalizeTokenPart(m.conversation)+"|"+normalizeTokenPart(m.sender)+"|"+normalizeTokenPart(m.body)+"|ts="+m.timestamp;
        try{return MetadataPrivacy.token(getApplicationContext(),"msgtok",raw)+"#"+Math.max(1,m.identitySlot);}catch(Throwable t){return "";}
    }

    private String normalizeTokenPart(String value) {
        if (value == null) return "";
        return value.replace("\u200e", "").replace("\u200f", "")
                .replace("\u202a", "").replace("\u202b", "").replace("\u202c", "")
                .replace("\u2066", "").replace("\u2067", "").replace("\u2068", "").replace("\u2069", "")
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String conversationSnapshotKey(String conversation) {
        String normalized = normalizeTokenPart(conversation);
        try { return MetadataPrivacy.token(getApplicationContext(),"convsnap","legacy|"+normalized); }
        catch (Throwable t) { return "unavailable"; }
    }

    /** Package-scoped conversation identity. Shortcut id is preferred when WhatsApp exposes it. */
    private String conversationSnapshotKey(StatusBarNotification sbn,String conversation) {
        if(sbn==null)return "unavailable";
        String pkg=sbn.getPackageName()==null?"":sbn.getPackageName();
        String shortcut="";
        try{if(sbn.getNotification()!=null&&sbn.getNotification().getShortcutId()!=null)shortcut=sbn.getNotification().getShortcutId().trim();}catch(Throwable ignored){}
        String raw=pkg+"|"+(shortcut.isEmpty()?"conv:"+normalizeTokenPart(conversation):"shortcut:"+shortcut+"|conv:"+normalizeTokenPart(conversation));
        try{return MetadataPrivacy.token(getApplicationContext(),"convsnap",raw);}catch(Throwable t){return "unavailable";}
    }

    private List<SnapshotItem> missingItems(List<SnapshotItem> left, List<SnapshotItem> right) {
        List<SnapshotItem> out = new ArrayList<>();
        Set<String> rightTokens = new HashSet<>();
        Set<Long> rightIds = new HashSet<>();
        if (right != null) for (SnapshotItem x : right) {
            if (x == null) continue;
            if (!x.token.isEmpty()) rightTokens.add(x.token);
            if (x.id > 0) rightIds.add(x.id);
        }
        if (left != null) for (SnapshotItem x : left) {
            if (x == null) continue;
            boolean found = (!x.token.isEmpty() && rightTokens.contains(x.token)) || (x.id > 0 && rightIds.contains(x.id));
            if (!found) out.add(x);
        }
        return out;
    }

    private boolean allTokenized(List<SnapshotItem> items) {
        if (items == null || items.isEmpty()) return false;
        for (SnapshotItem x : items) if (x == null || x.token.isEmpty() || x.id <= 0) return false;
        return true;
    }

    private Set<String> tokenSet(List<SnapshotItem> items) {
        Set<String> out = new HashSet<>();
        if (items != null) for (SnapshotItem x : items) if (x != null && !x.token.isEmpty()) out.add(x.token);
        return out;
    }

    private List<String> parsedTokens(List<Parsed> parsed) {
        List<String> out = new ArrayList<>();
        Map<String,Integer> occurrences = new LinkedHashMap<>();
        if (parsed == null) return out;
        for (Parsed p : parsed) {
            if (p == null) continue;
            String low = p.text == null ? "" : p.text.toLowerCase(Locale.ROOT);
            if (deletedMarkerCount(low) > 0 || isOneTimeMarker(low) || shouldSkip(low)) continue;
            String base = messageTokenBase(p);
            int n = occurrences.containsKey(base) ? occurrences.get(base) + 1 : 1;
            occurrences.put(base, n);
            out.add(base + "#" + n);
        }
        return out;
    }

    private void scheduleProbableDeletionCheck(String notificationKey, String conversation, List<SnapshotItem> missing, Set<String> expectedCurrent) {
        // Legacy compatibility shim only. Snapshot absence/shrink is never deletion evidence.
        int count = missing == null ? 0 : missing.size();
        try { new VaultDb(getApplicationContext()).logEvent("DELETION_REJECTED",
                "WHY_DETECTED=LEGACY_SNAPSHOT_DIFF_DISABLED · SOURCE_EVENT=POLL_OR_SNAPSHOT · candidates="+count, 0L, 0L); } catch (Throwable ignored) {}
    }

    private List<Long> optimisticMark(VaultDb db, List<SnapshotItem> items, String code, String conversation) {
        // v0.5.30 fail-closed invariant: absence/snapshot shrink/APP_CANCEL never changes DB state.
        List<Long> out = new ArrayList<>();
        int count = items == null ? 0 : items.size();
        try { if (db != null) db.logEvent("DELETION_REJECTED", "WHY_DETECTED="+code+" · SOURCE_EVENT=SNAPSHOT_OR_REMOVAL · candidates="+count+" · "+conversation, 0L, 0L); } catch (Throwable ignored) {}
        return out;
    }

    private void rollbackOptimistic(List<Long> ids, String reason) {
        if (ids == null || ids.isEmpty()) return;
        VaultDb db = new VaultDb(getApplicationContext());
        int n = 0;
        for (Long id : ids) if (id != null && id > 0 && db.unmarkProbableDeletedById(id)) n++;
        try { db.logEvent("DELETE_OPTIMISTIC_ROLLBACK", reason + " · revertidos=" + n, 0L, 0L); } catch (Throwable ignored) {}
    }

    private void rejectDeletionCandidate(String reason, int count) {
        if (prefs != null) prefs.edit().putInt("pending_delete_candidates", 0).putString("last_delete_candidate_reject", reason).apply();
        try { new VaultDb(getApplicationContext()).logEvent("DELETE_CANDIDATE_REJECTED", reason + " · candidatos=" + count, 0L, 0L); } catch (Throwable ignored) {}
    }

    private boolean markDeletedAndKeepMedia(VaultDb db, long id, int deletionState) {
        if (db == null || id <= 0 || deletionState != VaultDb.DELETE_CONFIRMED) {
            try { if (db != null) db.logEvent("DELETION_REJECTED",
                    "WHY_DETECTED=NON_CONFIRMED_MUTATION_BLOCKED · SOURCE_EVENT=INTERNAL", id, 0L); } catch (Throwable ignored) {}
            return false;
        }
        boolean ok = db.markDeletedById(id, VaultDb.DELETE_CONFIRMED);
        if (ok) {
            try { MediaArchiver.promotePendingForMessage(getApplicationContext(), id, db.messageTimestamp(id)); } catch (Throwable ignored) {}
            try { db.logEvent("DELETE_CONFIRMED", "Mensaje marcado como borrado", id, 0L); } catch (Throwable ignored) {}
        }
        return ok;
    }

    private long markLatestDeletedAndKeepMedia(VaultDb db, String conversation, int deletionState) {
        // Never infer which historical row was deleted from conversation recency.
        try { if (db != null) db.logEvent("DELETION_REJECTED",
                "WHY_DETECTED=LEGACY_MARK_LATEST_DISABLED · SOURCE_EVENT=UNKNOWN · conversation="+conversation, 0L, 0L); } catch (Throwable ignored) {}
        return -1L;
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        if (sbn == null || !isWhatsApp(sbn.getPackageName())) return;
        lastWhatsAppEventAt=System.currentTimeMillis();
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        if (!baselineReady || lifecyclePhase != DeletionGuard.Phase.LIVE) {
            prefs.edit().putString("last_delete_path", "DELETION_REJECTED:SERVICE_OR_PROCESS_RESTART").apply();
            try { new VaultDb(getApplicationContext()).logEvent("DELETION_REJECTED", "WHY_DETECTED=REMOVAL_DURING_SYNC · SOURCE_EVENT=REAL_REMOVE · reason="+removalReasonName(reason), 0L, 0L); } catch (Throwable ignored) {}
            return;
        }

        final String oldKey = stableKey(sbn);
        final List<Long> oldIds = loadSnapshot(oldKey);
        String oldConversation = MetadataPrivacy.open(getApplicationContext(),prefs.getString("conv_" + oldKey, ""));
        if (oldConversation == null || oldConversation.trim().isEmpty()) oldConversation = conversationHint(sbn);
        List<SnapshotItem> oldItems = loadConversationSnapshot(sbn, oldConversation);
        if (oldItems.isEmpty()) oldItems = loadSnapshotV2(oldKey);
        if (oldItems.isEmpty()) for (Long id : oldIds) if (id != null && id > 0) oldItems.add(new SnapshotItem("", id));
        if (!oldItems.isEmpty() && oldConversation != null && !oldConversation.trim().isEmpty()) {
            saveRecentRemovedSnapshotV2(sbn, oldConversation, oldItems);
        }
        prefs.edit()
                .putLong("last_removed_at", System.currentTimeMillis())
                .putInt("last_removed_reason", reason)
                .putString("last_removed_reason_name", removalReasonName(reason))
                .putString("last_delete_path", "REMOVED:" + removalReasonName(reason))
                .apply();

        // Removal is notification lifecycle only. CLICK/swipe/clear-all and APP_CANCEL are all
        // non-authoritative for message deletion; only a later fresh REAL_POST marker may enter
        // the confirmation path, and only with one-to-one stable correlation.
        if (isAppDrivenRemovalReason(reason) && !oldItems.isEmpty()) {
            // APP_CANCEL is lifecycle evidence only. It is intentionally classified UNKNOWN and
            // never mutates message deletion state. If WhatsApp subsequently posts a literal
            // deletion marker, the REAL_POST path above performs the only allowed confirmation.
            traceDeletion("DELETION_CANDIDATE_APP_CANCEL_UNKNOWN", oldConversation, oldItems.size(), 0, 0);
            try { new VaultDb(getApplicationContext()).logEvent("DELETION_CANDIDATE", "WHY_DETECTED=APP_CANCEL · SOURCE_EVENT=REAL_REMOVE · classification=UNKNOWN · "+oldConversation, 0L, 0L); } catch (Throwable ignored) {}
            diagnostic("WhatsApp retiró la notificación · ausencia ≠ borrado", oldItems.size(), false);
        } else {
            // A click/swipe/clear-all ends this active notification snapshot. Keeping it around
            // would make a later identical "ok"/"Foto" look like the same old message.
            clearConversationIdentityState(sbn, oldConversation, oldKey);
            diagnostic("Notificación retirada por usuario/sistema · no es borrado", oldItems.size(), false);
        }
        try { MediaArchiver.resumePendingMonitors(getApplicationContext()); } catch (Throwable ignored) {}
    }

    private boolean shouldOpenDescriptor(Uri uri){
        if(uri==null)return false;long now=System.currentTimeMillis();String k=uri.toString();Long prev=recentDescriptorOpens.put(k,now);
        if(recentDescriptorOpens.size()>128)for(Map.Entry<String,Long> e:new ArrayList<>(recentDescriptorOpens.entrySet()))if(now-e.getValue()>30_000L)recentDescriptorOpens.remove(e.getKey(),e.getValue());
        return prev==null || now-prev>1500L;
    }

    private void clearConversationIdentityState(StatusBarNotification sbn,String conversation,String notificationKey){
        if(prefs==null)prefs=getSharedPreferences(DIAG_PREFS,MODE_PRIVATE);String ck=conversationSnapshotKey(sbn,conversation);
        snapshotPersistenceRetryKeys.remove(notificationKey);
        prefs.edit().remove("conv_snapshot2_"+ck).remove("burst2_"+ck)
                .remove("snapshot_"+notificationKey).remove("snapshot2_"+notificationKey).remove("state2_"+notificationKey).remove("conv_"+notificationKey).apply();
    }

    private String conversationHint(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) return "WhatsApp";
        Bundle e = sbn.getNotification().extras;
        String title = s(e.getCharSequence(Notification.EXTRA_TITLE));
        String conv = s(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        String sub = s(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        boolean group = false;
        try { group = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false); } catch (Throwable ignored) {}
        String out = group && !conv.isEmpty() ? conv : (!title.isEmpty() ? title : (!conv.isEmpty() ? conv : sub));
        return out.isEmpty() ? "WhatsApp" : out;
    }

    private boolean isAppDrivenRemovalReason(int reason) {
        // APP_CANCEL_ALL is a global notification lifecycle event (clear/regroup/etc.), not a
        // per-message deletion signal. Drop it before any candidate/probable work is scheduled.
        return reason == REASON_APP_CANCEL;
    }

    private String deletionEvidenceKey(StatusBarNotification sbn, String conversation, int markerCount,
                                       Parsed firstMarker, String stateSignature) {
        if (sbn == null || markerCount <= 0) return "";
        long markerTs = firstMarker != null && firstMarker.stableTimestamp ? firstMarker.timestamp : 0L;
        String markerText = firstMarker == null ? "" : normalizeDeleteMarkerText(firstMarker.text);
        String raw = sbn.getPackageName()+"|"+stableKey(sbn)+"|"+sbn.getPostTime()+"|"
                +conversationSnapshotKey(sbn,conversation)+"|"+markerCount+"|mts="+markerTs
                +"|mt="+markerText+"|state="+(stateSignature==null?"":stateSignature);
        try { return MetadataPrivacy.token(getApplicationContext(), "deleteevt", raw); }
        catch (Throwable t) { return ""; }
    }

    private void traceDeletion(String path, String conversation, int previous, int current, int marked) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        prefs.edit()
                .putString("last_delete_path", path == null ? "" : path)
                                .putInt("last_delete_previous", previous)
                .putInt("last_delete_current", current)
                .putInt("last_delete_marked", marked)
                .putLong("last_delete_trace_at", System.currentTimeMillis())
                .apply();
        try { new VaultDb(getApplicationContext()).logEvent("DELETE_TRACE", (path==null?"":path)+" · prev="+previous+" · current="+current+" · marked="+marked, 0L, 0L); } catch (Throwable ignored) {}
    }

    private void scheduleEmptyReplacementCheck(String notificationKey, String conversation, List<SnapshotItem> previous) {
        // Legacy compatibility shim only. Empty/temporarily unavailable notification state is
        // not deletion evidence and cannot mutate message state.
        int count = previous == null ? 0 : previous.size();
        traceDeletion("DELETION_REJECTED_EMPTY_STATE", conversation, count, 0, 0);
        try { new VaultDb(getApplicationContext()).logEvent("DELETION_REJECTED",
                "WHY_DETECTED=EMPTY_SNAPSHOT · SOURCE_EVENT=SNAPSHOT · candidates="+count, 0L, 0L); } catch (Throwable ignored) {}
    }

    /** v0.5.10: best-effort guard against treating "user opened WhatsApp" as remote deletion.
     * On modern Android this signal is not guaranteed for third-party processes, so an UNKNOWN
     * result is treated conservatively by the verifier rather than as proof of deletion. */
    private int whatsAppForegroundState() {
        try {
            android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
            if(am==null)return 0;
            List<android.app.ActivityManager.RunningAppProcessInfo> ps=am.getRunningAppProcesses();
            if(ps==null)return 0;
            boolean sawWa=false;
            for(android.app.ActivityManager.RunningAppProcessInfo pi:ps){
                if(pi==null||pi.pkgList==null)continue;
                boolean wa=false;
                for(String pkg:pi.pkgList)if(isWhatsApp(pkg)){wa=true;break;}
                if(!wa)continue;
                sawWa=true;
                if(pi.importance<=android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return 1;
            }
            return sawWa ? -1 : 0;
        }catch(Throwable ignored){return 0;}
    }

    private void reconcileProbableCandidate(VaultDb db, List<SnapshotItem> candidate, StatusBarNotification active) {
        if(db==null||candidate==null||candidate.isEmpty())return;
        Set<String> live=new HashSet<>();
        if(active!=null)live.addAll(parsedTokens(parseNotification(active)));
        for(SnapshotItem x:candidate){
            if(x==null||x.id<=0)continue;
            if(!x.token.isEmpty()&&live.contains(x.token))db.unmarkProbableDeletedById(x.id);
        }
    }

    /** v0.5.16: APP_CANCEL is never sufficient deletion proof by itself. We still keep the
     * two-stage reconciliation so transient reposts settle, but confirmation requires a literal
     * WhatsApp deletion marker. A vanished notification with no marker is rolled back and remains
     * ordinary history, preventing normal incoming messages from becoming "Mensaje borrado". */
    private void scheduleVerifiedRemovedPromotion(String notificationKey, String conversation, List<SnapshotItem> candidate, int reason, long generation) {
        // v0.5.30: retained only as a compatibility shim for historical source references.
        // Notification removal/APP_CANCEL is not deletion proof and can never promote rows.
        rollbackCandidateProbables(candidate, "APP_CANCEL/REMOVAL classification=UNKNOWN");
        removalCandidateGenerations.remove(conversationSnapshotKey(conversation), generation);
        recordUnverifiableDelete("APP_CANCEL/removal sin marcador REAL_POST", conversation, candidate==null?0:candidate.size());
        try { new VaultDb(getApplicationContext()).logEvent("DELETION_REJECTED",
                "WHY_DETECTED=APP_CANCEL_LEGACY_PATH · SOURCE_EVENT=REAL_REMOVE · classification=UNKNOWN", 0L, 0L); } catch (Throwable ignored) {}
    }

    private void rollbackCandidateProbables(List<SnapshotItem> candidate,String reason){
        VaultDb db=new VaultDb(getApplicationContext());int n=0;for(SnapshotItem x:candidate)if(x!=null&&x.id>0&&db.unmarkProbableDeletedById(x.id))n++;
        try{db.logEvent("DELETE_OPTIMISTIC_ROLLBACK",reason+" · revertidos="+n,0L,0L);}catch(Throwable ignored){}
    }

    private void scheduleRemovedDeletionCheck(String notificationKey, String conversation, List<SnapshotItem> previous, int reason) {
        // v0.5.30 hard fail-closed: a removal is absence/lifecycle only. No DB mutation, no
        // optimistic state, no delayed promotion. A later literal REAL_POST marker is handled by
        // the sole confirmation path in handleNotification().
        traceDeletion("DELETION_REJECTED_REMOVAL_LEGACY", conversation, previous==null?0:previous.size(), 0, 0);
        recordUnverifiableDelete("removal/APP_CANCEL", conversation, previous==null?0:previous.size());
    }

    /**
     * v0.5.11: expand an APP_CANCEL candidate backwards through a compact, contiguous DB burst.
     * This fixes mixed batches where WhatsApp's visible notification keeps only the newest
     * audio/photo/video rows and older text rows have already slid out. We deliberately expand
     * only backwards from the oldest known candidate, with a tight per-message gap and total span,
     * so ordinary older chat history is not swept into the deletion batch.
     */
    private List<SnapshotItem> expandRemovalCandidateWithDbCohort(VaultDb db, String conversation, List<SnapshotItem> base, int max) {
        List<SnapshotItem> out = mergeSnapshotItems(base, null, max);
        if (db == null || out.size() < 3 || conversation == null || conversation.trim().isEmpty()) return out;

        Set<Long> ids = new HashSet<>();
        long oldestKnown = Long.MAX_VALUE;
        long newestKnown = 0L;
        for (SnapshotItem x : out) {
            if (x == null || x.id <= 0) continue;
            ids.add(x.id);
            long ts = db.messageTimestamp(x.id);
            if (ts <= 0) continue;
            oldestKnown = Math.min(oldestKnown, ts);
            newestKnown = Math.max(newestKnown, ts);
        }
        if (oldestKnown == Long.MAX_VALUE || newestKnown <= 0) return out;

        long notBefore = oldestKnown - MIXED_BACKFILL_MAX_SPAN_MS;
        List<VaultDb.Msg> recent = db.listRecentBurstCandidatesForConversation(conversation, Math.min(256, Math.max(max * 3, 96)), notBefore);
        if (recent == null || recent.isEmpty()) return out;

        // listRecentBurstCandidatesForConversation is newest-first. We only want rows older than
        // the oldest active candidate; newer rows are either already in base or belong to a later
        // event and must not influence the backward chain.
        long frontier = oldestKnown;
        List<SnapshotItem> prefix = new ArrayList<>();
        for (VaultDb.Msg m : recent) {
            if (m == null || m.id <= 0 || m.timestamp <= 0 || ids.contains(m.id)) continue;
            if (m.timestamp >= oldestKnown) continue;
            long gap = frontier - m.timestamp;
            long span = newestKnown - m.timestamp;
            if (gap < 0) continue;
            if (gap > MIXED_BACKFILL_MAX_GAP_MS || span > MIXED_BACKFILL_MAX_SPAN_MS) break;
            // A previously confirmed row belongs to an older resolved deletion and is never reused.
            if (m.deleted && m.deletionState == VaultDb.DELETE_CONFIRMED) break;
            prefix.add(new SnapshotItem(messageTokenForMsg(m), m.id));
            ids.add(m.id);
            frontier = m.timestamp;
            if (out.size() + prefix.size() >= Math.max(1, max)) break;
        }

        // Preserve newest-first semantics: base is the active suffix; appended prefix is already
        // newest-to-oldest while walking backwards. mergeSnapshotItems also protects duplicates.
        return mergeSnapshotItems(out, prefix, max);
    }

    private void scheduleAudioRescue(long linkedMessageId, long notificationTime) {
        if (linkedMessageId <= 0) return;
        final android.content.Context app = getApplicationContext();
        final long ts = notificationTime > 0 ? notificationTime : System.currentTimeMillis();
        // FileObserver is primary. One generation of sparse retries services the whole current
        // pending-audio FIFO, so a voice-note burst cannot enqueue 8 expensive MediaStore scans
        // per message. Older message arms remain durable in SQLite and are reconciled by order.
        final long rescueGeneration=audioRescueToken.incrementAndGet();
        long[] delays = new long[]{0L, 250L, 1000L, 4000L, 10000L, 24000L};
        for (long delay : delays) {
            mediaScheduler.schedule(() -> {
                if(rescueGeneration!=audioRescueToken.get())return;
                try { DirectVoiceWatcher.fastScanAsync(app, linkedMessageId, ts); } catch (Throwable ignored) {}
                try {
                    int n = MediaArchiver.scanRecentAudioAggressive(app, 0L, ts);
                    if(n>0)new VaultDb(app).reconcilePendingMediaByOrder("audio");
                    if (n > 0 && prefs != null) prefs.edit().putString("notif_audio_status", "AUDIO PRESERVADO · pendiente de correlación segura").putLong("notif_audio_rescue_at", System.currentTimeMillis()).apply();
                } catch (Throwable t) { try{new VaultDb(app).logCaptureFailure("AUDIO_RESCUE","audio",linkedMessageId,t);}catch(Throwable ignored){} }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleMediaWatch(long linkedMessageId, long notificationTime) {
        final android.content.Context app = getApplicationContext();
        latestLinkedMessageId = linkedMessageId;
        latestNotificationTime = notificationTime > 0 ? notificationTime : System.currentTimeMillis();

        // Ultra-fast path: arm FileObserver/inotify immediately. This avoids a recursive
        // SAF walk for every incoming message and reacts when the .opus is actually created.
        try { DirectVoiceWatcher.armForMessage(app, linkedMessageId, latestNotificationTime); } catch (Throwable ignored) {}
        // Photos/videos are armed with an explicit type in handleNotification(). Do not create
        // a generic media arm here, because that can associate unrelated downloads. 

        final long token = mediaBurstToken.incrementAndGet();
        // Coalesced single-thread fallback. Older versions launched one long polling thread
        // per notification; bursts of messages could pile up dozens of directory crawls and
        // make capture progressively slower. Only the newest burst remains active now.
        long[] delays = DirectVoiceWatcher.isAvailable(app)
                ? new long[]{0L, 180L, 700L, 2200L}
                : new long[]{0L, 120L, 350L, 800L, 1600L, 3200L, 6000L, 12000L, 22000L};
        for (long delay : delays) {
            mediaScheduler.schedule(() -> {
                if (token != mediaBurstToken.get()) return;
                try {
                    long id = latestLinkedMessageId;
                    long ts = latestNotificationTime;
                    DirectVoiceWatcher.fastScanAsync(app, id, ts);
                    if (!DirectVoiceWatcher.isAvailable(app)) {
                        MediaArchiver.scanVoiceBankFast(app, id, ts);
                    }
                    // Do not scan the full photo/video library here. New manual downloads are
                    // captured by FileObserver/MediaStore only while a typed message is pending.
                } catch (Throwable ignored) {}
            }, delay, TimeUnit.MILLISECONDS);
        }
        mediaScheduler.schedule(() -> {
            if(token != mediaBurstToken.get()) return;
            try { MediaArchiver.resumePendingMonitors(app); } catch (Throwable ignored) {}
        }, 31, TimeUnit.SECONDS);
    }

    private void rememberConversationBurst(String conversation, String token, long id, long timestamp) {
        if (id <= 0) return;
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String key = "burst2_" + conversationSnapshotKey(conversation);
        long now = System.currentTimeMillis();
        List<String> keep = new ArrayList<>();
        String raw = prefs.getString(key, "");
        if (raw != null && !raw.isEmpty()) for (String part : raw.split(";")) {
            String[] f = part.split(",", 3);
            if (f.length < 3) continue;
            try { long ts=Long.parseLong(f[0]); long oldId=Long.parseLong(f[1]); if (now-ts <= AGGRESSIVE_BATCH_WINDOW_MS && oldId != id) keep.add(part); } catch (Throwable ignored) {}
        }
        keep.add(0, now + "," + id + "," + (token == null ? "" : token));
        while (keep.size() > 96) keep.remove(keep.size()-1);
        prefs.edit().putString(key, android.text.TextUtils.join(";", keep)).apply();
    }

    private List<SnapshotItem> loadRecentConversationBurst(String conversation, long windowMs, int max) {
        List<SnapshotItem> out = new ArrayList<>();
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String raw = prefs.getString("burst2_" + conversationSnapshotKey(conversation), "");
        if (raw == null || raw.isEmpty()) return out;
        long now=System.currentTimeMillis();
        for (String part : raw.split(";")) {
            if (out.size() >= Math.max(1,max)) break;
            String[] f=part.split(",",3); if(f.length<3)continue;
            try { long ts=Long.parseLong(f[0]); long id=Long.parseLong(f[1]); if(id>0 && now-ts<=windowMs) out.add(new SnapshotItem(f[2],id)); } catch(Throwable ignored){}
        }
        return out;
    }

    /**
     * Count a compact unread/capture burst from the current notification lifecycle. The burst is
     * reset on user-driven notification removal, so it is a stronger signal than a generic DB
     * history query. We only expand marker counts when at least three marker rows are present and
     * the replacement has no surviving normal messages.
     */
    private int compactActiveBurstCount(List<SnapshotItem> burst) {
        if (burst == null || burst.isEmpty()) return 0;
        VaultDb db = new VaultDb(getApplicationContext());
        int count = 0;
        long newest = 0L;
        long oldest = Long.MAX_VALUE;
        Set<Long> seen = new HashSet<>();
        for (SnapshotItem x : burst) {
            if (x == null || x.id <= 0 || seen.contains(x.id)) continue;
            long ts = db.messageTimestamp(x.id);
            if (ts <= 0) continue;
            seen.add(x.id);
            count++;
            newest = Math.max(newest, ts);
            oldest = Math.min(oldest, ts);
        }
        // "Seguidos" should be a genuinely compact notification burst, not a long conversation
        // history. v0.5.11 shares the mixed-batch bounds so short media-transfer pauses do not
        // split one user-selected delete batch into text-vs-media halves.
        if (count <= 1 || newest <= 0 || oldest == Long.MAX_VALUE || newest - oldest > MIXED_BACKFILL_MAX_SPAN_MS) return 0;
        return count;
    }

    /**
     * DB-backed fallback for builds/OEMs that clear or truncate the in-memory notification ledger.
     * Walk only the newest unresolved messages and stop at the first meaningful time gap. v0.5.11
     * allows short media-transfer pauses (35 s) while keeping the total reconstructed span bounded
     * to 120 s, so mixed text/audio/photo/video batches are not split in half.
     */
    private int compactRecentDbBurstCount(VaultDb db, String conversation, long referenceTime) {
        if (db == null || conversation == null || conversation.trim().isEmpty()) return 0;
        long ref = referenceTime > 0 ? referenceTime : System.currentTimeMillis();
        List<VaultDb.Msg> recent = db.listRecentBurstCandidatesForConversation(conversation, AGGRESSIVE_BATCH_MAX, ref - 180_000L);
        if (recent == null || recent.isEmpty()) return 0;
        int count = 0;
        long newest = 0L;
        long previous = 0L;
        for (VaultDb.Msg m : recent) {
            if (m == null || m.id <= 0 || m.timestamp <= 0) continue;
            if (count == 0) {
                if (Math.abs(ref - m.timestamp) > 180_000L) continue;
                newest = m.timestamp; previous = m.timestamp; count = 1;
                continue;
            }
            long gap = Math.abs(previous - m.timestamp);
            long span = Math.abs(newest - m.timestamp);
            if (gap > MIXED_BACKFILL_MAX_GAP_MS || span > MIXED_BACKFILL_MAX_SPAN_MS) break;
            previous = m.timestamp;
            count++;
        }
        return count;
    }

    private List<SnapshotItem> mergeSnapshotItems(List<SnapshotItem> a, List<SnapshotItem> b, int max) {
        List<SnapshotItem> out=new ArrayList<>(); Set<Long> ids=new HashSet<>();
        for(List<SnapshotItem> src:new List[]{a,b}) if(src!=null) for(SnapshotItem x:src){ if(x==null||x.id<=0||ids.contains(x.id))continue;out.add(x);ids.add(x.id);if(out.size()>=max)return out; }
        return out;
    }

    private StatusBarNotification findActiveConversation(String conversation, String removedKey) {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return null;
            String wanted = conversation == null ? "" : conversation.trim();
            for (StatusBarNotification a : active) {
                if (a == null || !isWhatsApp(a.getPackageName())) continue;
                if (stableKey(a).equals(removedKey)) return a;
                if (!wanted.isEmpty()) {
                    List<Parsed> ps = parseNotification(a);
                    for (Parsed p : ps) {
                        if (p != null && wanted.equalsIgnoreCase(p.conversation)) return a;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String removalReasonName(int reason) {
        switch (reason) {
            case REASON_CLICK: return "CLICK";
            case REASON_CANCEL: return "USER_CANCEL";
            case REASON_CANCEL_ALL: return "USER_CANCEL_ALL";
            case REASON_APP_CANCEL: return "APP_CANCEL";
            case REASON_APP_CANCEL_ALL: return "APP_CANCEL_ALL";
            case REASON_GROUP_SUMMARY_CANCELED: return "GROUP_SUMMARY";
            case REASON_GROUP_OPTIMIZATION: return "GROUP_OPTIMIZATION";
            case REASON_TIMEOUT: return "TIMEOUT";
            default: return "REASON_" + reason;
        }
    }

    /** Public only for deterministic integrity testing: APP_CANCEL needs literal delete evidence. */
    public static boolean appCancelIsConfirmable(boolean explicitDeleteMarker){return explicitDeleteMarker;}

    /** v0.5.14 strict placeholder parser. Free-form sentences never arm multimedia capture. */
    public static String strictMediaKindForText(String text) {
        String b=normalizeMediaPlaceholder(text);
        if(b.isEmpty())return "";
        if(b.equals("sticker")||b.equals("pegatina")||b.equals("autocollant")||b.equals("figurinha")||b.equals("adhesivo"))return "";
        if(b.equals("audio")||b.equals("voz")||b.equals("voice message")||b.equals("audio message")||b.equals("mensaje de voz")||b.equals("mensaje de audio")||b.equals("nota de voz")||b.equals("message vocal")||b.equals("message audio")||b.equals("mensagem de voz")||b.equals("áudio"))return "audio";
        if(b.equals("foto")||b.equals("photo")||b.equals("imagen")||b.equals("image")||b.equals("gif"))return "image";
        if(b.equals("video")||b.equals("vídeo"))return "video";
        if(b.equals("documento")||b.equals("document")||b.equals("archivo adjunto")||b.equals("pièce jointe")||b.equals("arquivo")||b.matches("^[^\\s]+\\.(pdf|docx?|xlsx?|pptx?|zip|rar|7z|csv|rtf|epub)$"))return "document";
        return "";
    }
    public static boolean isExactStickerPlaceholder(String text){
        String b=normalizeMediaPlaceholder(text);return b.equals("sticker")||b.equals("pegatina")||b.equals("autocollant")||b.equals("figurinha")||b.equals("adhesivo");
    }
    private static String normalizeMediaPlaceholder(String text){
        if(text==null)return "";String b=text.trim().toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+","").replaceAll("[^\\p{L}\\p{N}.:]+$","");return b.replaceAll("\\s+"," ").trim();
    }

    private static class Parsed {
        final String conversation, sender, text;
        final long timestamp;
        final Uri dataUri;
        final String dataMime;
        final boolean isGroup;
        final int messageIndex;
        final boolean stableTimestamp;
        Parsed(String conversation, String sender, String text, long timestamp, boolean isGroup, int messageIndex) {
            this(conversation, sender, text, timestamp, null, "", isGroup, messageIndex, false);
        }
        Parsed(String conversation, String sender, String text, long timestamp, Uri dataUri, String dataMime, boolean isGroup, int messageIndex) {
            this(conversation, sender, text, timestamp, dataUri, dataMime, isGroup, messageIndex, false);
        }
        Parsed(String conversation, String sender, String text, long timestamp, Uri dataUri, String dataMime, boolean isGroup, int messageIndex, boolean stableTimestamp) {
            this.conversation = conversation;
            this.sender = sender;
            this.text = text;
            this.timestamp = timestamp;
            this.dataUri = dataUri;
            this.dataMime = dataMime == null ? "" : dataMime;
            this.isGroup = isGroup;
            this.messageIndex = messageIndex;
            this.stableTimestamp = stableTimestamp;
        }
        String mediaKind() {
            if(isExactStickerPlaceholder(text))return "";
            String m=dataMime==null?"":dataMime.toLowerCase(Locale.ROOT);
            if(m.startsWith("audio/")||m.contains("opus")||m.contains("ogg")||m.contains("amr")||m.contains("aac")||m.contains("mpeg")||m.contains("wav"))return "audio";
            if(m.startsWith("image/"))return "image";if(m.startsWith("video/"))return "video";
            if(m.startsWith("application/")||m.startsWith("text/")||m.contains("pdf")||m.contains("document")||m.contains("zip"))return "document";
            return strictMediaKindForText(text);
        }
        boolean hasMediaData() {
            if(isExactStickerPlaceholder(text))return false;
            String m = dataMime == null ? "" : dataMime.toLowerCase(Locale.ROOT);
            return dataUri != null && (m.startsWith("audio/") || m.startsWith("image/") || m.startsWith("video/") || m.startsWith("application/") || m.startsWith("text/") || m.contains("opus") || m.contains("ogg") || m.contains("amr") || m.contains("aac") || m.contains("mpeg") || m.contains("wav") || m.contains("pdf") || m.contains("zip"));
        }
    }

    private Uri findMediaUri(Bundle b) {
        if (b == null) return null;
        try {
            for (String key : b.keySet()) {
                if (key == null) continue;
                String k=key.toLowerCase(Locale.ROOT);
                if (!(k.contains("uri") || k.contains("media") || k.contains("image") || k.contains("picture") || k.contains("thumb") || k.contains("preview"))) continue;
                Object v; try { v=b.get(key); } catch(Throwable t){continue;}
                if (v instanceof Uri) return (Uri)v;
                if (v instanceof String) {
                    String x=((String)v).trim();
                    if (x.startsWith("content://")) try { return Uri.parse(x); } catch(Throwable ignored){}
                }
                if (v instanceof Bundle) { Uri nested=findMediaUri((Bundle)v); if(nested!=null)return nested; }
            }
        } catch(Throwable ignored){}
        return null;
    }

    private List<Parsed> parseNotification(StatusBarNotification sbn) {
        List<Parsed> out = new ArrayList<>();
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        String title = s(e.getCharSequence(Notification.EXTRA_TITLE));
        String sub = s(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String conversationTitle = s(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
        boolean isGroup = false;
        boolean explicitGroupFlag = false;
        try {
            explicitGroupFlag = e.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION);
            isGroup = e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        } catch (Throwable ignored) {}
        // Do not blindly treat any conversation title as a group: some OEM/WhatsApp builds
        // also populate it for 1:1 chats. Only infer a group when there is no explicit flag and
        // the conversation title is meaningfully different from the notification title.
        if (!explicitGroupFlag && !conversationTitle.isEmpty() && !conversationTitle.equalsIgnoreCase(title)) isGroup = true;
        String conversation;
        if (isGroup) conversation = !conversationTitle.isEmpty() ? conversationTitle : (!sub.isEmpty() ? sub : title);
        else conversation = !title.isEmpty() ? title : (!conversationTitle.isEmpty() ? conversationTitle : sub);
        if (conversation.isEmpty()) conversation = "WhatsApp";

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Parcelable[] bundles = e.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (bundles != null && bundles.length > 0) {
                    List<Notification.MessagingStyle.Message> messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
                    int msgIndex = 0;
                    for (Notification.MessagingStyle.Message m : messages) {
                        if (m == null) { msgIndex++; continue; }
                        String text = s(m.getText());
                        Uri dataUri = null;
                        String dataMime = "";
                        try { dataUri = m.getDataUri(); } catch (Throwable ignored) {}
                        try { dataMime = m.getDataMimeType(); } catch (Throwable ignored) {}
                        if (dataUri == null && dataMime != null && !dataMime.trim().isEmpty()) {
                            String declared=dataMime.toLowerCase(Locale.ROOT);
                            if(declared.startsWith("audio/")||declared.startsWith("image/")||declared.startsWith("video/")||declared.startsWith("application/"))
                                try { dataUri = findMediaUri(m.getExtras()); } catch (Throwable ignored) {}
                        }
                        if (dataUri != null && (dataMime == null || dataMime.isEmpty())) {
                            try { dataMime = getContentResolver().getType(dataUri); } catch (Throwable ignored) {}
                        }
                        boolean audioData = dataUri != null && isAudioMime(dataMime);
                        String dmLow = dataMime == null ? "" : dataMime.toLowerCase(Locale.ROOT);
                        if (text.isEmpty() && audioData) text = "Nota de voz";
                        else if (text.isEmpty() && dataUri != null && dmLow.startsWith("image/")) text = "Foto";
                        else if (text.isEmpty() && dataUri != null && dmLow.startsWith("video/")) text = "Video";
                        if (text.isEmpty()) { msgIndex++; continue; }
                        Person person = m.getSenderPerson();
                        String sender = person == null ? "" : s(person.getName());
                        if (sender.isEmpty()) sender = title.isEmpty() ? conversation : title;
                        boolean stableTs = m.getTimestamp() > 0;
                        long ts = stableTs ? m.getTimestamp() : sbn.getPostTime();
                        out.add(new Parsed(conversation, sender, text, ts, dataUri, dataMime, isGroup, msgIndex, stableTs));
                        msgIndex++;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (out.isEmpty()) {
            try {
                Parcelable[] raw = e.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (raw != null) {
                    int msgIndex = 0;
                    for (Parcelable parcel : raw) {
                        if (!(parcel instanceof Bundle)) continue;
                        Bundle b = (Bundle) parcel;
                        CharSequence tc = b.getCharSequence("text");
                        String text = s(tc);
                        long rawTs = b.getLong("time", 0L);
                        boolean stableTs = rawTs > 0L;
                        long ts = stableTs ? rawTs : sbn.getPostTime();
                        String sender = s(b.getCharSequence("sender"));
                        if (sender.isEmpty()) sender = title.isEmpty() ? conversation : title;
                        String dm = b.getString("type", "");
                        Uri du = null;
                        try {
                            Parcelable u = b.getParcelable("uri");
                            if (u instanceof Uri) du = (Uri) u;
                        } catch (Throwable ignored) {}
                        if (du == null && dm != null && !dm.trim().isEmpty()) {
                            String declared=dm.toLowerCase(Locale.ROOT);
                            if(declared.startsWith("audio/")||declared.startsWith("image/")||declared.startsWith("video/")||declared.startsWith("application/"))
                                try { du = findMediaUri(b); } catch (Throwable ignored) {}
                        }
                        if (du != null && (dm == null || dm.isEmpty())) try { dm = getContentResolver().getType(du); } catch (Throwable ignored) {}
                        boolean audioData = du != null && isAudioMime(dm);
                        String dmLow = dm == null ? "" : dm.toLowerCase(Locale.ROOT);
                        if (text.isEmpty() && audioData) text = "Nota de voz";
                        else if (text.isEmpty() && du != null && dmLow.startsWith("image/")) text = "Foto";
                        else if (text.isEmpty() && du != null && dmLow.startsWith("video/")) text = "Video";
                        if (!text.isEmpty()) out.add(new Parsed(conversation, sender, text, ts, du, dm, isGroup, msgIndex, stableTs));
                        msgIndex++;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (!out.isEmpty()) return out;

        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            int index = 0;
            for (CharSequence line : lines) {
                String text = s(line);
                if (text.isEmpty()) continue;
                out.add(new Parsed(conversation, title.isEmpty() ? conversation : title, text, sbn.getPostTime() + index, isGroup, index));
                index++;
            }
            if (!out.isEmpty()) return out;
        }

        String text = s(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = s(e.getCharSequence(Notification.EXTRA_TEXT));
        if (!text.isEmpty()) out.add(new Parsed(conversation, title.isEmpty() ? conversation : title, text, sbn.getPostTime(), isGroup, 0));
        return out;
    }

    private static boolean isAudioMime(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return m.startsWith("audio/") || m.contains("opus") || m.contains("ogg") || m.contains("amr") || m.contains("aac") || m.contains("mpeg") || m.contains("wav");
    }

    private boolean isDeletedMarker(String low) { return deletedMarkerCount(low) > 0; }

    /** Exact singular WhatsApp deletion markers only. Plural delete-looking phrases remain ordinary
     * message text because Android exposes no native per-message IDs for safely mapping a batch. */
    private int deletedMarkerCount(String low) {
        if (low == null) return 0;
        String t = normalizeDeleteMarkerText(low);
        int colon = t.indexOf(": ");
        if (colon > 0) {
            String suffix = t.substring(colon + 2).trim();
            if (isExactDeleteText(suffix)) t = suffix;
        }
        return isExactDeleteText(t) ? 1 : 0;
    }

    private String normalizeDeleteMarkerText(String value){
        if(value==null)return "";
        String t=value.replace("\u200b","").replace("\u200c","").replace("\u200d","").replace("\ufeff","")
                .replace('\u00a0',' ');
        t=normalizeTokenPart(t);
        while(!t.isEmpty() && (t.endsWith(".")||t.endsWith("!")||t.endsWith(":")||t.endsWith("…")))
            t=t.substring(0,t.length()-1).trim();
        return t;
    }

    private boolean isExactDeleteText(String t) {
        if (t == null) return false;
        // Full WhatsApp-style marker sentences only. Generic fragments remain ordinary chat.
        return t.equals("este mensaje fue eliminado")
                || t.equals("se eliminó este mensaje")
                || t.equals("eliminaste este mensaje")
                || t.equals("this message was deleted")
                || t.equals("you deleted this message")
                || t.equals("ce message a été supprimé")
                || t.equals("vous avez supprimé ce message")
                || t.equals("esta mensagem foi apagada")
                || t.equals("você apagou esta mensagem");
    }

    private int pluralDeleteCount(String t) {
        if (t == null || t.isEmpty()) return 0;
        String[] patterns = new String[]{
                "mensajes eliminados", "mensajes fueron eliminados", "mensajes borrados",
                "messages deleted", "messages were deleted",
                "messages supprimés", "messages ont été supprimés",
                "mensagens apagadas", "mensagens foram apagadas"
        };
        for (String phrase : patterns) {
            if (t.endsWith(" " + phrase)) {
                String prefix = t.substring(0, t.length() - phrase.length()).trim();
                try {
                    int n = Integer.parseInt(prefix);
                    return n>=2 ? Math.min(100,n) : 0;
                } catch (Throwable ignored) {}
            }
        }
        return 0;
    }

    /** Strong plural-marker gate: exactly one MessagingStyle plural candidate, all previous rows
     * tokenized, exactly N previous tokens missing, and zero newly-added normal tokens. */
    private int structuredPluralDeleteCount(List<Parsed> parsed,List<SnapshotItem> previous){
        if(parsed==null||previous==null||previous.size()<2||!allTokenized(previous))return 0;
        Parsed candidate=null;int count=0,candidates=0;
        List<String> preview=new ArrayList<>();Map<String,Integer> occurrences=new LinkedHashMap<>();
        for(Parsed p:parsed){
            if(p==null)continue;String low=p.text==null?"":p.text.toLowerCase(Locale.ROOT);String norm=normalizeDeleteMarkerText(low);
            if(isExactDeleteText(norm))continue;
            int plural=pluralDeleteCount(norm);
            if(plural>0){candidate=p;count=plural;candidates++;continue;}
            if(isOneTimeMarker(low)||shouldSkip(low))continue;
            String base=messageTokenBase(p);int n=occurrences.containsKey(base)?occurrences.get(base)+1:1;occurrences.put(base,n);preview.add(base+"#"+n);
        }
        if(candidates!=1||candidate==null||!candidate.stableTimestamp||candidate.dataUri!=null||!(candidate.dataMime==null||candidate.dataMime.isEmpty()))return 0;
        Set<String> before=new HashSet<>(),after=new HashSet<>(preview);for(SnapshotItem x:previous)before.add(x.token);
        int missing=0,added=0;for(String x:before)if(!after.contains(x))missing++;for(String x:after)if(!before.contains(x))added++;
        return added==0&&missing==count&&count<=previous.size()?count:0;
    }

    private void recordUnverifiableDelete(String reason,String conversation,int candidates){
        try{
            if(prefs==null)prefs=getSharedPreferences(DIAG_PREFS,MODE_PRIVATE);
            int add=Math.max(1,candidates),total=prefs.getInt("delete_unverifiable_count",0)+add;
            prefs.edit().putInt("delete_unverifiable_count",total).putLong("delete_unverifiable_at",System.currentTimeMillis())
                    .putString("delete_unverifiable_last",reason==null?"":reason).apply();
            new VaultDb(getApplicationContext()).logEvent("DELETE_UNVERIFIABLE","Candidato oculto sin prueba fuerte · "+(reason==null?"":reason)+" · n="+add,0L,0L);
        }catch(Throwable ignored){}
    }

    private boolean isOneTimeMarker(String low) {
        return low.contains("ver una vez") || low.contains("view once") || low.contains("voir une fois");
    }

    private boolean shouldSkip(String low) {
        String t = low.trim();
        return t.isEmpty()
                || t.equals("sticker") || t.equals("pegatina") || t.equals("autocollant") || t.equals("figurinha")
                || t.equals("checking for new messages")
                || t.equals("buscando mensajes nuevos")
                || t.equals("recherche de nouveaux messages");
    }

    private String stableKey(StatusBarNotification sbn) {
        if(sbn==null)return "";
        String raw = sbn.getPackageName() + "|" + (sbn.getKey() == null ? (sbn.getId() + "|" + sbn.getTag()) : sbn.getKey());
        try{return MetadataPrivacy.token(getApplicationContext(),"notif",raw);}catch(Throwable t){return "";}
    }

    private String stateSignature(List<Long> ids, int deletedMarkers, boolean once) {
        StringBuilder b = new StringBuilder();
        for (Long id : ids) {
            if (b.length() > 0) b.append(',');
            b.append(id == null ? 0 : id);
        }
        b.append("|d=").append(deletedMarkers).append("|o=").append(once ? 1 : 0);
        return b.toString();
    }

    private List<Long> loadSnapshot(String key) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String raw = prefs.getString("snapshot_" + key, "");
        List<Long> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String p : raw.split(",")) {
            try { out.add(Long.parseLong(p)); } catch (Throwable ignored) {}
        }
        return out;
    }

    private String encodeSnapshotIds(List<Long> ids) {
        StringBuilder b = new StringBuilder();
        if(ids!=null)for (Long id : ids) {
            if (id == null || id <= 0) continue;
            if (b.length() > 0) b.append(',');
            b.append(id);
        }
        return b.toString();
    }

    private void saveSnapshot(String key, List<Long> ids) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        prefs.edit().putString("snapshot_" + key, encodeSnapshotIds(ids)).commit();
    }

    private void saveRecentRemovedSnapshot(String conversation, List<Long> ids) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String key = conversationSnapshotKey(conversation);
        StringBuilder b = new StringBuilder();
        if (ids != null) for (Long id : ids) {
            if (id == null || id <= 0) continue;
            if (b.length() > 0) b.append(',');
            b.append(id);
        }
        prefs.edit()
                .putString("recent_removed_ids_" + key, b.toString())
                .putLong("recent_removed_at_" + key, System.currentTimeMillis())
                .apply();
    }

    private void saveRecentRemovedSnapshotV2(StatusBarNotification sbn,String conversation, List<SnapshotItem> items) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String key = conversationSnapshotKey(sbn,conversation);
        saveSnapshotItems("recent_removed_snapshot2_" + key, items);
        prefs.edit().putLong("recent_removed_at_" + key, System.currentTimeMillis()).apply();
    }

    private List<SnapshotItem> loadRecentRemovedSnapshot(StatusBarNotification sbn,String conversation) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String key = conversationSnapshotKey(sbn,conversation);
        long at = prefs.getLong("recent_removed_at_" + key, 0L);
        if (at <= 0 || System.currentTimeMillis() - at > REMOVED_SNAPSHOT_GRACE_MS) return new ArrayList<>();
        List<SnapshotItem> v2 = loadSnapshotItems("recent_removed_snapshot2_" + key);
        if (!v2.isEmpty()) return v2;
        String raw = prefs.getString("recent_removed_ids_" + key, "");
        List<SnapshotItem> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0) out.add(new SnapshotItem("", id));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private String stateSignatureV2(List<SnapshotItem> items, int deletedMarkers, boolean once) {
        StringBuilder b = new StringBuilder();
        if (items != null) for (SnapshotItem x : items) {
            if (x == null) continue;
            if (b.length() > 0) b.append(',');
            b.append(x.token.isEmpty() ? ("id:" + x.id) : x.token);
        }
        b.append("|d=").append(deletedMarkers).append("|o=").append(once ? 1 : 0);
        return b.toString();
    }

    private List<SnapshotItem> loadSnapshotV2(String key) {
        return loadSnapshotItems("snapshot2_" + key);
    }

    private void saveSnapshotV2(String key, List<SnapshotItem> items) {
        saveSnapshotItems("snapshot2_" + key, items);
    }

    private List<SnapshotItem> loadConversationSnapshot(StatusBarNotification sbn,String conversation) {
        return loadSnapshotItems("conv_snapshot2_" + conversationSnapshotKey(sbn,conversation));
    }

    private List<SnapshotItem> loadConversationSnapshot(String conversation) {
        return loadSnapshotItems("conv_snapshot2_" + conversationSnapshotKey(conversation));
    }

    private void saveConversationSnapshot(String conversation, List<SnapshotItem> items) {
        saveSnapshotItems("conv_snapshot2_" + conversationSnapshotKey(conversation), items);
    }

    private List<SnapshotItem> loadSnapshotItems(String prefKey) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        String raw = prefs.getString(prefKey, "");
        List<SnapshotItem> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            int at = part.lastIndexOf('@');
            if (at <= 0 || at >= part.length()-1) continue;
            try {
                String token = part.substring(0, at);
                long id = Long.parseLong(part.substring(at+1));
                if (id > 0) out.add(new SnapshotItem(token, id));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private String encodeSnapshotItems(List<SnapshotItem> items) {
        StringBuilder b = new StringBuilder();
        if (items != null) for (SnapshotItem x : items) {
            if (x == null || x.id <= 0 || x.token.isEmpty()) continue;
            if (b.length() > 0) b.append(',');
            b.append(x.token).append('@').append(x.id);
        }
        return b.toString();
    }

    private boolean persistAuthoritativeSnapshot(StatusBarNotification sbn,String notificationKey,String conversation,List<Long> ids,List<SnapshotItem> items,String stateSignature){
        if(prefs==null)prefs=getSharedPreferences(DIAG_PREFS,MODE_PRIVATE);
        String ck=conversationSnapshotKey(sbn,conversation);
        try{
            return prefs.edit()
                    .putString("snapshot_"+notificationKey,encodeSnapshotIds(ids))
                    .putString("snapshot2_"+notificationKey,encodeSnapshotItems(items))
                    .putString("conv_snapshot2_"+ck,encodeSnapshotItems(items))
                    .putString("conv_"+notificationKey,MetadataPrivacy.seal(getApplicationContext(),conversation))
                    .putString("state2_"+notificationKey,stateSignature==null?"":stateSignature)
                    .commit();
        }catch(Throwable t){return false;}
    }

    private void saveSnapshotItems(String prefKey, List<SnapshotItem> items) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        prefs.edit().putString(prefKey, encodeSnapshotItems(items)).commit();
    }

    private void diagnostic(String event, int parsedCount, boolean deletionSignal) {
        if (prefs == null) prefs = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE);
        prefs.edit()
                .putLong("last_whatsapp_at", System.currentTimeMillis())
                .putString("last_event", event)
                .putInt("last_parsed_count", parsedCount)
                .putBoolean("last_deletion_signal", deletionSignal)
                .apply();
        try { new VaultDb(getApplicationContext()).logEvent("DIAG", event + " · parsed=" + parsedCount, 0L, 0L); } catch (Throwable ignored) {}
    }
}
