package com.sgggzg.qmzg;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

public class DarkMaskManager {

    private static final String TAG = "DarkMask";

    public interface Callback {
        void onRequestClose();
    }

    private static final long LONG_PRESS_MS = 2000L;
    private static final long HINT_LINGER_MS = 3000L;
    private static final long ANIM_MS = 400L;
    private static final float DIM_BRIGHTNESS = 0.01f;

    private Context context;
    private WindowManager windowManager;
    private MaskView maskView;
    private WindowManager.LayoutParams maskParams;
    private float maskAlpha = 0.95f;

    // 【C 模块】四边
    private float topRatio = 0.66f;
    private float bottomRatio = 0.81f;
    private float leftRatio = 0f;
    private float rightRatio = 1f;

    private boolean isShowing = false;
    private boolean isAnimating = false;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Callback callback;

    private boolean isTouching = false;
    private long touchStartTime = 0;
    private Runnable longPressChecker;
    private Runnable dimRunnable;
    private ValueAnimator brightnessAnimator;

    private float initialBrightness = 0.5f;

    public DarkMaskManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void setCallback(Callback cb) { this.callback = cb; }

    public void setMaskAlpha(float alpha) {
        this.maskAlpha = Math.max(0f, Math.min(1f, alpha));
        if (maskView != null) maskView.invalidate();
    }

    // 【C 模块】4 参数版本
    public void setRegion(float top, float bottom, float left, float right) {
        this.topRatio = top;
        this.bottomRatio = bottom;
        this.leftRatio = left;
        this.rightRatio = right;
        if (maskView != null) maskView.invalidate();
    }

    public boolean isShowing() { return isShowing; }

    public void show() {
        if (isShowing || maskView != null || isAnimating) return;
        initialBrightness = readSystemBrightness();
        isShowing = true;
        isAnimating = true;
        mainShow();
    }

