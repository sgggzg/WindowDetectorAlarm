package com.sgggzg.qmzg.ocr.ppocr;

import android.graphics.Bitmap;

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
 * PP-OCRv4 文本检测（DB）。
 *
 * <p>输出是文本区域的概率图，这里用连通域 + 外扩的方式得到矩形框。
 * 游戏界面文字基本都是水平排列，所以不做旋转变换，省掉 OpenCV 依赖。</p>
 */
final class Detector implements AutoCloseable {

    private static final int MAX_SIDE = 960;
    private static final float BIN_THRESHOLD = 0.3f;
    private static final float BOX_THRESHOLD = 0.45f;
    private static final float UNCLIP_RATIO = 1.6f;
    private static final int MIN_AREA = 5;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;

    Detector(OrtEnvironment env, String modelPath, int threads) throws OrtException {
        this.env = env;
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, threads));
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath, options);
        this.inputName = session.getInputNames().iterator().next();
        this.outputName = session.getOutputNames().iterator().next();
    }

    List<Box> detect(Bitmap source) throws OrtException {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        if (srcW < 16 || srcH < 16) {
            return Collections.emptyList();
        }

        float scale = Math.min(1f, MAX_SIDE / (float) Math.max(srcW, srcH));
        int inputW = Math.max(32, Math.round(srcW * scale / 32f) * 32);
        int inputH = Math.max(32, Math.round(srcH * scale / 32f) * 32);

        Bitmap resized = source;
        if (inputW != srcW || inputH != srcH) {
            resized = Bitmap.createScaledBitmap(source, inputW, inputH, true);
        }

        float[] input = new float[3 * inputW * inputH];
        try {
            int[] pixels = new int[inputW * inputH];
            resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH);
            int area = inputW * inputH;
            for (int i = 0; i < area; i++) {
                int p = pixels[i];
                float b = ((p & 0xFF) / 255f - 0.485f) / 0.229f;
                float g = (((p >> 8) & 0xFF) / 255f - 0.456f) / 0.224f;
                float r = (((p >> 16) & 0xFF) / 255f - 0.406f) / 0.225f;
                input[i] = b;
                input[area + i] = g;
                input[2 * area + i] = r;
            }
        } finally {
            if (resized != source) {
                resized.recycle();
            }
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input),
                new long[]{1, 3, inputH, inputW})) {
            java.util.Map<String, OnnxTensor> feeds = Collections.singletonMap(inputName, tensor);
            try (OrtSession.Result result = session.run(feeds)) {
                OnnxTensor output = (OnnxTensor) result.get(0);
                TensorInfo info = (TensorInfo) output.getInfo();
                long[] shape = info.getShape();
                int outH = (int) shape[shape.length - 2];
                int outW = (int) shape[shape.length - 1];
                float[] prob = new float[outH * outW];
                FloatBuffer buffer = output.getFloatBuffer();
                buffer.rewind();
                int count = Math.min(prob.length, buffer.remaining());
                buffer.get(prob, 0, count);
                return postProcess(prob, outH, outW, srcW, srcH);
            }
        }
    }

    private List<Box> postProcess(float[] prob, int outH, int outW, int srcW, int srcH) {
        int total = outH * outW;
        boolean[] visited = new boolean[total];
        int[] queue = new int[total];
        float scaleX = srcW / (float) outW;
        float scaleY = srcH / (float) outH;

        List<Box> raw = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            if (visited[index] || prob[index] <= BIN_THRESHOLD) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = index;
            visited[index] = true;
            int minX = outW;
            int maxX = 0;
            int minY = outH;
            int maxY = 0;
            int area = 0;
            float scoreSum = 0f;

            while (head < tail) {
                int current = queue[head++];
                int y = current / outW;
                int x = current % outW;
                area++;
                scoreSum += prob[current];
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                if (x > 0) {
                    int n = current - 1;
                    if (!visited[n] && prob[n] > BIN_THRESHOLD) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
                if (x + 1 < outW) {
                    int n = current + 1;
                    if (!visited[n] && prob[n] > BIN_THRESHOLD) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
                if (y > 0) {
                    int n = current - outW;
                    if (!visited[n] && prob[n] > BIN_THRESHOLD) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
                if (y + 1 < outH) {
                    int n = current + outW;
                    if (!visited[n] && prob[n] > BIN_THRESHOLD) {
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
            }

            if (area < MIN_AREA) {
                continue;
            }
            float score = scoreSum / area;
            if (score < BOX_THRESHOLD) {
                continue;
            }
            int boxW = maxX - minX + 1;
            int boxH = maxY - minY + 1;
            float distance = area * UNCLIP_RATIO / (2f * (boxW + boxH));
            float x0 = (minX - distance) * scaleX;
            float y0 = (minY - distance) * scaleY;
            float x1 = (maxX + 1 + distance) * scaleX;
            float y1 = (maxY + 1 + distance) * scaleY;

            int ix0 = clamp((int) Math.floor(x0), 0, srcW - 1);
            int iy0 = clamp((int) Math.floor(y0), 0, srcH - 1);
            int ix1 = clamp((int) Math.ceil(x1), 0, srcW);
            int iy1 = clamp((int) Math.ceil(y1), 0, srcH);
            if (ix1 - ix0 < 8 || iy1 - iy0 < 8) {
                continue;
            }
            raw.add(new Box(ix0, iy0, ix1, iy1, score));
        }
        return mergeSameLine(raw);
    }

    /** 同一行的多个碎片（例如被标点断开）合并成一个框。 */
    private List<Box> mergeSameLine(List<Box> boxes) {
        List<Box> merged = new ArrayList<>();
        for (Box box : boxes) {
            boolean placed = false;
            for (int i = 0; i < merged.size(); i++) {
                Box other = merged.get(i);
                int overlap = Math.min(box.y1, other.y1) - Math.max(box.y0, other.y0);
                int h1 = box.y1 - box.y0;
                int h2 = other.y1 - other.y0;
                int gap = Math.max(box.x0, other.x0) - Math.min(box.x1, other.x1);
                if (overlap > 0.5f * Math.max(h1, h2) && gap < Math.max(h1, h2) * 0.9f) {
                    merged.set(i, new Box(
                            Math.min(box.x0, other.x0),
                            Math.min(box.y0, other.y0),
                            Math.max(box.x1, other.x1),
                            Math.max(box.y1, other.y1),
                            Math.max(box.score, other.score)));
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                merged.add(box);
            }
        }
        Collections.sort(merged, new java.util.Comparator<Box>() {
            @Override
            public int compare(Box a, Box b) {
                if (a.y0 != b.y0) {
                    return a.y0 < b.y0 ? -1 : 1;
                }
                return a.x0 < b.x0 ? -1 : (a.x0 == b.x0 ? 0 : 1);
            }
        });
        return merged;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    static final class Box {
        final int x0;
        final int y0;
        final int x1;
        final int y1;
        final float score;

        Box(int x0, int y0, int x1, int y1, float score) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.score = score;
        }
    }
}
