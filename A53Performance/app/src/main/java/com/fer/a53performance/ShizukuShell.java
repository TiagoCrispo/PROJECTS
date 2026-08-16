package com.fer.a53performance;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

public final class ShizukuShell {
    private final ExecutorService timeoutPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "a53-shell-timeout"); t.setDaemon(true); return t;
    });

    public boolean available() {
        try { return Shizuku.pingBinder(); } catch (Throwable ignored) { return false; }
    }

    public boolean permissionGranted() {
        try { return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable ignored) { return false; }
    }

    public void requestPermission(int requestCode) {
        try {
            if (available() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
                    !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable ignored) {}
    }

    public Result exec(String command, long timeoutMs) {
        if (!permissionGranted()) return new Result(false, "Shizuku sin permiso", -1);
        Future<Result> f = timeoutPool.submit(() -> run(command));
        try { return f.get(Math.max(400, timeoutMs), TimeUnit.MILLISECONDS); }
        catch (Throwable e) { f.cancel(true); return new Result(false, "timeout", -2); }
    }

    @SuppressWarnings("deprecation")
    private Result run(String command) {
        Process p = null;
        try {
            p = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null && out.length() < 4096) out.append(line).append('\n');
            }
            StringBuilder err = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line; while ((line = br.readLine()) != null && err.length() < 2048) err.append(line).append('\n');
            }
            int code = p.waitFor();
            String text = out.length() > 0 ? out.toString().trim() : err.toString().trim();
            return new Result(code == 0, text, code);
        } catch (Throwable e) {
            return new Result(false, e.getClass().getSimpleName(), -3);
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    public void shutdown() { timeoutPool.shutdownNow(); }

    public record Result(boolean ok, String output, int code) {}
}
