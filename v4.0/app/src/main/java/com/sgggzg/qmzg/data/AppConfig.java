package com.sgggzg.qmzg.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一套“检测方案”：一块屏幕区域 + 一组识别/匹配参数。
 *
 * <p>识别单位由 {@link MatchMode} 决定：整框 / 单句 / 单行。</p>
 */
public class AppConfig {

    /** PP-OCR 引擎（默认，推荐）。 */
    public static final String ENGINE_PPOCR = "ppocr";

    public static final String DEFAULT_WHITELIST =
            "0123456789来自军团的成功坚守至正在尝试占领计时结束击飞了一名敌人，。！？-犁庭扫穴平定中原快来阻止他们已经达成占领条件";

    public String id = "default";
    public String name = "默认方案";

    // ===== 识别区域（相对屏幕的比例） =====
    public float top = 0.66f;
    public float bottom = 0.81f;
    public float left = 0f;
    public float right = 1f;

    /** 匹配单位：整框 / 单句 / 单行。 */
    public MatchMode matchMode = MatchMode.SENTENCE;

    /** 识别调度方式：固定间隔 / 实时推流帧差。 */
    public RecognitionMode recognitionMode = RecognitionMode.FIXED_INTERVAL;

    /** 条件组：组内 AND，组间 OR。 */
    public final List<ConditionGroup> conditionGroups = new ArrayList<>();
    /** 剔除条件组：组内 AND，组间 OR；命中则跳过该单位。 */
    public final List<ConditionGroup> excludeGroups = new ArrayList<>();

    // ===== 采样与匹配 =====
    public float intervalSec = 3f;
    public boolean skipNoChange = true;
    /** 默认 5 秒；已有方案保存的值不会被强制改。 */
    public int cooldownSec = 5;
    /** 推流模式 OCR 队列容量；满时拒绝新帧。 */
    public int queueCapacity = 10;

    /** 模糊匹配时，期望字符的绝对概率下限。 */
    public float minCharProbability = 0.20f;
    /** 模糊匹配时，期望字符概率 / 解码字符概率 的下限。 */
    public float minCharRatio = 0.45f;

    // ===== 报警方式 =====
    public boolean vibrate = true;
    public boolean sound = true;

    // ===== OCR 引擎 =====
    public String ocrEngine = ENGINE_PPOCR;
    public float minConfidence = 0.45f;

    // ===== 兼容字段（Tesseract 时代保留，PP-OCR 不需要） =====
    public boolean whitelistEnabled = true;
    public String whitelist = DEFAULT_WHITELIST;

    public static AppConfig createDefault(String id, String name) {
        AppConfig c = new AppConfig();
        c.id = id;
        c.name = name;
        c.conditionGroups.add(ConditionGroup.create("158", "正在尝试占领"));
        c.excludeGroups.add(ConditionGroup.create("击飞了一名敌人"));
        c.excludeGroups.add(ConditionGroup.create("成功坚守至占领计时结束"));
        c.excludeGroups.add(ConditionGroup.create("来自101"));
        return c;
    }

    /** 深拷贝，避免服务线程与界面线程互相修改同一个对象。 */
    public AppConfig copy() {
        AppConfig c = new AppConfig();
        c.id = id;
        c.name = name;
        c.top = top;
        c.bottom = bottom;
        c.left = left;
        c.right = right;
        c.matchMode = matchMode;
        c.recognitionMode = recognitionMode;
        for (ConditionGroup group : conditionGroups) {
            c.conditionGroups.add(group.copy());
        }
        for (ConditionGroup group : excludeGroups) {
            c.excludeGroups.add(group.copy());
        }
        c.intervalSec = intervalSec;
        c.skipNoChange = skipNoChange;
        c.cooldownSec = cooldownSec;
        c.queueCapacity = queueCapacity;
        c.minCharProbability = minCharProbability;
        c.minCharRatio = minCharRatio;
        c.vibrate = vibrate;
        c.sound = sound;
        c.ocrEngine = ocrEngine;
        c.minConfidence = minConfidence;
        c.whitelistEnabled = whitelistEnabled;
        c.whitelist = whitelist;
        return c;
    }

