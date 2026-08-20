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

    private ModelDownloader() {}

    static File ensureIsNet(Context context, Progress progress) throws Exception {
        final String url = "https://huggingface.co/jellybox/isnet-general-use/resolve/bc3706ce4ee38a3db2c3c58082a92582fcdfcfdc/isnet-general-use_1024.onnx?download=true";
        final String sha256 = "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a";
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("No se pudo crear la carpeta de modelos");
        File dst = new File(dir, "isnet-general-use_1024.onnx");
        if (dst.isFile() && sha256(dst).equals(sha256)) {
            if (progress != null) progress.onProgress(100);
            return dst;
        }
        File part = new File(dir, dst.getName() + ".part");
        if (part.exists() && !part.delete()) throw new IllegalStateException("No se pudo reiniciar la descarga del modelo");

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "ProductShot-Local/0.2");
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("Descarga del modelo falló: HTTP " + status);
        long total = conn.getContentLengthLong();
        long done = 0;
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(part)) {
            int n;
            int last = -1;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                done += n;
                if (total > 0 && progress != null) {
                    int pct = (int)Math.min(99, (done * 100L) / total);
                    if (pct != last) { last = pct; progress.onProgress(pct); }
                }
            }
            out.getFD().sync();
        } finally {
            conn.disconnect();
        }
        String actual = sha256(part);
        if (!actual.equals(sha256)) {
            part.delete();
            throw new IllegalStateException(String.format(Locale.ROOT, "Checksum de modelo inválido: %s", actual));
        }
        if (dst.exists() && !dst.delete()) throw new IllegalStateException("No se pudo reemplazar el modelo anterior");
        if (!part.renameTo(dst)) throw new IllegalStateException("No se pudo publicar el modelo descargado");
        if (progress != null) progress.onProgress(100);
        return dst;
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
