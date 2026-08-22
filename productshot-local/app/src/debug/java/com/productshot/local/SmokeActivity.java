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

/** Debug-only CI entry point. It performs the real local model download and ONNX inference. */
public final class SmokeActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        worker.execute(this::runSmoke);
    }

    private void runSmoke() {
        File dir = new File(getFilesDir(), "smoke");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            finishSafely();
            return;
        }
        File status = new File(dir, "status.txt");
        File resultFile = new File(dir, "result.jpg");
        status.delete();
        resultFile.delete();

        Bitmap source = null;
        Bitmap result = null;
        try {
            source = syntheticProduct();
            result = new LocalProductPipeline(this).run(source, (pct, msg) -> { });
            if (result.getWidth() != 1536 || result.getHeight() != 1024) {
                throw new IllegalStateException("unexpected result dimensions " + result.getWidth() + "x" + result.getHeight());
            }
            try (FileOutputStream out = new FileOutputStream(resultFile)) {
                if (!result.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
                    throw new IllegalStateException("jpeg compress failed");
                }
                out.getFD().sync();
            }
            if (!resultFile.isFile() || resultFile.length() < 1024) {
                throw new IllegalStateException("result jpeg missing or too small");
            }
            write(status, "PASS 1536x1024 bytes=" + resultFile.length() + "\n");
        } catch (Throwable t) {
            resultFile.delete();
            String message = String.valueOf(t.getMessage()).replace('\n', ' ').replace('\r', ' ');
            write(status, "FAIL " + t.getClass().getName() + ": " + message + "\n");
        } finally {
            if (source != null && !source.isRecycled()) source.recycle();
            if (result != null && !result.isRecycled()) result.recycle();
            finishSafely();
        }
    }

    private void finishSafely() {
        runOnUiThread(this::finish);
    }

    private static Bitmap syntheticProduct() {
        Bitmap bitmap = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.drawColor(Color.rgb(232, 228, 221));

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(40, 0, 0, 0));
        c.drawOval(170, 620, 850, 686, shadow);

        Paint wood = new Paint(Paint.ANTI_ALIAS_FLAG);
        wood.setColor(Color.rgb(145, 91, 52));
        c.drawRoundRect(180, 220, 844, 382, 22, 22, wood);

        Paint darkWood = new Paint(Paint.ANTI_ALIAS_FLAG);
        darkWood.setColor(Color.rgb(102, 64, 39));
        c.drawRect(210, 365, 814, 406, darkWood);
        c.drawRoundRect(226, 397, 282, 640, 10, 10, darkWood);
        c.drawRoundRect(742, 397, 798, 640, 10, 10, darkWood);

        for (int x = 286; x <= 730; x += 34) {
            c.drawRoundRect(x, 553, x + 22, 615, 5, 5, wood);
        }
        return bitmap;
    }

    private static void write(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
