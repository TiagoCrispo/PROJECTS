package com.productshot.local;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;

final class LocalProductPipeline {
    interface Progress { void update(int percent, String message); }

    private final Context context;
    LocalProductPipeline(Context context) { this.context = context.getApplicationContext(); }

    Bitmap run(Bitmap source, Progress progress) throws Exception {
        progress.update(2, "Preparando IA local…");
        File model = ModelDownloader.ensureIsNet(context, pct -> progress.update(2 + (pct * 33 / 100), "Preparando IA local " + pct + "%"));
        progress.update(38, "Separando el producto…");
        Bitmap mask;
        try (OnnxSegmentationEngine segmenter = new OnnxSegmentationEngine(model)) {
            mask = segmenter.createAlphaMask(source);
        }
        progress.update(78, "Construyendo estudio…");
        Bitmap out = new CatalogComposer().compose(source, mask);
        mask.recycle();
        progress.update(100, "Listo");
        return out;
    }
}
