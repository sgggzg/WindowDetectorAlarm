package com.sgggzg.qmzg.ocr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次 OCR 的完整结果。 */
public final class OcrResult {

    private static final OcrResult EMPTY = new OcrResult(Collections.<OcrLine>emptyList());

    private final List<OcrLine> lines;

    public OcrResult(List<OcrLine> lines) {
        this.lines = lines == null ? new ArrayList<OcrLine>() : lines;
    }

    public static OcrResult empty() {
        return EMPTY;
    }

    public List<OcrLine> getLines() {
        return lines;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** 按行拼接的纯文本（兼容旧逻辑和整框模式）。 */
    public String rawText() {
        StringBuilder sb = new StringBuilder();
        for (OcrLine line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line.text);
        }
        return sb.toString();
    }

    /** 所有行的平均置信度。 */
    public float averageConfidence() {
        if (lines.isEmpty()) {
            return 0f;
        }
        float sum = 0f;
        for (OcrLine line : lines) {
            sum += line.confidence;
        }
        return sum / lines.size();
    }
}
