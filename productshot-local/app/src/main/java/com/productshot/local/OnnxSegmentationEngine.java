package com.productshot.local;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

final class OnnxSegmentationEngine implements AutoCloseable {
    private static final int SIZE = 1024;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    OnnxSegmentationEngine(File model) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(model.getAbsolutePath(), options);
        if (session.getInputNames().isEmpty()) throw new IllegalStateException("ISNet model has no inputs");
        inputName = session.getInputNames().iterator().next();
    }

    Bitmap createAlphaMask(Bitmap source) throws OrtException {
        Bitmap scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true);
        int[] pixels = new int[SIZE * SIZE];
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        scaled.recycle();

        FloatBuffer fb = ByteBuffer.allocateDirect(3 * SIZE * SIZE * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        int plane = SIZE * SIZE;
        float[] tensor = new float[plane * 3];
        for (int i = 0; i < plane; i++) {
            int c = pixels[i];
            tensor[i] = (Color.red(c) / 255.0f) - 0.5f;
            tensor[plane + i] = (Color.green(c) / 255.0f) - 0.5f;
            tensor[plane * 2 + i] = (Color.blue(c) / 255.0f) - 0.5f;
        }
        fb.put(tensor).rewind();

        float[] output = new float[plane];
        try (OnnxTensor input = OnnxTensor.createTensor(env, fb, new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxTensor out = (OnnxTensor) result.get(0);
            FloatBuffer ob = out.getFloatBuffer();
            ob.rewind();
            int count = ob.remaining();
            if (count != output.length) throw new IllegalStateException("Unexpected ISNet output size: " + count);
            ob.get(output);
        }

        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float v : output) { if (v < min) min = v; if (v > max) max = v; }
        float range = Math.max(1e-6f, max - min);
        int[] maskPixels = new int[plane];
        for (int i = 0; i < plane; i++) {
            float normalized = (output[i] - min) / range;
            float eased = smoothstep(0.08f, 0.92f, normalized);
            int a = Math.max(0, Math.min(255, Math.round(eased * 255f)));
            maskPixels[i] = Color.argb(a, 255, 255, 255);
        }
        Bitmap mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        mask.setPixels(maskPixels, 0, SIZE, 0, 0, SIZE, SIZE);
        Bitmap resized = Bitmap.createScaledBitmap(mask, source.getWidth(), source.getHeight(), true);
        mask.recycle();
        return resized;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    @Override public void close() throws OrtException { session.close(); }
}
