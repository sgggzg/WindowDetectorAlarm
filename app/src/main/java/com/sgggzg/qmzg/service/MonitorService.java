package com.sgggzg.qmzg.service;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.sgggzg.qmzg.core.Actions;
import com.sgggzg.qmzg.core.AppLog;
import com.sgggzg.qmzg.core.MonitorState;
import com.sgggzg.qmzg.core.RuntimeState;
import com.sgggzg.qmzg.data.AppConfig;
import com.sgggzg.qmzg.data.ConfigRepository;
import com.sgggzg.qmzg.service.overlay.AlarmWindowManager;
import com.sgggzg.qmzg.service.overlay.DarkMaskManager;
import com.sgggzg.qmzg.service.overlay.FloatingBallManager;

import java.util.List;

/**
 * 前台监控服务：负责生命周期、通知栏、悬浮球、报警窗、暗屏遮罩。
 *
 * <p>截屏/OCR/匹配逻辑已经下沉到 {@link MonitorController}。</p>
 */
public class MonitorService extends Service {

    private MonitorController controller;
    private FloatingBallManager floatingBallManager;
    private AlarmWindowManager alarmWindowManager;
    private DarkMaskManager darkMaskManager;

    private Handler mainHandler;
    private MediaPlayer alarmPlayer;
    private PowerManager.WakeLock wakeLock;

    private boolean maskOn;
    private boolean testAlarm;
    private boolean captureStarted;

