package com.sgggzg.qmzg.core;

import android.content.Intent;
import android.graphics.Bitmap;

import com.sgggzg.qmzg.ocr.OcrResult;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 只在进程内使用的瞬时运行状态（不做持久化）。
 *
 * <p>持久化配置统一走 {@code data.ConfigRepository}。这里保留的都是
 * “服务是否在跑、是否在报警、最近一帧预览图”这类运行时信息。</p>
 */
public final class RuntimeState {

    private static volatile boolean monitoring;
    private static volatile boolean alarming;

    private static volatile boolean imagePreviewEnabled = true;

    /** 日志开关：关闭后既不写内存日志，也不写文件、不发日志广播。 */
    private static volatile boolean logEnabled = true;

    /** 屏幕录制授权结果，由 UI 授权后交给服务启动采集。 */
    private static volatile Intent captureIntent;
    private static volatile int captureResultCode;

    private static final AtomicReference<Bitmap> lastPreview = new AtomicReference<>();
    private static final AtomicReference<OcrResult> lastOcrResult = new AtomicReference<>();

    private RuntimeState() {
    }

    public static boolean isMonitoring() {
        return monitoring;
    }

    public static void setMonitoring(boolean value) {
        monitoring = value;
    }

    public static boolean isAlarming() {
        return alarming;
    }

    public static void setAlarming(boolean value) {
        alarming = value;
    }

    public static boolean isImagePreviewEnabled() {
        return imagePreviewEnabled;
    }

    public static void setImagePreviewEnabled(boolean value) {
        imagePreviewEnabled = value;
    }

    public static boolean isLogEnabled() {
        return logEnabled;
    }

    public static void setLogEnabled(boolean value) {
        logEnabled = value;
    }

    public static Intent getCaptureIntent() {
        return captureIntent;
    }

    public static void setCaptureIntent(Intent intent) {
        captureIntent = intent;
    }

    public static int getCaptureResultCode() {
        return captureResultCode;
    }

    public static void setCaptureResultCode(int code) {
        captureResultCode = code;
    }

    public static Bitmap getLastPreview() {
        return lastPreview.get();
    }

    /**
     * 保存最近一帧预览图。旧图不主动 recycle：UI 可能正在绘制它，
     * 交给 GC 回收反而更安全（图本身很小）。
     */
    public static void setLastPreview(Bitmap bitmap) {
        lastPreview.set(bitmap);
    }

    public static OcrResult getLastOcrResult() {
        return lastOcrResult.get();
    }

    public static void setLastOcrResult(OcrResult result) {
        lastOcrResult.set(result);
    }
}
