package com.sgggzg.qmzg.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志入口：内存环形缓冲（给界面显示）+ 可选文件日志（便于离线排查）。
 */
public final class AppLog {

    private static final String TAG = "AppLog";
    private static final int MAX_LINES = 500;
    private static final long MAX_FILE_BYTES = 1024 * 1024L;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private static Context appContext;
    private static int appendCount;

    private AppLog() {
    }

    public static void init(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    public static synchronized void append(String message) {
        if (message == null || !RuntimeState.isLogEnabled()) {
            return;
        }
        String line = TIME.format(new Date()) + " " + message;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
        writeFile(line);
    }

    public static synchronized String getText() {
        StringBuilder sb = new StringBuilder();
        for (String line : LINES) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    private static void writeFile(String line) {
        if (!RuntimeState.isLogEnabled() || appContext == null) {
            return;
        }
        try {
            File dir = appContext.getExternalFilesDir("ocr");
            if (dir == null) {
                dir = new File(appContext.getFilesDir(), "ocr");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File file = new File(dir, "monitor.log");
            if (file.length() > MAX_FILE_BYTES) {
                File old = new File(dir, "monitor.log.old");
                if (old.exists()) {
                    old.delete();
                }
                file.renameTo(old);
            }
            FileOutputStream fos = new FileOutputStream(file, true);
            try {
                fos.write((line + "\n").getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            appendCount++;
        } catch (Throwable t) {
            // 日志写入失败绝不能影响主流程
            if (appendCount % 200 == 0) {
                Log.w(TAG, "write log failed", t);
            }
        }
    }
}
