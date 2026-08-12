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
import com.eraser2333.arkpaint.editing.PatternEditorModel;
import com.eraser2333.arkpaint.imaging.Palette;

public final class PixelEditorView extends View {
    public interface Listener {
        void onPatternChanged();

        void onStrokeCommitted();

        void onColorPicked(int paletteIndex);
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF canvasBounds = new RectF();
    private PatternEditorModel model;
    private Listener listener;
    private int selectedPaletteIndex;
    private int lastRow = -1;
    private int lastColumn = -1;
    private boolean showNumbers = true;
    private boolean eyedropper;

    public PixelEditorView(Context context) {
        this(context, null);
    }

    public PixelEditorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PixelEditorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFocusable(true);
        setClickable(true);
        numberPaint.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
        ));
    }

    public void setModel(PatternEditorModel model) {
        this.model = model;
        invalidate();
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void setSelectedPaletteIndex(int paletteIndex) {
        Palette.requireValidIndex(paletteIndex);
        selectedPaletteIndex = paletteIndex;
    }

    public void setShowNumbers(boolean showNumbers) {
        this.showNumbers = showNumbers;
        invalidate();
    }

    public void setEyedropper(boolean eyedropper) {
        this.eyedropper = eyedropper;
        setContentDescription(getResources().getString(
                eyedropper ? R.string.editor_canvas_pick_description : R.string.editor_canvas_description
        ));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = dp(620);
        int width = resolveSize(desired, widthMeasureSpec);
        int height = resolveSize(desired, heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (widthMode == MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
            height = width;
        } else if (heightMode == MeasureSpec.EXACTLY && widthMode != MeasureSpec.EXACTLY) {
            width = height;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float outerPadding = dp(12);
        float side = Math.max(0f, Math.min(
                getWidth() - outerPadding * 2f,
                getHeight() - outerPadding * 2f
        ));
        float left = (getWidth() - side) / 2f;
        float top = (getHeight() - side) / 2f;
        canvasBounds.set(left, top, left + side, top + side);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(ContextCompat.getColor(getContext(), R.color.surface_container_low));
        canvas.drawRoundRect(
                0f,
                0f,
                getWidth(),
                getHeight(),
                dp(20),
                dp(20),
                fillPaint
        );

        if (model == null || side <= 0f) {
            return;
        }
        float cell = side / PatternEditorModel.WIDTH;
        numberPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setTextSize(Math.min(cell * 0.42f, sp(12)));
        Paint.FontMetrics metrics = numberPaint.getFontMetrics();
        float numberOffset = -(metrics.ascent + metrics.descent) / 2f;

        for (int row = 0; row < PatternEditorModel.HEIGHT; row++) {
            for (int column = 0; column < PatternEditorModel.WIDTH; column++) {
                int paletteIndex = model.getPixel(row, column);
                int color = Palette.COLORS[paletteIndex];
                float cellLeft = left + column * cell;
                float cellTop = top + row * cell;
                fillPaint.setColor(color);
                canvas.drawRect(
                        cellLeft,
                        cellTop,
                        cellLeft + cell + 0.5f,
                        cellTop + cell + 0.5f,
                        fillPaint
                );
                if (showNumbers && cell >= dp(13)) {
                    numberPaint.setColor(contrastTextColor(color));
                    canvas.drawText(
                            Palette.number(paletteIndex),
                            cellLeft + cell / 2f,
                            cellTop + cell / 2f + numberOffset,
                            numberPaint
                    );
                }
            }
        }

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.pixel_grid));
        gridPaint.setStrokeWidth(Math.max(1f, dp(0.6f)));
        for (int line = 1; line < PatternEditorModel.WIDTH; line++) {
            float offset = line * cell;
            canvas.drawLine(left + offset, top, left + offset, top + side, gridPaint);
            canvas.drawLine(left, top + offset, left + side, top + offset, gridPaint);
        }
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.rhodes_cyan));
        gridPaint.setStrokeWidth(dp(2));
        canvas.drawRect(canvasBounds, gridPaint);
        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.pixel_grid_strong));
        gridPaint.setStrokeWidth(dp(1.25f));
        for (int line = 6; line < PatternEditorModel.WIDTH; line += 6) {
            float offset = line * cell;
            canvas.drawLine(left + offset, top, left + offset, top + side, gridPaint);
            canvas.drawLine(left, top + offset, left + side, top + offset, gridPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (model == null) {
            return false;
        }
        int[] cell = cellAt(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (cell == null) {
                    return false;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                if (eyedropper) {
                    int picked = model.getPixel(cell[0], cell[1]);
                    if (listener != null) {
                        listener.onColorPicked(picked);
                    }
                    performClick();
                    return true;
                }
                model.beginStroke();
                lastRow = cell[0];
                lastColumn = cell[1];
                paintCell(lastRow, lastColumn);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (lastRow < 0 || cell == null) {
                    return true;
                }
                paintLine(lastRow, lastColumn, cell[0], cell[1]);
                lastRow = cell[0];
                lastColumn = cell[1];
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishStroke();
                getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void paintLine(int startRow, int startColumn, int endRow, int endColumn) {
        int x0 = startColumn;
        int y0 = startRow;
        int x1 = endColumn;
        int y1 = endRow;
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            paintCell(y0, x0);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                x0 += sx;
            }
            if (doubled <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private void paintCell(int row, int column) {
        if (model.paint(row, column, selectedPaletteIndex)) {
            invalidate();
            if (listener != null) {
                listener.onPatternChanged();
            }
        }
    }

    private void finishStroke() {
        lastRow = -1;
        lastColumn = -1;
        if (model.endStroke() && listener != null) {
            listener.onStrokeCommitted();
        }
    }

    @Nullable
    private int[] cellAt(float x, float y) {
        if (!canvasBounds.contains(x, y) || canvasBounds.width() <= 0f) {
            return null;
        }
        float cell = canvasBounds.width() / PatternEditorModel.WIDTH;
        int column = Math.min(
                PatternEditorModel.WIDTH - 1,
                (int) ((x - canvasBounds.left) / cell)
        );
        int row = Math.min(
                PatternEditorModel.HEIGHT - 1,
                (int) ((y - canvasBounds.top) / cell)
        );
        return new int[]{row, column};
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
