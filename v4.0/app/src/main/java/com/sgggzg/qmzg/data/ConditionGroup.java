package com.sgggzg.qmzg.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个条件组：组内关键词 AND，组间 OR。
 *
 * <p>模糊额度分两级：</p>
 * <ul>
 *   <li>{@link #maxFuzzyTotal}：整个条件组允许的错字总数，-1 表示不限；</li>
 *   <li>{@link KeywordRule#maxFuzzy}：单个关键词允许的错字数，默认 0（不允许模糊）。</li>
 * </ul>
 */
public class ConditionGroup {

    public static final int UNLIMITED = -1;
    private static final int MAX_KEYWORD_FUZZY = 20;
    private static final int MAX_GROUP_FUZZY = 99;
    private static final int MAX_REQUIRED_COUNT = 99;

    public final List<KeywordRule> keywords = new ArrayList<>();
    /** -1 = 不限；否则是组内所有关键词错字数之和的上限。 */
    public int maxFuzzyTotal = UNLIMITED;

    public ConditionGroup() {
    }

    public static ConditionGroup create(String... words) {
        ConditionGroup group = new ConditionGroup();
        for (String word : words) {
            if (word != null && !word.trim().isEmpty()) {
                group.keywords.add(new KeywordRule(word.trim(), 0));
            }
        }
        return group;
    }

    public ConditionGroup copy() {
        ConditionGroup copy = new ConditionGroup();
        copy.maxFuzzyTotal = maxFuzzyTotal;
        for (KeywordRule keyword : keywords) {
            copy.keywords.add(keyword.copy());
        }
        return copy;
    }

    public void validate() {
        List<KeywordRule> cleaned = new ArrayList<>();
        for (KeywordRule keyword : keywords) {
            if (keyword == null || keyword.text == null) {
                continue;
            }
            String text = keyword.text.trim();
            if (text.isEmpty()) {
                continue;
            }
            boolean duplicate = false;
            for (KeywordRule existing : cleaned) {
                if (existing.text.equals(text)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                keyword.text = text;
                // 模糊字数不能超过关键词本身的字数
                keyword.maxFuzzy = clamp(keyword.maxFuzzy, 0, text.length());
                keyword.requiredCount = clamp(keyword.requiredCount, 1, MAX_REQUIRED_COUNT);
                cleaned.add(keyword);
            }
        }
        keywords.clear();
        keywords.addAll(cleaned);
        int totalKeywordLength = 0;
        for (KeywordRule keyword : keywords) {
            totalKeywordLength += keyword.text.length();
        }
        if (maxFuzzyTotal < UNLIMITED) {
            maxFuzzyTotal = UNLIMITED;
        }
        if (maxFuzzyTotal > MAX_GROUP_FUZZY) {
            maxFuzzyTotal = MAX_GROUP_FUZZY;
        }
        if (maxFuzzyTotal > totalKeywordLength) {
            maxFuzzyTotal = totalKeywordLength;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("maxFuzzyTotal", maxFuzzyTotal);
        JSONArray array = new JSONArray();
        for (KeywordRule keyword : keywords) {
            JSONObject item = new JSONObject();
            item.put("text", keyword.text);
            item.put("maxFuzzy", keyword.maxFuzzy);
            item.put("requiredCount", keyword.requiredCount);
            array.put(item);
        }
        o.put("keywords", array);
        return o;
    }

    public static ConditionGroup fromJson(JSONObject o) {
        ConditionGroup group = new ConditionGroup();
        if (o == null) {
            return group;
        }
        group.maxFuzzyTotal = o.optInt("maxFuzzyTotal", UNLIMITED);
        JSONArray array = o.optJSONArray("keywords");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object raw = array.opt(i);
                if (raw instanceof JSONObject) {
                    JSONObject item = (JSONObject) raw;
                    group.keywords.add(new KeywordRule(
                            item.optString("text", ""),
                            item.optInt("maxFuzzy", 0),
                            item.optInt("requiredCount", 1)));
                } else if (raw instanceof String) {
                    // 兼容更早的纯字符串关键词
                    group.keywords.add(new KeywordRule((String) raw, 0));
                }
            }
        }
        group.validate();
        return group;
    }

    public static ConditionGroup fromLegacyArray(JSONArray array) {
        ConditionGroup group = new ConditionGroup();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String word = array.optString(i, "");
                if (!word.isEmpty()) {
                    group.keywords.add(new KeywordRule(word, 0));
                }
            }
        }
        group.validate();
        return group;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    public static final class KeywordRule {
        public String text;
        public int maxFuzzy;
        /** 该关键词在同一个匹配单位内至少出现几次。 */
        public int requiredCount;

        public KeywordRule(String text, int maxFuzzy) {
            this(text, maxFuzzy, 1);
        }

        public KeywordRule(String text, int maxFuzzy, int requiredCount) {
            this.text = text;
            this.maxFuzzy = maxFuzzy;
            this.requiredCount = requiredCount < 1 ? 1 : requiredCount;
        }

        public KeywordRule copy() {
            return new KeywordRule(text, maxFuzzy, requiredCount);
        }
    }
}
