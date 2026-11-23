package com.sgggzg.qmzg.service;

import android.graphics.Bitmap;

/** 轻量帧差：只比较降采样后的灰度图。 */
final class FrameDiffDetector {

    private static final int TARGET_WIDTH = 160;
    private static final int AVG_THRESHOLD = 4;
    private static final float CHANGE_RATIO_THRESHOLD = 0.015f;

    private byte[] previous;
    private int previousWidth;
    private int previousHeight;

    boolean isChanged(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        int targetWidth = Math.min(TARGET_WIDTH, bitmap.getWidth());
        int targetHeight = Math.max(1,
                Math.round(bitmap.getHeight() * targetWidth / (float) bitmap.getWidth()));
        Bitmap scaled = null;
        try {
            scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, false);
            int[] pixels = new int[targetWidth * targetHeight];
            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight);
            byte[] current = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int gray = (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF)) / 3;
                current[i] = (byte) gray;
            }

            boolean changed = previous == null
                    || previousWidth != targetWidth
                    || previousHeight != targetHeight;
            if (previous != null
                    && previousWidth == targetWidth
                    && previousHeight == targetHeight) {
                long total = 0;
                int changedPixels = 0;
                for (int i = 0; i < current.length; i++) {
                    int diff = Math.abs((current[i] & 0xFF) - (previous[i] & 0xFF));
                    total += diff;
                    if (diff > 18) {
                        changedPixels++;
                    }
                }
                float avg = total / (float) current.length;
                float ratio = changedPixels / (float) current.length;
                changed = avg > AVG_THRESHOLD || ratio > CHANGE_RATIO_THRESHOLD;
            }

            previous = current;
            previousWidth = targetWidth;
            previousHeight = targetHeight;
            return changed;
        } catch (Throwable t) {
            return false;
        } finally {
            if (scaled != null && scaled != bitmap) {
                scaled.recycle();
            }
        }
    }

    void reset() {
        previous = null;
        previousWidth = 0;
        previousHeight = 0;
    }
}