    private float readSystemBrightness() {
        try {
            int b = Settings.System.getInt(context.getContentResolver(),
                                           Settings.System.SCREEN_BRIGHTNESS, 128);
            float normalized = b / 255f;
            if (normalized < 0.02f) normalized = 0.02f;
            if (normalized > 1.0f) normalized = 1.0f;
            return normalized;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    private void mainShow() {
        handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        maskView = new MaskView(context);
                        maskView.setAlpha(0f);

                        DisplayMetrics metrics = new DisplayMetrics();
                        windowManager.getDefaultDisplay().getRealMetrics(metrics);
                        int screenW = metrics.widthPixels;
                        int screenH = metrics.heightPixels;
                        Log.i(TAG, "遮罩尺寸: " + screenW + "x" + screenH);

                        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                            WindowManager.LayoutParams.TYPE_PHONE;

                        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

                        maskParams = new WindowManager.LayoutParams(
                            screenW,
                            screenH,
                            layoutFlag, flags, PixelFormat.TRANSLUCENT);
                        maskParams.gravity = Gravity.TOP | Gravity.START;
                        maskParams.x = 0;
                        maskParams.y = 0;
                        maskParams.screenBrightness = DIM_BRIGHTNESS;

                        maskView.setOnTouchListener(new View.OnTouchListener() {
                                @Override
                                public boolean onTouch(View v, MotionEvent event) {
                                    return handleMaskTouch(event);
                                }
                            });

                        windowManager.addView(maskView, maskParams);

                        maskView.animate().alpha(1f).setDuration(ANIM_MS)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    isAnimating = false;
                                }
                            }).start();
                    } catch (Exception e) {
                        Log.e(TAG, "显示遮罩失败: " + e.getMessage());
                        isShowing = false;
                        isAnimating = false;
                        maskView = null;
                    }
                }
            });
    }

    public void hide() {
        if (!isShowing || maskView == null || isAnimating) return;
        isAnimating = true;

        if (longPressChecker != null) handler.removeCallbacks(longPressChecker);
        if (dimRunnable != null) handler.removeCallbacks(dimRunnable);
        if (brightnessAnimator != null) brightnessAnimator.cancel();
        isTouching = false;

        final MaskView viewToHide = maskView;

        handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        try { viewToHide.clearHintImmediate(); } catch (Exception ignored) {}

                        if (maskParams != null) {
                            final float startBrightness = maskParams.screenBrightness < 0
                                ? DIM_BRIGHTNESS
                                : maskParams.screenBrightness;
                            brightnessAnimator = ValueAnimator.ofFloat(startBrightness, initialBrightness);
                            brightnessAnimator.setDuration(ANIM_MS);
                            brightnessAnimator.setInterpolator(new DecelerateInterpolator());
                            brightnessAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                                    @Override
                                    public void onAnimationUpdate(ValueAnimator anim) {
                                        if (maskParams == null || viewToHide == null || windowManager == null) return;
                                        float val = (float) anim.getAnimatedValue();
                                        maskParams.screenBrightness = val;
                                        try { windowManager.updateViewLayout(viewToHide, maskParams); } catch (Exception ignored) {}
                                    }
                                });
                            brightnessAnimator.start();
                        }

                        viewToHide.animate().alpha(0f).setDuration(ANIM_MS)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        if (maskParams != null && windowManager != null) {
                                            maskParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
                                            try { windowManager.updateViewLayout(viewToHide, maskParams); } catch (Exception ignored) {}
                                        }
                                        if (windowManager != null) {
                                            windowManager.removeView(viewToHide);
                                        }
                                    } catch (Exception ignored) {}
                                    if (maskView == viewToHide) maskView = null;
                                    isShowing = false;
                                    isAnimating = false;
                                }
                            }).start();
                    } catch (Exception e) {
                        try { if (windowManager != null) windowManager.removeView(viewToHide); } catch (Exception ignored) {}
                        if (maskView == viewToHide) maskView = null;
                        isShowing = false;
                        isAnimating = false;
                    }
                }
            });
    }

    public void release() {
        if (longPressChecker != null) handler.removeCallbacks(longPressChecker);
        if (dimRunnable != null) handler.removeCallbacks(dimRunnable);
        if (brightnessAnimator != null) brightnessAnimator.cancel();
        if (maskView != null) {
            try { if (windowManager != null) windowManager.removeView(maskView); } catch (Exception ignored) {}
            maskView = null;
        }
        isShowing = false;
        isAnimating = false;
    }

    private boolean handleMaskTouch(MotionEvent event) {
        if (isAnimating && !isShowing) return true;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            isTouching = true;
            touchStartTime = System.currentTimeMillis();
            cancelDimRunnable();
            animateBrightness(initialBrightness);
            if (maskView != null) maskView.setHintVisible(true);
            startLongPressChecker();
            return true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isTouching = false;
            if (longPressChecker != null) handler.removeCallbacks(longPressChecker);
            scheduleDimBack();
            return true;
        }
        return true;
    }

    private void startLongPressChecker() {
        if (longPressChecker != null) handler.removeCallbacks(longPressChecker);
        longPressChecker = new Runnable() {
            @Override
            public void run() {
                if (!isTouching) return;
                long held = System.currentTimeMillis() - touchStartTime;
                if (held >= LONG_PRESS_MS) {
                    if (callback != null) callback.onRequestClose();
                    return;
                }
                handler.postDelayed(this, 50);
            }
        };
        handler.postDelayed(longPressChecker, 50);
    }

    private void scheduleDimBack() {
        cancelDimRunnable();
        dimRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTouching) return;
                animateBrightness(DIM_BRIGHTNESS);
                if (maskView != null) maskView.setHintVisible(false);
            }
        };
        handler.postDelayed(dimRunnable, HINT_LINGER_MS);
    }

    private void cancelDimRunnable() {
        if (dimRunnable != null) {
            handler.removeCallbacks(dimRunnable);
            dimRunnable = null;
        }
    }

    private void animateBrightness(float target) {
        if (maskParams == null || maskView == null || windowManager == null) return;
        if (brightnessAnimator != null) brightnessAnimator.cancel();

        final float start = maskParams.screenBrightness < 0
            ? DIM_BRIGHTNESS
            : maskParams.screenBrightness;

        if (Math.abs(start - target) < 0.02f) {
            maskParams.screenBrightness = target;
            try { windowManager.updateViewLayout(maskView, maskParams); } catch (Exception ignored) {}
            return;
        }

        brightnessAnimator = ValueAnimator.ofFloat(start, target);
        brightnessAnimator.setDuration(ANIM_MS);
        brightnessAnimator.setInterpolator(new DecelerateInterpolator());
        brightnessAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator anim) {
                    if (maskParams == null || maskView == null || windowManager == null) return;
                    float val = (float) anim.getAnimatedValue();
                    maskParams.screenBrightness = val;
                    try { windowManager.updateViewLayout(maskView, maskParams); } catch (Exception ignored) {}
                }
            });
        brightnessAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    forceApplyBrightness(target);
                }
            });
        brightnessAnimator.start();
    }

    private void forceApplyBrightness(final float target) {
        if (maskParams == null || maskView == null || windowManager == null) return;
        maskParams.screenBrightness = target;
        try { windowManager.updateViewLayout(maskView, maskParams); } catch (Exception ignored) {}

        handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (maskParams == null || maskView == null || windowManager == null) return;
                    maskParams.screenBrightness = target;
                    try { windowManager.updateViewLayout(maskView, maskParams); } catch (Exception ignored) {}
                }
            }, 60);
    }

    private class MaskView extends View {
        private Paint blackPaint = new Paint();
        private Paint semiPaint = new Paint();
        private Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float hintAlpha = 0f;
        private ValueAnimator hintAnimator;

        public MaskView(Context ctx) {
            super(ctx);
            blackPaint.setColor(0xFF000000);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextSize(56f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
            textPaint.setShadowLayer(6f, 0f, 2f, 0xFF000000);
        }

        public void setHintVisible(boolean visible) {
            float target = visible ? 1f : 0f;
            if (hintAnimator != null) hintAnimator.cancel();
            hintAnimator = ValueAnimator.ofFloat(hintAlpha, target);
            hintAnimator.setDuration(ANIM_MS);
            hintAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator anim) {
                        hintAlpha = (float) anim.getAnimatedValue();
                        invalidate();
                    }
                });
            hintAnimator.start();
        }

        public void clearHintImmediate() {
            if (hintAnimator != null) {
                hintAnimator.cancel();
                hintAnimator = null;
            }
            hintAlpha = 0f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            Log.i(TAG, "遮罩View实际尺寸: " + w + "x" + h);

            int top = (int) (h * topRatio);
            int bottom = (int) (h * bottomRatio);
            int left = (int) (w * leftRatio);
            int right = (int) (w * rightRatio);
            if (bottom <= top || right <= left) return;

            // 1. 上下纯黑
            canvas.drawRect(0, 0, w, top, blackPaint);
            canvas.drawRect(0, bottom, w, h, blackPaint);

            // 2. 中间左右两侧纯黑
            canvas.drawRect(0, top, left, bottom, blackPaint);
            canvas.drawRect(right, top, w, bottom, blackPaint);

            // 3. 识别区半透明黑
            int alphaInt = (int) (maskAlpha * 255);
            semiPaint.setColor((alphaInt << 24));
            canvas.drawRect(left, top, right, bottom, semiPaint);

            if (hintAlpha > 0.01f) {
                textPaint.setAlpha((int) (hintAlpha * 255));
                canvas.drawText("长按 2 秒关闭遮罩", w / 2f, h / 2f, textPaint);
            }
        }
    }
}
