package com.sgggzg.qmzg.data;

/** 识别调度方式：固定间隔 / 实时推流帧差。 */
public enum RecognitionMode {
    FIXED_INTERVAL("fixed_interval"),
    STREAM("stream");

    private final String key;

    RecognitionMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static RecognitionMode fromKey(String key, RecognitionMode fallback) {
        if (key != null) {
            for (RecognitionMode mode : values()) {
                if (mode.key.equals(key)) {
                    return mode;
                }
            }
        }
        return fallback;
    }
}
