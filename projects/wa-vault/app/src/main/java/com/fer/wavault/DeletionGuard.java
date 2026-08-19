package com.fer.wavault;

/**
 * Production fail-closed policy for deletion detection.
 *
 * Android exposes notification lifecycle, not a WhatsApp message-deletion API. Therefore absence,
 * removal, polling and snapshot shrink are never deletion proof. A historical message may be
 * confirmed deleted only after a fresh REAL_POST marker and a strong one-to-one correlation with a
 * trusted baseline from the current listener session.
 */
public final class DeletionGuard {
    private DeletionGuard() {}

    public enum Phase {
        INITIALIZATION,
        RESTORING,
        BASELINE_BUILDING,
        LIVE,
        DISCONNECTED
    }

    public enum Source { BASELINE_SYNC, POLL_SYNC, REAL_POST, REAL_REMOVE }
    public enum Confidence { CONFIRMED, PROBABLE, UNKNOWN }

    public static boolean canEvaluateDeletionMarker(Phase phase, Source source, boolean baselineReady,
                                                     long notificationPostTime, long baselineReadyAt) {
        if (!baselineReady || phase != Phase.LIVE || source != Source.REAL_POST) return false;
        if (notificationPostTime <= 0L || baselineReadyAt <= 0L) return false;
        // Replayed active notifications retain their old postTime; they are baseline/recovery data,
        // not a new deletion event. This is event ordering, not a sleep/delay workaround.
        return notificationPostTime >= baselineReadyAt;
    }

    /** Compatibility alias used by older source tests. */
    public static boolean canConfirmDeletion(Phase phase, Source source, boolean baselineReady,
                                             long notificationPostTime, long baselineReadyAt) {
        return canEvaluateDeletionMarker(phase, source, baselineReady, notificationPostTime, baselineReadyAt);
    }

    /** Missing/absence alone is explicitly never a deletion signal. */
    public static boolean absenceConfirmsDeletion(int previousCount, int currentCount) {
        return false;
    }

    /**
     * Strong correlation for a single historical row. Count equality alone is insufficient.
     * Confirmation requires a trusted current-session baseline plus exactly one previous row whose
     * original stable MessagingStyle timestamp equals the stable timestamp carried by the marker.
     */
    public static boolean canConfirmSingularMessage(boolean trustedLiveSessionBaseline,
                                                    int explicitMarkerCount,
                                                    boolean markerHasStableTimestamp,
                                                    int exactTimestampMatches) {
        return trustedLiveSessionBaseline
                && explicitMarkerCount == 1
                && markerHasStableTimestamp
                && exactTimestampMatches == 1;
    }

    /**
     * Legacy count-only matching is intentionally disabled in production. It is retained as a
     * source-compatible helper so a future regression cannot silently re-enable N->N guessing.
     */
    public static int safelyMappableCount(int explicitMarkerCount, int missingCount,
                                          boolean trustedLiveSessionBaseline) {
        return 0;
    }

    /** Unmapped evidence is UNKNOWN; it must not create a confirmed placeholder. */
    public static Confidence classifyUnmappedMarker(int explicitMarkerCount) {
        return explicitMarkerCount > 0 ? Confidence.UNKNOWN : Confidence.UNKNOWN;
    }

    /** Kept for deterministic accounting only; callers must not promote these as confirmed rows. */
    public static int unresolvedMarkerCount(int explicitMarkerCount, int mappedCount) {
        return Math.max(0, Math.max(0, explicitMarkerCount) - Math.max(0, mappedCount));
    }
}
