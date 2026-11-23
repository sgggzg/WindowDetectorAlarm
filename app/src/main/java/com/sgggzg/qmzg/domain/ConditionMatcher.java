package com.sgggzg.qmzg.domain;

import android.graphics.Rect;

import com.sgggzg.qmzg.data.AppConfig;
import com.sgggzg.qmzg.data.ConditionGroup;
import com.sgggzg.qmzg.data.MatchMode;
import com.sgggzg.qmzg.ocr.OcrChar;
import com.sgggzg.qmzg.ocr.OcrLine;
import com.sgggzg.qmzg.ocr.OcrResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 条件匹配器。
 *
 * <p>三级单位：整框 / 单句 / 单行。匹配分两阶段：</p>
 * <ol>
 *   <li>精确阶段：任意单位、任意条件组完全命中就立即报警；</li>
 *   <li>模糊阶段：所有单位精确扫描完后，才检查允许错字数的候选，
 *       并且只对不一致的字符做概率复核。</li>
 * </ol>
 *
 * <p>剔除词按同一单位生效：整框/单句/单行命中剔除词，该单位直接跳过。</p>
 */
public final class ConditionMatcher {

    public interface LogCallback {
        void onLog(String message);
    }

    /** 会直接分割句子的标点；_-% 等不算。 */
    private static final String HARD_PUNCTUATION = "，。？！；,!?;";

    private ConditionMatcher() {
    }

    public static MatchResult match(OcrResult result, AppConfig config, LogCallback log) {
        if (result == null || result.isEmpty() || config == null) {
            return MatchResult.none();
        }
        List<ConditionGroup> groups = config.conditionGroups;
        if (groups == null || groups.isEmpty()) {
            return MatchResult.none();
        }

        List<Unit> units = buildUnits(result, config.matchMode);
        if (units.isEmpty()) {
            return MatchResult.none();
        }

        // 剔除词按单位生效，先过滤一遍，避免精确/模糊阶段重复判断
        List<Unit> active = new ArrayList<>();
        for (Unit unit : units) {
            if (unit.text.isEmpty()) {
                continue;
            }
            String excluded = findExclude(unit.text, config.excludeGroups);
            if (excluded != null) {
                log(log, "命中剔除词 [" + excluded + "]，跳过" + unitName(config.matchMode) + ": "
                        + shorten(unit.text));
                continue;
            }
            active.add(unit);
        }
        if (active.isEmpty()) {
            return MatchResult.none();
        }

        // 第一阶段：完全命中，发现即报警
        for (Unit unit : active) {
            for (int gi = 0; gi < groups.size(); gi++) {
                ConditionGroup group = groups.get(gi);
                if (group == null || group.keywords.isEmpty()) {
                    continue;
                }
                if (groupExactMatch(unit.text, group)) {
                    log(log, unitName(config.matchMode) + "精确命中条件组 " + (gi + 1));
                    return MatchResult.of(unit.rawText, gi);
                }
            }
        }

        // 第二阶段：所有单位精确扫描完后，才检查模糊候选
        for (Unit unit : active) {
            for (int gi = 0; gi < groups.size(); gi++) {
                ConditionGroup group = groups.get(gi);
                if (group == null || group.keywords.isEmpty()) {
                    continue;
                }
                FuzzyResult fuzzy = groupFuzzyMatch(unit, group, config, log);
                if (fuzzy != null) {
                    log(log, unitName(config.matchMode) + "模糊命中条件组 " + (gi + 1)
                            + "（错 " + fuzzy.totalMismatches + " 字）");
                    return MatchResult.of(unit.rawText, gi);
                }
            }
        }
        return MatchResult.none();
    }

    // ==================== 单位构造 ====================

