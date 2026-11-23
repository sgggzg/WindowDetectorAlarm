package com.sgggzg.qmzg;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingBallManager {

    public interface ActionCallback {
        void onStopMonitor();
        void onStopAlarm();
        void onToggleDarkMask();
        void onLogMessage(String msg);
    }

    private Context context;
    private WindowManager windowManager;
    private View floatingView;
    private TextView tvFloatingBall;
    private LinearLayout layoutFloatMenu;
    private TextView btnFloatStopAlarm;
    private TextView btnFloatStopMonitor;
    private TextView btnFloatToggleMask;
    private WindowManager.LayoutParams floatingParams;
    private boolean isMenuExpanded = false;
    private ActionCallback callback;

    private int lastX = 50;
    private int lastY = 200;
    private boolean isMaskMode = false;
    // 【新增】记住报警状态，重建视图时恢复
    private boolean isAlarming = false;

    public FloatingBallManager(Context context, ActionCallback callback) {
        this.context = context;
        this.callback = callback;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        if (floatingView != null) return;

        try {
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            floatingView = inflater.inflate(R.layout.float_ball, null);

            tvFloatingBall = floatingView.findViewById(R.id.tv_float_ball);
            layoutFloatMenu = floatingView.findViewById(R.id.layout_float_menu);
            btnFloatStopAlarm = floatingView.findViewById(R.id.btn_float_stop_alarm);
            btnFloatStopMonitor = floatingView.findViewById(R.id.btn_float_stop_monitor);
            btnFloatToggleMask = floatingView.findViewById(R.id.btn_float_toggle_mask);

            int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

            floatingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
            floatingParams.gravity = Gravity.TOP | Gravity.START;
            floatingParams.x = lastX;
            floatingParams.y = lastY;

            tvFloatingBall.setOnClickListener(v -> {
                isMenuExpanded = !isMenuExpanded;
                layoutFloatMenu.setVisibility(isMenuExpanded ? View.VISIBLE : View.GONE);
                if (floatingView != null && windowManager != null) {
                    windowManager.updateViewLayout(floatingView, floatingParams);
                }
            });

            tvFloatingBall.setOnTouchListener(new View.OnTouchListener() {
                    private int initialX, initialY;
                    private float initialTouchX, initialTouchY;
                    private boolean isDragging = false;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                initialX = floatingParams.x;
                                initialY = floatingParams.y;
                                initialTouchX = event.getRawX();
                                initialTouchY = event.getRawY();
                                isDragging = false;
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                if (Math.abs(event.getRawX() - initialTouchX) > 20
                                    || Math.abs(event.getRawY() - initialTouchY) > 20) {
                                    isDragging = true;
                                }
                                if (isDragging) {
                                    lastX = initialX + (int) (event.getRawX() - initialTouchX);
                                    lastY = initialY + (int) (event.getRawY() - initialTouchY);
                                    floatingParams.x = lastX;
                                    floatingParams.y = lastY;
                                    if (windowManager != null && floatingView != null) {
                                        windowManager.updateViewLayout(floatingView, floatingParams);
                                    }
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                                if (!isDragging) v.performClick();
                                return true;
                        }
                        return false;
                    }
                });

            btnFloatStopAlarm.setOnClickListener(v -> { if (callback != null) callback.onStopAlarm(); });
            btnFloatToggleMask.setOnClickListener(v -> { if (callback != null) callback.onToggleDarkMask(); });
            btnFloatStopMonitor.setOnClickListener(v -> { if (callback != null) callback.onStopMonitor(); });

            applyMaskModeVisual(isMaskMode);
            // 【新增】恢复报警状态（重建视图时不再丢失"停止报警"按钮）
            applyAlarmingVisual(isAlarming);

            floatingView.setAlpha(1f);
            windowManager.addView(floatingView, floatingParams);
        } catch (Exception e) {
            if (callback != null) callback.onLogMessage("添加悬浮窗失败: " + e.getMessage());
        }
    }

    public void hide() {
        if (windowManager != null && floatingView != null) {
            try { windowManager.removeView(floatingView); } catch (Exception ignored) {}
            floatingView = null;
        }
    }

    public void fadeOutThenRun(Runnable action) {
        if (floatingView == null) {
            if (action != null) action.run();
            return;
        }
        try {
            floatingView.setEnabled(false);
            floatingView.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> {
                if (action != null) action.run();
            }).start();
        } catch (Exception e) {
            if (action != null) action.run();
        }
    }

    public void fadeIn() {
        if (floatingView == null) return;
        try {
            floatingView.setAlpha(0f);
            floatingView.setEnabled(true);
            floatingView.animate().alpha(1f).setDuration(200).start();
        } catch (Exception ignored) {}
    }

    public void collapseMenu() {
        if (layoutFloatMenu != null && isMenuExpanded) {
            isMenuExpanded = false;
            layoutFloatMenu.setVisibility(View.GONE);
            if (windowManager != null && floatingView != null) {
                try { windowManager.updateViewLayout(floatingView, floatingParams); } catch (Exception ignored) {}
            }
        }
    }

    public void setDarkModeWithoutReAdd(boolean dark) {
        isMaskMode = dark;
        applyMaskModeVisual(dark);
    }

    private void applyMaskModeVisual(boolean maskOn) {
        if (tvFloatingBall == null) return;
        try {
            if (maskOn) {
                tvFloatingBall.setBackgroundResource(R.drawable.bg_float_ball_dark);
                tvFloatingBall.setTextColor(0xFFFFFFFF);
                if (btnFloatToggleMask != null) {
                    btnFloatToggleMask.setText("亮屏");
                }
            } else {
                tvFloatingBall.setBackgroundResource(R.drawable.bg_float_ball);
                tvFloatingBall.setTextColor(0xFFFFFFFF);
                if (btnFloatToggleMask != null) {
                    btnFloatToggleMask.setText("暗屏");
                }
            }
        } catch (Exception ignored) {}
    }

    // 【新增】不带动画地应用报警视觉状态
    private void applyAlarmingVisual(boolean alarming) {
        if (floatingView == null) return;
        try {
            if (alarming) {
                if (btnFloatStopAlarm != null) btnFloatStopAlarm.setVisibility(View.VISIBLE);
                if (layoutFloatMenu != null) {
                    layoutFloatMenu.setVisibility(View.VISIBLE);
                    isMenuExpanded = true;
                }
            } else {
                if (btnFloatStopAlarm != null) btnFloatStopAlarm.setVisibility(View.GONE);
                if (layoutFloatMenu != null) {
                    layoutFloatMenu.setVisibility(View.GONE);
                    isMenuExpanded = false;
                }
            }
        } catch (Exception ignored) {}
    }

    public void setAlarming(boolean alarming) {
        this.isAlarming = alarming;
        if (floatingView == null) return;

        floatingView.post(() -> {
            try {
                if (alarming) {
                    if (btnFloatStopAlarm != null) btnFloatStopAlarm.setVisibility(View.VISIBLE);
                    if (layoutFloatMenu != null) {
                        layoutFloatMenu.setVisibility(View.VISIBLE);
                        isMenuExpanded = true;
                    }
                    if (windowManager != null && floatingView != null) {
                        windowManager.updateViewLayout(floatingView, floatingParams);
                    }
                    pulseAnimation();
                } else {
                    if (btnFloatStopAlarm != null) btnFloatStopAlarm.setVisibility(View.GONE);
                    if (layoutFloatMenu != null) {
                        layoutFloatMenu.setVisibility(View.GONE);
                        isMenuExpanded = false;
                    }
                    if (windowManager != null && floatingView != null) {
                        windowManager.updateViewLayout(floatingView, floatingParams);
                    }
                }
            } catch (Exception e) {
                if (callback != null) callback.onLogMessage("更新悬浮球状态失败: " + e.getMessage());
            }
        });
    }

    private void pulseAnimation() {
        if (tvFloatingBall == null) return;
        try {
            ScaleAnimation scale = new ScaleAnimation(1.0f, 1.3f, 1.0f, 1.3f,
                                                      Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            scale.setDuration(300);
            scale.setRepeatCount(3);
            scale.setRepeatMode(Animation.REVERSE);
            tvFloatingBall.startAnimation(scale);
        } catch (Exception ignored) {}
    }
}
