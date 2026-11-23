package com.sgggzg.qmzg.ui;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.sgggzg.qmzg.R;
import com.sgggzg.qmzg.core.Actions;
import com.sgggzg.qmzg.core.AppLog;
import com.sgggzg.qmzg.core.MonitorState;
import com.sgggzg.qmzg.core.RuntimeState;
import com.sgggzg.qmzg.data.ConfigRepository;
import com.sgggzg.qmzg.service.MonitorService;
import com.sgggzg.qmzg.ocr.OcrResult;
import com.sgggzg.qmzg.ui.view.MarkedSeekBar;
import com.sgggzg.qmzg.ui.view.OcrOverlayView;
import com.sgggzg.qmzg.ui.view.MonitorStateView;

/** 首页：状态、暗屏透明度、预览/日志切换、开始监控与测试报警。 */
public class HomeFragment extends Fragment {

    private static final int REQUEST_MEDIA_PROJECTION = 2001;
    private static final int REQUEST_OVERLAY = 2002;
    private static final int REQUEST_NOTIFICATION = 2003;
    private static final int MAX_MASK_ALPHA = 99;

    private TextView tvStatus;
    private TextView tvLog;
    private ImageView ivPreview;
    private Button btnToggleLog;
    private Button btnStart;
    private Button btnTestAlarm;
    private MonitorStateView monitorStateView;
    private MaterialSwitch swEnableImage;
    private MaterialSwitch swEnableLog;
    private MaterialSwitch swShowOcrOverlay;

    private MarkedSeekBar sbMaskAlpha;
    private TextView tvMaskAlphaValue;
    private View cardLog;
    private View cardPreview;
    private OcrOverlayView ocrOverlay;

