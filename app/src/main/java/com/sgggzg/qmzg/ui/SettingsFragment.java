package com.sgggzg.qmzg.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.flexbox.FlexboxLayout;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.sgggzg.qmzg.R;
import com.sgggzg.qmzg.core.AppLog;
import com.sgggzg.qmzg.data.AppConfig;
import com.sgggzg.qmzg.data.ConditionGroup;
import com.sgggzg.qmzg.data.ConfigRepository;
import com.sgggzg.qmzg.data.MatchMode;
import com.sgggzg.qmzg.data.RecognitionMode;
import com.sgggzg.qmzg.ui.view.ExpandableLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/** 设置页：方案、区域、匹配单位、条件组、模糊额度、剔除词、报警方式。 */
public class SettingsFragment extends Fragment {

    private static final int MAX_KEYWORD_FUZZY = 20;
    private static final int MAX_GROUP_FUZZY = 99;

    private MaterialSwitch swVibrate;
    private MaterialSwitch swSound;
    private MaterialSwitch swSkipNoChange;
    private RadioGroup rgRecognitionMode;
    private MaterialRadioButton rbFixedInterval;
    private MaterialRadioButton rbStream;
    private View tilOcrInterval;

    private RadioGroup rgMatchMode;
    private MaterialRadioButton rbWholeBox;
    private MaterialRadioButton rbSentence;
    private MaterialRadioButton rbLine;

    private EditText etTopRatio;
    private EditText etBottomRatio;
    private EditText etLeftRatio;
    private EditText etRightRatio;
    private EditText etOcrInterval;
    private EditText etCooldown;

    private Button btnProfile;
    private Button btnPreview;
    private Button btnAddConditionGroup;
    private Button btnAddExcludeGroup;
    private FlexboxLayout layoutConditionContainer;
    private LinearLayout layoutExcludeGroupContainer;
    private ExpandableLayout layoutDetectionContent;
    private ExpandableLayout layoutExcludeContent;
    private ExpandableLayout layoutRecognitionSummary;
    private ExpandableLayout layoutRecognitionContent;
    private TextView tvDetectionArrow;
    private TextView tvExcludeArrow;
    private TextView tvDetectionSummary;
    private TextView tvExcludeSummary;
    private TextView tvRecognitionArrow;
    private TextView tvRecognitionSummary;

    private ConfigRepository repository;
    private AppConfig config;
    private boolean loading;
    private boolean detectionExpanded = true;
    private boolean excludeExpanded = true;
    private boolean recognitionExpanded = true;
    private final Set<Integer> collapsedConditionGroups = new HashSet<>();
    private final Set<Integer> collapsedExcludeGroups = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        repository = ConfigRepository.get(requireContext());
        config = repository.getActive();

