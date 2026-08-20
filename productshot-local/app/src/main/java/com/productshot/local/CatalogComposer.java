package com.productshot.local;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

final class CatalogComposer {
    private static final int OUT_W = 1536;
    private static final int OUT_H = 1024;

    Bitmap compose(Bitmap source, Bitmap alphaMask) {
        Bitmap cutout = cutout(source, alphaMask);
        Rect objectBounds = alphaBounds(alphaMask);
        if (objectBounds.width() < 8 || objectBounds.height() < 8) objectBounds = new Rect(0, 0, source.getWidth(), source.getHeight());

        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        drawBackground(c);

        RectF hero = new RectF(52, 52, 1024, 972);
        drawPanel(c, hero, 28f, Color.rgb(248, 246, 242));
        drawProduct(c, cutout, objectBounds, hero, 0.80f, true);

        RectF top = new RectF(1064, 52, 1484, 492);
        RectF bottom = new RectF(1064, 532, 1484, 972);
        drawPanel(c, top, 28f, Color.rgb(243, 240, 235));
        drawPanel(c, bottom, 28f, Color.rgb(252, 251, 249));
        drawDetailCrop(c, cutout, objectBounds, top, 0.0f);
        drawDetailCrop(c, cutout, objectBounds, bottom, 1.0f);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.argb(36, 30, 28, 26));
        line.setStrokeWidth(2f);
        c.drawLine(1044, 64, 1044, 960, line);

        cutout.recycle();
        return out;
    }

    private static Bitmap cutout(Bitmap source, Bitmap mask) {
        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, 0, 0, p);
        p.setXfermode(null);
        return result;
    }

    private static void drawBackground(Canvas c) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, OUT_W, OUT_H,
                Color.rgb(252, 250, 247), Color.rgb(235, 231, 225), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, OUT_W, OUT_H, p);
    }

    private static void drawPanel(Canvas c, RectF rect, float radius, int color) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(24, 0, 0, 0));
        shadow.setMaskFilter(new BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL));
        RectF shifted = new RectF(rect.left + 4, rect.top + 10, rect.right + 4, rect.bottom + 10);
        c.drawRoundRect(shifted, radius, radius, shadow);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        c.drawRoundRect(rect, radius, radius, fill);
    }

    private static void drawProduct(Canvas c, Bitmap cutout, Rect srcBounds, RectF panel, float occupancy, boolean shadow) {
        float maxW = panel.width() * occupancy;
        float maxH = panel.height() * occupancy;
        float scale = Math.min(maxW / srcBounds.width(), maxH / srcBounds.height());
        float w = srcBounds.width() * scale;
        float h = srcBounds.height() * scale;
        float left = panel.centerX() - w / 2f;
        float top = panel.centerY() - h / 2f - panel.height() * 0.015f;
        RectF dst = new RectF(left, top, left + w, top + h);

        if (shadow) {
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(Color.argb(45, 50, 45, 40));
            sp.setMaskFilter(new BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL));
            float sx = dst.centerX();
            float sy = dst.bottom - h * 0.02f;
            c.drawOval(new RectF(sx - w * 0.32f, sy - h * 0.025f, sx + w * 0.32f, sy + h * 0.035f), sp);
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(cutout, srcBounds, dst, p);
    }

    private static void drawDetailCrop(Canvas c, Bitmap cutout, Rect bounds, RectF dst, float verticalBias) {
        int bw = bounds.width();
        int bh = bounds.height();
        int cropW = Math.max(32, Math.round(bw * 0.58f));
        int cropH = Math.max(32, Math.round(bh * 0.58f));
        int x = bounds.left + Math.max(0, (bw - cropW) / 2);
        int y = bounds.top + Math.max(0, Math.round((bh - cropH) * verticalBias));
        Rect src = new Rect(x, y, Math.min(cutout.getWidth(), x + cropW), Math.min(cutout.getHeight(), y + cropH));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.save();
        c.clipRoundRect(dst, 28f, 28f);
        float s = Math.max(dst.width() / src.width(), dst.height() / src.height());
        float w = src.width() * s;
        float h = src.height() * s;
        RectF scaled = new RectF(dst.centerX() - w / 2f, dst.centerY() - h / 2f, dst.centerX() + w / 2f, dst.centerY() + h / 2f);
        c.drawBitmap(cutout, src, scaled, p);
        c.restore();
    }

    private static Rect alphaBounds(Bitmap mask) {
        int w = mask.getWidth(), h = mask.getHeight();
        int left = w, top = h, right = -1, bottom = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            mask.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (Color.alpha(row[x]) >= 48) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        if (right < left || bottom < top) return new Rect(0, 0, w, h);
        int padX = Math.max(2, Math.round((right - left + 1) * 0.02f));
        int padY = Math.max(2, Math.round((bottom - top + 1) * 0.02f));
        return new Rect(Math.max(0, left - padX), Math.max(0, top - padY), Math.min(w, right + padX + 1), Math.min(h, bottom + padY + 1));
    }
}
