package com.fer.a53performance;

import android.content.Context;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public final class PrivilegedUserService extends IPrivilegedService.Stub {
    public PrivilegedUserService() {}

    @Keep
    public PrivilegedUserService(Context context) {}

    @Override public void destroy() { System.exit(0); }

    @Override public String exec(String command, long timeoutMs) throws RemoteException {
        if (command == null || command.isBlank()) return "-4\nempty command";
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            p = pb.start();
            boolean finished = p.waitFor(Math.max(400L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!finished) {
                try { p.destroy(); } catch (Throwable ignored) {}
                try { if (!p.waitFor(120, TimeUnit.MILLISECONDS)) p.destroyForcibly(); } catch (Throwable ignored) {}
                return "-2\ntimeout";
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null && out.length() < 8192) out.append(line).append('\n');
            }
            return p.exitValue() + "\n" + out.toString().trim();
        } catch (Throwable t) {
            return "-3\n" + t.getClass().getSimpleName();
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }
}
