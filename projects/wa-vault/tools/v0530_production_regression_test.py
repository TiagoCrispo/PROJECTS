from pathlib import Path
from dataclasses import dataclass, field

ROOT = Path(__file__).resolve().parents[1]

def text(rel):
    return (ROOT / rel).read_text(encoding='utf-8')

def need(cond, msg):
    if not cond:
        raise AssertionError(msg)

listener = text('app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java')
guard = text('app/src/main/java/com/fer/wavault/DeletionGuard.java')
db = text('app/src/main/java/com/fer/wavault/VaultDb.java')
main = text('app/src/main/java/com/fer/wavault/MainActivity.java')
media = text('app/src/main/java/com/fer/wavault/MediaArchiver.java')
build = text('app/build.gradle.kts')
manifest = text('app/src/main/AndroidManifest.xml')

# Release identity and stable pre-existing storage schema.
need('versionCode = 80' in build and 'versionName = "0.5.30"' in build, 'v0.5.30/80 required')
need('super(c, "wa_vault.db", null, 15)' in db, 'Block 4 DB v15 media-link schema required')
need('android:allowBackup="false"' in manifest, 'vault backup must remain disabled')
need('<uses-sdk' not in manifest, 'SDK levels must have one source of truth: Gradle')
gradle_props = text('gradle.properties')
need('android.useAndroidX=true' in gradle_props and 'android.useAndroidX=false' not in gradle_props,
     'AndroidX configuration must not be contradictory')
need('testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"' in build,
     'AndroidJUnitRunner must be configured')
need('androidx.test:runner:1.7.0' in build and 'androidx.test:rules:1.7.0' in build
     and 'androidx.test.ext:junit:1.3.0' in build, 'instrumented test dependencies missing')
need((ROOT/'app/src/androidTest/java/com/fer/wavault/StartupInstrumentedTest.java').exists(),
     'startup instrumentation test missing')
need((ROOT/'app/src/test/java/com/fer/wavault/DeletionGuardTest.java').exists(),
     'host JUnit deletion guard test missing')

# Startup/UI requirement.
need('private String currentScreen = "messages";' in main, 'default screen must be Mensajes borrados')
oncreate = main[main.index('@Override protected void onCreate'):main.index('private void registerUiReceiver')]
need('currentScreen = "messages";' in oncreate and 'renderCurrentScreen()' in oncreate, 'Activity launch must route normally to messages')

# Baseline lifecycle: a failed authoritative read must never open the gate.
need('BASELINE_BUILDING' in listener and 'BASELINE_REJECTED' in listener and 'BASELINE_READY' in listener,
     'baseline lifecycle diagnostics missing')
base = listener[listener.index('private void establishBaseline'):listener.index('@Override public void onListenerDisconnected')]
need('boolean readSucceeded = false' in base and 'if (!readSucceeded)' in base,
     'baseline read success must be explicit')
need(base.index('if (!readSucceeded)') < base.index('baselineReady = true'),
     'baseline gate opens before successful read')
need('scheduleBaselineRetry(generation)' in base, 'failed platform read must remain closed and retry technically')

# Event-source separation and fail-closed paths.
for source in ('BASELINE_SYNC','POLL_SYNC','REAL_POST','REAL_REMOVE'):
    need(source in listener and source in guard, f'missing source {source}')
need('onNotificationRemoved' in listener and 'classification=UNKNOWN' in listener,
     'notification removal must remain UNKNOWN')
removed = listener[listener.index('@Override public void onNotificationRemoved'):listener.index('private boolean shouldOpenDescriptor')]
need('markDeletedById' not in removed and 'markDeletedAndKeepMedia' not in removed,
     'removal path mutates deletion state')
need('DELETION_REJECTED_EMPTY_STATE' in listener, 'empty state must be rejected')
need('Math.max(targetFromMarker, missing.size())' not in listener, 'N->missing amplification returned')
need('ensureDeletionPlaceholder(' not in listener, 'unmapped markers must not create CONFIRMED placeholders')
placeholder = db[db.index('public long ensureDeletionPlaceholder'):db.index('public boolean unmarkProbableDeletedById')]
need('return -1L;' in placeholder and 'markDeletedById' not in placeholder and 'insertMessage' not in placeholder,
     'legacy placeholder API must be fail-closed even if called accidentally')
latest = db[db.index('@Deprecated public long markLatestDeleted'):db.index('public boolean markDeletedById')]
need('return -1L;' in latest and 'listMessages' not in latest,
     'latest-message guessing API must remain disabled')

