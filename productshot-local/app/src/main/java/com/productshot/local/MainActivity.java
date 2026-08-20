package com.productshot.local;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1001;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout root;
    private ImageView preview;
    private TextView status;
    private TextView percent;
    private ProgressBar progress;
    private Button primary;
    private Button save;
    private Button again;
    private Bitmap sourceBitmap;
    private Bitmap resultBitmap;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        buildUi();
        showIdle();
    }

    private void configureWindow() {
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.rgb(250, 248, 245));
            getWindow().setNavigationBarColor(Color.rgb(250, 248, 245));
        }
        getWindow().setNavigationBarContrastEnforced(false);
    }

    private void configureSystemBarsAfterDecor() {
        if (Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            return;
        }
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            int appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(appearance, appearance);
        }
    }

    private void buildUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.rgb(250, 248, 245));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(30));
        scroller.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("ProductShot");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(35,31,28));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8));

        TextView subtitle = new TextView(this);
        subtitle.setText("Catálogo profesional · IA local · sin límites");
        subtitle.setTextSize(15);
        subtitle.setTextColor(Color.rgb(91,84,78));
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 18));

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(Color.rgb(235,231,226));
        root.addView(preview, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(430), 0, 16));

        status = new TextView(this);
        status.setTextSize(17);
        status.setTextColor(Color.rgb(64,57,52));
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(10), 0, 8));

        percent = new TextView(this);
        percent.setTextSize(16);
        percent.setTextColor(Color.rgb(64,57,52));
        percent.setGravity(Gravity.CENTER);
        percent.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(percent, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 16));

        primary = button("Subir foto");
        primary.setOnClickListener(v -> {
            if (sourceBitmap == null) choosePhoto();
            else startLocalGeneration();
        });
        root.addView(primary, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10));

        save = button("Guardar resultado");
        save.setOnClickListener(v -> saveResult());
        root.addView(save, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 10));

        again = button("Crear otra");
        again.setOnClickListener(v -> reset());
        root.addView(again, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        setContentView(scroller);
        configureSystemBarsAfterDecor();
        installInsets();
    }

    private void installInsets() {
        final int bl = dp(22), bt = dp(24), br = dp(22), bb = dp(30);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = bars.left; t = bars.top; r = bars.right; b = bars.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(bl + l, bt + t, br + r, bb + b);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void choosePhoto() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
        }
        startActivityForResult(intent, REQ_PICK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            Bitmap loaded = decode(uri, 2048);
            recycleSource();
            sourceBitmap = loaded;
            preview.setImageBitmap(sourceBitmap);
            showReady();
        } catch (Exception e) {
            showError("No pude abrir esa foto");
        }
    }

    private Bitmap decode(Uri uri, int maxEdge) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int largest = Math.max(w, h);
            if (largest > maxEdge) {
                float s = maxEdge / (float)largest;
                decoder.setTargetSize(Math.max(1, Math.round(w*s)), Math.max(1, Math.round(h*s)));
            }
        });
    }

    private void startLocalGeneration() {
        if (sourceBitmap == null) return;
        primary.setEnabled(false);
        save.setVisibility(View.GONE);
        again.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        percent.setVisibility(View.VISIBLE);
        setProgress(1, "Preparando IA local…");
        Bitmap input = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
        worker.execute(() -> {
            try {
                Bitmap result = new LocalProductPipeline(this).run(input, (pct, msg) -> runOnUiThread(() -> setProgress(pct, msg)));
                input.recycle();
                runOnUiThread(() -> {
                    recycleResult();
                    resultBitmap = result;
                    preview.setImageBitmap(resultBitmap);
                    showSuccess();
                });
            } catch (Exception e) {
                input.recycle();
                runOnUiThread(() -> showError("La IA local no pudo terminar. Toca Crear foto para reintentar."));
            }
        });
    }

    private void saveResult() {
        if (resultBitmap == null) return;
        Uri uri = null;
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "ProductShot_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProductShot");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("insert");
            try (java.io.OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null || !resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IllegalStateException("write");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            Toast.makeText(this, "Guardado en Pictures/ProductShot", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            Toast.makeText(this, "No pude guardar el resultado", Toast.LENGTH_SHORT).show();
        }
    }

    private void showIdle() {
        preview.setImageDrawable(null);
        status.setText("Sube una foto del producto");
        progress.setVisibility(View.GONE);
        percent.setVisibility(View.GONE);
        primary.setText("Subir foto");
        primary.setEnabled(true);
        save.setVisibility(View.GONE);
        again.setVisibility(View.GONE);
    }

    private void showReady() {
        status.setText("Producto listo");
        progress.setVisibility(View.GONE);
        percent.setVisibility(View.GONE);
        primary.setText("Crear foto");
        primary.setEnabled(true);
        save.setVisibility(View.GONE);
        again.setVisibility(View.VISIBLE);
    }

    private void showSuccess() {
        status.setText("Catálogo creado localmente");
        progress.setProgress(100);
        progress.setVisibility(View.VISIBLE);
        percent.setText("100%");
        percent.setVisibility(View.VISIBLE);
        primary.setEnabled(true);
        primary.setText("Crear foto otra vez");
        save.setVisibility(View.VISIBLE);
        again.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        status.setText(message);
        progress.setVisibility(View.GONE);
        percent.setVisibility(View.GONE);
        primary.setEnabled(true);
        primary.setText(sourceBitmap == null ? "Subir foto" : "Crear foto");
        save.setVisibility(resultBitmap == null ? View.GONE : View.VISIBLE);
        again.setVisibility(sourceBitmap == null ? View.GONE : View.VISIBLE);
    }

    private void setProgress(int pct, String message) {
        int safe = Math.max(0, Math.min(100, pct));
        progress.setProgress(safe);
        percent.setText(String.format(java.util.Locale.ROOT, "%d%%", safe));
        status.setText(message);
    }

    private void reset() {
        recycleSource(); recycleResult();
        showIdle();
    }

    private void recycleSource() { if (sourceBitmap != null) { sourceBitmap.recycle(); sourceBitmap = null; } }
    private void recycleResult() { if (resultBitmap != null) { resultBitmap.recycle(); resultBitmap = null; } }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top); p.bottomMargin = dp(bottom);
        return p;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        recycleSource(); recycleResult();
        super.onDestroy();
    }
}
