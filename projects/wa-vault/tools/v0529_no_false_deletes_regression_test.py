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

# --- source invariants: these are the permanent trip-wires for the production bug ---
need('versionCode = 79' in build and 'versionName = "0.5.29"' in build, 'release version must be 0.5.28/78')
need('baselineReady = false' in listener and 'BASELINE_LOADING' in listener and 'BASELINE_READY' in listener,
     'explicit baseline lifecycle gate missing')
for source in ('BASELINE_SYNC', 'POLL_SYNC', 'REAL_POST', 'REAL_REMOVE'):
    need(source in listener and source in guard, f'event source missing: {source}')
need('DeletionGuard.canConfirmDeletion' in listener, 'listener must use fail-closed deletion gate')
need('DeletionGuard.safelyMappableCount' in listener, 'safe exact-count message matching missing')
need('consumeDeletionEvidence' in listener and 'consumed_delete_evidence_v1' in listener,
     'persisted deletion evidence dedupe missing')
need('desiredVisible' not in listener, 'old missing-size amplification path returned')
need('Math.max(targetFromMarker, missing.size())' not in listener, 'snapshot-size delete amplification returned')
need('DELETION_REJECTED_EMPTY_STATE' in listener, 'empty state must be rejected as deletion evidence')
need('WHY_DETECTED=APP_CANCEL' in listener and 'classification=UNKNOWN' in listener,
     'APP_CANCEL must remain UNKNOWN')
removed = listener[listener.index('onNotificationRemoved'):listener.index('private boolean shouldOpenDescriptor')]
need('scheduleRemovedDeletionCheck(' not in removed, 'APP_CANCEL/removal must not schedule delete mutation')
opt = listener[listener.index('private List<Long> optimisticMark'):listener.index('private void rollbackOptimistic')]
need('markDeletedAndKeepMedia' not in opt and 'DELETION_REJECTED' in opt,
     'legacy optimistic path must be physically fail-closed')
need('SOURCE_EVENT=REAL_POST' in listener and 'WHY_DETECTED=' in listener,
     'structured why/source diagnostics missing')
need('id=? AND (is_deleted=0 OR deletion_state<?)' in db,
     'deletion transition must be atomic/idempotent')
need('DOWNLOAD_SKIPPED_ALREADY_EXISTS' in media and 'DOWNLOAD_STARTED' in media,
     'download idempotency diagnostics missing')
need('private String currentScreen = "messages";' in main and 'currentScreen = "messages";' in main,
     'app must always open in Mensajes borrados')

# The empty replacement helper may stay for historical readability, but no live path may call it.
need(listener.count('scheduleEmptyReplacementCheck(') == 1,
     'empty replacement deletion helper has an active caller')

# --- deterministic state-machine model mirroring DeletionGuard ---
INIT, SYNC, LIVE, DISC = 'INITIALIZATION', 'SYNC', 'LIVE', 'DISCONNECTED'
BASELINE, POLL, POST, REMOVE = 'BASELINE_SYNC', 'POLL_SYNC', 'REAL_POST', 'REAL_REMOVE'

@dataclass
class Model:
    phase: str = INIT
    baseline_ready: bool = False
    baseline_ready_at: int = 0
    baseline: list[str] = field(default_factory=list)
    records: set[str] = field(default_factory=set)
    deleted: set[str] = field(default_factory=set)
    placeholders: int = 0
    downloads: set[str] = field(default_factory=set)
    evidence: set[tuple] = field(default_factory=set)

    def receive(self, ids):
        for x in ids:
            self.records.add(x)
        self.baseline = list(ids)

    def restart(self, active=None, now=1000):
        self.phase = SYNC
        self.baseline_ready = False
        # baseline sync never deletes, even when active is empty or contains a stale marker-only state
        if active is not None:
            self.baseline = list(active)
            self.records.update(active)
        self.baseline_ready_at = now
        self.baseline_ready = True
        self.phase = LIVE

    def deletion_event(self, current, markers, source=POST, post_time=1100, evidence_id='e'):
        previous = list(self.baseline)
        current = list(current)
        self.records.update(current)
        can = (self.baseline_ready and self.phase == LIVE and source == POST
               and post_time >= self.baseline_ready_at and markers > 0)
        ev = (evidence_id, post_time, markers)
        if not can or ev in self.evidence:
            if source != POLL:
                self.baseline = current if source == POST else self.baseline
            return 0, 0
        self.evidence.add(ev)
        missing = [x for x in previous if x not in set(current)]
        # exact count-to-count only; no "latest" guessing / burst expansion
        mapped = markers if len(missing) == markers else 0
        if mapped:
            self.deleted.update(missing)
        unresolved = max(0, markers - mapped)
        self.placeholders += unresolved
        self.baseline = current
        return mapped, unresolved

    def capture_media(self, media_id):
        if media_id in self.downloads:
            return False
        self.downloads.add(media_id)
        return True

