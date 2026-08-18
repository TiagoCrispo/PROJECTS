package com.fer.wavault;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Centralized Android 10+ Downloads export helper. */
public final class DownloadsExporter {
    private static final String ENC_EXT = ".wvenc";

    private DownloadsExporter() {}

    public static boolean exportJson(Context context, byte[] data, String displayName) {
        File tmp = null;
        try {
            File dir = new File(context.getCacheDir(), "export-json");
            if (!dir.exists() && !dir.mkdirs()) return false;
            tmp = new File(dir, "export-" + System.nanoTime() + ".json");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data == null ? new byte[0] : data);
                out.flush();
            }
            return save(context, tmp, displayName, "application/json", false, null);
        } catch (Throwable t) {
            return false;
        } finally {
            if (tmp != null) try { tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    public static boolean save(Context context, File source, String displayName, String mimeType,
                               boolean encryptedSource, String encryptedLogicalName) {
        if (context == null || source == null || !source.exists() || source.length() <= 0) return false;
        File readable = null;
        try {
            readable = materializeReadable(context, source, encryptedSource, encryptedLogicalName);
            if (readable == null || !readable.exists()) return false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return saveLegacy(readable, displayName);
            return saveScoped(context, readable, displayName, mimeType);
        } catch (Throwable t) {
            return false;
        } finally {
            if (readable != null && !sameFile(readable, source)) {
                try { readable.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private static boolean saveScoped(Context context, File readable, String displayName, String mimeType) {
        Uri created = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WA Vault");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (created == null) return false;
            try (InputStream in = new FileInputStream(readable); OutputStream out = resolver.openOutputStream(created, "w")) {
                if (out == null) throw new java.io.IOException("Downloads output stream unavailable");
                copy(in, out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(created, values, null, null);
            return true;
        } catch (Throwable t) {
            if (created != null) {
                try { context.getContentResolver().delete(created, null, null); } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    public static String displayName(Context context, Uri uri, String fallback) {
        String out = null;
        try (android.database.Cursor c = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) out = c.getString(i);
            }
        } catch (Throwable ignored) {}
        if (out == null || out.trim().isEmpty()) out = fallback;
        return out == null ? "WA-Vault-export" : sanitizeName(out);
    }

    private static File materializeReadable(Context context, File source, boolean encrypted, String logicalName) {
        if (!encrypted && !source.getName().toLowerCase(java.util.Locale.ROOT).endsWith(ENC_EXT)) return source;
        try {
            File dir = new File(context.getCacheDir(), "export-decrypted");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = logicalName;
            if (name == null || name.trim().isEmpty()) name = stripEncryptedSuffix(source.getName());
            name = sanitizeName(name == null ? "WA-Vault-file" : name);
            File out = new File(dir, "dec-" + System.nanoTime() + "-" + name);
            if (!VaultCrypto.decryptFileTo(source, out)) {
                try { out.delete(); } catch (Throwable ignored) {}
                return null;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean saveLegacy(File source, String displayName) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloads, "WA Vault");
            if (!dir.exists() && !dir.mkdirs()) return false;
            File out = uniqueFile(dir, sanitizeName(displayName));
            try (InputStream in = new FileInputStream(source); OutputStream os = new FileOutputStream(out)) { copy(in, os); }
            return out.exists() && out.length() > 0;
        } catch (Throwable t) { return false; }
    }

    private static File uniqueFile(File dir, String displayName) {
        String name = displayName == null || displayName.trim().isEmpty() ? "WA-Vault-file" : displayName;
        File out = new File(dir, name);
        if (!out.exists()) return out;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 10000; i++) {
            out = new File(dir, base + " (" + i + ")" + ext);
            if (!out.exists()) return out;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String sanitizeName(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty()) clean = "WA-Vault-file";
        if (clean.length() > 120) clean = clean.substring(0, 120);
        return clean;
    }

    private static String stripEncryptedSuffix(String name) {
        if (name == null) return null;
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(ENC_EXT)
                ? name.substring(0, name.length() - ENC_EXT.length()) : name;
    }

    private static boolean sameFile(File a, File b) {
        try { return a.getCanonicalPath().equals(b.getCanonicalPath()); }
        catch (Throwable ignored) { return a.equals(b); }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) if (read > 0) out.write(buffer, 0, read);
        out.flush();
    }
}