    /** 修正非法值，保证服务端拿到的一定是可用配置。 */
    public AppConfig validate() {
        if (id == null || id.trim().isEmpty()) {
            id = "default";
        }
        if (name == null || name.trim().isEmpty()) {
            name = "未命名方案";
        }
        top = clamp(top, 0f, 1f);
        bottom = clamp(bottom, 0f, 1f);
        left = clamp(left, 0f, 1f);
        right = clamp(right, 0f, 1f);
        if (bottom - top < 0.02f) {
            top = 0.66f;
            bottom = 0.81f;
        }
        if (right - left < 0.02f) {
            left = 0f;
            right = 1f;
        }
        intervalSec = clamp(intervalSec, 0.5f, 30f);
        minConfidence = clamp(minConfidence, 0f, 1f);
        minCharProbability = clamp(minCharProbability, 0f, 1f);
        minCharRatio = clamp(minCharRatio, 0f, 1f);
        if (cooldownSec < 0) {
            cooldownSec = 0;
        }
        if (cooldownSec > 3600) {
            cooldownSec = 3600;
        }
        if (matchMode == null) {
            matchMode = MatchMode.SENTENCE;
        }
        if (recognitionMode == null) {
            recognitionMode = RecognitionMode.FIXED_INTERVAL;
        }
        if (queueCapacity < 1) {
            queueCapacity = 1;
        }
        if (queueCapacity > 50) {
            queueCapacity = 50;
        }
        if (ocrEngine == null || ocrEngine.trim().isEmpty()) {
            ocrEngine = ENGINE_PPOCR;
        }
        if (whitelist == null) {
            whitelist = DEFAULT_WHITELIST;
        }
        sanitizeGroups();
        sanitizeExcludeGroups();
        return this;
    }

    private void sanitizeGroups() {
        List<ConditionGroup> cleaned = new ArrayList<>();
        for (ConditionGroup group : conditionGroups) {
            if (group == null) {
                continue;
            }
            group.validate();
            if (!group.keywords.isEmpty()) {
                cleaned.add(group);
            }
        }
        conditionGroups.clear();
        conditionGroups.addAll(cleaned);
    }

    private void sanitizeExcludeGroups() {
        List<ConditionGroup> cleaned = new ArrayList<>();
        for (ConditionGroup group : excludeGroups) {
            if (group == null) {
                continue;
            }
            group.validate();
            if (!group.keywords.isEmpty()) {
                cleaned.add(group);
            }
        }
        excludeGroups.clear();
        excludeGroups.addAll(cleaned);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("top", top);
        o.put("bottom", bottom);
        o.put("left", left);
        o.put("right", right);
        o.put("matchMode", matchMode.key());
        o.put("recognitionMode", recognitionMode.key());

        JSONArray groups = new JSONArray();
        for (ConditionGroup group : conditionGroups) {
            groups.put(group.toJson());
        }
        o.put("conditionGroups", groups);

        JSONArray excludeGroupsJson = new JSONArray();
        for (ConditionGroup group : excludeGroups) {
            excludeGroupsJson.put(group.toJson());
        }
        o.put("excludeGroups", excludeGroupsJson);

        o.put("intervalSec", intervalSec);
        o.put("skipNoChange", skipNoChange);
        o.put("cooldownSec", cooldownSec);
        o.put("queueCapacity", queueCapacity);
        o.put("minCharProbability", minCharProbability);
        o.put("minCharRatio", minCharRatio);
        o.put("vibrate", vibrate);
        o.put("sound", sound);
        o.put("ocrEngine", ocrEngine);
        o.put("minConfidence", minConfidence);
        o.put("whitelistEnabled", whitelistEnabled);
        o.put("whitelist", whitelist);
        return o;
    }

