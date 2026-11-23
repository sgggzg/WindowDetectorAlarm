package com.sgggzg.qmzg.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.sgggzg.qmzg.core.MonitorState;
import com.sgggzg.qmzg.core.RuntimeState;
import com.sgggzg.qmzg.data.AppConfig;
import com.sgggzg.qmzg.data.ConfigRepository;
import com.sgggzg.qmzg.data.RecognitionMode;
import com.sgggzg.qmzg.domain.AlarmGate;
import com.sgggzg.qmzg.domain.ConditionMatcher;
import com.sgggzg.qmzg.domain.MatchResult;
import com.sgggzg.qmzg.ocr.OcrEngine;
import com.sgggzg.qmzg.ocr.OcrLine;
import com.sgggzg.qmzg.ocr.OcrResult;
import com.sgggzg.qmzg.ocr.ppocr.PpOcrEngine;
import com.sgggzg.qmzg.util.BitmapUtils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 监控主循环：截屏 → 裁剪 → OCR → 条件匹配 → 回调报警。
 *
 * <p>整个循环跑在独立 HandlerThread 上，避免以前在主线里 OCR 导致界面卡顿。</p>
 */
public final class MonitorController {

    private static final String TAG = "MonitorController";

    public interface Callbacks {
        void onLog(String message);

        void onPreview(Bitmap bitmap);

        void onOcrResult(com.sgggzg.qmzg.ocr.OcrResult result);

        void onAlarm(String text, int count);

        void onProjectionStopped();

        void onStateChanged();
    }

    private final Context context;
    private final Callbacks callbacks;
    private final AlarmGate alarmGate = new AlarmGate();

    private HandlerThread thread;
    private Handler handler;
    private ScreenCaptureManager capture;
    private OcrEngine engine;
    private String failedEngineKey = "";

    private Bitmap lastFrame;
    private AppConfig config;
    private volatile boolean running;
    private volatile boolean ticking;
    private volatile boolean pauseRequested;
    private RecognitionMode mode = RecognitionMode.FIXED_INTERVAL;

