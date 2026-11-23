package com.sgggzg.qmzg.ui.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.sgggzg.qmzg.ocr.OcrChar;
import com.sgggzg.qmzg.ocr.OcrLine;
import com.sgggzg.qmzg.ocr.OcrResult;

/**
 * OCR 叠加显示：先给原图盖一层半透明灰影，再按 OCR 框的位置和行高绘制可读文字。
 */
public class OcrOverlayView extends View {

    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint charPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private OcrResult result;
    private int imageWidth;
    private int imageHeight;

    public OcrOverlayView(Context context) {
        super(context);
        init();
    }

    public OcrOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OcrOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;

        shadePaint.setStyle(Paint.Style.FILL);
        shadePaint.setColor(0xAA303030);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1.5f, 1.5f * density));
        linePaint.setColor(0xFF00E676);

        charPaint.setStyle(Paint.Style.STROKE);
        charPaint.setStrokeWidth(Math.max(1f, density));
        charPaint.setColor(0x6640C4FF);

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setShadowLayer(2f * density, 0f, 1f * density, Color.BLACK);
    }

    public void setResult(OcrResult result) {
        this.result = result;
        invalidate();
    }

    public void setImageSize(int width, int height) {
        this.imageWidth = width;
        this.imageHeight = height;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (result == null || result.isEmpty() || imageWidth <= 0 || imageHeight <= 0) {
            return;
        }
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        float scale = Math.min(viewWidth / (float) imageWidth, viewHeight / (float) imageHeight);
        float offsetX = (viewWidth - imageWidth * scale) / 2f;
        float offsetY = (viewHeight - imageHeight * scale) / 2f;

        // 半透明灰影覆盖实际图片区域，让 OCR 文字从背景中跳出来
        canvas.drawRect(offsetX, offsetY,
                offsetX + imageWidth * scale,
                offsetY + imageHeight * scale,
                shadePaint);

        for (OcrLine line : result.getLines()) {
            if (line.bounds == null) {
                continue;
            }
            map(line.bounds, scale, offsetX, offsetY, rect);
            canvas.drawRect(rect, linePaint);

            for (OcrChar ch : line.getChars()) {
                if (ch.bounds == null) {
                    continue;
                }
                map(ch.bounds, scale, offsetX, offsetY, rect);
                canvas.drawRect(rect, charPaint);
            }

            String text = line.text == null ? "" : line.text.replace('\n', ' ');
            if (text.isEmpty()) {
                continue;
            }
            map(line.bounds, scale, offsetX, offsetY, rect);
            float desired = rect.height() * 0.85f;
            float byWidth = rect.width() / Math.max(1, text.length()) * 0.95f;
            float textSize = Math.max(8f, Math.min(desired, byWidth));
            textPaint.setTextSize(textSize);

            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = rect.top - metrics.ascent + 1f;
            if (baseline > rect.bottom) {
                baseline = rect.top + textSize;
            }
            int save = canvas.save();
            canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom);
            canvas.drawText(text, rect.left + 2f, baseline, textPaint);
            canvas.restoreToCount(save);
        }
    }

    private void map(android.graphics.Rect source, float scale, float offsetX, float offsetY,
                     RectF out) {
        out.set(offsetX + source.left * scale,
                offsetY + source.top * scale,
                offsetX + source.right * scale,
                offsetY + source.bottom * scale);
    }
}