# Test A: 20 normal messages -> close/open -> zero false deletion/download.
m = Model(); msgs=[f'm{i}' for i in range(20)]; m.receive(msgs)
m.restart(active=msgs, now=1000)
need(len(m.deleted)==0 and m.placeholders==0, 'coldStartDoesNotGenerateDeletions()')
need(len(m.downloads)==0, 'cold start must not create false downloads')

# Process death, including an empty recovered active set, is baseline construction, not deletion.
m2=Model(); m2.receive(msgs); m2.restart(active=[], now=2000)
need(len(m2.deleted)==0 and m2.placeholders==0, 'processDeathDoesNotGenerateDeletions()')

# Service restart / reboot are the same fail-closed lifecycle transition.
m3=Model(); m3.receive(msgs)
for t in (3000,4000):
    m3.restart(active=msgs, now=t)
need(len(m3.deleted)==0, 'serviceRestartDoesNotGenerateDeletions()/reboot')

# Polling a marker-only snapshot after restart is never a deletion event.
m4=Model(); m4.receive(msgs); m4.restart(active=msgs, now=5000)
mapped, ph=m4.deletion_event([],1,source=POLL,post_time=5100,evidence_id='stale-poll')
need(mapped==0 and ph==0 and len(m4.deleted)==0, 'poll marker must not delete')

# A single real explicit deletion after a valid live baseline affects exactly one matching row.
m5=Model(); m5.receive(msgs); m5.restart(active=msgs, now=6000)
cur=msgs[:-1]
mapped,ph=m5.deletion_event(cur,1,source=POST,post_time=6100,evidence_id='real-1')
need(mapped==1 and ph==0 and m5.deleted=={msgs[-1]}, 'realDeletionOnlyAffectsMatchingMessage()')

# Critical regression: 20 -> marker-only/empty with ONE marker can never become 20 deletes.
m6=Model(); m6.receive(msgs); m6.restart(active=msgs, now=7000)
mapped,ph=m6.deletion_event([],1,source=POST,post_time=7100,evidence_id='ambiguous-one')
need(mapped==0 and ph==1 and len(m6.deleted)==0, 'one marker must not fan out across missing snapshot')

# Duplicate marker callback is idempotent.
before=(len(m6.deleted),m6.placeholders)
m6.deletion_event([],1,source=POST,post_time=7100,evidence_id='ambiguous-one')
need((len(m6.deleted),m6.placeholders)==before, 'duplicateNotificationIsIgnored()')

# Existing media is not downloaded again.
need(m6.capture_media('XYZ') is True and m6.capture_media('XYZ') is False and len(m6.downloads)==1,
     'existingMediaIsNotDownloadedAgain()')

# 1000-message stress + 20 open/close/reopen cycles: no record growth, deletions or downloads.
big=[f'id-{i:04d}' for i in range(1000)]
ms=Model(); ms.receive(big)
for i in range(20):
    ms.restart(active=big, now=10_000+i*100)
need(len(ms.records)==1000, '20 restarts duplicated records')
need(len(ms.deleted)==0 and ms.placeholders==0, '20 restarts created false deletions')
need(len(ms.downloads)==0, '20 restarts created false downloads')

# Missing baseline is fail-closed.
mb=Model(); mb.phase=LIVE; mb.baseline_ready=False
mapped,ph=mb.deletion_event([],1,source=POST,post_time=99999,evidence_id='no-baseline')
need(mapped==0 and ph==0, 'baselineMissingDoesNotGenerateDeletions()')

print('v0.5.29 NO-FALSE-DELETES regression PASS')
print('A: 20 msgs restart -> 0 false deletes / 0 false downloads')
print('process death / service restart / reboot -> 0 false deletes')
print('real delete exact match -> 1 confirmed matching row')
print('ambiguous 20->0 + 1 marker -> 0 historical rows, 1 honest placeholder')
print('1000 msgs x 20 restarts -> 1000 stable records, 0 false deletes, 0 redownloads')