    private final MonitorController.Callbacks controllerCallbacks = new MonitorController.Callbacks() {
        @Override
        public void onLog(String message) {
            sendLog(message);
        }

        @Override
        public void onPreview(Bitmap bitmap) {
            RuntimeState.setLastPreview(bitmap);
            try {
                LocalBroadcastManager.getInstance(MonitorService.this)
                        .sendBroadcast(new Intent(Actions.IMAGE));
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onOcrResult(com.sgggzg.qmzg.ocr.OcrResult result) {
            RuntimeState.setLastOcrResult(result);
            try {
                LocalBroadcastManager.getInstance(MonitorService.this)
                        .sendBroadcast(new Intent(Actions.OCR_RESULT));
            } catch (Exception ignored) {
            }
        }

        @Override
        public void onAlarm(final String text, final int count) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    triggerRealAlarm(text, count);
                }
            });
        }

        @Override
        public void onProjectionStopped() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    sendLog("MediaProjection 被系统停止");
                    stopSelf();
                }
            });
        }

        @Override
        public void onStateChanged() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    syncMonitorUi();
                }
            });
            sendServiceStateChanged();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        mainHandler = new Handler(Looper.getMainLooper());
        NotificationHelper.createChannel(this);
        MonitorState.set(MonitorState.Phase.STOPPED);

        ConfigRepository repo = ConfigRepository.get(this);
        RuntimeState.setImagePreviewEnabled(repo.isImagePreviewEnabled());
        RuntimeState.setLogEnabled(repo.isLogEnabled());

        acquireWakeLock();

        floatingBallManager = new FloatingBallManager(this, new FloatingBallManager.ActionCallback() {
            @Override
            public void onStopMonitor() {
                sendLog("悬浮球：停止监控");
                stopSelf();
            }

            @Override
            public void onPauseResume() {
                handlePauseResume();
            }

            @Override
            public void onToggleDarkMask() {
                toggleDarkMask();
            }

            @Override
            public void onLogMessage(String msg) {
                sendLog(msg);
            }
        });

        alarmWindowManager = new AlarmWindowManager(this, new AlarmWindowManager.AlarmActionCallback() {
            @Override
            public void onDismissAlarm() {
                stopAlarmAndResetUI();
            }
        });

        darkMaskManager = new DarkMaskManager(this);
        darkMaskManager.setCallback(new DarkMaskManager.Callback() {
            @Override
            public void onRequestClose() {
                sendLog("遮罩长按 2 秒，关闭");
                hideDarkMask();
            }
        });

        controller = new MonitorController(this, controllerCallbacks);
        sendLog("=== Service onCreate ===");
        sendServiceStateChanged();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            sendLog("系统意外重启服务，强制停止");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (Actions.STOP_MONITOR.equals(action)) {
            sendLog("通知栏：停止监控");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Actions.STOP_ALARM.equals(action)) {
            sendLog("收到停止报警指令");
            stopAlarmAndResetUI();
            return START_STICKY;
        }
        if (Actions.TOGGLE_MASK.equals(action)) {
            sendLog("通知栏：切换暗屏");
            toggleDarkMask();
            return START_STICKY;
        }
        if (Actions.PAUSE_RESUME.equals(action)) {
            handlePauseResume();
            return START_STICKY;
        }
        if (Actions.TEST_ALARM.equals(action)) {
            testAlarm();
            return START_STICKY;
        }

        try {
            startMonitoring();
        } catch (Exception e) {
            sendLog("启动监控失败: " + e.getMessage());
            stopSelf();
        }
        return START_STICKY;
    }

    private void startMonitoring() {
        AppConfig config = ConfigRepository.get(this).getActive();
        RuntimeState.setMonitoring(true);
        RuntimeState.setAlarming(false);
        MonitorState.set(MonitorState.Phase.RUNNING);
        testAlarm = false;

        sendLog("=== 开始监控：《" + config.name + "》 ===");
        // 先进入前台，避免 startForegroundService 超时异常
        startForegroundNotification(config);

        if (RuntimeState.getCaptureIntent() == null) {
            sendLog("缺少屏幕录制授权，无法启动监控");
            stopSelf();
            return;
        }
        floatingBallManager.show();

        if (!captureStarted) {
            captureStarted = true;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startCapture();
                }
            }, 800);
        }
        sendServiceStateChanged();
    }

    private void startCapture() {
        Intent captureIntent = RuntimeState.getCaptureIntent();
        int resultCode = RuntimeState.getCaptureResultCode();
        if (captureIntent == null) {
            sendLog("屏幕录制 Intent 丢失，请重新授权");
            stopSelf();
            return;
        }
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection projection = manager.getMediaProjection(resultCode, captureIntent);
            if (projection == null) {
                sendLog("获取 MediaProjection 失败，请重新授权");
                stopSelf();
                return;
            }
            DisplayMetrics metrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            sendLog("屏幕尺寸: " + metrics.widthPixels + "x" + metrics.heightPixels);
            controller.start(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
        } catch (Exception e) {
            sendLog("启动屏幕采集异常: " + e.getMessage());
            stopSelf();
        }
    }

    private void startForegroundNotification(AppConfig config) {
        try {
            String content = "方案：" + config.name + " · "
                    + config.conditionGroups.size() + " 组条件 · "
                    + config.intervalSec + "s";
            Notification notification = NotificationHelper.buildNotification(
                    this, "屏幕文字监控中", content, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, notification);
            }
        } catch (Exception e) {
            sendLog("startForeground 异常: " + e.getMessage());
        }
    }

    // ==================== 报警 ====================

    private void testAlarm() {
        AppConfig config = ConfigRepository.get(this).getActive();
        boolean wasMonitoring = controller != null && controller.isRunning();
        RuntimeState.setMonitoring(true);
        RuntimeState.setAlarming(true);
        MonitorState.set(MonitorState.Phase.ALARM);
        if (controller != null) {
            controller.onAlarmPause();
        }
        // 只有在“未开始监控、单纯测试报警”时才在关闭后自动停止服务
        testAlarm = !wasMonitoring;
        floatingBallManager.show();
        floatingBallManager.setMonitorPhase(MonitorState.get());
        playAlarmEffect(config);

        String sample = "【测试】检测到「" + firstKeyword(config)
                + "」时会像这样报警，点“停止报警”即可关闭";
        if (alarmWindowManager != null) {
            alarmWindowManager.show(sample, 1, config.cooldownSec);
        }
        NotificationHelper.notify(this, "测试报警", "报警窗口已弹出", true);
        sendLog("=== 测试报警 ===");
        sendServiceStateChanged();
    }

    private void triggerRealAlarm(String text, int count) {
        if (RuntimeState.isAlarming()) {
            return;
        }
        RuntimeState.setAlarming(true);
        MonitorState.set(MonitorState.Phase.ALARM);
        if (controller != null) {
            controller.onAlarmPause();
        }
        AppConfig config = controller != null ? controller.getConfig()
                : ConfigRepository.get(this).getActive();
        playAlarmEffect(config);
        if (alarmWindowManager != null) {
            alarmWindowManager.show(text, count, config.cooldownSec);
        }
        NotificationHelper.notify(this, "警报！检测到目标内容", "点击查看详情", true);
        floatingBallManager.setMonitorPhase(MonitorState.get());
        sendLog("=== 触发报警（第 " + count + " 次）===");
        sendServiceStateChanged();
    }

    private void stopAlarmAndResetUI() {
        RuntimeState.setAlarming(false);
        stopAlarmEffect();
        if (alarmWindowManager != null) {
            alarmWindowManager.hide();
        }
        if (floatingBallManager != null) {
            floatingBallManager.setMonitorPhase(MonitorState.get());
        }
        if (testAlarm) {
            testAlarm = false;
            RuntimeState.setMonitoring(false);
            MonitorState.set(MonitorState.Phase.STOPPED);
            sendLog("测试报警结束，自动停止监控");
            sendServiceStateChanged();
            stopForeground(true);
            stopSelf();
            return;
        }
        MonitorState.set(MonitorState.Phase.RUNNING);
        if (controller != null) {
            controller.resume();
        }
        if (floatingBallManager != null) {
            floatingBallManager.setMonitorPhase(MonitorState.get());
        }
        NotificationHelper.notify(this, "屏幕文字监控中",
                maskOn ? "暗屏已开启" : "监控中", false);
        sendServiceStateChanged();
    }

    private void playAlarmEffect(AppConfig config) {
        if (config.vibrate) {
            try {
                Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(
                                new long[]{0, 500, 200, 500, 200, 500}, -1));
                    } else {
                        vibrator.vibrate(new long[]{0, 500, 200, 500, 200, 500}, -1);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (!config.sound) {
            return;
        }
        if (alarmPlayer != null && alarmPlayer.isPlaying()) {
            return;
        }
        try {
            if (alarmPlayer != null) {
                alarmPlayer.release();
                alarmPlayer = null;
            }
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            alarmPlayer = MediaPlayer.create(this, uri);
            if (alarmPlayer != null) {
                alarmPlayer.setLooping(true);
                alarmPlayer.start();
            }
        } catch (Exception e) {
            sendLog("播放报警音失败: " + e.getMessage());
        }
    }

    private void stopAlarmEffect() {
        if (alarmPlayer != null) {
            try {
                if (alarmPlayer.isPlaying()) {
                    alarmPlayer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                alarmPlayer.release();
            } catch (Exception ignored) {
            }
            alarmPlayer = null;
        }
    }

    private static String firstKeyword(AppConfig config) {
        if (!config.conditionGroups.isEmpty()
                && !config.conditionGroups.get(0).keywords.isEmpty()) {
            return config.conditionGroups.get(0).keywords.get(0).text;
        }
        return "目标文字";
    }

    // ==================== 暗屏遮罩 ====================

    private void toggleDarkMask() {
        if (maskOn) {
            hideDarkMask();
        } else {
            showDarkMask();
        }
    }

    private void showDarkMask() {
        if (darkMaskManager == null || darkMaskManager.isShowing()) {
            return;
        }
        AppConfig config = ConfigRepository.get(this).getActive();
        int alphaPct = ConfigRepository.get(this).getMaskAlphaPct();
        final float alpha = alphaPct / 100f;

        darkMaskManager.setRegion(config.top, config.bottom, config.left, config.right);
        darkMaskManager.setMaskAlpha(alpha);
        if (controller != null) {
            controller.setMaskState(true, alpha);
        }
        maskOn = true;
        sendLog("开启暗屏遮罩（不透明度 " + alphaPct + "%）");

        final FloatingBallManager ball = floatingBallManager;
        if (ball != null) {
            ball.collapseMenu();
            ball.fadeOutThenRun(new Runnable() {
                @Override
                public void run() {
                    ball.hide();
                    darkMaskManager.show();
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (floatingBallManager != null) {
                                floatingBallManager.setDarkModeWithoutReAdd(true);
                                floatingBallManager.show();
                                floatingBallManager.fadeIn();
                            }
                        }
                    }, 350);
                }
            });
        } else {
            darkMaskManager.show();
        }

        NotificationHelper.setMaskOn(true);
        NotificationHelper.notify(this, "屏幕文字监控中", "暗屏已开启", RuntimeState.isAlarming());
    }

    private void hideDarkMask() {
        if (darkMaskManager == null) {
            return;
        }
        if (controller != null) {
            controller.setMaskState(false, 0.95f);
        }
        maskOn = false;
        sendLog("关闭暗屏遮罩");

        final FloatingBallManager ball = floatingBallManager;
        if (ball != null) {
            ball.fadeOutThenRun(new Runnable() {
                @Override
                public void run() {
                    ball.hide();
                    darkMaskManager.hide();
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (floatingBallManager != null) {
                                floatingBallManager.setDarkModeWithoutReAdd(false);
                                floatingBallManager.show();
                                floatingBallManager.fadeIn();
                            }
                        }
                    }, 350);
                }
            });
        } else {
            darkMaskManager.hide();
        }

        NotificationHelper.setMaskOn(false);
        NotificationHelper.notify(this, "屏幕文字监控中", "监控中", RuntimeState.isAlarming());
    }

    // ==================== 日志 / 状态 ====================

    private void handlePauseResume() {
        MonitorState.Phase phase = MonitorState.get();
        if (phase == MonitorState.Phase.ALARM) {
            stopAlarmAndResetUI();
            return;
        }
        if (phase == MonitorState.Phase.PAUSING) {
            return;
        }
        if (phase == MonitorState.Phase.PAUSED) {
            if (controller != null) {
                controller.resume();
            }
            sendLog("继续监控");
        } else if (phase == MonitorState.Phase.RUNNING) {
            if (controller != null) {
                controller.pause();
            }
            sendLog("暂停监控");
        }
        syncMonitorUi();
        sendServiceStateChanged();
    }

    private void syncMonitorUi() {
        if (floatingBallManager != null) {
            floatingBallManager.setMonitorPhase(MonitorState.get());
        }
        String content;
        MonitorState.Phase phase = MonitorState.get();
        if (phase == MonitorState.Phase.PAUSED) {
            content = "已暂停";
        } else if (phase == MonitorState.Phase.PAUSING) {
            content = "正在暂停";
        } else {
            content = maskOn ? "暗屏已开启" : "监控中";
        }
        NotificationHelper.notify(this, "屏幕文字监控中", content, RuntimeState.isAlarming());
    }

    private void sendLog(String message) {
        if (!RuntimeState.isLogEnabled()) {
            return;
        }
        AppLog.append(message);
        try {
            Intent intent = new Intent(Actions.LOG);
            intent.putExtra(Actions.EXTRA_LOG, message);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } catch (Exception ignored) {
        }
    }

    private void sendServiceStateChanged() {
        try {
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                    new Intent(Actions.SERVICE_STATE_CHANGED));
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "WindowDetector::Monitor");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            sendLog("申请 WakeLock 失败: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        RuntimeState.setMonitoring(false);
        RuntimeState.setAlarming(false);
        MonitorState.set(MonitorState.Phase.STOPPED);
        maskOn = false;
        NotificationHelper.setMaskOn(false);

        if (controller != null) {
            controller.stop();
            controller = null;
        }
        stopAlarmEffect();
        if (darkMaskManager != null) {
            darkMaskManager.release();
        }
        if (alarmWindowManager != null) {
            alarmWindowManager.hide();
        }
        if (floatingBallManager != null) {
            floatingBallManager.hide();
        }
        releaseWakeLock();

        sendLog("=== onDestroy ===");
        stopForeground(true);
        NotificationHelper.cancelAll(this);
        sendServiceStateChanged();
        try {
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(Actions.SERVICE_STOPPED));
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
