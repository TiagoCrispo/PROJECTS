package com.fer.wavault;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Manual-only Gallery export. WA Vault never creates a decrypted Gallery copy automatically.
 * Legacy export metadata is retained only so old Gallery copies can still be removed on request.
 */
public final class GalleryExporter {
    private GalleryExporter() {}
    public interface SavedCallback { void onSaved(Uri uri); }
    private static final Set<String> EXPORTING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IO = VaultExecutors.bounded(
            1,16,"wa-vault-gallery-export",Thread.NORM_PRIORITY - 1);

    /** Delete only the Gallery copy created by WA Vault. The private vault copy is handled by VaultDb. */
    public static boolean deleteExportedCopy(Context context, VaultDb.Media media) {
        if(context==null||media==null)return false;
        Context app=context.getApplicationContext();
        String raw=media.galleryUri==null?"":media.galleryUri.trim();
        if(raw.isEmpty())return true;
        boolean deleted=false;
        try{
            Uri uri=Uri.parse(raw);
            if("file".equalsIgnoreCase(uri.getScheme())){
                String path=uri.getPath();File f=path==null||path.isEmpty()?null:new File(path);
                deleted=f==null||!f.exists()||f.delete();
            }else{
                ContentResolver cr=app.getContentResolver();
                deleted=cr.delete(uri,null,null)>0;
                if(!deleted){
                    // Android/Gallery may already have removed the exported copy. Missing is the
                    // desired end-state, so do not report a false failure to the user.
                    try(android.os.ParcelFileDescriptor check=cr.openFileDescriptor(uri,"r")){deleted=check==null;}
                    catch(java.io.FileNotFoundException missing){deleted=true;}
                    catch(SecurityException denied){deleted=false;}
                }
            }
        }catch(java.io.FileNotFoundException missing){deleted=true;}catch(Throwable ignored){}
        return deleted;
    }

    /** Queue at most one export for a given private file. Returns false when the same item is already exporting. */
    public static boolean saveFileAsync(Context context, File stored, String name, String mime, String type, SavedCallback success, Runnable failure) {
        if (context == null || stored == null || !stored.exists()) { if (failure != null) failure.run(); return false; }
        Context app = context.getApplicationContext();
        String exportKey=stored.getAbsolutePath();
        if(!EXPORTING.add(exportKey))return false;
        try{IO.execute(() -> {
            File readable = null;
            Uri created = null;
            try {
                readable = MediaCrypto.materialize(app, stored, name);
                if (readable != null) created = writeNewGalleryItem(app, readable, safeName(name, type), normalizeMime(mime, type), type);
            } catch (Throwable ignored) {
            } finally {
                if (readable != null && !readable.equals(stored)) try { readable.delete(); } catch (Throwable ignored) {}
                EXPORTING.remove(exportKey);
            }
            if (created != null) { if (success != null) success.onSaved(created); } else { if (failure != null) failure.run(); }
        });return true;}
        catch(RejectedExecutionException saturated){
            EXPORTING.remove(exportKey);
            app.getSharedPreferences("wa_vault_diag",Context.MODE_PRIVATE).edit()
                    .putLong("gallery_export_backpressure_at",System.currentTimeMillis()).apply();
            if(failure!=null)failure.run();
            return false;
        }
    }

    /** Conservative existence check: a permission denial counts as existing so we never create an accidental duplicate. */
    public static boolean exportedCopyExists(Context context,String rawUri){
        if(context==null||rawUri==null||rawUri.trim().isEmpty())return false;
        try{
            Uri uri=Uri.parse(rawUri.trim());
            if("file".equalsIgnoreCase(uri.getScheme())){String path=uri.getPath();return path!=null&&!path.isEmpty()&&new File(path).exists();}
            try(android.os.ParcelFileDescriptor pfd=context.getApplicationContext().getContentResolver().openFileDescriptor(uri,"r")){return pfd!=null;}
            catch(java.io.FileNotFoundException missing){return false;}
            catch(SecurityException denied){return true;}
        }catch(Throwable ignored){return false;}
    }

    /** Roll back a newly-created export if its URI could not be recorded in the vault DB. */
    public static boolean deleteUri(Context context, Uri uri) {
        if(context==null||uri==null)return true;
        try{
            if("file".equalsIgnoreCase(uri.getScheme())){
                String path=uri.getPath();File f=path==null||path.isEmpty()?null:new File(path);
                return f==null||!f.exists()||f.delete();
            }
            int n=context.getApplicationContext().getContentResolver().delete(uri,null,null);
            return n>0;
        }catch(Throwable ignored){return false;}
    }


    private static Uri writeNewGalleryItem(Context app, File readable, String name, String mime, String type) {
        if (readable == null || !readable.exists() || readable.length() <= 0) return null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = app.getContentResolver();
            boolean video = "video".equals(type);
            Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/WA Vault");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = cr.insert(base, v);
                if (uri == null) return null;
                try (InputStream in = new FileInputStream(readable); OutputStream out = cr.openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalStateException("no output");
                    byte[] buf = new byte[131072]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                }
                ContentValues done = new ContentValues(); done.put(MediaStore.MediaColumns.IS_PENDING, 0); cr.update(uri, done, null, null);
                return uri;
            } catch (Throwable t) {
                if (uri != null) try { cr.delete(uri, null, null); } catch (Throwable ignored) {}
                return null;
            }
        }

        File base = Environment.getExternalStoragePublicDirectory("video".equals(type) ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES);
        File dir = new File(base, "WA Vault"); if (!dir.exists()) dir.mkdirs();
        File outFile = uniqueFile(dir, name);
        try (InputStream in = new FileInputStream(readable); FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[131072]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); out.flush();out.getFD().sync();
            if(outFile.length()<=0)throw new IllegalStateException("empty export");
            MediaScannerConnection.scanFile(app, new String[]{outFile.getAbsolutePath()}, new String[]{mime}, null);
            return Uri.fromFile(outFile);
        } catch (Throwable t) { try { outFile.delete(); } catch (Throwable ignored) {} return null; }
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name); if (!f.exists()) return f;
        int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : "";
        for (int i=2;i<1000;i++) { f = new File(dir, base + "_" + i + ext); if (!f.exists()) return f; }
        return new File(dir, System.currentTimeMillis() + "_" + name);
    }

    private static String safeName(String name, String type) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = ("video".equals(type) ? "WA_Vault_Video_" : "WA_Vault_Foto_") + System.currentTimeMillis();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        String low = n.toLowerCase(Locale.ROOT);
        if (!n.contains(".")) n += "video".equals(type) ? ".mp4" : ".jpg";
        else if ("image".equals(type) && !(low.endsWith(".jpg")||low.endsWith(".jpeg")||low.endsWith(".png")||low.endsWith(".webp")||low.endsWith(".gif")||low.endsWith(".heic")||low.endsWith(".heif"))) n += ".jpg";
        return n;
    }

    private static String guessMime(String name,String type) {
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if ("video".equals(type)) {
            if(n.endsWith(".webm")) return "video/webm";
            if(n.endsWith(".3gp")) return "video/3gpp";
            if(n.endsWith(".mkv")) return "video/x-matroska";
            return "video/mp4";
        }
        if(n.endsWith(".png")) return "image/png";
        if(n.endsWith(".webp")) return "image/webp";
        if(n.endsWith(".gif")) return "image/gif";
        if(n.endsWith(".heic")||n.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    private static String normalizeMime(String mime, String type) {
        if (mime != null && !mime.trim().isEmpty() && !mime.equals("application/octet-stream")) return mime;
        return "video".equals(type) ? "video/mp4" : "image/jpeg";
    }
}
