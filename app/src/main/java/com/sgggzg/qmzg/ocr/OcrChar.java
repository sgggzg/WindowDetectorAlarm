package com.sgggzg.qmzg.ocr;

import android.graphics.Rect;

import java.text.Normalizer;

/** 识别出的单个字符，带该时间步的 top-k 候选概率。 */
public final class OcrChar {

    public final String text;
    public final float confidence;
    public final Rect bounds;
    /** top-k 候选文本，index 0 是解码结果。 */
    public final String[] candidates;
    /** 与 candidates 对应的概率。 */
    public final float[] candidateProbs;

    public OcrChar(String text, float confidence, Rect bounds,
                   String[] candidates, float[] candidateProbs) {
        this.text = text == null ? "" : text;
        this.confidence = confidence;
        this.bounds = bounds;
        this.candidates = candidates == null ? new String[0] : candidates;
        this.candidateProbs = candidateProbs == null ? new float[0] : candidateProbs;
    }

    public float probabilityOf(String expected) {
        if (expected == null || expected.isEmpty()) {
            return 0f;
        }
        String target = normalize(expected);
        float best = 0f;
        int count = Math.min(candidates.length, candidateProbs.length);
        for (int i = 0; i < count; i++) {
            if (target.equals(normalize(candidates[i])) && candidateProbs[i] > best) {
                best = candidateProbs[i];
            }
        }
        return best;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        try {
            return Normalizer.normalize(text, Normalizer.Form.NFKC);
        } catch (Throwable t) {
            return text;
        }
    }

    public float topProbability() {
        return candidateProbs.length > 0 ? candidateProbs[0] : confidence;
    }
}
