package com.fer.wavault;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Permanent host-side regression tests for the fail-closed deletion policy. */
public final class DeletionGuardTest {
    @Test public void coldStartDoesNotGenerateDeletions() {
        assertFalse(DeletionGuard.canEvaluateDeletionMarker(
                DeletionGuard.Phase.BASELINE_BUILDING, DeletionGuard.Source.REAL_POST,
                false, 2_000L, 1_000L));
    }

    @Test public void pollAndRemovalNeverConfirmDeletion() {
        assertFalse(DeletionGuard.canEvaluateDeletionMarker(
                DeletionGuard.Phase.LIVE, DeletionGuard.Source.POLL_SYNC,
                true, 2_000L, 1_000L));
        assertFalse(DeletionGuard.canEvaluateDeletionMarker(
                DeletionGuard.Phase.LIVE, DeletionGuard.Source.REAL_REMOVE,
                true, 2_000L, 1_000L));
    }

    @Test public void absenceNeverConfirmsDeletion() {
        assertFalse(DeletionGuard.absenceConfirmsDeletion(20, 0));
    }

    @Test public void staleReplayBeforeBaselineCannotConfirm() {
        assertFalse(DeletionGuard.canEvaluateDeletionMarker(
                DeletionGuard.Phase.LIVE, DeletionGuard.Source.REAL_POST,
                true, 999L, 1_000L));
    }

    @Test public void exactSingularCorrelationCanConfirm() {
        assertTrue(DeletionGuard.canConfirmSingularMessage(true, 1, true, 1));
    }

    @Test public void ambiguousOrPluralCorrelationFailsClosed() {
        assertFalse(DeletionGuard.canConfirmSingularMessage(true, 1, true, 2));
        assertFalse(DeletionGuard.canConfirmSingularMessage(true, 2, true, 1));
        assertFalse(DeletionGuard.canConfirmSingularMessage(false, 1, true, 1));
        assertFalse(DeletionGuard.canConfirmSingularMessage(true, 1, false, 1));
    }

    @Test public void countOnlyMappingIsDisabled() {
        assertEquals(0, DeletionGuard.safelyMappableCount(20, 20, true));
        assertEquals(DeletionGuard.Confidence.UNKNOWN,
                DeletionGuard.classifyUnmappedMarker(1));
    }
}
