package com.sgggzg.qmzg.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatSeekBar;

public class MarkedSeekBar extends AppCompatSeekBar {

    private Paint markPaint;
    private float markFraction = 90f / 99f;

    public MarkedSeekBar(Context context) {
        super(context);
        init();
    }

    public MarkedSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarkedSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setColor(0xFFFF5252);
    }

    public void setMarkFraction(float fraction) {
        this.markFraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        if (w <= 0) return;
        float x = getPaddingLeft() + w * markFraction;
        float y = getHeight() / 2f;
        float r = getResources().getDisplayMetrics().density * 3f;
        canvas.drawCircle(x, y, r, markPaint);
    }
}
