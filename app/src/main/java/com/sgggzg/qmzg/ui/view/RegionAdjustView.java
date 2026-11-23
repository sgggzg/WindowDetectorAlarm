package com.sgggzg.qmzg.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class RegionAdjustView extends View {

    private Paint paint;
    private RectF rect = new RectF();
    private float topRatio = 0.3f;
    private float bottomRatio = 0.7f;
    private float leftRatio = 0f;
    private float rightRatio = 1f;

    private boolean isDragging = false;
    private int dragEdge = -1; // 0:top, 1:bottom, 2:left, 3:right

    // 【新增】图片实际显示矩形（fitCenter 后的像素范围）
    private RectF imageRect = null;

    public interface OnRegionChangedListener {
        void onRegionChanged(float top, float bottom);
    }
    private OnRegionChangedListener listener;

    public RegionAdjustView(Context context) {
        super(context);
        init();
    }
    public RegionAdjustView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(Color.parseColor("#E94560"));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8);
        paint.setAlpha(200);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setRegion(float top, float bottom) {
        this.topRatio = top;
        this.bottomRatio = bottom;
        invalidate();
    }

    public void setRegionLR(float left, float right) {
        this.leftRatio = left;
        this.rightRatio = right;
        invalidate();
    }

    // 【新增】设置图片实际显示区域，坐标为空时回退到整个 View
    public void setImageRect(RectF r) {
        this.imageRect = r;
        invalidate();
    }

    public void clearImageRect() {
        this.imageRect = null;
        invalidate();
    }

    // 【新增】获取有效坐标系
    private RectF getEffectiveRect() {
        if (imageRect != null && imageRect.width() > 0 && imageRect.height() > 0) {
            return imageRect;
        }
        return new RectF(0, 0, getWidth(), getHeight());
    }

    public void setOnRegionChangedListener(OnRegionChangedListener l) {
        this.listener = l;
    }

    public float getTopRatio() { return topRatio; }
    public float getBottomRatio() { return bottomRatio; }
    public float getLeftRatio() { return leftRatio; }
    public float getRightRatio() { return rightRatio; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        RectF base = getEffectiveRect();
        float bw = base.width();
        float bh = base.height();
        if (bw <= 0 || bh <= 0) return;

        float left = base.left + leftRatio * bw;
        float right = base.left + rightRatio * bw;
        float top = base.top + topRatio * bh;
        float bottom = base.top + bottomRatio * bh;

        rect.set(left, top, right, bottom);
        canvas.drawRect(rect, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return super.onTouchEvent(event);

        RectF base = getEffectiveRect();
        float bw = base.width();
        float bh = base.height();
        if (bw <= 0 || bh <= 0) return super.onTouchEvent(event);

        // 用有效坐标系计算边界位置
        float left = base.left + leftRatio * bw;
        float right = base.left + rightRatio * bw;
        float top = base.top + topRatio * bh;
        float bottom = base.top + bottomRatio * bh;
        float TOUCH = 40f;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                    float dTop = Math.abs(y - top);
                    float dBottom = Math.abs(y - bottom);
                    float dLeft = Math.abs(x - left);
                    float dRight = Math.abs(x - right);
                    float min = Math.min(Math.min(dTop, dBottom), Math.min(dLeft, dRight));
                    if (min > TOUCH) {
                        isDragging = false;
                        return true;
                    }
                    if (min == dTop) dragEdge = 0;
                    else if (min == dBottom) dragEdge = 1;
                    else if (min == dLeft) dragEdge = 2;
                    else dragEdge = 3;
                    isDragging = true;
                    return true;
                }
            case MotionEvent.ACTION_MOVE: {
                    if (!isDragging) return true;
                    // 用有效坐标系转换触摸坐标
                    float nx = (x - base.left) / bw;
                    float ny = (y - base.top) / bh;

                    if (dragEdge == 0) {
                        float nt = ny;
                        nt = Math.max(0, Math.min(nt, bottomRatio - 0.05f));
                        topRatio = nt;
                    } else if (dragEdge == 1) {
                        float nb = ny;
                        nb = Math.min(1, Math.max(nb, topRatio + 0.05f));
                        bottomRatio = nb;
                    } else if (dragEdge == 2) {
                        float nl = nx;
                        nl = Math.max(0, Math.min(nl, rightRatio - 0.05f));
                        leftRatio = nl;
                    } else if (dragEdge == 3) {
                        float nr = nx;
                        nr = Math.min(1, Math.max(nr, leftRatio + 0.05f));
                        rightRatio = nr;
                    }
                    invalidate();
                    if (listener != null) listener.onRegionChanged(topRatio, bottomRatio);
                    return true;
                }
            case MotionEvent.ACTION_UP:
                isDragging = false;
                dragEdge = -1;
                return true;
        }
        return super.onTouchEvent(event);
    }
}
