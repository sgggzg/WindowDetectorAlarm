package com.sgggzg.qmzg.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置仓库：负责检测方案的持久化与旧版配置迁移。
 *
 * <p>存储结构（SharedPreferences "config" / "profiles_v2"）：</p>
 * <pre>
 * {"active":"default","profiles":[ {...}, {...} ]}
 * </pre>
 */
public final class ConfigRepository {

    private static final String PREFS = "config";
    private static final String KEY_PROFILES = "profiles_v2";
    private static final String KEY_ACTIVE = "active_profile_id";

    private static final String KEY_MASK_ALPHA = "dark_mask_alpha_pct";
    private static final String KEY_IMAGE = "enable_image";
    private static final String KEY_LOG = "enable_log";
    private static final String KEY_OVERLAY = "show_ocr_overlay";

    private static volatile ConfigRepository instance;

    private final SharedPreferences prefs;
    private final List<AppConfig> profiles = new ArrayList<>();
    private String activeId = "default";

    public static ConfigRepository get(Context context) {
        if (instance == null) {
            synchronized (ConfigRepository.class) {
                if (instance == null) {
                    instance = new ConfigRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ConfigRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    // ==================== 方案 ====================

    public synchronized AppConfig getActive() {
        AppConfig found = find(activeId);
        if (found == null) {
            found = profiles.isEmpty() ? null : profiles.get(0);
            if (found == null) {
                found = AppConfig.createDefault("default", "默认方案");
                profiles.add(found);
                persist();
            }
            activeId = found.id;
        }
        return found.copy();
    }

    public synchronized String getActiveId() {
        return activeId;
    }

    public synchronized String getActiveName() {
        return getActive().name;
    }

    public synchronized List<AppConfig> listProfiles() {
        List<AppConfig> result = new ArrayList<>();
        for (AppConfig c : profiles) {
            result.add(c.copy());
        }
        return result;
    }

    public synchronized void setActive(String id) {
        if (find(id) != null) {
            activeId = id;
            persist();
        }
    }

    public synchronized void save(AppConfig config) {
        if (config == null) {
            return;
        }
        AppConfig validated = config.validate();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(validated.id)) {
                profiles.set(i, validated.copy());
                persist();
                return;
            }
        }
        profiles.add(validated.copy());
        persist();
    }

    public synchronized AppConfig createProfile(String name) {
        AppConfig base = getActive();
        AppConfig created = base.copy();
        created.id = "p" + System.currentTimeMillis();
        created.name = name == null || name.trim().isEmpty() ? "新方案" : name.trim();
        profiles.add(created);
        activeId = created.id;
        persist();
        return created.copy();
    }

    public synchronized AppConfig duplicateProfile(String sourceId, String name) {
        AppConfig source = find(sourceId);
        if (source == null) {
            source = getActive();
        }
        AppConfig copy = source.copy();
        copy.id = "p" + System.currentTimeMillis();
        copy.name = name == null || name.trim().isEmpty() ? (source.name + " 副本") : name.trim();
        profiles.add(copy);
        activeId = copy.id;
        persist();
        return copy.copy();
    }

    public synchronized boolean renameProfile(String id, String newName) {
        AppConfig target = find(id);
        if (target == null || newName == null || newName.trim().isEmpty()) {
            return false;
        }
        target.name = newName.trim();
        persist();
        return true;
    }

    public synchronized boolean deleteProfile(String id) {
        if (profiles.size() <= 1) {
            return false;
        }
        AppConfig target = find(id);
        if (target == null) {
            return false;
        }
        profiles.remove(target);
        if (id.equals(activeId)) {
            activeId = profiles.get(0).id;
        }
        persist();
        return true;
    }

    private AppConfig find(String id) {
        if (id == null) {
            return null;
        }
        for (AppConfig c : profiles) {
            if (id.equals(c.id)) {
                return c;
            }
        }
        return null;
    }

    // ==================== 全局开关 ====================

    public boolean isImagePreviewEnabled() {
        return prefs.getBoolean(KEY_IMAGE, true);
    }

    public void setImagePreviewEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_IMAGE, enabled).apply();
    }