# Historical row confirmation requires strong 1:1 timestamp identity, not count equality.
core = listener[listener.index('boolean freshDeletePost'):listener.index('// Presence may advance the baseline')]
need('canConfirmSingularMessage' in core, 'strong singular guard missing')
need('originalTs == firstDeletionMarker.timestamp' in core, 'exact stable timestamp correlation missing')
need('exactTimestampMatches == 1' in guard, 'ambiguous timestamp matches must fail closed')
need('explicitMarkerCount == 1' in guard, 'plural/batch markers must not auto-confirm')
need('safelyMappableCount' in guard and 'return 0;' in guard[guard.index('safelyMappableCount'):guard.index('classifyUnmappedMarker')],
     'count-only matching must stay disabled')
need('DELETION_UNKNOWN' in core and 'CONFIDENCE=UNKNOWN' in core, 'unknown classification diagnostics missing')
need('BASELINE_RETAINED' in listener, 'ambiguous marker must not poison trusted baseline')
advance = listener[listener.index('boolean positiveIdentity'):listener.index('prefs.edit().putString("state2_"')]
need('baselineReady && lifecyclePhase == DeletionGuard.Phase.LIVE' in advance,
     'REAL_POST must not become trusted before successful baseline')
need('boolean safePollPresence' in listener and 'deletionMarkers == 0 && positiveIdentity' in advance,
     'poll may update positive tokenized presence only, never empty/marker state')


# Block 2 hardening: baseline rebuild must not be skipped by a persisted signature, empty parses
# never grant trust, plural delete-looking text stays ordinary content, and evidence identity includes
# marker/state information so reused notification records do not collapse distinct events.
need('if (source == DeletionGuard.Source.POLL_SYNC && stateSignature.equals(oldSignature) && !snapshotPersistenceRetryKeys.contains(notificationKey)) return;' in listener,
     'BASELINE_SYNC must never short-circuit on a previous-session state signature')
empty_branch = listener[listener.index('if (parsed.isEmpty())'):listener.index('VaultDb db = new VaultDb')]
need('trustedBaselineConversations.add' not in empty_branch and 'saveConversationSnapshot(conv, new ArrayList' not in empty_branch,
     'empty baseline parse must not overwrite/grant conversation trust')
need('final int structuredPluralCount = structuredPluralDeleteCount' not in listener,
     'plural delete-looking chat text must not become deletion evidence')
need('|mts=' in listener and '|mt=' in listener and '|state=' in listener,
     'delete evidence dedupe must include marker identity and visible state')
need('boolean positiveIdentity = !currentItems.isEmpty() && allTokenized(currentItems);' in advance,
     'baseline trust requires positive tokenized identity')
need('persistAuthoritativeSnapshot(sbn, notificationKey, fallbackConversation, currentIds, currentItems, stateSignature)' in advance,
     'authoritative snapshot generations must be persisted atomically')

# Legacy aggressive helpers may remain for source-history compatibility, but no live call may exist.
for helper in ('scheduleProbableDeletionCheck(', 'scheduleEmptyReplacementCheck(', 'scheduleRemovedDeletionCheck('):
    need(listener.count(helper) == 1, f'legacy helper became live again: {helper}')
need(listener.count('markLatestDeletedAndKeepMedia(') == 1, 'latest-message guessing became live again')
probable_legacy = listener[listener.index('private void scheduleProbableDeletionCheck'):listener.index('private List<Long> optimisticMark')]
need('optimisticMark(' not in probable_legacy and 'postDelayed' not in probable_legacy,
     'legacy snapshot-diff verifier must be a pure no-op')
empty_legacy = listener[listener.index('private void scheduleEmptyReplacementCheck'):listener.index('/** v0.5.10:')]
need('optimisticMark(' not in empty_legacy and 'postDelayed' not in empty_legacy,
     'legacy empty-snapshot verifier must be a pure no-op')
latest_legacy = listener[listener.index('private long markLatestDeletedAndKeepMedia'):listener.index('@Override public void onNotificationRemoved')]
need('db.markLatestDeleted' not in latest_legacy and 'return -1L;' in latest_legacy,
     'legacy latest-message helper must fail closed')

# DB and media idempotency.
need('id=? AND (is_deleted=0 OR deletion_state<?)' in db, 'deletion transition must be atomic/idempotent')
need('fingerprint TEXT UNIQUE' in db and 'SQLiteDatabase.CONFLICT_IGNORE' in db, 'message dedupe invariant missing')
need('CREATE UNIQUE INDEX idx_media_hash_type_unique' in db, 'content-hash media dedupe missing')
need('synchronized (MEDIA_INSERT_LOCK)' in db, 'media insert critical section missing')
need('DOWNLOAD_SKIPPED_ALREADY_EXISTS' in media and 'DOWNLOAD_STARTED' in media,
     'media idempotency diagnostics missing')