    private boolean isLogVisible;
    private boolean isOverlayEnabled;
    private boolean pendingTestAlarm;
    private ConfigRepository repository;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Actions.LOG.equals(action)) {
                refreshLogDisplay();
            } else if (Actions.IMAGE.equals(action)) {
                Bitmap bitmap = RuntimeState.getLastPreview();
                if (bitmap != null && ivPreview != null) {
                    ivPreview.setImageBitmap(bitmap);
                }
                updateOverlay();
            } else if (Actions.OCR_RESULT.equals(action)) {
                updateOverlay();
            } else if (Actions.SERVICE_STATE_CHANGED.equals(action)) {
                syncUiWithService();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        repository = ConfigRepository.get(requireContext());

        tvStatus = view.findViewById(R.id.tvStatus);
        tvLog = view.findViewById(R.id.tvLog);
        ivPreview = view.findViewById(R.id.ivPreview);
        btnToggleLog = view.findViewById(R.id.btnToggleLog);
        btnStart = view.findViewById(R.id.btnStart);
        btnTestAlarm = view.findViewById(R.id.btnTestAlarm);
        monitorStateView = view.findViewById(R.id.btnMonitorState);
        swEnableImage = view.findViewById(R.id.swEnableImage);
        swEnableLog = view.findViewById(R.id.swEnableLog);
        swShowOcrOverlay = view.findViewById(R.id.swShowOcrOverlay);
        ocrOverlay = view.findViewById(R.id.ocrOverlay);
        cardLog = view.findViewById(R.id.cardLog);
        cardPreview = view.findViewById(R.id.cardPreview);
        sbMaskAlpha = view.findViewById(R.id.sbMaskAlpha);
        tvMaskAlphaValue = view.findViewById(R.id.tvMaskAlphaValue);

        swEnableImage.setChecked(repository.isImagePreviewEnabled());
        swEnableLog.setChecked(repository.isLogEnabled());
        swShowOcrOverlay.setChecked(repository.isOcrOverlayEnabled());
        isOverlayEnabled = swShowOcrOverlay.isChecked();
        ocrOverlay.setVisibility(isOverlayEnabled ? View.VISIBLE : View.GONE);
        RuntimeState.setImagePreviewEnabled(swEnableImage.isChecked());
        RuntimeState.setLogEnabled(swEnableLog.isChecked());

        int maskAlphaPct = repository.getMaskAlphaPct();
        sbMaskAlpha.setMax(MAX_MASK_ALPHA);
        sbMaskAlpha.setProgress(maskAlphaPct);
        sbMaskAlpha.setMarkFraction(90f / MAX_MASK_ALPHA);
        tvMaskAlphaValue.setText(maskAlphaPct + "%");

        sbMaskAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > MAX_MASK_ALPHA) {
                    seekBar.setProgress(MAX_MASK_ALPHA);
                    return;
                }
                tvMaskAlphaValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = Math.min(seekBar.getProgress(), MAX_MASK_ALPHA);
                repository.setMaskAlphaPct(progress);
                Toast.makeText(requireContext(), "已保存，下次开暗屏生效", Toast.LENGTH_SHORT).show();
            }
        });

        swEnableImage.setOnCheckedChangeListener((v, checked) -> {
            RuntimeState.setImagePreviewEnabled(checked);
            repository.setImagePreviewEnabled(checked);
        });
        swEnableLog.setOnCheckedChangeListener((v, checked) -> {
            RuntimeState.setLogEnabled(checked);
            repository.setLogEnabled(checked);
        });
        swShowOcrOverlay.setOnCheckedChangeListener((v, checked) -> {
            isOverlayEnabled = checked;
            repository.setOcrOverlayEnabled(checked);
            ocrOverlay.setVisibility(checked ? View.VISIBLE : View.GONE);
            updateOverlay();
        });

        btnToggleLog.setOnClickListener(v -> {
            isLogVisible = !isLogVisible;
            if (isLogVisible) {
                cardLog.setVisibility(View.VISIBLE);
                cardPreview.setVisibility(View.GONE);
                btnToggleLog.setText("隐藏日志");
            } else {
                cardLog.setVisibility(View.GONE);
                cardPreview.setVisibility(View.VISIBLE);
                btnToggleLog.setText("显示日志");
            }
        });

        btnStart.setOnClickListener(v -> {
            if (MonitorState.get() == MonitorState.Phase.STOPPED) {
                checkPermissionsAndStart();
            } else {
                Intent stopIntent = new Intent(requireContext(), MonitorService.class);
                stopIntent.setAction(Actions.STOP_MONITOR);
                requireContext().startService(stopIntent);
            }
        });

        monitorStateView.setOnClickListener(v -> {
            MonitorState.Phase phase = MonitorState.get();
            if (phase == MonitorState.Phase.RUNNING
                    || phase == MonitorState.Phase.PAUSED
                    || phase == MonitorState.Phase.ALARM) {
                Intent intent = new Intent(requireContext(), MonitorService.class);
                intent.setAction(Actions.PAUSE_RESUME);
                requireContext().startService(intent);
            }
        });

        btnTestAlarm.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(requireContext())) {
                Toast.makeText(requireContext(), "测试报警需要悬浮窗权限，请先授权",
                        Toast.LENGTH_LONG).show();
                pendingTestAlarm = true;
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + requireContext().getPackageName()));
                startActivityForResult(intent, REQUEST_OVERLAY);
                return;
            }
            performTestAlarm();
        });

        appendLog("首页已加载");
        refreshLogDisplay();
        syncUiWithService();
        return view;
    }

    private void performTestAlarm() {
        Intent testIntent = new Intent(requireContext(), MonitorService.class);
        testIntent.setAction(Actions.TEST_ALARM);
        requireContext().startService(testIntent);
    }

    private void syncUiWithService() {
        if (getActivity() == null || tvStatus == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            MonitorState.Phase phase = MonitorState.get();
            String name = repository.getActiveName();
            String status;
            switch (phase) {
                case RUNNING:
                    status = "监控中 · " + name;
                    break;
                case PAUSING:
                    status = "正在暂停 · " + name;
                    break;
                case PAUSED:
                    status = "已暂停 · " + name;
                    break;
                case ALARM:
                    status = "报警中 · " + name;
                    break;
                default:
                    status = "待启动 · " + name;
                    break;
            }
            tvStatus.setText(status);
            btnStart.setText(phase == MonitorState.Phase.STOPPED ? "开始监控" : "停止监控");
            btnStart.setEnabled(true);
            if (monitorStateView != null) {
                monitorStateView.setPhase(phase);
            }
        });
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION);
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(requireContext())) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        appendLog("请求屏幕录制授权");
        MediaProjectionManager manager = (MediaProjectionManager)
                requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(requireContext(), "当前设备不支持屏幕录制", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissionsAndStart();
            } else {
                Toast.makeText(requireContext(), "必须授予通知权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY) {
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(requireContext());
            if (granted) {
                if (pendingTestAlarm) {
                    pendingTestAlarm = false;
                    performTestAlarm();
                } else {
                    checkPermissionsAndStart();
                }
            } else {
                pendingTestAlarm = false;
                Toast.makeText(requireContext(), "需要悬浮窗权限", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                RuntimeState.setCaptureIntent(data);
                RuntimeState.setCaptureResultCode(resultCode);
                RuntimeState.setAlarming(false);
                RuntimeState.setMonitoring(true);
                MonitorState.set(MonitorState.Phase.RUNNING);

                Intent serviceIntent = new Intent(requireContext(), MonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(serviceIntent);
                } else {
                    requireContext().startService(serviceIntent);
                }
                syncUiWithService();
                appendLog("MonitorService 已启动");
            } else {
                Toast.makeText(requireContext(), "屏幕录制授权失败", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void appendLog(String message) {
        AppLog.append(message);
        refreshLogDisplay();
    }

    private void refreshLogDisplay() {
        if (tvLog == null || getActivity() == null) {
            return;
        }
        requireActivity().runOnUiThread(() -> {
            tvLog.setText(AppLog.getText());
            View parent = (View) tvLog.getParent();
            if (parent instanceof ScrollView) {
                parent.post(() -> ((ScrollView) parent).fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        repository = ConfigRepository.get(requireContext());
        LocalBroadcastManager manager = LocalBroadcastManager.getInstance(requireContext());
        manager.registerReceiver(receiver, new IntentFilter(Actions.LOG));
        manager.registerReceiver(receiver, new IntentFilter(Actions.IMAGE));
        manager.registerReceiver(receiver, new IntentFilter(Actions.OCR_RESULT));
        manager.registerReceiver(receiver, new IntentFilter(Actions.SERVICE_STATE_CHANGED));

        syncUiWithService();
        refreshLogDisplay();
        Bitmap bitmap = RuntimeState.getLastPreview();
        if (bitmap != null && ivPreview != null) {
            ivPreview.setImageBitmap(bitmap);
        }
        updateOverlay();
    }

    private void updateOverlay() {
        if (ocrOverlay == null || !isOverlayEnabled) {
            return;
        }
        Bitmap bitmap = RuntimeState.getLastPreview();
        OcrResult result = RuntimeState.getLastOcrResult();
        if (bitmap == null || result == null) {
            return;
        }
        ocrOverlay.setImageSize(bitmap.getWidth(), bitmap.getHeight());
        ocrOverlay.setResult(result);
    }

    @Override
    public void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver);
    }
}
