package com.eraser2333.arkpaint.automation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.eraser2333.arkpaint.R;

import java.util.ArrayList;
import java.util.List;

@android.annotation.SuppressLint("ViewConstructor")
final class CalibrationOverlayView extends View {
    private static final long INITIAL_TOUCH_GUARD_MS = 300L;

    interface Callback {
        void onCompleted(List<PointF> points);

        void onCancelled();
    }

    private static final int[] STEP_LABELS = {
            R.string.calibration_step_canvas_tl,
            R.string.calibration_step_canvas_br,
            R.string.calibration_step_palette_tl,
            R.string.calibration_step_palette_tr,
            R.string.calibration_step_palette_bl
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<PointF> points = new ArrayList<>();
    private final List<PointF> screenPoints = new ArrayList<>();
    private final Callback callback;
    private final RectF cancelBounds = new RectF();
    private final RectF instructionBounds = new RectF();
    private final long acceptTouchesAfterMs;

    CalibrationOverlayView(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        acceptTouchesAfterMs = SystemClock.uptimeMillis() + INITIAL_TOUCH_GUARD_MS;
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        int cyan = ContextCompat.getColor(getContext(), R.color.rhodes_cyan);
        int paper = ContextCompat.getColor(getContext(), R.color.paper);
        int ink = ContextCompat.getColor(getContext(), R.color.ink);
        int signal = ContextCompat.getColor(getContext(), R.color.signal);

        canvas.drawColor(0x5A000000);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xF20E171A);
        instructionBounds.set(
                20f * density,
                18f * density,
                getWidth() - 98f * density,
                68f * density
        );
        canvas.drawRoundRect(instructionBounds, 9f * density, 9f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * density);
        paint.setColor(cyan);
        canvas.drawRoundRect(instructionBounds, 9f * density, 9f * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(paper);
        paint.setTextSize(15f * density);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int step = Math.min(points.size(), STEP_LABELS.length - 1);
        canvas.drawText(
                getResources().getString(STEP_LABELS[step]),
                instructionBounds.left + 16f * density,
                instructionBounds.centerY() + 5f * density,
                paint
        );

        cancelBounds.set(
                getWidth() - 86f * density,
                18f * density,
                getWidth() - 20f * density,
                68f * density
        );
        paint.setColor(0xF2F26A52);
        canvas.drawRoundRect(cancelBounds, 9f * density, 9f * density, paint);
        paint.setColor(ink);
        paint.setTextSize(14f * density);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("取消", cancelBounds.centerX(), cancelBounds.centerY() + 5f * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        for (int index = 0; index < points.size(); index++) {
            PointF point = points.get(index);
            paint.setColor(index < 2 ? cyan : signal);
            canvas.drawCircle(point.x, point.y, 10f * density, paint);
            canvas.drawLine(point.x - 16f * density, point.y, point.x + 16f * density, point.y, paint);
            canvas.drawLine(point.x, point.y - 16f * density, point.x, point.y + 16f * density, paint);
        }
        if (points.size() >= 2) {
            paint.setColor(cyan);
            canvas.drawRect(
                    points.get(0).x,
                    points.get(0).y,
                    points.get(1).x,
                    points.get(1).y,
                    paint
            );
        }
        if (points.size() >= 5) {
            paint.setColor(signal);
            canvas.drawRect(
                    points.get(2).x,
                    points.get(2).y,
                    points.get(3).x,
                    points.get(4).y,
                    paint
            );
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            return true;
        }
        if (event.getEventTime() < acceptTouchesAfterMs) {
            return true;
        }
        if (cancelBounds.contains(event.getX(), event.getY())) {
            callback.onCancelled();
            return true;
        }
        points.add(new PointF(event.getX(), event.getY()));
        screenPoints.add(new PointF(event.getRawX(), event.getRawY()));
        performClick();
        if (points.size() == STEP_LABELS.length) {
            callback.onCompleted(new ArrayList<>(screenPoints));
        } else {
            invalidate();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
