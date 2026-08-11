package com.eraser2333.arkpaint.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.eraser2333.arkpaint.R;

public final class CropImageView extends View {
    private static final int MAX_OUTPUT_SIZE = 2048;
    private static final float MAX_ZOOM_MULTIPLIER = 10f;

    private final Paint bitmapPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
    );
    private final Paint shadePaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cropRect = new RectF();
    private final RectF bitmapRect = new RectF();
    private final ScaleGestureDetector scaleDetector;

    private Bitmap bitmap;
    private float scale = 1f;
    private float minimumScale = 1f;
    private float offsetX;
    private float offsetY;
    private float lastTouchX;
    private float lastTouchY;
    private boolean geometryReady;

    public CropImageView(Context context) {
        this(context, null);
    }

    public CropImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFocusable(true);
        setClickable(true);
        setContentDescription(context.getString(R.string.crop_editor_description));
        shadePaint.setColor(0xB8000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2f));
        borderPaint.setColor(ContextCompat.getColor(context, R.color.rhodes_cyan));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setColor(0x88EAF3F1);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
    }

    public void setBitmap(Bitmap source) {
        bitmap = source;
        geometryReady = false;
        initializeGeometry();
        invalidate();
    }

    public void resetTransform() {
        geometryReady = false;
        initializeGeometry();
        invalidate();
    }

    public Bitmap createCroppedBitmap() {
        if (bitmap == null || !geometryReady || cropRect.isEmpty()) {
            throw new IllegalStateException("Crop editor is not ready");
        }
        RectF sourceRect = new RectF(
                (cropRect.left - offsetX) / scale,
                (cropRect.top - offsetY) / scale,
                (cropRect.right - offsetX) / scale,
                (cropRect.bottom - offsetY) / scale
        );
        sourceRect.left = clamp(sourceRect.left, 0f, bitmap.getWidth());
        sourceRect.top = clamp(sourceRect.top, 0f, bitmap.getHeight());
        sourceRect.right = clamp(sourceRect.right, sourceRect.left, bitmap.getWidth());
        sourceRect.bottom = clamp(sourceRect.bottom, sourceRect.top, bitmap.getHeight());
        float side = Math.min(sourceRect.width(), sourceRect.height());
        sourceRect.right = sourceRect.left + side;
        sourceRect.bottom = sourceRect.top + side;
        return renderSquare(bitmap, sourceRect);
    }

    public static Bitmap createCenterSquare(Bitmap source) {
        float side = Math.min(source.getWidth(), source.getHeight());
        float left = (source.getWidth() - side) / 2f;
        float top = (source.getHeight() - side) / 2f;
        return renderSquare(source, new RectF(left, top, left + side, top + side));
    }

    private static Bitmap renderSquare(Bitmap source, RectF sourceRect) {
        int sourceSide = Math.max(1, Math.min(
                Math.min(source.getWidth(), source.getHeight()),
                Math.round(Math.min(sourceRect.width(), sourceRect.height()))
        ));
        int sourceLeft = Math.max(0, Math.min(
                source.getWidth() - sourceSide,
                Math.round(sourceRect.left)
        ));
        int sourceTop = Math.max(0, Math.min(
                source.getHeight() - sourceSide,
                Math.round(sourceRect.top)
        ));
        Rect sourceBounds = new Rect(
                sourceLeft,
                sourceTop,
                sourceLeft + sourceSide,
                sourceTop + sourceSide
        );
        if (sourceSide <= MAX_OUTPUT_SIZE) {
            Bitmap cropped = Bitmap.createBitmap(
                    source,
                    sourceBounds.left,
                    sourceBounds.top,
                    sourceBounds.width(),
                    sourceBounds.height()
            );
            if (cropped != source) {
                return cropped;
            }
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }

        int outputSize = MAX_OUTPUT_SIZE;
        Bitmap output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(
                source,
                sourceBounds,
                new RectF(0f, 0f, outputSize, outputSize),
                paint
        );
        return output;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float padding = dp(18f);
        float side = Math.max(1f, Math.min(width, height) - 2f * padding);
        cropRect.set(
                (width - side) / 2f,
                (height - side) / 2f,
                (width + side) / 2f,
                (height + side) / 2f
        );
        geometryReady = false;
        initializeGeometry();
    }

    private void initializeGeometry() {
        if (bitmap == null || bitmap.isRecycled() || cropRect.isEmpty()) {
            return;
        }
        minimumScale = Math.max(
                cropRect.width() / bitmap.getWidth(),
                cropRect.height() / bitmap.getHeight()
        );
        scale = minimumScale;
        offsetX = cropRect.centerX() - bitmap.getWidth() * scale / 2f;
        offsetY = cropRect.centerY() - bitmap.getHeight() * scale / 2f;
        constrainTransform();
        geometryReady = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(ContextCompat.getColor(getContext(), R.color.ink));
        if (bitmap == null || !geometryReady) {
            return;
        }
        bitmapRect.set(
                offsetX,
                offsetY,
                offsetX + bitmap.getWidth() * scale,
                offsetY + bitmap.getHeight() * scale
        );
        canvas.drawBitmap(bitmap, null, bitmapRect, bitmapPaint);

        canvas.drawRect(0f, 0f, getWidth(), cropRect.top, shadePaint);
        canvas.drawRect(0f, cropRect.bottom, getWidth(), getHeight(), shadePaint);
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, shadePaint);
        canvas.drawRect(cropRect.right, cropRect.top, getWidth(), cropRect.bottom, shadePaint);

        for (int index = 1; index < 3; index++) {
            float x = cropRect.left + cropRect.width() * index / 3f;
            float y = cropRect.top + cropRect.height() * index / 3f;
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint);
            canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint);
        }
        canvas.drawRect(cropRect, borderPaint);
        drawCorner(canvas, cropRect.left, cropRect.top, 1f, 1f);
        drawCorner(canvas, cropRect.right, cropRect.top, -1f, 1f);
        drawCorner(canvas, cropRect.left, cropRect.bottom, 1f, -1f);
        drawCorner(canvas, cropRect.right, cropRect.bottom, -1f, -1f);
    }

    private void drawCorner(Canvas canvas, float x, float y, float directionX, float directionY) {
        float length = dp(18f);
        canvas.drawLine(x, y, x + directionX * length, y, borderPaint);
        canvas.drawLine(x, y, x, y + directionY * length, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float x = event.getX();
                    float y = event.getY();
                    offsetX += x - lastTouchX;
                    offsetY += y - lastTouchY;
                    lastTouchX = x;
                    lastTouchY = y;
                    constrainTransform();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int remainingIndex = event.getActionIndex() == 0 ? 1 : 0;
                if (remainingIndex < event.getPointerCount()) {
                    lastTouchX = event.getX(remainingIndex);
                    lastTouchY = event.getY(remainingIndex);
                }
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void constrainTransform() {
        if (bitmap == null || cropRect.isEmpty()) {
            return;
        }
        float scaledWidth = bitmap.getWidth() * scale;
        float scaledHeight = bitmap.getHeight() * scale;
        float minimumX = cropRect.right - scaledWidth;
        float maximumX = cropRect.left;
        float minimumY = cropRect.bottom - scaledHeight;
        float maximumY = cropRect.top;
        offsetX = clamp(offsetX, minimumX, maximumX);
        offsetY = clamp(offsetY, minimumY, maximumY);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float previousScale = scale;
            float desired = scale * detector.getScaleFactor();
            scale = clamp(
                    desired,
                    minimumScale,
                    minimumScale * MAX_ZOOM_MULTIPLIER
            );
            float ratio = scale / previousScale;
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();
            offsetX = focusX - (focusX - offsetX) * ratio;
            offsetY = focusY - (focusY - offsetY) * ratio;
            constrainTransform();
            invalidate();
            return true;
        }
    }
}
