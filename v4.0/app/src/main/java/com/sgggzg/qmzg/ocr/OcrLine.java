package com.sgggzg.qmzg.ocr;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/** OCR 识别出的一行文字，带逐字符位置与概率候选。 */
public final class OcrLine {

    public final String text;
    public final float confidence;
    public final Rect bounds;
    private final List<OcrChar> chars;

    public OcrLine(String text, float confidence, Rect bounds) {
        this(text, confidence, bounds, new ArrayList<OcrChar>());
    }

    public OcrLine(String text, float confidence, Rect bounds, List<OcrChar> chars) {
        this.text = text == null ? "" : text;
        this.confidence = confidence;
        this.bounds = bounds;
        this.chars = chars == null ? new ArrayList<OcrChar>() : chars;
    }

    public List<OcrChar> getChars() {
        return chars;
    }
}
