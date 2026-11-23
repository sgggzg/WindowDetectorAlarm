package com.sgggzg.qmzg.ui.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

/**
 * 抽屉式折叠容器：自身高度从 0 到内容高度，子内容同时从上往下滑入。
 * 收起时高度逐渐收回，子内容向上推回，外层卡片不会瞬间变大变小。
 */
public class ExpandableLayout extends LinearLayout {

    private static final long DURATION = 230L;

    private ValueAnimator animator;
    private float progress;
    private boolean expanded;
    private int fullHeight;

    public ExpandableLayout(Context context) {
        super(context);
        init();
    }

    public ExpandableLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ExpandableLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        setClipChildren(true);
        setClipToPadding(true);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded, boolean animate) {
        this.expanded = expanded;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        float target = expanded ? 1f : 0f;
        if (!animate || fullHeight <= 0) {
            progress = target;
            requestLayout();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(DURATION);
        animator.setInterpolator(new DecelerateInterpolator(1.25f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            requestLayout();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                progress = target;
                animator = null;
                requestLayout();
            }
        });
        animator.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        fullHeight = getMeasuredHeight();
        int targetHeight = Math.round(fullHeight * progress);
        setMeasuredDimension(getMeasuredWidth(), Math.max(0, targetHeight));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        applyChildTranslation();
    }

    private void applyChildTranslation() {
        float offset = (progress - 1f) * fullHeight;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setTranslationY(offset);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return progress > 0.95f && super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
