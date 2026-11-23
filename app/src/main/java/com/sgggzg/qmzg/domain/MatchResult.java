package com.sgggzg.qmzg.domain;

/** 匹配结果，带上命中的原文，方便报警窗和日志显示。 */
public final class MatchResult {

    public final boolean matched;
    public final String text;
    public final int groupIndex;

    private MatchResult(boolean matched, String text, int groupIndex) {
        this.matched = matched;
        this.text = text == null ? "" : text;
        this.groupIndex = groupIndex;
    }

    public static MatchResult none() {
        return new MatchResult(false, "", -1);
    }

    public static MatchResult of(String text, int groupIndex) {
        return new MatchResult(true, text, groupIndex);
    }
}
