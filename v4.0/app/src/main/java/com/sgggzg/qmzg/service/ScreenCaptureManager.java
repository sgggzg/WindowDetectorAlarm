package com.sgggzg.qmzg.service;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;

import java.nio.ByteBuffer;

/** 基于 MediaProjection 的屏幕采集，只负责取帧。 */
public class ScreenCaptureManager {

    public interface StopCallback {
        void onProjectionStopped();
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private volatile boolean isCapturing;
    private StopCallback stopCallback;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            isCapturing = false;
            StopCallback callback = stopCallback;
            if (callback != null) {
                callback.onProjectionStopped();
            }
        }
    };

    public void setStopCallback(StopCallback callback) {
        this.stopCallback = callback;
    }

    public boolean start(MediaProjection projection, int width, int height, int density, Handler handler) {
        if (projection == null || width <= 0 || height <= 0) {
            return false;
        }
        try {
            release();
            this.mediaProjection = projection;
            this.screenWidth = width;
            this.screenHeight = height;
            this.screenDensity = density;

            projection.registerCallback(projectionCallback, handler);
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "WindowDetectorCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, handler);
            isCapturing = true;
            return true;
        } catch (Exception e) {
            isCapturing = false;
            return false;
        }
    }

    public Bitmap acquireLatestBitmap() {
        if (!isCapturing || imageReader == null) {
            return null;
        }
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                return null;
            }
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowPadding = rowStride - pixelStride * width;
            int paddedWidth = width + rowPadding / pixelStride;

            Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);

            if (paddedWidth == width) {
                return padded;
            }
            Bitmap result = Bitmap.createBitmap(padded, 0, 0, width, height);
            padded.recycle();
            return result;
        } catch (Throwable t) {
            return null;
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void release() {
        isCapturing = false;
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (Exception ignored) {
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (Exception ignored) {
            }
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }
    }

    public boolean isCapturing() {
        return isCapturing;
    }
}
