package com.sgggzg.qmzg.service.overlay;

import com.sgggzg.qmzg.R;
import com.sgggzg.qmzg.core.MonitorState;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingBallManager {

    public interface ActionCallback {
        void onStopMonitor();
        void onPauseResume();
        void onToggleDarkMask();
        void onLogMessage(String msg);
    }

    private Context context;
    private WindowManager windowManager;
    private View floatingView;
    private TextView tvFloatingBall;
    private LinearLayout layoutFloatMenu;
    private TextView btnFloatPause;
    private TextView btnFloatStopMonitor;
    private TextView btnFloatToggleMask;
    private WindowManager.LayoutParams floatingParams;
    private boolean isMenuExpanded = false;
    private boolean attached = false;
    private ActionCallback callback;

    private int lastX = 50;
    private int lastY = 200;
    private boolean isMaskMode = false;
    private MonitorState.Phase monitorPhase = MonitorState.Phase.STOPPED;

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
            btnFloatPause = floatingView.findViewById(R.id.btn_float_pause);
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
                if (isMenuExpanded) {
                    expandMenu();
                } else {
                    collapseMenuAnimated();
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

            btnFloatPause.setOnClickListener(v -> {
                if (callback != null && btnFloatPause.isEnabled()) {
                    callback.onPauseResume();
                }
            });
            btnFloatToggleMask.setOnClickListener(v -> { if (callback != null) callback.onToggleDarkMask(); });
            btnFloatStopMonitor.setOnClickListener(v -> { if (callback != null) callback.onStopMonitor(); });

            applyMaskModeVisual(isMaskMode);
            setMonitorPhase(monitorPhase);

            floatingView.setAlpha(0f);
            floatingView.setScaleX(0.62f);
            floatingView.setScaleY(0.62f);
            windowManager.addView(floatingView, floatingParams);
            attached = true;
            if (isMenuExpanded) {
                expandMenu();
            }
            floatingView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(new OvershootInterpolator(0.9f))
                    .start();
        } catch (Exception e) {
            if (callback != null) callback.onLogMessage("添加悬浮窗失败: " + e.getMessage());
        }
    }

    public void hide() {
        if (windowManager != null && floatingView != null) {
            try { windowManager.removeView(floatingView); } catch (Exception ignored) {}
            floatingView = null;
            attached = false;
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
            collapseMenuAnimated();
        }
    }

    private void expandMenu() {
        if (layoutFloatMenu == null) {
            return;
        }
        layoutFloatMenu.animate().cancel();
        layoutFloatMenu.setVisibility(View.VISIBLE);
        if (!attached || floatingView == null) {
            layoutFloatMenu.setAlpha(1f);
            layoutFloatMenu.setScaleX(1f);
            layoutFloatMenu.setScaleY(1f);
            layoutFloatMenu.setTranslationX(0f);
            return;
        }
        layoutFloatMenu.setAlpha(0f);
        layoutFloatMenu.setScaleX(0.86f);
        layoutFloatMenu.setScaleY(0.86f);
        layoutFloatMenu.setTranslationX(-10f * context.getResources().getDisplayMetrics().density);
        updateWindowLayout();
        layoutFloatMenu.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .setDuration(190)
                .setInterpolator(new OvershootInterpolator(0.7f))
                .start();
    }

    private void collapseMenuAnimated() {
        if (layoutFloatMenu == null) {
            return;
        }
        layoutFloatMenu.animate().cancel();
        if (!attached || floatingView == null) {
            layoutFloatMenu.setVisibility(View.GONE);
            return;
        }
        layoutFloatMenu.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .translationX(-8f * context.getResources().getDisplayMetrics().density)
                .setDuration(130)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (!isMenuExpanded && layoutFloatMenu != null) {
                        layoutFloatMenu.setVisibility(View.GONE);
                        layoutFloatMenu.setAlpha(1f);
                        layoutFloatMenu.setScaleX(1f);
                        layoutFloatMenu.setScaleY(1f);
                        layoutFloatMenu.setTranslationX(0f);
                        updateWindowLayout();
                    }
                })
                .start();
    }

    private void updateWindowLayout() {
        if (windowManager != null && floatingView != null) {
            try {
                windowManager.updateViewLayout(floatingView, floatingParams);
            } catch (Exception ignored) {
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

    public void setMonitorPhase(MonitorState.Phase phase) {
        this.monitorPhase = phase == null ? MonitorState.Phase.STOPPED : phase;
        if (btnFloatPause == null) {
            return;
        }
        String label;
        boolean enabled = true;
        switch (monitorPhase) {
            case RUNNING:
                label = "暂停监控";
                break;
            case PAUSING:
                label = "正在暂停";
                enabled = false;
                break;
            case PAUSED:
                label = "继续监控";
                break;
            case ALARM:
                label = "停止报警";
                break;
            default:
                label = "暂停监控";
                break;
        }
        btnFloatPause.setText(label);
        btnFloatPause.setEnabled(enabled);
        btnFloatPause.setAlpha(enabled ? 1f : 0.55f);
        if (monitorPhase == MonitorState.Phase.ALARM && layoutFloatMenu != null
                && (!isMenuExpanded || layoutFloatMenu.getVisibility() != View.VISIBLE)) {
            isMenuExpanded = true;
            expandMenu();
        }
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
