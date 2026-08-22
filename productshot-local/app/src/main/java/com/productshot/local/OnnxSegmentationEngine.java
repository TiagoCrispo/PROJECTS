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
    private static final float COMPONENT_THRESHOLD = 0.10f;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;

    OnnxSegmentationEngine(File model) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(model.getAbsolutePath(), options);
        } finally {
            options.close();
        }
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

        float[] confidence = new float[plane];
        try (OnnxTensor input = OnnxTensor.createTensor(env, fb, new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, input))) {
            OnnxTensor out = (OnnxTensor) result.get(0);
            FloatBuffer ob = out.getFloatBuffer();
            ob.rewind();
            int count = ob.remaining();
            if (count != confidence.length) throw new IllegalStateException("Unexpected ISNet output size: " + count);
            ob.get(confidence);
        }

        normalizeInPlace(confidence);
        byte[] keep = isolatePrimaryComponent(confidence);

        int[] maskPixels = new int[plane];
        for (int i = 0; i < plane; i++) {
            float eased = keep[i] == 0 ? 0f : smoothstep(0.035f, 0.92f, confidence[i]);
            int a = Math.max(0, Math.min(255, Math.round(eased * 255f)));
            maskPixels[i] = Color.argb(a, 255, 255, 255);
        }
        Bitmap mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        mask.setPixels(maskPixels, 0, SIZE, 0, 0, SIZE, SIZE);
        Bitmap resized = Bitmap.createScaledBitmap(mask, source.getWidth(), source.getHeight(), true);
        mask.recycle();
        return resized;
    }

    private static void normalizeInPlace(float[] values) {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = Math.max(1e-6f, max - min);
        for (int i = 0; i < values.length; i++) values[i] = (values[i] - min) / range;
    }

    /**
     * ISNet is a foreground model, not an object detector. Workshop photos can contain stools,
     * benches and tools. Keep the strongest large component, mildly preferring the image centre,
     * then dilate that selection two pixels so the original soft matte survives around its edge.
     */
    private static byte[] isolatePrimaryComponent(float[] confidence) {
        int plane = SIZE * SIZE;
        int[] labels = new int[plane];
        int[] queue = new int[plane];
        int label = 0;
        int bestLabel = 0;
        int bestArea = 0;
        double bestScore = -1.0;

        for (int start = 0; start < plane; start++) {
            if (labels[start] != 0 || confidence[start] < COMPONENT_THRESHOLD) continue;
            label++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            labels[start] = label;
            int area = 0;
            long sumX = 0L, sumY = 0L;

            while (head < tail) {
                int p = queue[head++];
                int y = p / SIZE;
                int x = p - y * SIZE;
                area++;
                sumX += x;
                sumY += y;

                if (x > 0) tail = enqueue(p - 1, label, confidence, labels, queue, tail);
                if (x + 1 < SIZE) tail = enqueue(p + 1, label, confidence, labels, queue, tail);
                if (y > 0) tail = enqueue(p - SIZE, label, confidence, labels, queue, tail);
                if (y + 1 < SIZE) tail = enqueue(p + SIZE, label, confidence, labels, queue, tail);
            }

            double cx = sumX / (double)Math.max(1, area);
            double cy = sumY / (double)Math.max(1, area);
            double dx = (cx - (SIZE - 1) * 0.5) / (SIZE * 0.5);
            double dy = (cy - (SIZE - 1) * 0.5) / (SIZE * 0.5);
            double distance = Math.min(1.0, Math.sqrt(dx * dx + dy * dy));
            double centreWeight = 1.0 - distance;
            double score = area * (0.75 + 0.25 * centreWeight);
            if (score > bestScore) {
                bestScore = score;
                bestArea = area;
                bestLabel = label;
            }
        }

        byte[] keep = new byte[plane];
        if (bestLabel == 0 || bestArea < plane / 200) {
            for (int i = 0; i < plane; i++) if (confidence[i] >= COMPONENT_THRESHOLD) keep[i] = 1;
            return keep;
        }
        for (int i = 0; i < plane; i++) if (labels[i] == bestLabel) keep[i] = 1;
        dilate(keep);
        dilate(keep);
        return keep;
    }

    private static int enqueue(int p, int label, float[] confidence, int[] labels, int[] queue, int tail) {
        if (labels[p] == 0 && confidence[p] >= COMPONENT_THRESHOLD) {
            labels[p] = label;
            queue[tail++] = p;
        }
        return tail;
    }

    private static void dilate(byte[] mask) {
        byte[] original = mask.clone();
        for (int y = 0; y < SIZE; y++) {
            int row = y * SIZE;
            for (int x = 0; x < SIZE; x++) {
                int p = row + x;
                if (original[p] != 0) continue;
                if ((x > 0 && original[p - 1] != 0)
                        || (x + 1 < SIZE && original[p + 1] != 0)
                        || (y > 0 && original[p - SIZE] != 0)
                        || (y + 1 < SIZE && original[p + SIZE] != 0)) {
                    mask[p] = 1;
                }
            }
        }
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    @Override public void close() throws OrtException { session.close(); }
}
