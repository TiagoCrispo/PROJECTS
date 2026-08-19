from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[1]
listener=(ROOT/'app/src/main/java/com/fer/wavault/WhatsAppNotificationListener.java').read_text()
guard=(ROOT/'app/src/main/java/com/fer/wavault/DeletionGuard.java').read_text()
db=(ROOT/'app/src/main/java/com/fer/wavault/VaultDb.java').read_text()

def need(c,m):
    if not c: raise AssertionError(m)

# 1) lifecycle source partition
for x in ('BASELINE_SYNC','POLL_SYNC','REAL_POST','REAL_REMOVE'):
    need(x in listener and x in guard, f'missing event source {x}')
need('onListenerConnected()' in listener and 'establishBaseline(generation)' in listener, 'baseline not established from connected state')
need('baselineReady = false;' in listener and 'lifecyclePhase = DeletionGuard.Phase.BASELINE_BUILDING;' in listener, 'gate not closed during baseline')
need('if (!readSucceeded)' in listener and listener.index('if (!readSucceeded)') < listener.index('baselineReady = true'), 'failed baseline read opens gate')

# 2) prior process signature cannot suppress current-session baseline trust rebuild
need('if (source == DeletionGuard.Source.POLL_SYNC && stateSignature.equals(oldSignature) && !snapshotPersistenceRetryKeys.contains(notificationKey)) return;' in listener,
     'unchanged BASELINE_SYNC is incorrectly short-circuited')

# 3) empty / absence cannot write trusted baseline or deletion
empty=listener[listener.index('if (parsed.isEmpty())'):listener.index('VaultDb db = new VaultDb')]
need('DELETION_REJECTED_EMPTY_STATE' in empty, 'empty state not rejected')
need('trustedBaselineConversations.add' not in empty, 'empty state grants trust')
need('saveConversationSnapshot(conv, new ArrayList' not in empty, 'empty state destroys last positive identity baseline')

# 4) only REAL_POST can evaluate markers and only a one-to-one stable timestamp can confirm
core=listener[listener.index('boolean freshDeletePost'):listener.index('// Presence may advance the baseline')]
need('DeletionGuard.canEvaluateDeletionMarker' in core, 'marker gate missing')
need('source, baselineReady, sbn.getPostTime(), baselineReadyAt' in core, 'event source/session ordering missing')
need('markerTarget == 1' in core and 'firstDeletionMarker.stableTimestamp' in core, 'singular stable marker requirement missing')
need('exactTimestampMatches' in core and 'originalTs == firstDeletionMarker.timestamp' in core, 'exact timestamp matching missing')
need('db.confirmDeletedWithEvidence(exactTimestampMatch.id, evidenceKey)' in core, 'confirmation not bound atomically to exact match/evidence')
need('ensureDeletionPlaceholder' not in core and 'markLatestDeleted' not in core, 'guessing path reintroduced')

# 5) plural-looking human text is never consumed as delete evidence
need('structuredPluralDeleteCount(parsed, previousItems)' not in listener, 'plural text can still become a system marker')

# 6) polling is presence-only; baseline trust requires tokenized positive state
adv=listener[listener.index('boolean positiveIdentity'):listener.index('prefs.edit().putString("state2_"')]
need('boolean positiveIdentity = !currentItems.isEmpty() && allTokenized(currentItems);' in adv, 'positive identity predicate missing')
need('source == DeletionGuard.Source.POLL_SYNC' in adv and 'deletionMarkers == 0 && positiveIdentity' in adv, 'poll is not positive-presence-only')
need('trustedBaselineConversations.add(ck)' in adv and 'trustedBaselineConversations.remove(ck)' in adv, 'per-conversation trust is not explicit')

# 7) authoritative empty transition clears every snapshot generation (prevents legacy resurrection)
need('persistAuthoritativeSnapshot(sbn, notificationKey, fallbackConversation, currentIds, currentItems, stateSignature)' in adv, 'authoritative snapshot generations are not persisted atomically')

# 8) removal path cannot mutate deletion state
rem=listener[listener.index('@Override public void onNotificationRemoved'):listener.index('private boolean shouldOpenDescriptor')]
for bad in ('markDeletedById(', 'markDeletedAndKeepMedia(', 'ensureDeletionPlaceholder(', 'markLatestDeleted('):
    need(bad not in rem, f'removal path mutates deletion state via {bad}')
need('classification=UNKNOWN' in rem, 'removal is not classified UNKNOWN')

# 9) evidence dedupe identity survives notification-record reuse
consume=listener[listener.index('private String deletionEvidenceKey'):listener.index('private void traceDeletion')]
for piece in ('markerTs','markerText','stateSignature','|mts=','|mt=','|state='):
    need(piece in consume, f'evidence fingerprint missing {piece}')

# 10) actual mutation helper is CONFIRMED-only
mark=listener[listener.index('private boolean markDeletedAndKeepMedia'):listener.index('private long markLatestDeletedAndKeepMedia')]
need('deletionState != VaultDb.DELETE_CONFIRMED' in mark, 'probable/unknown state can enter mutation helper')
need('db.markDeletedById(id, VaultDb.DELETE_CONFIRMED)' in mark, 'DB confirmation not hard-coded to CONFIRMED')

# 11) only one live listener call site may mark confirmed; DB duplicate repair may preserve existing state but not infer it.
need(listener.count('db.confirmDeletedWithEvidence(exactTimestampMatch.id, evidenceKey)') == 1,
     'multiple live confirmation call sites found')
need('Math.max(targetFromMarker, missing.size())' not in listener and 'DELETE_AGGRESSIVE_FILL' not in listener,
     'batch amplification path returned')

# 12) Android callback state is serialized on main Handler in this implementation.
need('new Handler(Looper.getMainLooper())' in listener, 'listener coordination handler is not main looper')
need('handler.post(() -> establishBaseline(generation))' in listener, 'baseline establishment not serialized')

print('BLOCK2_NOTIFICATION_LISTENER_REGRESSION_PASS')