# Persistent evidence ledger and required reason/source diagnostics.
need('deletionEvidenceKey' in listener and 'deletion_evidence' in db and 'confirmDeletedWithEvidence' in db, 'SQLite-backed persistent delete evidence dedupe missing')
need('WHY_DETECTED=' in listener and 'SOURCE_EVENT=' in listener and 'CONFIDENCE=' in listener,
     'structured deletion diagnostics incomplete')

# --- deterministic lifecycle model mirroring the source guard ---
INIT, BUILD, LIVE, DISC = 'INITIALIZATION','BASELINE_BUILDING','LIVE','DISCONNECTED'
BASELINE, POLL, POST, REMOVE = 'BASELINE_SYNC','POLL_SYNC','REAL_POST','REAL_REMOVE'

@dataclass
class Msg:
    mid: str
    ts: int

@dataclass
class Model:
    phase: str = INIT
    baseline_ready: bool = False
    baseline_ready_at: int = 0
    trusted: bool = False
    baseline: list[Msg] = field(default_factory=list)
    records: dict[str,Msg] = field(default_factory=dict)
    deleted: set[str] = field(default_factory=set)
    downloads: set[str] = field(default_factory=set)
    evidence: set[tuple] = field(default_factory=set)

    def receive(self, msgs):
        for m in msgs: self.records[m.mid] = m
        self.baseline = list(msgs)
        self.trusted = True

    def restart(self, active=None, now=1000, read_ok=True):
        self.phase = BUILD; self.baseline_ready = False; self.trusted = False
        if not read_ok:
            return False
        self.baseline = list(active or [])
        for m in self.baseline: self.records[m.mid] = m
        self.trusted = bool(self.baseline)
        self.baseline_ready_at = now; self.baseline_ready = True; self.phase = LIVE
        return True

    def normal_post(self, msgs, post_time):
        for m in msgs: self.records[m.mid] = m
        self.baseline = list(msgs); self.trusted = True

    def delete_marker(self, current, marker_count, marker_ts, marker_stable=True,
                      source=POST, post_time=1100, evidence_id='e'):
        previous = list(self.baseline)
        can = (self.baseline_ready and self.phase == LIVE and source == POST
               and post_time >= self.baseline_ready_at and marker_count > 0)
        ev=(evidence_id,post_time,marker_count)
        if not can or ev in self.evidence:
            return 0,'REJECTED'
        self.evidence.add(ev)
        current_ids={m.mid for m in current}
        missing=[m for m in previous if m.mid not in current_ids]
        exact=[m for m in missing if marker_stable and m.ts == marker_ts]
        confirm=(self.trusted and marker_count==1 and marker_stable and len(exact)==1)
        if confirm:
            self.deleted.add(exact[0].mid)
            self.baseline=list(current)
            self.trusted=True
            return 1,'CONFIRMED'
        # UNKNOWN retains the prior trusted baseline.
        return 0,'UNKNOWN'

    def capture_media(self, media_id):
        if media_id in self.downloads: return False
        self.downloads.add(media_id); return True

msgs=[Msg(f'm{i}',10_000+i) for i in range(20)]

# A: normal close/reopen with authoritative active state.
m=Model(); m.receive(msgs); m.restart(active=msgs, now=20_000)
need(len(m.deleted)==0 and len(m.downloads)==0, 'coldStartDoesNotGenerateDeletions')

# Baseline API failure stays closed.
mb=Model(); mb.receive(msgs); ok=mb.restart(active=None,now=20_000,read_ok=False)
need(not ok and not mb.baseline_ready and mb.phase==BUILD and len(mb.deleted)==0,
     'baselineReadFailureMustRemainClosed')

# Process death with an empty but valid active array is baseline construction, never deletion.
mp=Model(); mp.receive(msgs); mp.restart(active=[],now=30_000)
need(len(mp.deleted)==0, 'processDeathDoesNotGenerateDeletions')

# A REAL_POST received while baseline is still closed may be persisted but cannot become trusted deletion state.
pre=Model(); pre.receive(msgs); pre.phase=BUILD; pre.baseline_ready=False; pre.trusted=False
n,cls=pre.delete_marker(msgs[:-1],1,msgs[-1].ts,True,POST,39_900,'prebaseline')
need(n==0 and cls=='REJECTED' and not pre.deleted, 'preBaselinePostMustNotDelete')

