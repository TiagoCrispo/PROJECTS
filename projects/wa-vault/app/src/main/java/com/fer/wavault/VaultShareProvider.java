package com.fer.wavault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider used only for temporary user-initiated sharing. */
public final class VaultShareProvider extends ContentProvider {
    public static final String AUTHORITY = "com.fer.wavault.share";

    @Override public boolean onCreate() {
        cleanupOld();
        return true;
    }

    public static Uri uriFor(File file) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(file == null ? "" : file.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File dir = new File(getContext().getCacheDir(), "vault_share");
        File f = new File(dir, name);
        try {
            String root = dir.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(root) || !f.exists() || !f.isFile()) throw new FileNotFoundException();
        } catch (FileNotFoundException e) { throw e; }
        catch (Throwable t) { throw new FileNotFoundException(); }
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r")) throw new FileNotFoundException();
        File f=resolve(uri);try{f.setLastModified(System.currentTimeMillis());}catch(Throwable ignored){}
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try {
            String n = resolve(uri).getName();
            int dot = n.lastIndexOf('.');
            String ext = dot >= 0 ? n.substring(dot + 1).toLowerCase() : "";
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return m == null ? "application/octet-stream" : m;
        } catch (Throwable t) { return "application/octet-stream"; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor c = new MatrixCursor(cols, 1);
            MatrixCursor.RowBuilder r = c.newRow();
            for (String col : cols) {
                if (OpenableColumns.DISPLAY_NAME.equals(col)) r.add(VaultFileNames.genericDisplayName(f));
                else if (OpenableColumns.SIZE.equals(col)) r.add(f.length());
                else r.add(null);
            }
            return c;
        } catch (Throwable t) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    private void cleanupOld() {
        try {
            if (getContext() == null) return;
            File d = new File(getContext().getCacheDir(), "vault_share");
            File[] fs = d.listFiles();
            long cutoff = System.currentTimeMillis() - 15L * 60L * 1000L;
            if (fs != null) for (File f : fs) if (f.lastModified() < cutoff) f.delete();
        } catch (Throwable ignored) {}
    }
}