        bindViews(view);
        bindListeners(view);
        loadData();
        renderConditionGroups();
        renderExcludeGroups();
        updateRecognitionSection(false);
        return view;
    }

    private void bindViews(View view) {
        swVibrate = view.findViewById(R.id.swVibrate);
        swSound = view.findViewById(R.id.swSound);
        swSkipNoChange = view.findViewById(R.id.swSkipNoChange);
        rgRecognitionMode = view.findViewById(R.id.rgRecognitionMode);
        rbFixedInterval = view.findViewById(R.id.rbFixedInterval);
        rbStream = view.findViewById(R.id.rbStream);
        tilOcrInterval = view.findViewById(R.id.tilOcrInterval);

        rgMatchMode = view.findViewById(R.id.rgMatchMode);
        rbWholeBox = view.findViewById(R.id.rbWholeBox);
        rbSentence = view.findViewById(R.id.rbSentence);
        rbLine = view.findViewById(R.id.rbLine);

        etTopRatio = view.findViewById(R.id.etTopRatio);
        etBottomRatio = view.findViewById(R.id.etBottomRatio);
        etLeftRatio = view.findViewById(R.id.etLeftRatio);
        etRightRatio = view.findViewById(R.id.etRightRatio);
        etOcrInterval = view.findViewById(R.id.etOcrInterval);
        etCooldown = view.findViewById(R.id.etCooldown);

        btnProfile = view.findViewById(R.id.btnProfile);
        btnPreview = view.findViewById(R.id.btnPreview);
        btnAddConditionGroup = view.findViewById(R.id.btnAddConditionGroup);
        btnAddExcludeGroup = view.findViewById(R.id.btnAddExcludeGroup);
        layoutConditionContainer = view.findViewById(R.id.layoutConditionContainer);
        layoutExcludeGroupContainer = view.findViewById(R.id.layoutExcludeGroupContainer);
        layoutDetectionContent = view.findViewById(R.id.layoutDetectionContent);
        layoutExcludeContent = view.findViewById(R.id.layoutExcludeContent);
        tvDetectionArrow = view.findViewById(R.id.tvDetectionArrow);
        tvExcludeArrow = view.findViewById(R.id.tvExcludeArrow);
        tvDetectionSummary = view.findViewById(R.id.tvDetectionSummary);
        tvExcludeSummary = view.findViewById(R.id.tvExcludeSummary);
        layoutRecognitionSummary = view.findViewById(R.id.layoutRecognitionSummary);
        layoutRecognitionContent = view.findViewById(R.id.layoutRecognitionContent);
        tvRecognitionArrow = view.findViewById(R.id.tvRecognitionArrow);
        tvRecognitionSummary = view.findViewById(R.id.tvRecognitionSummary);
    }

    private void bindListeners(View root) {
        swVibrate.setOnCheckedChangeListener((v, checked) -> {
            if (loading) {
                return;
            }
            config.vibrate = checked;
            saveConfig();
        });
        swSound.setOnCheckedChangeListener((v, checked) -> {
            if (loading) {
                return;
            }
            config.sound = checked;
            saveConfig();
        });
        swSkipNoChange.setOnCheckedChangeListener((v, checked) -> {
            if (loading) {
                return;
            }
            config.skipNoChange = checked;
            saveConfig();
        });

        rgRecognitionMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (loading) {
                return;
            }
            config.recognitionMode = checkedId == R.id.rbStream
                    ? RecognitionMode.STREAM : RecognitionMode.FIXED_INTERVAL;
            updateRecognitionVisibility();
            saveConfig();
            toast(config.recognitionMode == RecognitionMode.STREAM
                    ? "实时帧差：下次开始监控生效；OCR 间隔已隐藏"
                    : "固定间隔：下次开始监控生效");
        });

        rgMatchMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (loading) {
                return;
            }
            if (checkedId == R.id.rbWholeBox) {
                config.matchMode = MatchMode.WHOLE_BOX;
                toast("整框：框内任意位置出现条件组即报警，出现剔除词则整框不报");
            } else if (checkedId == R.id.rbLine) {
                config.matchMode = MatchMode.LINE;
                toast("单行：每个物理行独立匹配，关键词不能跨行");
            } else {
                config.matchMode = MatchMode.SENTENCE;
                toast("单句：按标点和行边缘/字大小分句，关键词可分句内跨行");
            }
            saveConfig();
        });

        View.OnFocusChangeListener ratioListener = (v, hasFocus) -> {
            if (!hasFocus) {
                saveRatios();
            }
        };
        etTopRatio.setOnFocusChangeListener(ratioListener);
        etBottomRatio.setOnFocusChangeListener(ratioListener);
        etLeftRatio.setOnFocusChangeListener(ratioListener);
        etRightRatio.setOnFocusChangeListener(ratioListener);

        etOcrInterval.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            try {
                float value = Float.parseFloat(etOcrInterval.getText().toString());
                if (value < 0.5f) {
                    value = 0.5f;
                    toast("间隔不能低于 0.5 秒，已重置");
                }
                value = Math.round(value * 100) / 100f;
                etOcrInterval.setText(String.valueOf(value));
                config.intervalSec = value;
                saveConfig();
            } catch (NumberFormatException e) {
                etOcrInterval.setText("3.0");
                config.intervalSec = 3f;
                saveConfig();
            }
        });

        etCooldown.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            try {
                int value = Integer.parseInt(etCooldown.getText().toString());
                value = Math.max(0, Math.min(3600, value));
                etCooldown.setText(String.valueOf(value));
                config.cooldownSec = value;
                saveConfig();
            } catch (NumberFormatException e) {
                etCooldown.setText(String.valueOf(config.cooldownSec));
            }
        });

        btnPreview.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(requireContext(), PreviewActivity.class);
                intent.putExtra("top", Float.parseFloat(etTopRatio.getText().toString()));
                intent.putExtra("bottom", Float.parseFloat(etBottomRatio.getText().toString()));
                intent.putExtra("left", Float.parseFloat(etLeftRatio.getText().toString()));
                intent.putExtra("right", Float.parseFloat(etRightRatio.getText().toString()));
                startActivityForResult(intent, 1005);
            } catch (Exception e) {
                toast("比例输入错误");
            }
        });

        btnProfile.setOnClickListener(v -> showProfileDialog());
        btnAddConditionGroup.setOnClickListener(v -> showAddConditionDialog());
        btnAddExcludeGroup.setOnClickListener(v -> showAddExcludeGroupDialog());

        View recognitionHeader = root.findViewById(R.id.layoutRecognitionHeader);
        recognitionHeader.setOnClickListener(v -> {
            recognitionExpanded = !recognitionExpanded;
            updateRecognitionSection(true);
        });

        View detectionHeader = root.findViewById(R.id.layoutDetectionHeader);
        detectionHeader.setOnClickListener(v -> {
            detectionExpanded = !detectionExpanded;
            updateSectionStates(true);
        });
        View excludeHeader = root.findViewById(R.id.layoutExcludeHeader);
        excludeHeader.setOnClickListener(v -> {
            excludeExpanded = !excludeExpanded;
            updateSectionStates(true);
        });
    }

    private void checkRadioGroup(RadioGroup group, int checkedId) {
        group.check(checkedId);
        group.jumpDrawablesToCurrentState();
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).jumpDrawablesToCurrentState();
        }
    }

    private void loadData() {
        loading = true;
        swVibrate.setChecked(config.vibrate);
        swSound.setChecked(config.sound);
        swSkipNoChange.setChecked(config.skipNoChange);

        int recognitionId = config.recognitionMode == RecognitionMode.STREAM
                ? R.id.rbStream : R.id.rbFixedInterval;
        checkRadioGroup(rgRecognitionMode, recognitionId);
        updateRecognitionVisibility();

        int checkedId = R.id.rbSentence;
        if (config.matchMode == MatchMode.WHOLE_BOX) {
            checkedId = R.id.rbWholeBox;
        } else if (config.matchMode == MatchMode.LINE) {
            checkedId = R.id.rbLine;
        }
        checkRadioGroup(rgMatchMode, checkedId);

        etTopRatio.setText(String.valueOf(config.top));
        etBottomRatio.setText(String.valueOf(config.bottom));
        etLeftRatio.setText(String.valueOf(config.left));
        etRightRatio.setText(String.valueOf(config.right));
        etOcrInterval.setText(String.valueOf(config.intervalSec));
        etCooldown.setText(String.valueOf(config.cooldownSec));

        btnProfile.setText("检测方案：" + config.name + "  ▾");
        loading = false;
        updateRecognitionSummaryText();
    }

    private void updateRecognitionVisibility() {
        boolean stream = config.recognitionMode == RecognitionMode.STREAM;
        if (tilOcrInterval != null) {
            tilOcrInterval.setVisibility(stream ? View.GONE : View.VISIBLE);
        }
        if (swSkipNoChange != null) {
            swSkipNoChange.setVisibility(stream ? View.GONE : View.VISIBLE);
        }
    }

    private void saveConfig() {
        repository.save(config);
        AppLog.append("配置已保存：《" + config.name + "》");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1005 && resultCode == Activity.RESULT_OK && data != null) {
            float top = data.getFloatExtra("top", config.top);
            float bottom = data.getFloatExtra("bottom", config.bottom);
            float left = data.getFloatExtra("left", config.left);
            float right = data.getFloatExtra("right", config.right);

            if (bottom <= top || right <= left) {
                toast("区域无效：下必须大于上，右必须大于左");
                return;
            }
            config.top = top;
            config.bottom = bottom;
            config.left = left;
            config.right = right;
            saveConfig();

            etTopRatio.setText(String.valueOf(top));
            etBottomRatio.setText(String.valueOf(bottom));
            etLeftRatio.setText(String.valueOf(left));
            etRightRatio.setText(String.valueOf(right));
            AppLog.append("预览区域已应用: top=" + top + ", bottom=" + bottom
                    + ", left=" + left + ", right=" + right);
        }
    }

    private void saveRatios() {
        try {
            float top = Float.parseFloat(etTopRatio.getText().toString());
            float bottom = Float.parseFloat(etBottomRatio.getText().toString());
            float left = Float.parseFloat(etLeftRatio.getText().toString());
            float right = Float.parseFloat(etRightRatio.getText().toString());
            if (bottom <= top || right <= left) {
                toast("区域无效：下必须大于上，右必须大于左");
                return;
            }
            config.top = top;
            config.bottom = bottom;
            config.left = left;
            config.right = right;
            saveConfig();
        } catch (NumberFormatException ignored) {
        }
    }

    // ==================== 条件组 ====================

    private void renderConditionGroups() {
        layoutConditionContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        final List<ConditionGroup> groups = config.conditionGroups;

        for (int i = 0; i < groups.size(); i++) {
            final int groupIndex = i;
            ConditionGroup group = groups.get(i);

            View groupView = inflater.inflate(R.layout.item_condition_group,
                    layoutConditionContainer, false);
            LinearLayout header = groupView.findViewById(R.id.layoutGroupHeader);
            LinearLayout content = groupView.findViewById(R.id.layoutGroupContent);
            TextView title = groupView.findViewById(R.id.tvGroupTitle);
            TextView summary = groupView.findViewById(R.id.tvGroupSummary);
            TextView arrow = groupView.findViewById(R.id.tvGroupArrow);
            FlexboxLayout layoutKeywords = groupView.findViewById(R.id.layoutKeywords);
            Button btnDeleteGroup = groupView.findViewById(R.id.btnDeleteGroup);
            Button btnAddKeyword = groupView.findViewById(R.id.btnAddKeyword);
            Button btnMinus = groupView.findViewById(R.id.btnGroupFuzzyMinus);
            Button btnPlus = groupView.findViewById(R.id.btnGroupFuzzyPlus);
            final EditText etGroupFuzzy = groupView.findViewById(R.id.etGroupFuzzy);

            title.setText("条件组 " + (groupIndex + 1));
            summary.setText(group.keywords.size() + " 个关键词");
            final boolean collapsed = collapsedConditionGroups.contains(groupIndex);
            ((ExpandableLayout) content).setExpanded(!collapsed, false);
            setArrowRotation(arrow, !collapsed, false);
            header.setOnClickListener(v -> {
                ExpandableLayout expandable = (ExpandableLayout) content;
                boolean nowCollapsed = expandable.isExpanded();
                expandable.setExpanded(!nowCollapsed, true);
                setArrowRotation(arrow, !nowCollapsed, true);
                if (nowCollapsed) {
                    collapsedConditionGroups.add(groupIndex);
                } else {
                    collapsedConditionGroups.remove(groupIndex);
                }
            });

            int maxGroupFuzzy = 0;
            for (ConditionGroup.KeywordRule rule : group.keywords) {
                maxGroupFuzzy += rule.text.length();
            }
            int initialGroupFuzzy = group.maxFuzzyTotal;
            if (initialGroupFuzzy > maxGroupFuzzy) {
                initialGroupFuzzy = maxGroupFuzzy;
                group.maxFuzzyTotal = initialGroupFuzzy;
            }
            writeGroupFuzzy(etGroupFuzzy, initialGroupFuzzy);
            updateGroupFuzzyButtons(btnMinus, btnPlus, initialGroupFuzzy, maxGroupFuzzy);

            final int groupFuzzyMax = maxGroupFuzzy;
            etGroupFuzzy.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    int value = readGroupFuzzy(etGroupFuzzy, groupFuzzyMax);
                    group.maxFuzzyTotal = value;
                    writeGroupFuzzy(etGroupFuzzy, value);
                    updateGroupFuzzyButtons(btnMinus, btnPlus, value, groupFuzzyMax);
                    saveConfig();
                }
            });
            btnMinus.setOnClickListener(v -> {
                int value = readGroupFuzzy(etGroupFuzzy, groupFuzzyMax);
                value = value < 0 ? 0 : Math.max(0, value - 1);
                group.maxFuzzyTotal = value;
                writeGroupFuzzy(etGroupFuzzy, value);
                updateGroupFuzzyButtons(btnMinus, btnPlus, value, groupFuzzyMax);
                saveConfig();
            });
            btnPlus.setOnClickListener(v -> {
                int value = readGroupFuzzy(etGroupFuzzy, groupFuzzyMax);
                value = value < 0 ? 1 : Math.min(groupFuzzyMax, value + 1);
                group.maxFuzzyTotal = value;
                writeGroupFuzzy(etGroupFuzzy, value);
                updateGroupFuzzyButtons(btnMinus, btnPlus, value, groupFuzzyMax);
                saveConfig();
            });

            layoutKeywords.removeAllViews();
            for (int j = 0; j < group.keywords.size(); j++) {
                final int keywordIndex = j;
                ConditionGroup.KeywordRule keyword = group.keywords.get(j);
                TextView tag = (TextView) inflater.inflate(
                        R.layout.item_keyword_tag, layoutKeywords, false);
                StringBuilder tagText = new StringBuilder(keyword.text);
                if (keyword.requiredCount > 1) {
                    tagText.append(" ×").append(keyword.requiredCount);
                }
                if (keyword.maxFuzzy > 0) {
                    tagText.append("  模糊").append(keyword.maxFuzzy);
                }
                tag.setText(tagText.toString());
                tag.setOnClickListener(v -> showKeywordDialog(groupIndex, keywordIndex));
                layoutKeywords.addView(tag);
            }

            btnDeleteGroup.setOnClickListener(v -> {
                if (groupIndex < config.conditionGroups.size()) {
                    config.conditionGroups.remove(groupIndex);
                }
                collapsedConditionGroups.clear();
                saveConfig();
                renderConditionGroups();
            });
            btnAddKeyword.setOnClickListener(v -> {
                collapsedConditionGroups.remove(groupIndex);
                showKeywordDialog(groupIndex, -1);
            });
            layoutConditionContainer.addView(groupView);
        }
        updateSectionStates();
    }

    private void updateRecognitionSection(boolean animate) {
        if (layoutRecognitionContent == null) {
            return;
        }
        updateRecognitionSummaryText();
        layoutRecognitionSummary.setExpanded(!recognitionExpanded, animate);
        layoutRecognitionContent.setExpanded(recognitionExpanded, animate);
        if (recognitionExpanded) {
            for (int i = 0; i < rgRecognitionMode.getChildCount(); i++) {
                rgRecognitionMode.getChildAt(i).jumpDrawablesToCurrentState();
            }
            for (int i = 0; i < rgMatchMode.getChildCount(); i++) {
                rgMatchMode.getChildAt(i).jumpDrawablesToCurrentState();
            }
        }
        setArrowRotation(tvRecognitionArrow, recognitionExpanded, animate);
    }

    private void updateRecognitionSummaryText() {
        if (tvRecognitionSummary == null) {
            return;
        }
        StringBuilder text = new StringBuilder();
        if (config.recognitionMode == RecognitionMode.STREAM) {
            text.append("识别方式：实时帧差");
        } else {
            text.append("识别方式：固定间隔");
            text.append("\n间隔：").append(formatSeconds(config.intervalSec)).append("秒");
        }
        text.append("\n匹配方式：").append(matchModeText(config.matchMode));
        text.append("\n报警冷却：").append(config.cooldownSec).append("秒");
        tvRecognitionSummary.setText(text.toString());
    }

    private String formatSeconds(float value) {
        if (Math.abs(value - Math.round(value)) < 0.001f) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.CHINA, "%.1f", value);
    }

    private String matchModeText(MatchMode mode) {
        if (mode == MatchMode.SENTENCE) {
            return "单句识别";
        }
        if (mode == MatchMode.LINE) {
            return "单行识别";
        }
        return "整框识别";
    }

    private void updateSectionStates() {
        updateSectionStates(false);
    }

    private void updateSectionStates(boolean animate) {
        if (layoutDetectionContent != null) {
            layoutDetectionContent.setExpanded(detectionExpanded, animate);
            setArrowRotation(tvDetectionArrow, detectionExpanded, animate);
            tvDetectionSummary.setText("已设置条件组 " + config.conditionGroups.size() + " 个");
        }
        if (layoutExcludeContent != null) {
            layoutExcludeContent.setExpanded(excludeExpanded, animate);
            setArrowRotation(tvExcludeArrow, excludeExpanded, animate);
            tvExcludeSummary.setText("已设置剔除组 " + config.excludeGroups.size() + " 个");
        }
    }

    private void setArrowRotation(TextView arrow, boolean expanded, boolean animate) {
        if (arrow == null) {
            return;
        }
        arrow.setText("▶");
        float target = expanded ? 90f : 0f;
        if (animate) {
            arrow.animate().rotation(target).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            arrow.animate().cancel();
            arrow.setRotation(target);
        }
    }

    private int readGroupFuzzy(EditText editText, int max) {
        String raw = editText.getText().toString().trim();
        if (raw.isEmpty()) {
            return ConditionGroup.UNLIMITED;
        }
        try {
            int value = Integer.parseInt(raw);
            return Math.max(0, Math.min(max, value));
        } catch (NumberFormatException e) {
            return ConditionGroup.UNLIMITED;
        }
    }

    private void writeGroupFuzzy(EditText editText, int value) {
        editText.setText(value < 0 ? "" : String.valueOf(value));
        editText.setHint("不限");
    }

    private void updateGroupFuzzyButtons(Button minus, Button plus, int value, int max) {
        minus.setEnabled(max > 0 && value != 0);
        plus.setEnabled(max > 0 && (value < 0 || value < max));
    }

    private void showAddConditionDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint("输入第一个关键词（如 158）");
        new AlertDialog.Builder(requireContext())
                .setTitle("新建条件组")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String keyword = input.getText().toString().trim();
                    if (keyword.isEmpty()) {
                        return;
                    }
                    Runnable create = () -> {
                        ConditionGroup group = new ConditionGroup();
                        group.keywords.add(new ConditionGroup.KeywordRule(keyword, 0));
                        config.conditionGroups.add(group);
                        saveConfig();
                        renderConditionGroups();
                    };
                    String conflict = findConflict(keyword, false);
                    if (conflict != null) {
                        showConflictDialog(keyword, conflict, false, create);
                    } else {
                        create.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showKeywordDialog(final int groupIndex, final int keywordIndex) {
        if (groupIndex < 0 || groupIndex >= config.conditionGroups.size()) {
            return;
        }
        final ConditionGroup group = config.conditionGroups.get(groupIndex);
        final ConditionGroup.KeywordRule existing =
                keywordIndex >= 0 && keywordIndex < group.keywords.size()
                        ? group.keywords.get(keywordIndex) : null;

        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_edit_keyword, null);
        final EditText etKeyword = view.findViewById(R.id.etKeywordText);
        final TextView tvValue = view.findViewById(R.id.tvFuzzyValue);
        final TextView tvFuzzyHint = view.findViewById(R.id.tvFuzzyHint);
        Button btnMinus = view.findViewById(R.id.btnFuzzyMinus);
        Button btnPlus = view.findViewById(R.id.btnFuzzyPlus);
        Button btnCountMinus = view.findViewById(R.id.btnCountMinus);
        Button btnCountPlus = view.findViewById(R.id.btnCountPlus);
        final TextView tvCountValue = view.findViewById(R.id.tvCountValue);
        Button btnDelete = view.findViewById(R.id.btnDeleteKeyword);
        final int[] fuzzy = {existing == null ? 0 : existing.maxFuzzy};
        final int[] required = {existing == null ? 1 : existing.requiredCount};

        etKeyword.setText(existing == null ? "" : existing.text);
        etKeyword.setSelection(etKeyword.getText().length());
        updateFuzzyControls(etKeyword, tvValue, btnMinus, btnPlus, tvFuzzyHint, fuzzy);
        updateCountValue(tvCountValue, required[0]);
        updateCountButtons(btnCountMinus, btnCountPlus, required[0]);

        etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int length = s.toString().trim().length();
                if (fuzzy[0] > length) {
                    fuzzy[0] = length;
                    toast("模糊字数不能超过关键词字数，已调整为 " + length);
                }
                updateFuzzyControls(etKeyword, tvValue, btnMinus, btnPlus, tvFuzzyHint, fuzzy);
            }
        });

        btnMinus.setOnClickListener(v -> {
            if (fuzzy[0] > 0) {
                fuzzy[0]--;
                updateFuzzyControls(etKeyword, tvValue, btnMinus, btnPlus, tvFuzzyHint, fuzzy);
            }
        });
        btnPlus.setOnClickListener(v -> {
            int length = etKeyword.getText().toString().trim().length();
            if (fuzzy[0] < length) {
                fuzzy[0]++;
                updateFuzzyControls(etKeyword, tvValue, btnMinus, btnPlus, tvFuzzyHint, fuzzy);
            }
        });
        btnCountMinus.setOnClickListener(v -> {
            required[0] = Math.max(1, required[0] - 1);
            updateCountValue(tvCountValue, required[0]);
            updateCountButtons(btnCountMinus, btnCountPlus, required[0]);
        });
        btnCountPlus.setOnClickListener(v -> {
            required[0] = Math.min(99, required[0] + 1);
            updateCountValue(tvCountValue, required[0]);
            updateCountButtons(btnCountMinus, btnCountPlus, required[0]);
        });
        updateFuzzyControls(etKeyword, tvValue, btnMinus, btnPlus, tvFuzzyHint, fuzzy);
        updateCountValue(tvCountValue, required[0]);
        updateCountButtons(btnCountMinus, btnCountPlus, required[0]);
        btnDelete.setVisibility(existing == null ? View.GONE : View.VISIBLE);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(existing == null ? "新建关键词" : "编辑关键词")
                .setView(view)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();

        btnDelete.setOnClickListener(v -> {
            int index = group.keywords.indexOf(existing);
            if (index >= 0) {
                group.keywords.remove(index);
                saveConfig();
                renderConditionGroups();
            }
            dialog.dismiss();
        });

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String text = etKeyword.getText().toString().trim();
                    if (text.isEmpty()) {
                        toast("关键词不能为空");
                        return;
                    }
                    if (fuzzy[0] > text.length()) {
                        toast("模糊字数不能超过关键词字数");
                        fuzzy[0] = text.length();
                        updateFuzzyControls(etKeyword, tvValue, btnMinus, btnPlus, tvFuzzyHint, fuzzy);
                        return;
                    }
                    Runnable apply = () -> {
                        if (existing == null) {
                            group.keywords.add(new ConditionGroup.KeywordRule(
                                    text, fuzzy[0], required[0]));
                        } else {
                            existing.text = text;
                            existing.maxFuzzy = fuzzy[0];
                            existing.requiredCount = required[0];
                        }
                        saveConfig();
                        renderConditionGroups();
                        dialog.dismiss();
                    };
                    String conflict = findConflict(text, false);
                    if (conflict != null) {
                        showConflictDialog(text, conflict, false, apply);
                    } else {
                        apply.run();
                    }
                }));
        dialog.show();
    }

    private void updateFuzzyControls(EditText etKeyword, TextView tvValue,
                                     Button minus, Button plus, TextView hint,
                                     int[] fuzzy) {
        int length = etKeyword.getText().toString().trim().length();
        if (fuzzy[0] < 0) {
            fuzzy[0] = 0;
        }
        if (fuzzy[0] > length) {
            fuzzy[0] = length;
        }
        tvValue.setText(String.valueOf(fuzzy[0]));
        minus.setEnabled(fuzzy[0] > 0);
        plus.setEnabled(length > 0 && fuzzy[0] < length);
        hint.setText(length == 0
                ? "请先输入关键词；模糊字数上限为 0"
                : "0~" + length + "；0 表示不模糊");
    }

    private void updateCountValue(TextView tvValue, int value) {
        tvValue.setText(String.valueOf(value));
    }

    private void updateCountButtons(Button minus, Button plus, int value) {
        minus.setEnabled(value > 1);
        plus.setEnabled(value < 99);
    }

    private void updateFuzzyValue(TextView textView, int value) {
        textView.setText(String.valueOf(value));
    }

    private String findConflict(String newWord, boolean isExclude) {
        if (newWord == null || newWord.isEmpty()) {
            return null;
        }
        if (isExclude) {
            for (ConditionGroup group : config.conditionGroups) {
                for (ConditionGroup.KeywordRule keyword : group.keywords) {
                    if (keyword.text.contains(newWord) || newWord.contains(keyword.text)) {
                        return keyword.text;
                    }
                }
            }
        } else {
            for (ConditionGroup group : config.excludeGroups) {
                for (ConditionGroup.KeywordRule keyword : group.keywords) {
                    if (keyword.text.contains(newWord) || newWord.contains(keyword.text)) {
                        return keyword.text;
                    }
                }
            }
        }
        return null;
    }

    private void showConflictDialog(String newWord, String conflictWord,
                                    boolean isExclude, final Runnable onContinue) {
        String tip;
        if (isExclude) {
            tip = "剔除关键词「" + newWord + "」和条件组关键词「" + conflictWord + "」存在包含关系。\n\n"
                    + "包含它的单位可能被整段过滤，导致正条件组永远不触发。\n\n是否仍然添加？";
        } else {
            tip = "关键词「" + newWord + "」和剔除关键词「" + conflictWord + "」存在包含关系。\n\n"
                    + "包含该关键词的单位可能被剔除组过滤。\n\n是否仍然添加？";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("检测到冲突")
                .setMessage(tip)
                .setPositiveButton("仍然添加", (dialog, which) -> onContinue.run())
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 剔除逻辑 ====================

    private void renderExcludeGroups() {
        layoutExcludeGroupContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        final List<ConditionGroup> groups = config.excludeGroups;

        for (int i = 0; i < groups.size(); i++) {
            final int groupIndex = i;
            ConditionGroup group = groups.get(i);

            View groupView = inflater.inflate(R.layout.item_exclude_group,
                    layoutExcludeGroupContainer, false);
            LinearLayout header = groupView.findViewById(R.id.layoutExcludeGroupHeader);
            LinearLayout content = groupView.findViewById(R.id.layoutExcludeGroupContent);
            TextView title = groupView.findViewById(R.id.tvExcludeGroupTitle);
            TextView summary = groupView.findViewById(R.id.tvExcludeGroupSummary);
            TextView arrow = groupView.findViewById(R.id.tvExcludeGroupArrow);
            FlexboxLayout keywords = groupView.findViewById(R.id.layoutExcludeKeywords);
            Button btnDeleteGroup = groupView.findViewById(R.id.btnDeleteExcludeGroup);
            Button btnAddKeyword = groupView.findViewById(R.id.btnAddExcludeKeyword);

            title.setText("剔除组 " + (groupIndex + 1));
            summary.setText(group.keywords.size() + " 个关键词");
            final boolean collapsed = collapsedExcludeGroups.contains(groupIndex);
            ((ExpandableLayout) content).setExpanded(!collapsed, false);
            setArrowRotation(arrow, !collapsed, false);
            header.setOnClickListener(v -> {
                ExpandableLayout expandable = (ExpandableLayout) content;
                boolean nowCollapsed = expandable.isExpanded();
                expandable.setExpanded(!nowCollapsed, true);
                setArrowRotation(arrow, !nowCollapsed, true);
                if (nowCollapsed) {
                    collapsedExcludeGroups.add(groupIndex);
                } else {
                    collapsedExcludeGroups.remove(groupIndex);
                }
            });

            keywords.removeAllViews();
            for (int j = 0; j < group.keywords.size(); j++) {
                final int keywordIndex = j;
                ConditionGroup.KeywordRule keyword = group.keywords.get(j);
                TextView tag = (TextView) inflater.inflate(
                        R.layout.item_keyword_tag, keywords, false);
                tag.setText(keyword.text);
                tag.setOnClickListener(v -> showExcludeKeywordDialog(groupIndex, keywordIndex));
                keywords.addView(tag);
            }

            btnDeleteGroup.setOnClickListener(v -> {
                if (groupIndex < config.excludeGroups.size()) {
                    config.excludeGroups.remove(groupIndex);
                }
                collapsedExcludeGroups.clear();
                saveConfig();
                renderExcludeGroups();
            });
            btnAddKeyword.setOnClickListener(v -> showExcludeKeywordDialog(groupIndex, -1));
            layoutExcludeGroupContainer.addView(groupView);
        }
        updateSectionStates();
    }

    private void showAddExcludeGroupDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint("输入第一个剔除关键词（如 158）");
        new AlertDialog.Builder(requireContext())
                .setTitle("新建剔除组")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String word = input.getText().toString().trim();
                    if (word.isEmpty()) {
                        return;
                    }
                    Runnable create = () -> {
                        config.excludeGroups.add(ConditionGroup.create(word));
                        collapsedExcludeGroups.clear();
                        saveConfig();
                        renderExcludeGroups();
                    };
                    String conflict = findConflict(word, true);
                    if (conflict != null) {
                        showConflictDialog(word, conflict, true, create);
                    } else {
                        create.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showExcludeKeywordDialog(final int groupIndex, final int keywordIndex) {
        if (groupIndex < 0 || groupIndex >= config.excludeGroups.size()) {
            return;
        }
        final ConditionGroup group = config.excludeGroups.get(groupIndex);
        final ConditionGroup.KeywordRule existing =
                keywordIndex >= 0 && keywordIndex < group.keywords.size()
                        ? group.keywords.get(keywordIndex) : null;

        final EditText input = new EditText(requireContext());
        input.setHint("剔除关键词");
        input.setText(existing == null ? "" : existing.text);
        input.setSelection(input.getText().length());

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(existing == null ? "新建剔除关键词" : "编辑剔除关键词")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String word = input.getText().toString().trim();
                    if (word.isEmpty()) {
                        toast("剔除关键词不能为空");
                        return;
                    }
                    Runnable apply = () -> {
                        if (existing == null) {
                            group.keywords.add(new ConditionGroup.KeywordRule(word, 0, 1));
                        } else {
                            existing.text = word;
                        }
                        saveConfig();
                        renderExcludeGroups();
                    };
                    String conflict = findConflict(word, true);
                    if (conflict != null) {
                        showConflictDialog(word, conflict, true, apply);
                    } else {
                        apply.run();
                    }
                })
                .setNegativeButton("取消", null);

        if (existing != null) {
            builder.setNeutralButton("删除", (dialog, which) -> {
                int index = group.keywords.indexOf(existing);
                if (index >= 0) {
                    group.keywords.remove(index);
                }
                if (group.keywords.isEmpty() && groupIndex < config.excludeGroups.size()) {
                    config.excludeGroups.remove(groupIndex);
                }
                collapsedExcludeGroups.clear();
                saveConfig();
                renderExcludeGroups();
            });
        }
        builder.show();
    }

    // ==================== 检测方案 ====================

    private void showProfileDialog() {
        final View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_profiles, null);
        final ListView list = view.findViewById(R.id.lvProfiles);
        final List<AppConfig> profiles = repository.listProfiles();
        final String[] names = new String[profiles.size()];
        int checked = 0;
        for (int i = 0; i < profiles.size(); i++) {
            names[i] = profiles.get(i).name;
            if (profiles.get(i).id.equals(config.id)) {
                checked = i;
            }
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_list_item_single_choice, names);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setItemChecked(checked, true);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("检测方案")
                .setView(view)
                .create();

        list.setOnItemClickListener((parent, itemView, position, id) -> {
            String oldId = config.id;
            String newId = profiles.get(position).id;
            repository.setActive(newId);
            config = repository.getActive();
            loadData();
            renderConditionGroups();
            if (!oldId.equals(newId)) {
                AppLog.append("切换检测方案：《" + config.name + "》");
            }
            dialog.dismiss();
        });

        view.findViewById(R.id.btnNewProfile).setOnClickListener(v -> promptName("新建方案", "", name -> {
            config = repository.createProfile(name);
            loadData();
            renderConditionGroups();
            dialog.dismiss();
        }));

        view.findViewById(R.id.btnCopyProfile).setOnClickListener(v -> promptName(
                "复制方案", config.name + " 副本", name -> {
                    config = repository.duplicateProfile(config.id, name);
                    loadData();
                    renderConditionGroups();
                    dialog.dismiss();
                }));

        view.findViewById(R.id.btnRenameProfile).setOnClickListener(v -> promptName(
                "重命名方案", config.name, name -> {
                    if (repository.renameProfile(config.id, name)) {
                        config.name = name;
                        loadData();
                        dialog.dismiss();
                    }
                }));

        view.findViewById(R.id.btnDeleteProfile).setOnClickListener(v -> {
            if (!repository.deleteProfile(config.id)) {
                toast("至少保留一个方案");
                return;
            }
            config = repository.getActive();
            loadData();
            renderConditionGroups();
            dialog.dismiss();
        });

        dialog.show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void promptName(String title, String initial, final NameCallback callback) {
        final EditText input = new EditText(requireContext());
        input.setHint("方案名称");
        input.setText(initial);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名称不能为空");
                        return;
                    }
                    if (name.length() > 20) {
                        name = name.substring(0, 20);
                    }
                    callback.onName(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
