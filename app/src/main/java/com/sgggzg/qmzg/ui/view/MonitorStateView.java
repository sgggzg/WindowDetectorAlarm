package com.sgggzg.qmzg.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.sgggzg.qmzg.core.MonitorState;

/**
 * 主页状态/暂停按钮：
 * 灰色圆 = 已停止；双竖线 = 运行中（点击暂停）；
 * 向右三角 = 已暂停（点击继续）；报警时三角点击停止报警并继续。
 */
public class MonitorStateView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private MonitorState.Phase phase = MonitorState.Phase.STOPPED;

    public MonitorStateView(Context context) {
        super(context);
        init();
    }

    public MonitorStateView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MonitorStateView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        paint.setStyle(Paint.Style.FILL);
        updateDescription();
    }

    public void setPhase(MonitorState.Phase phase) {
        this.phase = phase == null ? MonitorState.Phase.STOPPED : phase;
        updateDescription();
        invalidate();
    }

    public MonitorState.Phase getPhase() {
        return phase;
    }

    private void updateDescription() {
        switch (phase) {
            case RUNNING:
                setContentDescription("运行中，点击暂停监控");
                break;
            case PAUSING:
                setContentDescription("正在暂停");
                break;
            case PAUSED:
                setContentDescription("已暂停，点击继续监控");
                break;
            case ALARM:
                setContentDescription("报警中，点击停止报警并继续监控");
                break;
            default:
                setContentDescription("监控已停止");
                break;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float cx = width / 2f;
        float cy = height / 2f;
        float unit = Math.min(width, height);
        paint.setPathEffect(null);

        switch (phase) {
            case RUNNING:
                drawBars(canvas, cx, cy, unit, 1f, 0xFF00E676);
                break;
            case PAUSING:
                drawBars(canvas, cx, cy, unit, 0.45f, 0xFF00E676);
                break;
            case PAUSED:
            case ALARM:
                drawTriangle(canvas, cx, cy, unit,
                        phase == MonitorState.Phase.ALARM ? 0xFFE94560 : 0xFFFFB300);
                break;
            default:
                paint.setColor(0xFF777777);
                paint.setAlpha(255);
                canvas.drawCircle(cx, cy, unit * 0.22f, paint);
                break;
        }
    }

    private void drawBars(Canvas canvas, float cx, float cy, float unit, float alpha, int color) {
        paint.setColor(color);
        paint.setAlpha((int) (255 * alpha));
        float barWidth = unit * 0.16f;
        float barHeight = unit * 0.55f;
        float gap = unit * 0.13f;
        float left = cx - gap / 2f - barWidth;
        float top = cy - barHeight / 2f;
        rect.set(left, top, left + barWidth, top + barHeight);
        canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
        rect.set(cx + gap / 2f, top, cx + gap / 2f + barWidth, top + barHeight);
        canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint);
    }

    private void drawTriangle(Canvas canvas, float cx, float cy, float unit, int color) {
        paint.setColor(color);
        paint.setAlpha(255);
        paint.setPathEffect(new CornerPathEffect(unit * 0.11f));
        float halfHeight = unit * 0.29f;
        float left = cx - unit * 0.21f;
        float right = cx + unit * 0.28f;
        path.reset();
        path.moveTo(left, cy - halfHeight);
        path.lineTo(left, cy + halfHeight);
        path.lineTo(right, cy);
        path.close();
        canvas.drawPath(path, paint);
        paint.setPathEffect(null);
    }
}
