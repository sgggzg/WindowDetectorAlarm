package com.sgggzg.qmzg.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import com.sgggzg.qmzg.data.AppConfig;

/** Bitmap 小工具：区域裁剪、画面变化判断、暗屏像素还原。 */
public final class BitmapUtils {

    private BitmapUtils() {
    }

    public static Bitmap crop(Bitmap source, AppConfig config) {
        if (source == null || source.isRecycled() || config == null) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        int top = (int) (height * config.top);
        int bottom = (int) (height * config.bottom);
        int left = (int) (width * config.left);
        int right = (int) (width * config.right);
        left = clamp(left, 0, width - 1);
        top = clamp(top, 0, height - 1);
        right = clamp(right, left + 1, width);
        bottom = clamp(bottom, top + 1, height);
        try {
            return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 暗屏遮罩做了 1/(1-alpha) 的提亮，这里反向压暗，保证 OCR 对比度正常。 */
    public static Bitmap restoreFromDarkMask(Bitmap source, float maskAlpha) {
        if (source == null || source.isRecycled()) {
            return source;
        }
        if (maskAlpha <= 0.01f || maskAlpha >= 0.995f) {
            return source;
        }
        try {
            float factor = 1f / (1f - maskAlpha);
            Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint();
            ColorMatrix matrix = new ColorMatrix(new float[]{
                    factor, 0, 0, 0, 0,
                    0, factor, 0, 0, 0,
                    0, 0, factor, 0, 0,
                    0, 0, 0, 1, 0
            });
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
            canvas.drawBitmap(source, 0, 0, paint);
            return out;
        } catch (Throwable t) {
            return source;
        }
    }

    /** 判断两帧是否几乎一样：缩到 96 宽后抽样比较，避免逐像素 getPixel。 */
    public static boolean sameImage(Bitmap first, Bitmap second) {
        if (first == null || second == null || first.isRecycled() || second.isRecycled()) {
            return false;
        }
        Bitmap smallFirst = null;
        Bitmap smallSecond = null;
        try {
            int targetWidth = 96;
            int firstHeight = Math.max(1,
                    (int) (first.getHeight() * targetWidth / (float) first.getWidth()));
            int secondHeight = Math.max(1,
                    (int) (second.getHeight() * targetWidth / (float) second.getWidth()));
            smallFirst = Bitmap.createScaledBitmap(first, targetWidth, firstHeight, false);
            smallSecond = Bitmap.createScaledBitmap(second, targetWidth, secondHeight, false);

            int w = Math.min(smallFirst.getWidth(), smallSecond.getWidth());
            int h = Math.min(smallFirst.getHeight(), smallSecond.getHeight());
            int[] pixelsFirst = new int[w * h];
            int[] pixelsSecond = new int[w * h];
            smallFirst.getPixels(pixelsFirst, 0, smallFirst.getWidth(), 0, 0, w, h);
            smallSecond.getPixels(pixelsSecond, 0, smallSecond.getWidth(), 0, 0, w, h);

            long diff = 0;
            int samples = 0;
            for (int y = 0; y < h; y += 2) {
                int row = y * w;
                for (int x = 0; x < w; x += 2) {
                    int p1 = pixelsFirst[row + x];
                    int p2 = pixelsSecond[row + x];
                    diff += Math.abs(Color.red(p1) - Color.red(p2));
                    diff += Math.abs(Color.green(p1) - Color.green(p2));
                    diff += Math.abs(Color.blue(p1) - Color.blue(p2));
                    samples += 3;
                }
            }
            return samples > 0 && diff / samples < 4;
        } catch (Throwable t) {
            return false;
        } finally {
            if (smallFirst != null && smallFirst != first) {
                smallFirst.recycle();
            }
            if (smallSecond != null && smallSecond != second) {
                smallSecond.recycle();
            }
        }
    }

    public static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}
