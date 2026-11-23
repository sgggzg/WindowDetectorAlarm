package com.sgggzg.qmzg.domain;

import java.text.Normalizer;

/** 文本归一化：全角转半角、去空白和零宽字符。 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized;
        try {
            normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        } catch (Throwable t) {
            normalized = text;
        }
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == '\u3000'
                    || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF'
                    || Character.isWhitespace(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 是否包含汉字（用于判断关键词能否做模糊匹配）。 */
    public static boolean containsCjk(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') {
                return true;
            }
        }
        return false;
    }

    /** 行尾是否是句子结束符。 */
    public static boolean endsWithSentenceEnd(String text) {
        if (text == null) {
            return false;
        }
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '…' || c == '”' || c == '"';
        }
        return false;
    }
}
