package com.eraser2333.arkpaint.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.eraser2333.arkpaint.R;
import com.eraser2333.arkpaint.imaging.Palette;

import java.util.Arrays;
import java.util.Locale;

public final class PixelPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF gridBounds = new RectF();
    private int[] indices;
    private ValueAnimator scanAnimator;
    private float scanProgress = -1f;
    private boolean showNumbers = true;

    public PixelPreviewView(Context context) {
        this(context, null);
    }

    public PixelPreviewView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PixelPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    public void setPattern(@Nullable int[] paletteIndices) {
        if (paletteIndices != null && paletteIndices.length != 24 * 24) {
            throw new IllegalArgumentException("Pattern must contain 576 pixels");
        }
        indices = paletteIndices == null ? null : Arrays.copyOf(paletteIndices, paletteIndices.length);
        updateAnimation();
        invalidate();
    }

    public void setShowNumbers(boolean showNumbers) {
        this.showNumbers = showNumbers;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int panel = ContextCompat.getColor(getContext(), R.color.panel_raised);
        int grid = ContextCompat.getColor(getContext(), R.color.grid);
        int cyan = ContextCompat.getColor(getContext(), R.color.rhodes_cyan);
        int muted = ContextCompat.getColor(getContext(), R.color.muted);
        int paper = ContextCompat.getColor(getContext(), R.color.paper);

        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(panel);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 12f * density, 12f * density, paint);

        float topLabelSpace = 38f * density;
        float bottomLabelSpace = 24f * density;
        float horizontalSpace = 28f * density;
        float side = Math.min(
                getWidth() - horizontalSpace * 2,
                getHeight() - topLabelSpace - bottomLabelSpace
        );
        float left = (getWidth() - side) / 2f;
        float top = topLabelSpace;
        gridBounds.set(left, top, left + side, top + side);

        textPaint.setTextSize(10f * density);
        textPaint.setColor(muted);
        canvas.drawText("X 00                                      23", left, 20f * density, textPaint);
        canvas.drawText("Y 00", 7f * density, top + 9f * density, textPaint);
        canvas.drawText("23", 11f * density, top + side, textPaint);

        paint.setStyle(Paint.Style.FILL);
        if (indices == null) {
            paint.setColor(ContextCompat.getColor(getContext(), R.color.ink));
            canvas.drawRect(gridBounds, paint);
            textPaint.setColor(muted);
            textPaint.setTextSize(13f * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    getResources().getString(R.string.preview_empty),
                    gridBounds.centerX(),
                    gridBounds.centerY(),
                    textPaint
            );
            textPaint.setTextAlign(Paint.Align.LEFT);
        } else {
            float cell = side / 24f;
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(Math.min(cell * 0.42f, 10f * density));
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baselineOffset = -(metrics.ascent + metrics.descent) / 2f;
            for (int row = 0; row < 24; row++) {
                for (int column = 0; column < 24; column++) {
                    int paletteIndex = indices[row * 24 + column];
                    int color = Palette.COLORS[paletteIndex];
                    paint.setColor(color);
                    float cellLeft = left + column * cell;
                    float cellTop = top + row * cell;
                    canvas.drawRect(
                            cellLeft,
                            cellTop,
                            cellLeft + cell + 0.5f,
                            cellTop + cell + 0.5f,
                            paint
                    );
                    if (showNumbers && cell >= 11f * density) {
                        textPaint.setColor(contrastTextColor(color));
                        canvas.drawText(
                                Palette.number(paletteIndex),
                                cellLeft + cell / 2f,
                                cellTop + cell / 2f + baselineOffset,
                                textPaint
                        );
                    }
                }
            }
            textPaint.setTextAlign(Paint.Align.LEFT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(0.5f, density * 0.55f));
            paint.setColor(0x5532464C);
            for (int index = 1; index < 24; index++) {
                float offset = index * cell;
                canvas.drawLine(left + offset, top, left + offset, top + side, paint);
                canvas.drawLine(left, top + offset, left + side, top + offset, paint);
            }
            if (scanProgress >= 0f) {
                float scanY = top + side * scanProgress;
                paint.setStrokeWidth(1.5f * density);
                paint.setColor(0xCC2CC3CF);
                canvas.drawLine(left, scanY, left + side, scanY, paint);
            }
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(grid);
        canvas.drawRect(gridBounds, paint);
        drawCornerBrackets(canvas, gridBounds, cyan, density);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(indices == null ? muted : paper);
        textPaint.setTextSize(10f * density);
        String readout = indices == null
                ? "NO SIGNAL"
                : String.format(Locale.ROOT, "%02d COLORS / READY", uniqueColors());
        canvas.drawText(readout, gridBounds.right, getHeight() - 8f * density, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCornerBrackets(Canvas canvas, RectF rect, int color, float density) {
        float length = 14f * density;
        float offset = 5f * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(color);
        canvas.drawLine(rect.left - offset, rect.top - offset, rect.left + length, rect.top - offset, paint);
        canvas.drawLine(rect.left - offset, rect.top - offset, rect.left - offset, rect.top + length, paint);
        canvas.drawLine(rect.right + offset, rect.top - offset, rect.right - length, rect.top - offset, paint);
        canvas.drawLine(rect.right + offset, rect.top - offset, rect.right + offset, rect.top + length, paint);
        canvas.drawLine(rect.left - offset, rect.bottom + offset, rect.left + length, rect.bottom + offset, paint);
        canvas.drawLine(rect.left - offset, rect.bottom + offset, rect.left - offset, rect.bottom - length, paint);
        canvas.drawLine(rect.right + offset, rect.bottom + offset, rect.right - length, rect.bottom + offset, paint);
        canvas.drawLine(rect.right + offset, rect.bottom + offset, rect.right + offset, rect.bottom - length, paint);
    }

    private int uniqueColors() {
        boolean[] used = new boolean[Palette.size()];
        int count = 0;
        for (int index : indices) {
            if (!used[index]) {
                used[index] = true;
                count++;
            }
        }
        return count;
    }

    private static int contrastTextColor(int color) {
        double luminance = 0.2126 * Color.red(color)
                + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color);
        return luminance > 155.0 ? 0xD9000000 : 0xEFFFFFFF;
    }

    private void updateAnimation() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
            scanAnimator = null;
        }
        scanProgress = -1f;
        if (indices == null || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        scanAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanAnimator.setDuration(2400L);
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.setRepeatMode(ValueAnimator.RESTART);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.addUpdateListener(animation -> {
            scanProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        scanAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (scanAnimator != null) {
            scanAnimator.cancel();
            scanAnimator = null;
        }
        super.onDetachedFromWindow();
    }
}