    public static AppConfig fromJson(JSONObject o) {
        AppConfig c = new AppConfig();
        if (o == null) {
            return c.validate();
        }
        c.id = o.optString("id", c.id);
        c.name = o.optString("name", c.name);
        c.top = (float) o.optDouble("top", c.top);
        c.bottom = (float) o.optDouble("bottom", c.bottom);
        c.left = (float) o.optDouble("left", c.left);
        c.right = (float) o.optDouble("right", c.right);

        c.recognitionMode = RecognitionMode.fromKey(
                o.optString("recognitionMode", ""), RecognitionMode.FIXED_INTERVAL);

        // 新字段 matchMode；旧版由 wholeBoxMode / mergeWrappedLines 推导
        if (o.has("matchMode")) {
            c.matchMode = MatchMode.fromKey(o.optString("matchMode", ""), MatchMode.SENTENCE);
        } else if (o.optBoolean("wholeBoxMode", false)) {
            c.matchMode = MatchMode.WHOLE_BOX;
        } else if (o.optBoolean("mergeWrappedLines", true)) {
            c.matchMode = MatchMode.SENTENCE;
        } else {
            c.matchMode = MatchMode.LINE;
        }

        c.conditionGroups.clear();
        JSONArray groups = o.optJSONArray("conditionGroups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                Object raw = groups.opt(i);
                ConditionGroup group = null;
                if (raw instanceof JSONObject) {
                    group = ConditionGroup.fromJson((JSONObject) raw);
                } else if (raw instanceof JSONArray) {
                    group = ConditionGroup.fromLegacyArray((JSONArray) raw);
                }
                if (group != null && !group.keywords.isEmpty()) {
                    c.conditionGroups.add(group);
                }
            }
        }
        if (c.conditionGroups.isEmpty()) {
            c.conditionGroups.add(ConditionGroup.create("158", "正在尝试占领"));
        }

        c.excludeGroups.clear();
        JSONArray excludeGroupsJson = o.optJSONArray("excludeGroups");
        if (excludeGroupsJson != null) {
            for (int i = 0; i < excludeGroupsJson.length(); i++) {
                Object raw = excludeGroupsJson.opt(i);
                if (raw instanceof JSONObject) {
                    ConditionGroup group = ConditionGroup.fromJson((JSONObject) raw);
                    if (!group.keywords.isEmpty()) {
                        c.excludeGroups.add(group);
                    }
                }
            }
        } else {
            // 兼容旧的平铺剔除词：每个词变成单关键词剔除组
            JSONArray excludes = o.optJSONArray("excludeWords");
            if (excludes != null) {
                for (int i = 0; i < excludes.length(); i++) {
                    String word = excludes.optString(i, "");
                    if (!word.isEmpty()) {
                        c.excludeGroups.add(ConditionGroup.create(word));
                    }
                }
            }
        }

        c.intervalSec = (float) o.optDouble("intervalSec", c.intervalSec);
        c.skipNoChange = o.optBoolean("skipNoChange", c.skipNoChange);
        c.cooldownSec = o.optInt("cooldownSec", c.cooldownSec);
        c.queueCapacity = o.optInt("queueCapacity", c.queueCapacity);
        c.minCharProbability = (float) o.optDouble("minCharProbability", c.minCharProbability);
        c.minCharRatio = (float) o.optDouble("minCharRatio", c.minCharRatio);
        c.vibrate = o.optBoolean("vibrate", c.vibrate);
        c.sound = o.optBoolean("sound", c.sound);
        c.ocrEngine = o.optString("ocrEngine", c.ocrEngine);
        c.minConfidence = (float) o.optDouble("minConfidence", c.minConfidence);
        c.whitelistEnabled = o.optBoolean("whitelistEnabled", c.whitelistEnabled);
        c.whitelist = o.optString("whitelist", c.whitelist);
        return c.validate();
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
