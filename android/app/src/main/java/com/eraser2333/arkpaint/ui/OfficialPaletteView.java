package com.eraser2333.arkpaint.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.eraser2333.arkpaint.R;
import com.eraser2333.arkpaint.imaging.Palette;

public final class OfficialPaletteView extends View {
    public interface OnColorSelectedListener {
        void onColorSelected(int paletteIndex);
    }

    private static final int COLUMNS = 4;
    private static final int ROWS = 10;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cellBounds = new RectF();
    private final RectF selectionBounds = new RectF();
    private OnColorSelectedListener listener;
    private int selectedPaletteIndex;

    public OfficialPaletteView(Context context) {
        this(context, null);
    }

    public OfficialPaletteView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OfficialPaletteView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        setFocusable(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
        ));
        setContentDescription(getResources().getString(R.string.official_palette_description));
    }

    public void setOnColorSelectedListener(@Nullable OnColorSelectedListener listener) {
        this.listener = listener;
    }

    public void setSelectedPaletteIndex(int paletteIndex) {
        Palette.requireValidIndex(paletteIndex);
        selectedPaletteIndex = paletteIndex;
        setContentDescription(getResources().getString(
                R.string.palette_selected_accessibility,
                Palette.number(paletteIndex),
                Palette.hex(paletteIndex)
        ));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = dp(360);
        int desiredHeight = dp(530);
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float gap = dp(4);
        float cellWidth = (getWidth() - gap * (COLUMNS - 1)) / COLUMNS;
        float cellHeight = (getHeight() - gap * (ROWS - 1)) / ROWS;
        textPaint.setTextSize(Math.min(sp(13), cellHeight * 0.34f));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baselineOffset = -(metrics.ascent + metrics.descent) / 2f;

        for (int index = 0; index < Palette.size(); index++) {
            int row = index / COLUMNS;
            int column = index % COLUMNS;
            float left = column * (cellWidth + gap);
            float top = row * (cellHeight + gap);
            cellBounds.set(left, top, left + cellWidth, top + cellHeight);
            int color = Palette.COLORS[index];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawRoundRect(cellBounds, dp(8), dp(8), paint);

            if (index == selectedPaletteIndex) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                paint.setColor(ContextCompat.getColor(getContext(), R.color.rhodes_cyan));
                selectionBounds.set(cellBounds);
                selectionBounds.inset(dp(2), dp(2));
                canvas.drawRoundRect(selectionBounds, dp(7), dp(7), paint);
            }

            textPaint.setColor(contrastTextColor(color));
            canvas.drawText(
                    Palette.number(index),
                    cellBounds.centerX(),
                    cellBounds.centerY() + baselineOffset,
                    textPaint
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            return true;
        }
        int selected = paletteIndexAt(event.getX(), event.getY());
        if (selected >= 0) {
            setSelectedPaletteIndex(selected);
            if (listener != null) {
                listener.onColorSelected(selected);
            }
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int paletteIndexAt(float x, float y) {
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
            return -1;
        }
        float gap = dp(4);
        float cellWidth = (getWidth() - gap * (COLUMNS - 1)) / COLUMNS;
        float cellHeight = (getHeight() - gap * (ROWS - 1)) / ROWS;
        int column = (int) (x / (cellWidth + gap));
        int row = (int) (y / (cellHeight + gap));
        if (column >= COLUMNS || row >= ROWS) {
            return -1;
        }
        float localX = x - column * (cellWidth + gap);
        float localY = y - row * (cellHeight + gap);
        if (localX > cellWidth || localY > cellHeight) {
            return -1;
        }
        return row * COLUMNS + column;
    }

    private static int contrastTextColor(int color) {
        double luminance = 0.2126 * Color.red(color)
                + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color);
        return luminance > 155.0 ? 0xE6000000 : 0xF2FFFFFF;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
