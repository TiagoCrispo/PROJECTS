package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LoadGateTest {
    @Test
    public void rejectsOverlappingLoad() {
        LoadGate gate = new LoadGate();
        long first = gate.begin();
        assertTrue(first > 0);
        assertEquals(LoadGate.REJECTED, gate.begin());
        assertTrue(gate.isActive(first));
    }

    @Test
    public void finishedTokenCannotUpdateAgain() {
        LoadGate gate = new LoadGate();
        long token = gate.begin();
        gate.finish(token);
        assertFalse(gate.isActive(token));
        assertFalse(gate.isLoading());
    }

    @Test
    public void invalidateMakesOldCallbackStale() {
        LoadGate gate = new LoadGate();
        long old = gate.begin();
        gate.invalidate();
        assertFalse(gate.isActive(old));
        long next = gate.begin();
        assertTrue(next > old);
        assertTrue(gate.isActive(next));
    }

    @Test
    public void staleFinishCannotStopNewRequest() {
        LoadGate gate = new LoadGate();
        long old = gate.begin();
        gate.invalidate();
        long current = gate.begin();
        gate.finish(old);
        assertTrue(gate.isActive(current));
    }
}
