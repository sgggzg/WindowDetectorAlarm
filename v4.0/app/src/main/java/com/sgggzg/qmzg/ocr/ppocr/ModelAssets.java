package com.sgggzg.qmzg.ocr.ppocr;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 把 assets 里的 ONNX 模型释放到 filesDir（ONNX Runtime 需要真实文件路径）。 */
final class ModelAssets {

    private ModelAssets() {
    }

    static File ensure(Context context, String assetPath, String fileName, long minBytes) throws IOException {
        File dir = new File(context.getFilesDir(), "ocr");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建模型目录: " + dir);
        }
        File target = new File(dir, fileName);
        if (target.exists() && target.length() >= minBytes) {
            return target;
        }

        File tmp = new File(dir, fileName + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = context.getAssets().open(assetPath);
            out = new FileOutputStream(tmp);
            byte[] buffer = new byte[64 * 1024];
            int len;
            long total = 0;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                total += len;
            }
            out.flush();
            if (total < minBytes) {
                throw new IOException("模型文件不完整: " + assetPath + " (" + total + " bytes)");
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }

        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖旧模型: " + target);
        }
        if (!tmp.renameTo(target)) {
            // 某些文件系统不支持 rename 覆盖，退化为复制
            copy(tmp, target);
            tmp.delete();
        }
        return target;
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new java.io.FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buffer = new byte[64 * 1024];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
