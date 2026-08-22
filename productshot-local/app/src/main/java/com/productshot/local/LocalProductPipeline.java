package com.productshot.local;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;

final class LocalProductPipeline {
    interface Progress { void update(int percent, String message); }

    private static final Object ENGINE_LOCK = new Object();
    private static OnnxSegmentationEngine warmSegmenter;
    private static String warmModelPath;

    private final Context context;
    LocalProductPipeline(Context context) { this.context = context.getApplicationContext(); }

    Bitmap run(Bitmap source, Progress progress) throws Exception {
        progress.update(2, "Preparando IA local…");
        File model = ModelDownloader.ensureIsNet(context,
                pct -> progress.update(2 + (pct * 33 / 100), "Preparando IA local " + pct + "%"));

        progress.update(38, "Separando el producto…");
        Bitmap mask;
        synchronized (ENGINE_LOCK) {
            OnnxSegmentationEngine segmenter = obtainSegmenter(model);
            mask = segmenter.createAlphaMask(source);
        }

        progress.update(78, "Construyendo estudio…");
        Bitmap out;
        try {
            out = new CatalogComposer().compose(source, mask);
        } finally {
            mask.recycle();
        }
        progress.update(100, "Listo");
        return out;
    }

    private static OnnxSegmentationEngine obtainSegmenter(File model) throws Exception {
        String path = model.getCanonicalPath();
        if (warmSegmenter != null && path.equals(warmModelPath)) return warmSegmenter;
        closeWarmSegmenter();
        warmSegmenter = new OnnxSegmentationEngine(model);
        warmModelPath = path;
        return warmSegmenter;
    }

    private static void closeWarmSegmenter() {
        if (warmSegmenter != null) {
            try { warmSegmenter.close(); } catch (Exception ignored) { }
            warmSegmenter = null;
            warmModelPath = null;
        }
    }
}