    private static List<Unit> buildUnits(OcrResult result, MatchMode mode) {
        List<OcrLine> lines = new ArrayList<>(result.getLines());
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

        if (mode == MatchMode.WHOLE_BOX) {
            List<OcrChar> all = new ArrayList<>();
            StringBuilder fallback = new StringBuilder();
            for (OcrLine line : lines) {
                if (!line.getChars().isEmpty()) {
                    all.addAll(line.getChars());
                } else {
                    fallback.append(line.text);
                }
            }
            Unit unit = all.isEmpty()
                    ? textUnit(fallback.toString(), null)
                    : makeUnit(all);
            List<Unit> units = new ArrayList<>();
            if (unit != null) {
                units.add(unit);
            }
            return units;
        }

        if (mode == MatchMode.LINE) {
            List<Unit> units = new ArrayList<>();
            for (OcrLine line : lines) {
                Unit unit = line.getChars().isEmpty()
                        ? textUnit(line.text, line.bounds)
                        : makeUnit(line.getChars());
                if (unit != null && !unit.text.isEmpty()) {
                    units.add(unit);
                }
            }
            return units;
        }

        // SENTENCE：先按标点切成片段，再用版面几何合并跨行片段
        List<Fragment> fragments = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            if (line.getChars().isEmpty()) {
                // 没有逐字框时无法做字级几何合并，退化为“每行一个单位”
                List<Unit> fallback = new ArrayList<>();
                for (OcrLine textLine : lines) {
                    Unit unit = textUnit(textLine.text, textLine.bounds);
                    if (unit != null) {
                        fallback.add(unit);
                    }
                }
                return fallback;
            }
            fragments.addAll(splitFragments(line.getChars(), i, line.bounds));
        }
        return mergeFragments(fragments);
    }

    private static List<Fragment> splitFragments(List<OcrChar> chars, int lineIndex, Rect lineBounds) {
        List<Fragment> fragments = new ArrayList<>();
        List<OcrChar> current = new ArrayList<>();
        for (OcrChar ch : chars) {
            if (ch == null) {
                continue;
            }
            current.add(ch);
            String normalized = TextNormalizer.normalize(ch.text);
            if (isHardPunctuation(normalized)) {
                fragments.add(new Fragment(new ArrayList<>(current), lineIndex, lineBounds));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            fragments.add(new Fragment(current, lineIndex, lineBounds));
        }
        return fragments;
    }

    private static List<Unit> mergeFragments(List<Fragment> fragments) {
        List<Unit> units = new ArrayList<>();
        Unit current = null;
        for (Fragment fragment : fragments) {
            if (fragment.chars.isEmpty()) {
                continue;
            }
            if (current == null) {
                current = makeUnit(fragment.chars);
                if (current != null) {
                    current.lastLineIndex = fragment.lineIndex;
                    current.lastLineBounds = fragment.lineBounds;
                }
                continue;
            }

            boolean sameLine = current.lastLineIndex == fragment.lineIndex;
            boolean sentenceEnded = endsWithHardPunctuation(current.text);
            if (sameLine || sentenceEnded || !canMerge(current, fragment)) {
                units.add(current);
                current = makeUnit(fragment.chars);
                if (current != null) {
                    current.lastLineIndex = fragment.lineIndex;
                    current.lastLineBounds = fragment.lineBounds;
                }
            } else {
                List<OcrChar> combined = new ArrayList<>(current.chars);
                combined.addAll(fragment.chars);
                Unit merged = makeUnit(combined);
                if (merged != null) {
                    merged.lastLineIndex = fragment.lineIndex;
                    merged.lastLineBounds = fragment.lineBounds;
                    current = merged;
                }
            }
        }
        if (current != null) {
            units.add(current);
        }
        return units;
    }

    private static boolean canMerge(Unit current, Fragment next) {
        Rect prev = current.lastLineBounds;
        Rect nb = next.lineBounds;
        if (prev == null || nb == null) {
            return false;
        }
        int prevHeight = prev.height();
        int nextHeight = nb.height();
        if (prevHeight < 6 || nextHeight < 6) {
            return false;
        }

        int gap = nb.top - prev.bottom;
        if (gap < -0.40f * prevHeight || gap > 0.75f * prevHeight) {
            return false;
        }

        int leftTolerance = Math.max(8, Math.round(Math.min(prevHeight, nextHeight) * 0.55f));
        if (Math.abs(nb.left - prev.left) > leftTolerance) {
            return false;
        }

        float currentCharHeight = averageCharHeight(current.chars);
        float nextCharHeight = averageCharHeight(next.chars);
        float currentCharWidth = averageCharWidth(current.chars);
        float nextCharWidth = averageCharWidth(next.chars);
        if (currentCharHeight > 0 && nextCharHeight > 0) {
            float ratio = currentCharHeight / nextCharHeight;
            if (ratio < 0.6f || ratio > 1.7f) {
                return false;
            }
        }
        if (currentCharWidth > 0 && nextCharWidth > 0) {
            float ratio = currentCharWidth / nextCharWidth;
            if (ratio < 0.55f || ratio > 1.8f) {
                return false;
            }
        }
        return true;
    }

    // ==================== 精确 / 模糊 ====================

    private static boolean groupExactMatch(String text, ConditionGroup group) {
        for (ConditionGroup.KeywordRule keyword : group.keywords) {
            String k = TextNormalizer.normalize(keyword.text);
            if (k.isEmpty()) {
                continue;
            }
            int required = Math.max(1, keyword.requiredCount);
            if (countExact(text, k) < required) {
                return false;
            }
        }
        return true;
    }

    private static FuzzyResult groupFuzzyMatch(Unit unit, ConditionGroup group,
                                               AppConfig config, LogCallback log) {
        if (unit.chars.isEmpty()) {
            return null;
        }
        int total = 0;
        boolean fuzzyUsed = false;
        for (ConditionGroup.KeywordRule keyword : group.keywords) {
            String k = TextNormalizer.normalize(keyword.text);
            if (k.isEmpty()) {
                continue;
            }
            int required = Math.max(1, keyword.requiredCount);
            CountResult result = countMatches(unit, k, required, keyword.maxFuzzy, config);
            if (result.count < required) {
                log(log, "关键词出现次数不足或模糊候选不可用: [" + k + "] "
                        + result.count + "/" + required);
                return null;
            }
            total += result.mismatches;
            if (result.mismatches > 0) {
                fuzzyUsed = true;
            }
        }
        if (!fuzzyUsed) {
            return null;
        }
        if (group.maxFuzzyTotal >= 0 && total > group.maxFuzzyTotal) {
            return null;
        }
        return new FuzzyResult(total);
    }

    private static int countExact(String text, String keyword) {
        if (keyword.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from <= text.length() - keyword.length()) {
            int index = text.indexOf(keyword, from);
            if (index < 0) {
                break;
            }
            count++;
            from = index + keyword.length();
        }
        return count;
    }

    /**
     * 在同一个匹配单位内统计关键词出现次数。
     * 先精确计数；不足时才扫描允许错字的窗口，并逐个做概率复核。
     */
    private static CountResult countMatches(Unit unit, String keyword, int required,
                                            int maxFuzzy, AppConfig config) {
        int exact = countExact(unit.text, keyword);
        if (exact >= required) {
            return new CountResult(required, 0);
        }
        if (maxFuzzy <= 0) {
            return new CountResult(exact, 0);
        }

        int klen = keyword.length();
        if (klen == 0 || unit.text.length() < klen) {
            return new CountResult(exact, 0);
        }
        maxFuzzy = Math.min(maxFuzzy, klen);
        int count = 0;
        int totalMismatches = 0;
        int start = 0;
        while (start + klen <= unit.text.length() && count < required) {
            int mismatches = 0;
            List<Mismatch> details = new ArrayList<>();
            boolean tooMany = false;
            for (int i = 0; i < klen; i++) {
                char expected = keyword.charAt(i);
                char actual = unit.text.charAt(start + i);
                if (expected != actual) {
                    mismatches++;
                    if (mismatches > maxFuzzy) {
                        tooMany = true;
                        break;
                    }
                    int cleanIndex = start + i;
                    if (cleanIndex < unit.map.length) {
                        details.add(new Mismatch(unit.map[cleanIndex], expected));
                    }
                }
            }
            if (tooMany) {
                start++;
                continue;
            }
            if (mismatches > 0 && !probabilitiesOk(unit, details, config)) {
                start++;
                continue;
            }
            count++;
            totalMismatches += mismatches;
            start += klen;
        }
        return new CountResult(count, totalMismatches);
    }

    private static boolean probabilitiesOk(Unit unit, List<Mismatch> mismatches, AppConfig config) {
        for (Mismatch mismatch : mismatches) {
            if (mismatch.charIndex < 0 || mismatch.charIndex >= unit.chars.size()) {
                return false;
            }
            OcrChar ocrChar = unit.chars.get(mismatch.charIndex);
            String expected = TextNormalizer.normalize(String.valueOf(mismatch.expected));
            float expectedProb = ocrChar.probabilityOf(expected);
            float topProb = ocrChar.topProbability();
            if (expectedProb < config.minCharProbability) {
                return false;
            }
            if (topProb <= 0f || expectedProb / topProb < config.minCharRatio) {
                return false;
            }
        }
        return true;
    }

    // ==================== Unit ====================

    private static Unit makeUnit(List<OcrChar> chars) {
        if (chars == null || chars.isEmpty()) {
            return null;
        }
        Unit unit = new Unit();
        unit.chars = chars;
        StringBuilder text = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        Rect bounds = null;
        for (int i = 0; i < chars.size(); i++) {
            OcrChar ch = chars.get(i);
            if (ch == null) {
                continue;
            }
            raw.append(ch.text);
            String normalized = TextNormalizer.normalize(ch.text);
            if (normalized.isEmpty()) {
                continue;
            }
            for (int j = 0; j < normalized.length(); j++) {
                text.append(normalized.charAt(j));
                map.add(i);
            }
            if (ch.bounds != null) {
                if (bounds == null) {
                    bounds = new Rect(ch.bounds);
                } else {
                    bounds.union(ch.bounds);
                }
            }
        }
        unit.text = text.toString();
        unit.rawText = raw.toString();
        unit.map = new int[map.size()];
        for (int i = 0; i < map.size(); i++) {
            unit.map[i] = map.get(i);
        }
        unit.bounds = bounds;
        return unit.text.isEmpty() ? null : unit;
    }

    private static Unit textUnit(String text, Rect bounds) {
        if (text == null) {
            return null;
        }
        Unit unit = new Unit();
        unit.text = TextNormalizer.normalize(text);
        unit.rawText = text;
        unit.map = new int[0];
        unit.chars = new ArrayList<>();
        unit.bounds = bounds;
        return unit.text.isEmpty() ? null : unit;
    }

    // ==================== 工具 ====================

    private static String findExclude(String text, List<ConditionGroup> groups) {
        if (groups == null) {
            return null;
        }
        for (ConditionGroup group : groups) {
            if (group == null || group.keywords.isEmpty()) {
                continue;
            }
            boolean matched = true;
            StringBuilder summary = new StringBuilder();
            for (ConditionGroup.KeywordRule keyword : group.keywords) {
                String word = TextNormalizer.normalize(keyword.text);
                if (word.isEmpty()) {
                    continue;
                }
                if (!text.contains(word)) {
                    matched = false;
                    break;
                }
                if (summary.length() > 0) {
                    summary.append(" + ");
                }
                summary.append(keyword.text);
            }
            if (matched && summary.length() > 0) {
                return summary.toString();
            }
        }
        return null;
    }

    private static boolean isHardPunctuation(String text) {
        if (text == null || text.length() != 1) {
            return false;
        }
        return HARD_PUNCTUATION.indexOf(text.charAt(0)) >= 0;
    }

    private static boolean endsWithHardPunctuation(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return isHardPunctuation(String.valueOf(text.charAt(text.length() - 1)));
    }

    private static List<String> splitTextByPunctuation(String text) {
        List<String> pieces = new ArrayList<>();
        if (text == null) {
            return pieces;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isHardPunctuation(String.valueOf(c))) {
                pieces.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private static float averageCharHeight(List<OcrChar> chars) {
        float sum = 0f;
        int count = 0;
        for (OcrChar ch : chars) {
            if (ch != null && ch.bounds != null && ch.bounds.height() > 0) {
                sum += ch.bounds.height();
                count++;
            }
        }
        return count == 0 ? 0f : sum / count;
    }

    private static float averageCharWidth(List<OcrChar> chars) {
        float sum = 0f;
        int count = 0;
        for (OcrChar ch : chars) {
            if (ch != null && ch.bounds != null && ch.bounds.width() > 0) {
                sum += ch.bounds.width();
                count++;
            }
        }
        return count == 0 ? 0f : sum / count;
    }

    private static String unitName(MatchMode mode) {
        if (mode == MatchMode.WHOLE_BOX) {
            return "整框";
        }
        if (mode == MatchMode.SENTENCE) {
            return "单句";
        }
        return "单行";
    }

    private static String shorten(String text) {
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 80) + "...";
    }

    private static void log(LogCallback log, String message) {
        if (log != null) {
            log.onLog(message);
        }
    }

    // ==================== 内部结构 ====================

    private static final class Unit {
        String text;
        String rawText;
        List<OcrChar> chars;
        int[] map;
        Rect bounds;
        int lastLineIndex = -1;
        Rect lastLineBounds;
    }

    private static final class Fragment {
        final List<OcrChar> chars;
        final int lineIndex;
        final Rect lineBounds;

        Fragment(List<OcrChar> chars, int lineIndex, Rect lineBounds) {
            this.chars = chars;
            this.lineIndex = lineIndex;
            this.lineBounds = lineBounds;
        }
    }

    private static final class Mismatch {
        final int charIndex;
        final char expected;

        Mismatch(int charIndex, char expected) {
            this.charIndex = charIndex;
            this.expected = expected;
        }
    }

    private static final class CountResult {
        final int count;
        final int mismatches;

        CountResult(int count, int mismatches) {
            this.count = count;
            this.mismatches = mismatches;
        }
    }

    private static final class FuzzyResult {
        final int totalMismatches;

        FuzzyResult(int totalMismatches) {
            this.totalMismatches = totalMismatches;
        }
    }
}
