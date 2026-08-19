package com.fer.wavault;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Best-effort preservation of an image/video preview embedded directly in a notification.
 * This never enters WhatsApp private storage. It only uses payloads Android already exposed
 * to the notification listener (BigPicture/preview extras or public Icon payloads).
 */
public final class NotificationPreviewCapture {
    private NotificationPreviewCapture() {}
    private static final ExecutorService WORK = VaultExecutors.bounded(
            2, 8, "wa-vault-notif-preview", Thread.NORM_PRIORITY);

    /** Never compress/decode/write notification previews on NotificationListener's main thread. */
    public static void captureBestPreviewAsync(Context context, Notification notification,
                                               long messageId, long sourceTime, String mediaType) {
        if(context==null||notification==null||messageId<=0)return;
        final Context app=context.getApplicationContext();
        try {
            WORK.execute(()->{
                try{captureBestPreview(app,notification,messageId,sourceTime,mediaType);}
                catch(Throwable t){try{new VaultDb(app).logCaptureFailure("NOTIF_PREVIEW_ASYNC",mediaType,messageId,t);}catch(Throwable ignored){}}
            });
        } catch (RejectedExecutionException saturated) {
            // Preview is best-effort. Never execute compression on the listener main thread.
            app.getSharedPreferences("wa_vault_diag", Context.MODE_PRIVATE).edit()
                    .putLong("preview_backpressure_at", System.currentTimeMillis())
                    .putString("preview_backpressure", "queue_full")
                    .apply();
        }
    }

    public static long captureBestPreview(Context context, Notification notification,
                                          long messageId, long sourceTime, String mediaType) {
        if (context == null || notification == null || messageId <= 0) return -1L;
        if (!("image".equals(mediaType) || "video".equals(mediaType))) return -1L;
        Bitmap bitmap = findBestBitmap(context.getApplicationContext(), notification);
        if (bitmap == null || bitmap.getWidth() < 48 || bitmap.getHeight() < 48) return -1L;

        Context app = context.getApplicationContext();
        File dir = new File(app.getFilesDir(), "vault_staging");
        if (!dir.exists()) dir.mkdirs();
        String base = "video".equals(mediaType) ? "video_preview" : "photo_preview";
        File out = new File(dir, VaultFileNames.stagingName("ready_", "image"));
        long bytes = 0L;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            Bitmap use = bitmap;
            // Keep notification previews useful but bounded. Full files replace them later.
            int max = 1600;
            if (bitmap.getWidth() > max || bitmap.getHeight() > max) {
                float s = Math.min(max / (float) bitmap.getWidth(), max / (float) bitmap.getHeight());
                use = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * s)),
                        Math.max(1, Math.round(bitmap.getHeight() * s)), true);
            }
            if (!use.compress(Bitmap.CompressFormat.JPEG, 90, fos)) {
                if (use != bitmap) use.recycle();
                out.delete();
                return -1L;
            }
            fos.flush();
            fos.getFD().sync();
            if (use != bitmap) use.recycle();
            bytes = out.length();
        } catch (Throwable t) {
            try { out.delete(); } catch (Throwable ignored) {}
            return -1L;
        }
        if (bytes < 1500L) { out.delete(); return -1L; }

        VaultDb db = new VaultDb(app);
        String source = "notification-preview:" + messageId + ":" + sourceTime;
        long id = MediaArchiver.commitGeneratedPreviewStaged(app, out, mediaType, messageId, sourceTime,
                source, "NOTIFICATION_PREVIEW",
                "video".equals(mediaType) ? "Vista previa de video.jpg" : "Vista previa de foto.jpg");
        if (id > 0) {
            db.logEvent("NOTIFICATION_PREVIEW", mediaType + " · " + bytes + " bytes", messageId, id);
        }
        return id;
    }

    private static Bitmap findBestBitmap(Context context, Notification n) {
        Bundle e = n.extras;
        Bitmap best = null;
        if (e != null) {
            try {
                Object p = e.get(Notification.EXTRA_PICTURE);
                best = asBitmap(context, p);
            } catch (Throwable ignored) {}
            if (best == null && Build.VERSION.SDK_INT >= 31) {
                try { best = asBitmap(context, e.get(Notification.EXTRA_PICTURE_ICON)); } catch (Throwable ignored) {}
            }
            if (best == null) best = scanPreviewKeys(context, e, 0);
        }
        return best;
    }

    private static Bitmap scanPreviewKeys(Context context, Bundle b, int depth) {
        if (b == null || depth > 2) return null;
        try {
            for (String key : b.keySet()) {
                if (key == null) continue;
                String k = key.toLowerCase(Locale.ROOT);
                boolean previewKey = k.contains("picture") || k.contains("preview") || k.contains("thumbnail") || k.contains("thumb") || k.contains("image");
                boolean reject = k.contains("avatar") || k.contains("person") || k.contains("sender") || k.contains("largeicon") || k.contains("large_icon");
                Object v;
                try { v = b.get(key); } catch (Throwable t) { continue; }
                if (previewKey && !reject) {
                    Bitmap found = asBitmap(context, v);
                    if (found != null && found.getWidth() >= 48 && found.getHeight() >= 48) return found;
                }
                if (v instanceof Bundle) {
                    Bitmap nested = scanPreviewKeys(context, (Bundle) v, depth + 1);
                    if (nested != null) return nested;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Bitmap asBitmap(Context context, Object value) {
        if (value instanceof Bitmap) return (Bitmap) value;
        if (Build.VERSION.SDK_INT >= 23 && value instanceof Icon) {
            try {
                Drawable d = ((Icon) value).loadDrawable(context);
                if (d == null) return null;
                int w = Math.max(1, d.getIntrinsicWidth());
                int h = Math.max(1, d.getIntrinsicHeight());
                w = Math.min(w, 1600); h = Math.min(h, 1600);
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, w, h);
                d.draw(c);
                return b;
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