    private ScreenCaptureManager streamCapture;
    private HandlerThread streamCaptureThread;
    private Thread streamProducerThread;
    private Thread streamOcrThread;
    private ArrayBlockingQueue<Bitmap> streamQueue;
    private final FrameDiffDetector frameDiff = new FrameDiffDetector();
    private volatile boolean streamRunning;
    private volatile boolean streamProducerBusy;
    private volatile boolean streamOcrBusy;
    private int streamDropCount;
    private long lastDropLogAt;
    private volatile boolean maskVisible;
    private volatile float maskAlpha = 0.95f;
    private int emptyStreak;
    private int skippedFrames;
    private long lastSkipLogAt;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            MonitorState.Phase phase = MonitorState.get();
            if (phase != MonitorState.Phase.RUNNING && phase != MonitorState.Phase.PAUSING) {
                return;
            }
            long begin = SystemClock.elapsedRealtime();
            ticking = true;
            try {
                tick();
            } catch (Throwable t) {
                Log.e(TAG, "loop error", t);
                log("识别循环异常: " + t.getMessage());
            } finally {
                ticking = false;
            }
            if (!running) {
                return;
            }
            if (MonitorState.isPausing()) {
                MonitorState.set(MonitorState.Phase.PAUSED);
                notifyStateChanged();
                return;
            }
            if (MonitorState.get() != MonitorState.Phase.RUNNING) {
                return;
            }
            long cost = SystemClock.elapsedRealtime() - begin;
            long interval = (long) (Math.max(0.5f, config == null ? 3f : config.intervalSec) * 1000);
            long delay = Math.max(50, interval - cost);
            Handler h = handler;
            if (h != null) {
                h.postDelayed(this, delay);
            }
        }
    };

    public MonitorController(Context context, Callbacks callbacks) {
        this.context = context.getApplicationContext();
        this.callbacks = callbacks;
        this.config = ConfigRepository.get(this.context).getActive();
    }

    public void start(final MediaProjection projection, final int width, final int height,
                      final int density) {
        if (running) {
            return;
        }
        final AppConfig startConfig = ConfigRepository.get(context).getActive();
        config = startConfig;
        mode = startConfig.recognitionMode;
        running = true;
        MonitorState.set(MonitorState.Phase.RUNNING);
        if (mode == RecognitionMode.STREAM) {
            startStream(projection, width, height, density);
            return;
        }
        thread = new HandlerThread("monitor-ocr", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        final Handler h = new Handler(thread.getLooper());
        handler = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                capture = new ScreenCaptureManager();
                capture.setStopCallback(new ScreenCaptureManager.StopCallback() {
                    @Override
                    public void onProjectionStopped() {
                        running = false;
                        if (callbacks != null) {
                            callbacks.onProjectionStopped();
                        }
                    }
                });
                boolean ok = capture.start(projection, width, height, density, h);
                if (!ok) {
                    log("屏幕采集启动失败");
                    running = false;
                    if (callbacks != null) {
                        callbacks.onProjectionStopped();
                    }
                    return;
                }
                log("屏幕采集已启动（" + width + "x" + height + "）");
                h.postDelayed(loop, 600);
            }
        });
    }

    public void stop() {
        running = false;
        MonitorState.set(MonitorState.Phase.STOPPED);
        if (mode == RecognitionMode.STREAM) {
            releaseStreamResources();
            return;
        }
        Handler h = handler;
        if (h == null) {
            releaseResources();
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                releaseResources();
            }
        });
    }

    public void pause() {
        if (mode == RecognitionMode.STREAM) {
            if (MonitorState.isRunning()) {
                MonitorState.set(MonitorState.Phase.PAUSING);
                notifyStateChanged();
                if (streamQueue == null) {
                    MonitorState.set(MonitorState.Phase.PAUSED);
                    notifyStateChanged();
                } else {
                    checkStreamPauseDone();
                }
            }
            return;
        }
        if (MonitorState.isRunning()) {
            MonitorState.set(MonitorState.Phase.PAUSING);
            pauseRequested = true;
            notifyStateChanged();
            if (!ticking) {
                MonitorState.set(MonitorState.Phase.PAUSED);
                notifyStateChanged();
            }
        }
    }

    public void resume() {
        pauseRequested = false;
        MonitorState.Phase phase = MonitorState.get();
        if (phase == MonitorState.Phase.PAUSED || phase == MonitorState.Phase.ALARM
                || phase == MonitorState.Phase.STOPPED) {
            MonitorState.set(MonitorState.Phase.RUNNING);
            notifyStateChanged();
            if (mode == RecognitionMode.FIXED_INTERVAL) {
                Handler h = handler;
                if (h != null) {
                    h.removeCallbacks(loop);
                    h.post(loop);
                }
            }
        }
    }

    public void onAlarmPause() {
        MonitorState.set(MonitorState.Phase.ALARM);
        if (mode == RecognitionMode.STREAM && streamQueue != null) {
            Bitmap bitmap;
            while ((bitmap = streamQueue.poll()) != null) {
                BitmapUtils.recycle(bitmap);
            }
            frameDiff.reset();
        }
        notifyStateChanged();
    }

    private void startStream(final MediaProjection projection, final int width,
                             final int height, final int density) {
        streamRunning = true;
        streamCaptureThread = new HandlerThread("stream-capture",
                Process.THREAD_PRIORITY_URGENT_DISPLAY);
        streamCaptureThread.start();
        Handler streamHandler = new Handler(streamCaptureThread.getLooper());

        streamCapture = new ScreenCaptureManager();
        streamCapture.setStopCallback(new ScreenCaptureManager.StopCallback() {
            @Override
            public void onProjectionStopped() {
                running = false;
                streamRunning = false;
                if (callbacks != null) {
                    callbacks.onProjectionStopped();
                }
            }
        });
        if (!streamCapture.start(projection, width, height, density, streamHandler)) {
            log("推流采集启动失败");
            running = false;
            releaseStreamResources();
            if (callbacks != null) {
                callbacks.onProjectionStopped();
            }
            return;
        }

        streamQueue = new ArrayBlockingQueue<>(Math.max(1, config.queueCapacity));
        initEngine();

        streamProducerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                streamProducerLoop();
            }
        }, "stream-producer");
        streamOcrThread = new Thread(new Runnable() {
            @Override
            public void run() {
                streamOcrLoop();
            }
        }, "stream-ocr");
        streamProducerThread.start();
        streamOcrThread.start();
        log("推流采集已启动（" + width + "x" + height + "，队列 " + config.queueCapacity + "）");
    }

    private void streamProducerLoop() {
        while (streamRunning) {
            try {
                if (!MonitorState.isRunning()) {
                    sleepQuietly(20);
                    continue;
                }
                Bitmap full = streamCapture == null ? null : streamCapture.acquireLatestBitmap();
                if (full == null) {
                    sleepQuietly(3);
                    continue;
                }
                streamProducerBusy = true;
                try {
                    Bitmap roi;
                    try {
                        roi = BitmapUtils.crop(full, config);
                    } finally {
                        full.recycle();
                    }
                    if (roi == null) {
                        continue;
                    }
                    if (frameDiff.isChanged(roi)) {
                        if (!MonitorState.shouldProcess()) {
                            BitmapUtils.recycle(roi);
                        } else {
                            if (RuntimeState.isImagePreviewEnabled()) {
                                Bitmap preview = roi.copy(Bitmap.Config.ARGB_8888, false);
                                if (preview != null && callbacks != null) {
                                    callbacks.onPreview(preview);
                                }
                            }
                            if (!streamQueue.offer(roi)) {
                                BitmapUtils.recycle(roi);
                                streamDropCount++;
                                long now = SystemClock.elapsedRealtime();
                                if (streamDropCount == 1 || streamDropCount % 10 == 0
                                        || now - lastDropLogAt >= 30000) {
                                    lastDropLogAt = now;
                                    log("OCR 队列已满（" + config.queueCapacity
                                            + "），累计丢弃 " + streamDropCount + " 帧");
                                }
                            }
                        }
                    } else {
                        BitmapUtils.recycle(roi);
                    }
                } finally {
                    streamProducerBusy = false;
                    checkStreamPauseDone();
                }
            } catch (Throwable t) {
                Log.e(TAG, "stream producer error", t);
                sleepQuietly(20);
            }
        }
    }

    private void streamOcrLoop() {
        while (streamRunning) {
            try {
                Bitmap bitmap = streamQueue == null ? null
                        : streamQueue.poll(200, TimeUnit.MILLISECONDS);
                if (bitmap == null) {
                    checkStreamPauseDone();
                    continue;
                }
                if (!MonitorState.shouldProcess()) {
                    BitmapUtils.recycle(bitmap);
                    checkStreamPauseDone();
                    continue;
                }
                OcrEngine currentEngine = engine;
                if (currentEngine == null || !currentEngine.isReady()) {
                    BitmapUtils.recycle(bitmap);
                    checkStreamPauseDone();
                    continue;
                }
                streamOcrBusy = true;
                try {
                    OcrResult result = currentEngine.recognize(bitmap);
                    if (callbacks != null) {
                        callbacks.onOcrResult(result);
                    }
                    if (MonitorState.isAlarm()) {
                        continue;
                    }
                    if (!result.isEmpty()) {
                        logOcr(result);
                        MatchResult match = ConditionMatcher.match(result, config,
                                new ConditionMatcher.LogCallback() {
                                    @Override
                                    public void onLog(String message) {
                                        log(message);
                                    }
                                });
                        if (match.matched) {
                            int count = alarmGate.permit(config.cooldownSec);
                            if (count >= 0 && callbacks != null) {
                                callbacks.onAlarm(match.text, count);
                            }
                        }
                    }
                } finally {
                    BitmapUtils.recycle(bitmap);
                    streamOcrBusy = false;
                    checkStreamPauseDone();
                }
            } catch (Throwable t) {
                Log.e(TAG, "stream ocr error", t);
                sleepQuietly(20);
            }
        }
    }

    private void checkStreamPauseDone() {
        if (MonitorState.isPausing() && !streamProducerBusy && !streamOcrBusy
                && streamQueue != null && streamQueue.isEmpty()) {
            MonitorState.set(MonitorState.Phase.PAUSED);
            notifyStateChanged();
        }
    }

    private void initEngine() {
        if (engine != null) {
            return;
        }
        final PpOcrEngine created = new PpOcrEngine(config.minConfidence);
        engine = created;
        log("正在加载 OCR 引擎: " + created.displayName());
        created.init(context, new OcrEngine.InitCallback() {
            @Override
            public void onSuccess() {
                if (engine == created) {
                    frameDiff.reset();
                    log("OCR 引擎就绪");
                }
            }

            @Override
            public void onFailure(String message) {
                log("OCR 初始化失败: " + message);
                if (engine == created) {
                    engine = null;
                }
                created.release();
            }
        });
    }

    private void releaseStreamResources() {
        streamRunning = false;
        if (streamCapture != null) {
            streamCapture.release();
            streamCapture = null;
        }
        if (streamProducerThread != null) {
            streamProducerThread.interrupt();
            streamProducerThread = null;
        }
        if (streamOcrThread != null) {
            streamOcrThread.interrupt();
            streamOcrThread = null;
        }
        if (streamCaptureThread != null) {
            streamCaptureThread.quitSafely();
            streamCaptureThread = null;
        }
        if (streamQueue != null) {
            Bitmap bitmap;
            while ((bitmap = streamQueue.poll()) != null) {
                BitmapUtils.recycle(bitmap);
            }
            streamQueue = null;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
        frameDiff.reset();
        MonitorState.set(MonitorState.Phase.STOPPED);
    }

    private void notifyStateChanged() {
        if (callbacks != null) {
            callbacks.onStateChanged();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    private void releaseResources() {
        if (capture != null) {
            capture.release();
            capture = null;
        }
        if (engine != null) {
            engine.release();
            engine = null;
        }
        BitmapUtils.recycle(lastFrame);
        lastFrame = null;
        HandlerThread t = thread;
        thread = null;
        handler = null;
        if (t != null) {
            t.quitSafely();
        }
        MonitorState.set(MonitorState.Phase.STOPPED);
    }

    public void setMaskState(boolean visible, float alpha) {
        this.maskVisible = visible;
        this.maskAlpha = alpha;
    }

    public void resetAlarmGate() {
        alarmGate.reset();
    }

    public boolean isRunning() {
        return running;
    }

    public AppConfig getConfig() {
        return config;
    }

    // ==================== 主循环 ====================

    private void tick() {
        if (RuntimeState.isAlarming()) {
            return;
        }
        ScreenCaptureManager captureManager = capture;
        if (captureManager == null) {
            return;
        }
        Bitmap full = captureManager.acquireLatestBitmap();
        if (full == null) {
            return;
        }

        AppConfig cfg = ConfigRepository.get(context).getActive();
        config = cfg;
        ensureEngine(cfg);

        Bitmap cropped;
        try {
            cropped = BitmapUtils.crop(full, cfg);
        } finally {
            full.recycle();
        }
        if (cropped == null) {
            return;
        }

        if (maskVisible) {
            Bitmap restored = BitmapUtils.restoreFromDarkMask(cropped, maskAlpha);
            if (restored != cropped) {
                BitmapUtils.recycle(cropped);
                cropped = restored;
            }
        }

        if (cfg.skipNoChange && lastFrame != null && BitmapUtils.sameImage(cropped, lastFrame)) {
            BitmapUtils.recycle(cropped);
            skippedFrames++;
            long now = SystemClock.elapsedRealtime();
            if (skippedFrames == 1 || skippedFrames % 10 == 0 || now - lastSkipLogAt >= 30000) {
                lastSkipLogAt = now;
                log("画面无变化，跳过 OCR（连续 " + skippedFrames + " 次）");
            }
            return;
        }
        if (skippedFrames > 0) {
            log("画面有变化，恢复识别（此前跳过 " + skippedFrames + " 次）");
            skippedFrames = 0;
        }
        replaceLastFrame(cropped);

        if (RuntimeState.isImagePreviewEnabled()) {
            try {
                Bitmap preview = cropped.copy(Bitmap.Config.ARGB_8888, false);
                if (preview != null && callbacks != null) {
                    callbacks.onPreview(preview);
                }
            } catch (Throwable ignored) {
            }
        }

        OcrEngine currentEngine = engine;
        if (currentEngine == null || !currentEngine.isReady()) {
            return;
        }

        OcrResult result = currentEngine.recognize(cropped);
        if (callbacks != null) {
            callbacks.onOcrResult(result);
        }
        if (result.isEmpty()) {
            emptyStreak++;
            if (emptyStreak == 1 || emptyStreak % 20 == 0) {
                log("未识别到文字（检查识别区域是否对准，或画面暂无文字）");
            }
            return;
        }
        emptyStreak = 0;
        logOcr(result);

        MatchResult match = ConditionMatcher.match(result, cfg, new ConditionMatcher.LogCallback() {
            @Override
            public void onLog(String message) {
                log(message);
            }
        });
        if (!match.matched) {
            return;
        }

        int count = alarmGate.permit(cfg.cooldownSec);
        if (count < 0) {
            log("命中目标，但仍在 " + cfg.cooldownSec + " 秒冷却时间内");
            return;
        }
        if (callbacks != null) {
            callbacks.onAlarm(match.text, count);
        }
    }

    private void replaceLastFrame(Bitmap bitmap) {
        Bitmap old = lastFrame;
        lastFrame = bitmap;
        if (old != null && old != bitmap) {
            BitmapUtils.recycle(old);
        }
    }

    private void ensureEngine(AppConfig cfg) {
        if (engine != null) {
            if (engine instanceof PpOcrEngine) {
                ((PpOcrEngine) engine).setOptions(cfg.minConfidence);
                return;
            }
            engine.release();
            engine = null;
        }

        final String key = cfg.ocrEngine + "|" + cfg.minConfidence;
        if (key.equals(failedEngineKey)) {
            return;
        }

        final PpOcrEngine created = new PpOcrEngine(cfg.minConfidence);
        engine = created;
        log("正在加载 OCR 引擎: " + created.displayName());
        created.init(context, new OcrEngine.InitCallback() {
            @Override
            public void onSuccess() {
                if (engine == created) {
                    frameDiff.reset();
                    log("OCR 引擎就绪");
                }
            }

            @Override
            public void onFailure(String message) {
                failedEngineKey = key;
                log("OCR 初始化失败: " + message);
                if (engine == created) {
                    engine = null;
                }
                created.release();
            }
        });
    }

    private void logOcr(OcrResult result) {
        int count = result.getLines().size();
        int confidence = (int) (result.averageConfidence() * 100);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (OcrLine line : result.getLines()) {
            if (shown >= 3) {
                break;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            sb.append(trim(line.text, 34));
            shown++;
        }
        log("OCR[" + count + "行 " + confidence + "%] " + sb);
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ');
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private void log(String message) {
        if (callbacks != null) {
            callbacks.onLog(message);
        }
    }
}
