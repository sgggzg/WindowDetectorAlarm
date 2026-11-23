package com.sgggzg.qmzg.ocr.ppocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.sgggzg.qmzg.ocr.OcrChar;
import com.sgggzg.qmzg.ocr.OcrEngine;
import com.sgggzg.qmzg.ocr.OcrLine;
import com.sgggzg.qmzg.ocr.OcrResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ai.onnxruntime.OrtEnvironment;

/**
 * PP-OCRv4（RapidOCR 同款模型）引擎。
 *
 * <p>只依赖 ONNX Runtime，不需要 OpenCV / NDK，纯 Java 实现前后处理，
 * 因此可以在 AIDE Pro 这类手机端构建环境里打包。</p>
 */
public final class PpOcrEngine implements OcrEngine {

    private static final String TAG = "PpOcrEngine";

    private static final long DET_MIN_BYTES = 4L * 1024 * 1024;
    private static final long REC_MIN_BYTES = 8L * 1024 * 1024;
    private static final long DICT_MIN_BYTES = 20L * 1024;

    private final Object lock = new Object();

    private volatile boolean ready;
    private volatile boolean released;
    private volatile float minConfidence = 0.45f;

    private Detector detector;
    private Recognizer recognizer;

    public PpOcrEngine(float minConfidence) {
        this.minConfidence = minConfidence;
    }

    public void setOptions(float minConfidence) {
        this.minConfidence = minConfidence;
    }

    @Override
    public void init(final Context context, final InitCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File detModel = ModelAssets.ensure(context, "ocr/ppocr_det.onnx",
                            "ppocr_det.onnx", DET_MIN_BYTES);
                    File recModel = ModelAssets.ensure(context, "ocr/ppocr_rec.onnx",
                            "ppocr_rec.onnx", REC_MIN_BYTES);
                    File dict = ModelAssets.ensure(context, "ocr/ppocr_keys_v1.txt",
                            "ppocr_keys_v1.txt", DICT_MIN_BYTES);

                    int threads = Math.max(1, Math.min(4,
                            Runtime.getRuntime().availableProcessors() / 2));
                    OrtEnvironment env = OrtEnvironment.getEnvironment();

                    Detector newDetector = new Detector(env, detModel.getAbsolutePath(), threads);
                    Recognizer newRecognizer;
                    try {
                        newRecognizer = new Recognizer(env, recModel.getAbsolutePath(),
                                dict.getAbsolutePath(), threads);
                    } catch (Exception e) {
                        newDetector.close();
                        throw e;
                    }

                    synchronized (lock) {
                        if (released) {
                            newDetector.close();
                            newRecognizer.close();
                            return;
                        }
                        detector = newDetector;
                        recognizer = newRecognizer;
                        ready = true;
                    }
                    Log.i(TAG, "PP-OCR 初始化完成，线程数=" + threads);
                    if (callback != null) {
                        callback.onSuccess();
                    }
                } catch (Throwable t) {
                    ready = false;
                    Log.e(TAG, "PP-OCR 初始化失败", t);
                    if (callback != null) {
                        callback.onFailure(t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            }
        }, "ppocr-init").start();
    }

    @Override
    public OcrResult recognize(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !ready) {
            return OcrResult.empty();
        }
        synchronized (lock) {
            if (!ready || detector == null || recognizer == null) {
                return OcrResult.empty();
            }
            try {
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                List<Detector.Box> boxes = detector.detect(bitmap);

                List<OcrLine> lines = new ArrayList<>();
                for (Detector.Box box : boxes) {
                    int x0 = Math.max(0, box.x0 - 2);
                    int y0 = Math.max(0, box.y0 - 2);
                    int x1 = Math.min(width, box.x1 + 2);
                    int y1 = Math.min(height, box.y1 + 2);
                    if (x1 - x0 < 8 || y1 - y0 < 8) {
                        continue;
                    }
                    Bitmap crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0);
                    try {
                        OcrLine line = recognizer.recognize(crop, minConfidence);
                        if (line != null) {
                            List<OcrChar> shiftedChars = new ArrayList<>();
                            for (OcrChar ch : line.getChars()) {
                                Rect charRect = ch.bounds == null ? null : new Rect(ch.bounds);
                                if (charRect != null) {
                                    charRect.offset(x0, y0);
                                }
                                shiftedChars.add(new OcrChar(ch.text, ch.confidence,
                                        charRect, ch.candidates, ch.candidateProbs));
                            }
                            lines.add(new OcrLine(line.text, line.confidence,
                                    new Rect(x0, y0, x1, y1), shiftedChars));
                        }
                    } finally {
                        if (crop != bitmap) {
                            crop.recycle();
                        }
                    }
                }

                Collections.sort(lines, new Comparator<OcrLine>() {
                    @Override
                    public int compare(OcrLine a, OcrLine b) {
                        int ay = a.bounds == null ? 0 : a.bounds.top;
                        int by = b.bounds == null ? 0 : b.bounds.top;
                        if (ay != by) {
                            return ay < by ? -1 : 1;
                        }
                        int ax = a.bounds == null ? 0 : a.bounds.left;
                        int bx = b.bounds == null ? 0 : b.bounds.left;
                        return ax < bx ? -1 : (ax == bx ? 0 : 1);
                    }
                });
                return new OcrResult(lines);
            } catch (Throwable t) {
                Log.e(TAG, "识别失败", t);
                return OcrResult.empty();
            }
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            released = true;
            ready = false;
            if (detector != null) {
                detector.close();
                detector = null;
            }
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String displayName() {
        return "PP-OCRv4 (ONNX)";
    }
}
