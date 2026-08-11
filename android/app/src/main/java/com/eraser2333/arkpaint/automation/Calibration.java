package com.eraser2333.arkpaint.automation;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.Locale;

public final class Calibration {
    public final int screenWidth;
    public final int screenHeight;
    public final RectF canvas;
    public final float[] paletteColumns;
    public final float[] paletteRows;

    public Calibration(
            int screenWidth,
            int screenHeight,
            RectF canvas,
            float[] paletteColumns,
            float[] paletteRows
    ) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.canvas = new RectF(canvas);
        this.paletteColumns = paletteColumns.clone();
        this.paletteRows = paletteRows.clone();
    }

    public static Calibration fromFivePoints(
            int screenWidth,
            int screenHeight,
            PointF canvasTopLeft,
            PointF canvasBottomRight,
            PointF paletteTopLeft,
            PointF paletteTopRight,
            PointF paletteBottomLeft
    ) {
        float[] columns = new float[4];
        float columnStep = (paletteTopRight.x - paletteTopLeft.x) / 3f;
        for (int index = 0; index < columns.length; index++) {
            columns[index] = paletteTopLeft.x + columnStep * index;
        }
        float[] rows = new float[6];
        float rowStep = (paletteBottomLeft.y - paletteTopLeft.y) / 5f;
        for (int index = 0; index < rows.length; index++) {
            rows[index] = paletteTopLeft.y + rowStep * index;
        }
        return new Calibration(
                screenWidth,
                screenHeight,
                new RectF(
                        canvasTopLeft.x,
                        canvasTopLeft.y,
                        canvasBottomRight.x,
                        canvasBottomRight.y
                ),
                columns,
                rows
        );
    }

    public boolean isValid() {
        if (screenWidth <= screenHeight || paletteColumns.length != 4 || paletteRows.length != 6) {
            return false;
        }
        if (canvas.left < 0 || canvas.top < 0
                || canvas.right > screenWidth || canvas.bottom > screenHeight) {
            return false;
        }
        if (canvas.width() < screenHeight * 0.45f || canvas.height() < screenHeight * 0.45f) {
            return false;
        }
        float ratio = canvas.width() / canvas.height();
        if (ratio < 0.85f || ratio > 1.15f) {
            return false;
        }
        for (int index = 1; index < paletteColumns.length; index++) {
            if (paletteColumns[index] <= paletteColumns[index - 1]) {
                return false;
            }
        }
        for (int index = 1; index < paletteRows.length; index++) {
            if (paletteRows[index] <= paletteRows[index - 1]) {
                return false;
            }
        }
        return paletteColumns[0] > canvas.right
                && paletteColumns[3] < screenWidth
                && paletteRows[0] >= 0
                && paletteRows[5] < screenHeight;
    }

    public Calibration scaledTo(int width, int height) {
        if (width <= height || screenWidth <= screenHeight) {
            return null;
        }
        double oldRatio = (double) screenWidth / screenHeight;
        double newRatio = (double) width / height;
        if (Math.abs(oldRatio - newRatio) / oldRatio > 0.035) {
            return null;
        }
        float scaleX = (float) width / screenWidth;
        float scaleY = (float) height / screenHeight;
        float[] columns = paletteColumns.clone();
        float[] rows = paletteRows.clone();
        for (int index = 0; index < columns.length; index++) {
            columns[index] *= scaleX;
        }
        for (int index = 0; index < rows.length; index++) {
            rows[index] *= scaleY;
        }
        Calibration scaled = new Calibration(
                width,
                height,
                new RectF(
                        canvas.left * scaleX,
                        canvas.top * scaleY,
                        canvas.right * scaleX,
                        canvas.bottom * scaleY
                ),
                columns,
                rows
        );
        return scaled.isValid() ? scaled : null;
    }

    public PointF canvasCellCenter(int column, int row) {
        if (column < 0 || column >= 24 || row < 0 || row >= 24) {
            throw new IllegalArgumentException("Canvas coordinates must be in the range 0..23");
        }
        return new PointF(
                canvas.left + (column + 0.5f) * canvas.width() / 24f,
                canvas.top + (row + 0.5f) * canvas.height() / 24f
        );
    }

    public PointF paletteCenter(int paletteIndex, int paletteStartRow) {
        int row = paletteIndex / 4 - paletteStartRow;
        int column = paletteIndex % 4;
        if (row < 0 || row >= paletteRows.length) {
            throw new IllegalArgumentException("Palette color is not visible");
        }
        return new PointF(paletteColumns[column], paletteRows[row]);
    }

    public String serialize() {
        StringBuilder value = new StringBuilder();
        value.append(screenWidth).append(',').append(screenHeight)
                .append(',').append(format(canvas.left))
                .append(',').append(format(canvas.top))
                .append(',').append(format(canvas.right))
                .append(',').append(format(canvas.bottom));
        for (float column : paletteColumns) {
            value.append(',').append(format(column));
        }
        for (float row : paletteRows) {
            value.append(',').append(format(row));
        }
        return value.toString();
    }

    public static Calibration parse(String serialized) {
        if (serialized == null || serialized.trim().isEmpty()) {
            return null;
        }
        try {
            String[] parts = serialized.split(",");
            if (parts.length != 16) {
                return null;
            }
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            RectF canvas = new RectF(
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5])
            );
            float[] columns = new float[4];
            float[] rows = new float[6];
            for (int index = 0; index < columns.length; index++) {
                columns[index] = Float.parseFloat(parts[6 + index]);
            }
            for (int index = 0; index < rows.length; index++) {
                rows[index] = Float.parseFloat(parts[10 + index]);
            }
            Calibration calibration = new Calibration(width, height, canvas, columns, rows);
            return calibration.isValid() ? calibration : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
