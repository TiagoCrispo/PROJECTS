package com.mendozameteo.x10;

/**
 * Small thread-safe lifecycle gate used to prevent overlapping loads and stale callbacks.
 * A token is valid only until it is finished or the gate is invalidated (for example onDestroy).
 */
final class LoadGate {
    static final long REJECTED = -1L;

    private long generation;
    private boolean loading;

    synchronized long begin() {
        if (loading) return REJECTED;
        loading = true;
        return ++generation;
    }

    synchronized boolean isActive(long token) {
        return loading && token == generation;
    }

    synchronized void finish(long token) {
        if (token == generation) loading = false;
    }

    synchronized void invalidate() {
        generation++;
        loading = false;
    }

    synchronized boolean isLoading() {
        return loading;
    }
}