    public boolean isLogEnabled() {
        return prefs.getBoolean(KEY_LOG, true);
    }

    public void setLogEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LOG, enabled).apply();
    }

    public boolean isOcrOverlayEnabled() {
        return prefs.getBoolean(KEY_OVERLAY, false);
    }

    public void setOcrOverlayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_OVERLAY, enabled).apply();
    }

    /** 暗屏遮罩不透明度（0 ~ 99），全局共用。 */
    public int getMaskAlphaPct() {
        return clampAlpha(prefs.getInt(KEY_MASK_ALPHA, 95));
    }

    public void setMaskAlphaPct(int pct) {
        prefs.edit().putInt(KEY_MASK_ALPHA, clampAlpha(pct)).apply();
    }

    private static int clampAlpha(int pct) {
        if (pct < 0) {
            return 0;
        }
        return Math.min(pct, 99);
    }

    // ==================== 持久化 / 迁移 ====================

    private void load() {
        profiles.clear();
        String raw = prefs.getString(KEY_PROFILES, null);
        if (raw != null) {
            try {
                JSONObject root = new JSONObject(raw);
                activeId = root.optString("active", "default");
                JSONArray array = root.optJSONArray("profiles");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject o = array.optJSONObject(i);
                        if (o != null) {
                            profiles.add(AppConfig.fromJson(o));
                        }
                    }
                }
            } catch (Exception ignored) {
                profiles.clear();
            }
        }
        if (profiles.isEmpty()) {
            profiles.add(migrateLegacy());
            activeId = profiles.get(0).id;
            persist();
        }
        if (find(activeId) == null) {
            activeId = profiles.get(0).id;
            persist();
        }
    }

    /** 从旧版散装 key 迁移出一套默认方案。 */
    private AppConfig migrateLegacy() {
        AppConfig c = AppConfig.createDefault("default", "默认方案");
        c.top = prefs.getFloat("topRatio", c.top);
        c.bottom = prefs.getFloat("bottomRatio", c.bottom);
        c.left = prefs.getFloat("leftRatio", c.left);
        c.right = prefs.getFloat("rightRatio", c.right);
        c.vibrate = prefs.getBoolean("vibrate", c.vibrate);
        c.sound = prefs.getBoolean("sound", c.sound);
        c.intervalSec = prefs.getFloat("ocr_interval", c.intervalSec);
        c.skipNoChange = prefs.getBoolean("no_change_skip", c.skipNoChange);
        boolean wholeBox = prefs.getBoolean("whole_box_mode", false);
        c.matchMode = wholeBox ? MatchMode.WHOLE_BOX : MatchMode.SENTENCE;
        c.whitelistEnabled = prefs.getBoolean("whitelist_enabled", c.whitelistEnabled);
        c.whitelist = prefs.getString("whitelist", c.whitelist);

        String groupsJson = prefs.getString("conditionGroups", null);
        if (groupsJson != null) {
            List<ConditionGroup> groups = parseGroups(groupsJson);
            if (!groups.isEmpty()) {
                c.conditionGroups.clear();
                c.conditionGroups.addAll(groups);
            }
        }
        String excludesJson = prefs.getString("excludeWords", null);
        if (excludesJson != null) {
            List<String> excludes = parseExcludes(excludesJson);
            if (!excludes.isEmpty()) {
                c.excludeGroups.clear();
                for (String word : excludes) {
                    c.excludeGroups.add(ConditionGroup.create(word));
                }
            }
        }
        return c.validate();
    }

    private static List<ConditionGroup> parseGroups(String json) {
        List<ConditionGroup> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONArray group = array.optJSONArray(i);
                if (group == null) {
                    continue;
                }
                ConditionGroup parsed = ConditionGroup.fromLegacyArray(group);
                if (!parsed.keywords.isEmpty()) {
                    result.add(parsed);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static List<String> parseExcludes(String json) {
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String word = array.optString(i, "");
                if (!word.isEmpty()) {
                    result.add(word);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            root.put("active", activeId);
            JSONArray array = new JSONArray();
            for (AppConfig c : profiles) {
                array.put(c.toJson());
            }
            root.put("profiles", array);
            prefs.edit().putString(KEY_PROFILES, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
