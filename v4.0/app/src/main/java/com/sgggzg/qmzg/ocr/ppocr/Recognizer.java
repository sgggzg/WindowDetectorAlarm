package com.sgggzg.qmzg.ocr.ppocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.sgggzg.qmzg.ocr.OcrChar;
import com.sgggzg.qmzg.ocr.OcrLine;

import java.io.File;
import java.io.FileInputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * PP-OCRv4 文本识别（CTC）。
 *
 * <p>输入高度固定 48，宽度按比例缩放，输出每个时间步的字符概率，
 * 用贪心 CTC 解码成文字。</p>
 */
final class Recognizer implements AutoCloseable {

    private static final int REC_HEIGHT = 48;
    private static final int MIN_WIDTH = 16;
    private static final int MAX_WIDTH = 640;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String[] dict;

    private float[] outBuffer;

    Recognizer(OrtEnvironment env, String modelPath, String dictPath, int threads) throws Exception {
        this.env = env;
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, threads));
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath, options);
        this.inputName = session.getInputNames().iterator().next();
        this.dict = loadDictionary(new File(dictPath));
    }

    private static String[] loadDictionary(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        byte[] bytes;
        try {
            long length = file.length();
            bytes = new byte[(int) length];
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        } finally {
            in.close();
        }
        String content = new String(bytes, "UTF-8");
        String[] lines = content.split("\n", -1);
        List<String> chars = new ArrayList<>();
        chars.add(""); // CTC blank 放在 0
        for (String line : lines) {
            String value = line;
            if (value.endsWith("\r")) {
                value = value.substring(0, value.length() - 1);
            }
            // 文件末尾可能带一个换行，跳过最后空行
            if (value.isEmpty() && chars.size() > 1) {
                continue;
            }
            chars.add(value);
        }
        chars.add(" "); // PaddleOCR 默认 use_space_char
        return chars.toArray(new String[0]);
    }

    /** 识别一张已经裁好的文本行图片；低于阈值返回 null。 */
    OcrLine recognize(Bitmap source, float minConfidence) throws OrtException {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        if (srcW < 8 || srcH < 8) {
            return null;
        }
        int width = Math.round(srcW * REC_HEIGHT / (float) srcH);
        width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width));

        Bitmap scaled = Bitmap.createScaledBitmap(source, width, REC_HEIGHT, true);
        float[] input = new float[3 * REC_HEIGHT * width];
        try {
            int[] pixels = new int[width * REC_HEIGHT];
            scaled.getPixels(pixels, 0, width, 0, 0, width, REC_HEIGHT);
            int area = width * REC_HEIGHT;
            for (int i = 0; i < area; i++) {
                int p = pixels[i];
                input[i] = (p & 0xFF) / 127.5f - 1f;
                input[area + i] = ((p >> 8) & 0xFF) / 127.5f - 1f;
                input[2 * area + i] = ((p >> 16) & 0xFF) / 127.5f - 1f;
            }
        } finally {
            if (scaled != source) {
                scaled.recycle();
            }
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input),
                new long[]{1, 3, REC_HEIGHT, width})) {
            java.util.Map<String, OnnxTensor> feeds = Collections.singletonMap(inputName, tensor);
            try (OrtSession.Result result = session.run(feeds)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                TensorInfo info = (TensorInfo) output.getInfo();
                long[] shape = info.getShape();
                int steps = (int) shape[shape.length - 2];
                int classes = (int) shape[shape.length - 1];
                int needed = steps * classes;
                if (outBuffer == null || outBuffer.length < needed) {
                    outBuffer = new float[needed];
                }
                FloatBuffer buffer = output.getFloatBuffer();
                buffer.rewind();
                int count = Math.min(needed, buffer.remaining());
                buffer.get(outBuffer, 0, count);
                if (count < needed) {
                    return null;
                }
                return decode(outBuffer, steps, classes, minConfidence,
                        new Rect(0, 0, srcW, srcH));
            }
        }
    }

    private OcrLine decode(float[] data, int steps, int classes, float minConfidence, Rect bounds) {
        if (steps <= 0 || classes <= 0) {
            return null;
        }
        int lineWidth = bounds.width();
        int lineHeight = bounds.height();

        int[] topIdx = new int[TOP_K];
        float[] topProb = new float[TOP_K];

        List<OcrChar> chars = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        float sum = 0f;
        int kept = 0;

        int previous = -1;
        int charStart = -1;
        int charEnd = -1;
        int[] charTopIdx = null;
        float[] charTopProb = null;

        for (int t = 0; t < steps; t++) {
            fillTopK(data, t * classes, classes, topIdx, topProb);
            int best = topIdx[0];

            if (best == 0) {
                if (charStart >= 0) {
                    OcrChar ocrChar = buildChar(charTopIdx, charTopProb, charStart, charEnd,
                            steps, lineWidth, lineHeight);
                    if (ocrChar != null && !ocrChar.text.isEmpty()) {
                        chars.add(ocrChar);
                        text.append(ocrChar.text);
                        sum += ocrChar.confidence;
                        kept++;
                    }
                    charStart = -1;
                }
                previous = 0;
                continue;
            }

            if (best != previous) {
                if (charStart >= 0) {
                    OcrChar ocrChar = buildChar(charTopIdx, charTopProb, charStart, charEnd,
                            steps, lineWidth, lineHeight);
                    if (ocrChar != null && !ocrChar.text.isEmpty()) {
                        chars.add(ocrChar);
                        text.append(ocrChar.text);
                        sum += ocrChar.confidence;
                        kept++;
                    }
                }
                charStart = t;
                charEnd = t;
                charTopIdx = topIdx.clone();
                charTopProb = topProb.clone();
            } else {
                charEnd = t;
            }
            previous = best;
        }

        if (charStart >= 0) {
            OcrChar ocrChar = buildChar(charTopIdx, charTopProb, charStart, charEnd,
                    steps, lineWidth, lineHeight);
            if (ocrChar != null && !ocrChar.text.isEmpty()) {
                chars.add(ocrChar);
                text.append(ocrChar.text);
                sum += ocrChar.confidence;
                kept++;
            }
        }

        if (text.length() == 0 || kept == 0) {
            return null;
        }
        float confidence = sum / kept;
        if (confidence < minConfidence) {
            return null;
        }
        return new OcrLine(text.toString(), confidence, bounds, chars);
    }

    private static final int TOP_K = 5;

    private static void fillTopK(float[] data, int base, int classes,
                                 int[] topIdx, float[] topProb) {
        int limit = Math.min(TOP_K, classes);
        for (int i = 0; i < TOP_K; i++) {
            topIdx[i] = -1;
            topProb[i] = -1f;
        }
        for (int c = 0; c < classes; c++) {
            float value = data[base + c];
            if (value <= topProb[limit - 1]) {
                continue;
            }
            int pos = limit - 1;
            while (pos > 0 && value > topProb[pos - 1]) {
                topProb[pos] = topProb[pos - 1];
                topIdx[pos] = topIdx[pos - 1];
                pos--;
            }
            topProb[pos] = value;
            topIdx[pos] = c;
        }
    }

    private OcrChar buildChar(int[] topIdx, float[] topProb, int start, int end,
                              int steps, int lineWidth, int lineHeight) {
        if (topIdx == null || topProb == null || topIdx.length == 0 || topIdx[0] <= 0) {
            return null;
        }
        int count = Math.min(topIdx.length, topProb.length);
        String[] candidates = new String[count];
        float[] probs = new float[count];
        for (int i = 0; i < count; i++) {
            int index = topIdx[i];
            candidates[i] = (index >= 0 && index < dict.length) ? dict[index] : "";
            probs[i] = topProb[i] < 0f ? 0f : topProb[i];
        }
        int x0 = Math.round(start * lineWidth / (float) steps);
        int x1 = Math.round((end + 1) * lineWidth / (float) steps);
        if (x1 <= x0) {
            x1 = x0 + 1;
        }
        x0 = Math.max(0, Math.min(x0, lineWidth - 1));
        x1 = Math.max(x0 + 1, Math.min(x1, lineWidth));
        Rect bounds = new Rect(x0, 0, x1, lineHeight);
        String text = candidates[0] == null ? "" : candidates[0];
        if (text.isEmpty()) {
            return null;
        }
        return new OcrChar(text, probs[0], bounds, candidates, probs);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
