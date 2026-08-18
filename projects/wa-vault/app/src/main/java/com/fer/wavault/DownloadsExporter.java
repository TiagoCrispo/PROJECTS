package com.fer.wavault;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.media.MediaScannerConnection;
import java.io.FileOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

final class DownloadsExporter {
    private DownloadsExporter() {}

    static void saveAsync(Context context, File stored, String displayName, String mime, Runnable ok, Runnable fail) {
        new Thread(() -> {
            boolean saved = save(context.getApplicationContext(), stored, displayName, mime);
            try { if (saved) { if (ok != null) ok.run(); } else if (fail != null) fail.run(); } catch (Throwable ignored) {}
        }, "wa-vault-download-export").start();
    }

    private static boolean save(Context context, File stored, String displayName, String mime) {
        if (context == null || stored == null || !stored.exists()) return false;
        File readable = null;
        Uri created = null;
        try {
            readable = MediaCrypto.materialize(context, stored, displayName);
            if (readable == null || !readable.exists()) return false;
            if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q){
                File base=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);File dir=new File(base,"WA Vault");if(!dir.exists()&&!dir.mkdirs())return false;File outFile=uniqueFile(dir,sanitize(displayName,stored.getName()));
                try{
                    try(InputStream in=new FileInputStream(readable);FileOutputStream out=new FileOutputStream(outFile)){byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);out.flush();out.getFD().sync();}
                    if(!outFile.exists()||outFile.length()<=0)throw new IllegalStateException("empty export");
                    MediaScannerConnection.scanFile(context,new String[]{outFile.getAbsolutePath()},new String[]{mime==null||mime.isEmpty()?"application/octet-stream":mime},null);return true;
                }catch(Throwable exportFail){try{outFile.delete();}catch(Throwable ignored){}return false;}
            }
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(displayName, stored.getName()));
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WA Vault");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (created == null) return false;
            try (InputStream in = new FileInputStream(readable); OutputStream out = resolver.openOutputStream(created, "w")) {
                if (out == null) throw new IllegalStateException("No output stream");
                byte[] buffer = new byte[131072];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(created, done, null, null);
            return true;
        } catch (Throwable t) {
            if (created != null) try { context.getContentResolver().delete(created, null, null); } catch (Throwable ignored) {}
            return false;
        } finally {
            if (readable != null && !readable.equals(stored)) try { readable.delete(); } catch (Throwable ignored) {}
        }
    }

    private static File uniqueFile(File dir,String name){File f=new File(dir,name);if(!f.exists())return f;int dot=name.lastIndexOf('.');String base=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";for(int i=2;i<1000;i++){f=new File(dir,base+"_"+i+ext);if(!f.exists())return f;}return new File(dir,System.currentTimeMillis()+"_"+name);}

    private static String sanitize(String wanted, String fallback) {
        String s = wanted == null || wanted.trim().isEmpty() ? fallback : wanted.trim();
        if (s == null || s.isEmpty()) s = "WA-Vault-" + System.currentTimeMillis();
        return s.replace('/', '_').replace('\\', '_');
    }
}
