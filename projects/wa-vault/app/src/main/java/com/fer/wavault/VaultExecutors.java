package com.fer.wavault;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Small bounded executors for bursty capture/UI work.
 *
 * We intentionally use AbortPolicy instead of running rejected work synchronously: callbacks such as
 * NotificationListenerService and FileObserver must never become an accidental place where
 * bitmap compression, file copies or decryption execute synchronously when the app is under load.
 * Callers must handle rejection explicitly and either drop best-effort work or leave durable
 * staging for a later recovery pass.
 */
public final class VaultExecutors {
    private VaultExecutors() {}

    public static ThreadPoolExecutor bounded(int threads, int queueCapacity, String threadName, int priority) {
        final int n = Math.max(1, threads);
        final int q = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                n,
                n,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(q),
                r -> {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    t.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(10, priority)));
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
