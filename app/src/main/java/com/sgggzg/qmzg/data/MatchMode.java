package com.sgggzg.qmzg.data;

/** 检测单位：整框 / 单句 / 单行。 */
public enum MatchMode {
    WHOLE_BOX("whole_box"),
    SENTENCE("sentence"),
    LINE("line");

    private final String key;

    MatchMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static MatchMode fromKey(String key, MatchMode fallback) {
        if (key != null) {
            for (MatchMode mode : values()) {
                if (mode.key.equals(key)) {
                    return mode;
                }
            }
        }
        return fallback;
    }
}
