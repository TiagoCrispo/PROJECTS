package com.productshot.local;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SmokeActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        worker.execute(this::runSmoke);
    }

    private void runSmoke() {
        File dir = new File(getFilesDir(), "smoke");
        if (!dir.isDirectory()) dir.mkdirs();
        File status = new File(dir, "status.txt");
        File resultFile = new File(dir, "result.jpg");
        try {
            if (status.exists()) status.delete();
            if (resultFile.exists()) resultFile.delete();
            Bitmap source = syntheticProduct();
            Bitmap result = new LocalProductPipeline(this).run(source, (pct, msg) -> {});
            if (result.getWidth() != 1536 || result.getHeight() != 1024) {
                throw new IllegalStateException("unexpected result dimensions " + result.getWidth() + "x" + result.getHeight());
            }
            try (FileOutputStream out = new FileOutputStream(resultFile)) {
                if (!result.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw new IllegalStateException("jpeg compress failed");
                out.getFD().sync();
            }
            source.recycle();
            result.recycle();
            write(status, "PASS\n");
        } catch (Throwable t) {
            write(status, "FAIL " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()) + "\n");
        } finally {
            runOnUiThread(this::finish);
        }
    }

    private static Bitmap syntheticProduct() {
        Bitmap bitmap = Bitmap.createBitmap(768, 576, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.drawColor(Color.rgb(224, 220, 214));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(105, 72, 48));
        c.drawRoundRect(145, 185, 625, 300, 18, 18, p);
        p.setColor(Color.rgb(72, 48, 34));
        c.drawRect(180, 292, 225, 495, p);
        c.drawRect(545, 292, 590, 495, p);
        Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
        detail.setColor(Color.rgb(140, 98, 64));
        detail.setStrokeWidth(6f);
        for (int y = 205; y < 285; y += 18) c.drawLine(175, y, 595, y + 4, detail);
        return bitmap;
    }

    private static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
