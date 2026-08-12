package com.eraser2333.arkpaint.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.eraser2333.arkpaint.R;
import com.eraser2333.arkpaint.imaging.Palette;

import java.util.Arrays;
import java.util.Locale;

public final class PaletteUsageView extends View {
    private static final int MAX_COLUMNS = 4;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final int[] counts = new int[Palette.size()];
    private int usedColorCount;

    public PaletteUsageView(Context context) {
        this(context, null);
    }

    public PaletteUsageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PaletteUsageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        setContentDescription(getResources().getString(R.string.palette_usage_description));
    }

    public void setPattern(@Nullable int[] paletteIndices) {
        Arrays.fill(counts, 0);
        usedColorCount = 0;
        if (paletteIndices != null) {
            if (paletteIndices.length != 24 * 24) {
                throw new IllegalArgumentException("Pattern must contain 576 pixels");
            }
            for (int paletteIndex : paletteIndices) {
                Palette.requireValidIndex(paletteIndex);
                if (counts[paletteIndex] == 0) {
                    usedColorCount++;
                }
                counts[paletteIndex]++;
            }
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = MeasureSpec.getSize(widthMeasureSpec);
        int columns = columnsForWidth(availableWidth);
        int rows = Math.max(1, (usedColorCount + columns - 1) / columns);
        int desiredHeight = rows * dp(44) + Math.max(0, rows - 1) * dp(6);
        setMeasuredDimension(
                resolveSize(dp(360), widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (usedColorCount == 0) {
            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.muted));
            textPaint.setTextSize(sp(12));
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(
                    getResources().getString(R.string.palette_usage_empty),
                    getWidth() / 2f,
                    getHeight() / 2f - (textPaint.ascent() + textPaint.descent()) / 2f,
                    textPaint
            );
            return;
        }

        float gap = dp(6);
        int columns = columnsForWidth(getWidth());
        float itemWidth = (getWidth() - gap * (columns - 1)) / columns;
        float itemHeight = dp(44);
        int displayed = 0;
        textPaint.setTextSize(sp(11));
        textPaint.setTextAlign(Paint.Align.LEFT);
        for (int paletteIndex = 0; paletteIndex < counts.length; paletteIndex++) {
            if (counts[paletteIndex] == 0) {
                continue;
            }
            int row = displayed / columns;
            int column = displayed % columns;
            float left = column * (itemWidth + gap);
            float top = row * (itemHeight + gap);
            bounds.set(left, top, left + itemWidth, top + itemHeight);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ContextCompat.getColor(getContext(), R.color.surface_container_high));
            canvas.drawRoundRect(bounds, dp(10), dp(10), paint);

            float swatch = dp(24);
            paint.setColor(Palette.COLORS[paletteIndex]);
            canvas.drawRoundRect(
                    left + dp(8),
                    top + dp(10),
                    left + dp(8) + swatch,
                    top + dp(10) + swatch,
                    dp(6),
                    dp(6),
                    paint
            );

            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.paper));
            String label = String.format(
                    Locale.ROOT,
                    "%s ×%d",
                    Palette.number(paletteIndex),
                    counts[paletteIndex]
            );
            canvas.drawText(
                    label,
                    left + dp(40),
                    top + itemHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f,
                    textPaint
            );
            displayed++;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int columnsForWidth(int width) {
        return width < dp(480) ? 2 : MAX_COLUMNS;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