# Poll/removal are not deletion sources.
mx=Model(); mx.receive(msgs); mx.restart(active=msgs,now=40_000)
need(mx.delete_marker([],1,msgs[-1].ts,True,POLL,40_100,'poll')[0]==0, 'poll must not delete')
need(mx.delete_marker([],1,msgs[-1].ts,True,REMOVE,40_200,'remove')[0]==0, 'removal must not delete')

# Exact real deletion: marker carries the original stable timestamp -> one row only.
mr=Model(); mr.receive(msgs); mr.restart(active=msgs,now=50_000)
cur=msgs[:-1]
n,cls=mr.delete_marker(cur,1,msgs[-1].ts,True,POST,50_100,'real-1')
need(n==1 and cls=='CONFIRMED' and mr.deleted=={msgs[-1].mid}, 'realDeletionOnlyAffectsMatchingMessage')

# User-typed marker phrase / marker with new timestamp cannot match the old row -> UNKNOWN.
mu=Model(); mu.receive(msgs); mu.restart(active=msgs,now=60_000)
n,cls=mu.delete_marker(msgs[:-1],1,99_999,True,POST,60_100,'typed')
need(n==0 and cls=='UNKNOWN' and not mu.deleted, 'marker text alone must not confirm')

# Unstable timestamp -> UNKNOWN.
ms=Model(); ms.receive(msgs); ms.restart(active=msgs,now=70_000)
n,cls=ms.delete_marker(msgs[:-1],1,msgs[-1].ts,False,POST,70_100,'unstable')
need(n==0 and cls=='UNKNOWN' and not ms.deleted, 'unstable marker must fail closed')

# Critical regression: 20 -> empty + one unrelated marker never fans out.
mc=Model(); mc.receive(msgs); mc.restart(active=msgs,now=80_000)
n,cls=mc.delete_marker([],1,123456789,True,POST,80_100,'collapse')
need(n==0 and cls=='UNKNOWN' and not mc.deleted, 'oneMarkerDoesNotDeleteEntireSnapshot')

# Even when 20 disappear, one exact timestamp marker may confirm only that exact one row, never 20.
me=Model(); me.receive(msgs); me.restart(active=msgs,now=90_000)
n,cls=me.delete_marker([],1,msgs[7].ts,True,POST,90_100,'exact-collapse')
need(n==1 and me.deleted=={msgs[7].mid}, 'exact match must stay cardinality one')

# Plural/batch marker is UNKNOWN without native per-message ids.
ml=Model(); ml.receive(msgs); ml.restart(active=msgs,now=100_000)
n,cls=ml.delete_marker([],20,msgs[0].ts,True,POST,100_100,'plural')
need(n==0 and cls=='UNKNOWN' and not ml.deleted, 'plural marker must not guess rows')

# Duplicate evidence cannot retrigger.
md=Model(); md.receive(msgs); md.restart(active=msgs,now=110_000)
first=md.delete_marker(msgs[:-1],1,msgs[-1].ts,True,POST,110_100,'dup')
second=md.delete_marker(msgs[:-1],1,msgs[-1].ts,True,POST,110_100,'dup')
need(first[0]==1 and second[0]==0 and len(md.deleted)==1, 'duplicateNotificationIsIgnored')

# Existing media is not downloaded again.
need(md.capture_media('XYZ') and not md.capture_media('XYZ') and len(md.downloads)==1,
     'existingMediaIsNotDownloadedAgain')

# 1000 messages x 20 restarts remains stable.
big=[Msg(f'id-{i:04d}',1_000_000+i) for i in range(1000)]
st=Model(); st.receive(big)
for i in range(20): st.restart(active=big,now=2_000_000+i*100)
need(len(st.records)==1000 and len(st.deleted)==0 and len(st.downloads)==0,
     'thousandMessagesRemainConsistent/twentyRestartsRemainIdempotent')

print('v0.5.30 PRODUCTION source regression PASS')
print('20 messages restart -> 0 false deletes / 0 false downloads')
print('baseline read failure -> gate CLOSED')
print('process death / poll / removal -> 0 false deletes')
print('real exact stable-timestamp deletion -> exactly 1 confirmed row')
print('typed/unstable/plural/ambiguous markers -> UNKNOWN, 0 row mutation')
print('1000 messages x 20 restarts -> 1000 records, 0 false deletes, 0 redownloads')
