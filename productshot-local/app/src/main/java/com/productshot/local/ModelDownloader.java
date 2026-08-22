package com.productshot.local;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

final class ModelDownloader {
    interface Progress { void onProgress(int percent); }

    private static final String MODEL_URL = "https://huggingface.co/jellybox/isnet-general-use/resolve/bc3706ce4ee38a3db2c3c58082a92582fcdfcfdc/isnet-general-use_1024.onnx?download=true";
    private static final String MODEL_SHA256 = "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a";
    private static final long MODEL_BYTES = 178_648_008L;

    private ModelDownloader() {}

    static File ensureIsNet(Context context, Progress progress) throws Exception {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("No se pudo crear la carpeta de modelos");
        File dst = new File(dir, "isnet-general-use_1024.onnx");
        if (isVerified(dst)) {
            if (progress != null) progress.onProgress(100);
            return dst;
        }
        if (dst.exists() && !dst.delete()) throw new IllegalStateException("No se pudo reemplazar un modelo incompleto");

        File part = new File(dir, dst.getName() + ".part");
        if (part.length() > MODEL_BYTES) {
            if (!part.delete()) throw new IllegalStateException("No se pudo reiniciar una descarga dañada");
        }

        downloadResume(part, progress);
        if (part.length() != MODEL_BYTES) {
            throw new IllegalStateException("Descarga incompleta: " + part.length() + " / " + MODEL_BYTES);
        }
        String actual = sha256(part);
        if (!actual.equals(MODEL_SHA256)) {
            part.delete();
            throw new IllegalStateException(String.format(Locale.ROOT, "Checksum de modelo inválido: %s", actual));
        }
        if (!part.renameTo(dst)) throw new IllegalStateException("No se pudo publicar el modelo descargado");
        if (progress != null) progress.onProgress(100);
        return dst;
    }

    private static void downloadResume(File part, Progress progress) throws Exception {
        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "ProductShot-Local/0.2");
        if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

        int status = conn.getResponseCode();
        boolean append;
        long done;
        if (existing > 0 && status == HttpURLConnection.HTTP_PARTIAL) {
            append = true;
            done = existing;
        } else if (status >= 200 && status < 300) {
            append = false;
            done = 0L;
        } else {
            conn.disconnect();
            throw new IllegalStateException("Descarga del modelo falló: HTTP " + status);
        }

        if (!append && existing > 0 && !part.delete()) {
            conn.disconnect();
            throw new IllegalStateException("No se pudo reiniciar una descarga no reanudable");
        }

        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(part, append)) {
            int last = -1;
            if (progress != null && done > 0) {
                last = (int)Math.min(99, (done * 100L) / MODEL_BYTES);
                progress.onProgress(last);
            }
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                done += n;
                if (done > MODEL_BYTES) throw new IllegalStateException("El servidor entregó más bytes de los esperados");
                if (progress != null) {
                    int pct = (int)Math.min(99, (done * 100L) / MODEL_BYTES);
                    if (pct != last) {
                        last = pct;
                        progress.onProgress(pct);
                    }
                }
            }
            out.getFD().sync();
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isVerified(File file) throws Exception {
        return file.isFile() && file.length() == MODEL_BYTES && sha256(file).equals(MODEL_SHA256);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) if (n > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }
}
