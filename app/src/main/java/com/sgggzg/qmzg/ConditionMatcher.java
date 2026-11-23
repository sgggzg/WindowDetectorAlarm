package com.sgggzg.qmzg;

import java.util.List;

public class ConditionMatcher {

    public interface MatchLogCallback {
        void onLog(String msg);
    }

    /**
     * 判断是否触发报警
     *
     * @param text            OCR 输出的原始文本
     * @param conditionGroups 条件组列表
     * @param excludeWords    剔除词列表
     * @param wholeBoxMode    true=整框识别，false=单行识别
     * @param logCallback     日志回调
     * @return 是否触发
     */
    public static boolean shouldTriggerAlarm(String text,
                                             List<List<String>> conditionGroups,
                                             List<String> excludeWords,
                                             boolean wholeBoxMode,
                                             MatchLogCallback logCallback) {
        if (text == null || text.trim().isEmpty()) return false;
        if (conditionGroups == null || conditionGroups.isEmpty()) return false;

        if (wholeBoxMode) {
            return matchWholeBox(text, conditionGroups, excludeWords, logCallback);
        } else {
            return matchLineByLine(text, conditionGroups, excludeWords, logCallback);
        }
    }

    // ==================== 整框模式 ====================
    private static boolean matchWholeBox(String text,
                                         List<List<String>> conditionGroups,
                                         List<String> excludeWords,
                                         MatchLogCallback logCallback) {
        // 去掉所有空格、全角空格、换行，把整框内容拼成一整段
        String cleaned = text.replace(" ", "")
            .replace("　", "")
            .replace("\n", "")
            .replace("\r", "");
        if (cleaned.isEmpty()) return false;

        // 剔除词过滤：整段命中任一剔除词就直接返回 false
        if (excludeWords != null) {
            for (String ew : excludeWords) {
                if (ew == null || ew.isEmpty()) continue;
                if (cleaned.contains(ew)) {
                    if (logCallback != null) {
                        logCallback.onLog("整框模式：命中剔除词 [" + ew + "]，忽略整框");
                    }
                    return false;
                }
            }
        }

        // 条件组匹配：组内 AND，组间 OR
        for (int gi = 0; gi < conditionGroups.size(); gi++) {
            List<String> group = conditionGroups.get(gi);
            if (group == null || group.isEmpty()) continue;

            boolean matched = true;
            for (String kw : group) {
                if (kw == null || kw.isEmpty()) continue;
                if (!cleaned.contains(kw)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                if (logCallback != null) {
                    logCallback.onLog("整框模式：条件组 " + (gi + 1) + " 匹配成功");
                }
                return true;
            }
        }
        return false;
    }

    // ==================== 单行模式 ====================
    private static boolean matchLineByLine(String text,
                                           List<List<String>> conditionGroups,
                                           List<String> excludeWords,
                                           MatchLogCallback logCallback) {
        String[] lines = text.split("\n");
        for (String line : lines) {
            // 1. 清洗行内空格
            String cleanedLine = line.replace(" ", "").replace("　", "");
            if (cleanedLine.isEmpty()) continue;

            // 2. 剔除词过滤
            boolean isExcluded = false;
            if (excludeWords != null) {
                for (String excludeWord : excludeWords) {
                    if (excludeWord == null || excludeWord.isEmpty()) continue;
                    if (cleanedLine.contains(excludeWord)) {
                        isExcluded = true;
                        if (logCallback != null) {
                            logCallback.onLog("检测到剔除词 [" + excludeWord + "]，忽略本行: " + cleanedLine);
                        }
                        break;
                    }
                }
            }
            if (isExcluded) continue;

            // 3. 条件组匹配（组间 OR，组内 AND）
            for (int gi = 0; gi < conditionGroups.size(); gi++) {
                List<String> group = conditionGroups.get(gi);
                if (group == null || group.isEmpty()) continue;

                boolean groupMatched = true;
                for (String keyword : group) {
                    if (keyword == null || keyword.isEmpty()) continue;
                    if (!cleanedLine.contains(keyword)) {
                        groupMatched = false;
                        break;
                    }
                }
                if (groupMatched) {
                    if (logCallback != null) {
                        logCallback.onLog("匹配到目标行(已清洗空格): " + cleanedLine);
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
