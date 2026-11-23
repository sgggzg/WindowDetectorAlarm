package com.sgggzg.qmzg.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.sgggzg.qmzg.core.Actions;
import com.sgggzg.qmzg.core.MonitorState;
import com.sgggzg.qmzg.ui.MainActivity;

/** 前台服务通知与通知栏快捷操作。 */
public final class NotificationHelper {

    private static final String CHANNEL_ID = "game_monitor";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean maskOn;

    private NotificationHelper() {
    }

    public static void setMaskOn(boolean on) {
        maskOn = on;
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "游戏监控", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("实时监控屏幕文字并在命中条件时报警");
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static Notification buildNotification(Context context, String title, String content,
                                                 boolean isAlarming) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, 4, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stopMonitor = PendingIntent.getService(context, 1,
                command(context, Actions.STOP_MONITOR),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_CANCEL_CURRENT);

        NotificationCompat.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new NotificationCompat.Builder(context, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(context);
        }
        builder.setContentTitle(title)
                .setContentText(content)
                .setContentIntent(openPending)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);

        PendingIntent toggleMask = PendingIntent.getService(context, 3,
                command(context, Actions.TOGGLE_MASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_CANCEL_CURRENT);
        builder.addAction(android.R.drawable.ic_menu_view, maskOn ? "亮屏" : "暗屏", toggleMask);
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止监控", stopMonitor);

        PendingIntent pauseResume = PendingIntent.getService(context, 2,
                command(context, Actions.PAUSE_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_CANCEL_CURRENT);
        builder.addAction(android.R.drawable.ic_media_pause,
                pauseLabel(), pauseResume);
        return builder.build();
    }

    private static String pauseLabel() {
        MonitorState.Phase phase = MonitorState.get();
        if (phase == MonitorState.Phase.PAUSING) {
            return "正在暂停";
        }
        if (phase == MonitorState.Phase.PAUSED) {
            return "继续监控";
        }
        if (phase == MonitorState.Phase.ALARM) {
            return "停止报警";
        }
        return "暂停监控";
    }

    private static Intent command(Context context, String action) {
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(action);
        return intent;
    }

    public static void notify(Context context, String title, String content, boolean isAlarming) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(context, title, content, isAlarming));
        }
    }

    public static void cancelAll(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancelAll();
        }
    }
}
