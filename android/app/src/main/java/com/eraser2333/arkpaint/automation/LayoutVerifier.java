package com.eraser2333.arkpaint.automation;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;

import com.eraser2333.arkpaint.imaging.Palette;

public final class LayoutVerifier {
    private LayoutVerifier() {
    }

    public static void verifyCanvas(Bitmap screenshot, Calibration calibration)
            throws VerificationException {
        verifyDimensions(screenshot, calibration);
        GridAnalyzer.Result result = GridAnalyzer.analyze(
                bitmapSource(screenshot),
                canvasBounds(calibration)
        );
        if (!result.isSufficient()) {
            throw new VerificationException("画布网格不足（"
                    + result.verticalHits + "×" + result.horizontalHits + "）");
        }
    }

    public static Calibration refineCanvasCalibration(
            Bitmap screenshot,
            Calibration calibration
    ) throws VerificationException {
        verifyDimensions(screenshot, calibration);
        GridAnalyzer.Bounds snapped = GridAnalyzer.snapToCanvasBorder(
                bitmapSource(screenshot),
                canvasBounds(calibration)
        );
        if (snapped == null) {
            return calibration;
        }
        Calibration refined = new Calibration(
                calibration.screenWidth,
                calibration.screenHeight,
                new RectF(snapped.left, snapped.top, snapped.right, snapped.bottom),
                calibration.paletteColumns,
                calibration.paletteRows
        );
        return refined.isValid() ? refined : calibration;
    }

    public static void verifyPalette(
            Bitmap screenshot,
            Calibration calibration,
            int paletteStartRow
    ) throws VerificationException {
        verifyDimensions(screenshot, calibration);
        int matches = 0;
        int total = 0;
        for (int visibleRow = 0; visibleRow < 6; visibleRow++) {
            for (int column = 0; column < 4; column++) {
                int paletteIndex = (paletteStartRow + visibleRow) * 4 + column;
                if (paletteIndex >= Palette.size()) {
                    continue;
                }
                total++;
                PointF center = new PointF(
                        calibration.paletteColumns[column],
                        calibration.paletteRows[visibleRow]
                );
                int sampled = sampleMedianColor(screenshot, center.x, center.y, 3);
                if (colorDistance(sampled, Palette.COLORS[paletteIndex]) <= 48.0) {
                    matches++;
                }
            }
        }
        int required = Math.max(8, total / 2);
        if (matches < required) {
            throw new VerificationException("调色板仅匹配 " + matches + "/" + total + " 个色块");
        }
    }

    public static boolean[] whiteCanvasMask(Bitmap screenshot, Calibration calibration) {
        boolean[] white = new boolean[24 * 24];
        float cellSize = Math.min(
                calibration.canvas.width() / 24f,
                calibration.canvas.height() / 24f
        );
        int radius = Math.max(1, Math.round(cellSize * 0.12f));
        for (int row = 0; row < 24; row++) {
            for (int column = 0; column < 24; column++) {
                PointF center = calibration.canvasCellCenter(column, row);
                int color = sampleMedianColor(screenshot, center.x, center.y, radius);
                white[row * 24 + column] = Color.red(color) >= 245
                        && Color.green(color) >= 245
                        && Color.blue(color) >= 245;
            }
        }
        return white;
    }

    public static CanvasMatcher.Result compareCanvas(
            Bitmap screenshot,
            Calibration calibration,
            int[] expectedIndices,
            int[] paletteSamples
    ) throws VerificationException {
        verifyDimensions(screenshot, calibration);
        if (expectedIndices.length != 24 * 24) {
            throw new IllegalArgumentException("Pattern must contain 576 cells");
        }
        int[] observedColors = new int[24 * 24];
        float cellSize = Math.min(
                calibration.canvas.width() / 24f,
                calibration.canvas.height() / 24f
        );
        int radius = Math.max(1, Math.round(cellSize * 0.12f));
        for (int row = 0; row < 24; row++) {
            for (int column = 0; column < 24; column++) {
                PointF center = calibration.canvasCellCenter(column, row);
                observedColors[row * 24 + column] = sampleMedianColor(
                        screenshot,
                        center.x,
                        center.y,
                        radius
                );
            }
        }
        return CanvasMatcher.compare(observedColors, expectedIndices, paletteSamples);
    }

    public static void updatePaletteSamples(
            Bitmap screenshot,
            Calibration calibration,
            int paletteStartRow,
            int[] paletteSamples
    ) throws VerificationException {
        verifyDimensions(screenshot, calibration);
        if (paletteSamples.length < Palette.size()) {
            throw new IllegalArgumentException("Palette sample array is too small");
        }
        for (int visibleRow = 0; visibleRow < 6; visibleRow++) {
            for (int column = 0; column < 4; column++) {
                int paletteIndex = (paletteStartRow + visibleRow) * 4 + column;
                if (paletteIndex >= Palette.size()) {
                    continue;
                }
                int sampled = sampleMedianColor(
                        screenshot,
                        calibration.paletteColumns[column],
                        calibration.paletteRows[visibleRow],
                        3
                );
                if (colorDistance(sampled, Palette.COLORS[paletteIndex]) <= 64.0) {
                    paletteSamples[paletteIndex] = sampled;
                }
            }
        }
    }

    private static void verifyDimensions(Bitmap screenshot, Calibration calibration)
            throws VerificationException {
        if (screenshot.getWidth() <= screenshot.getHeight()) {
            throw new VerificationException("当前不是横屏");
        }
        if (!calibration.isValid()
                || calibration.screenWidth != screenshot.getWidth()
                || calibration.screenHeight != screenshot.getHeight()) {
            throw new VerificationException("校准坐标与当前分辨率不一致");
        }
    }

    private static GridAnalyzer.Bounds canvasBounds(Calibration calibration) {
        return new GridAnalyzer.Bounds(
                calibration.canvas.left,
                calibration.canvas.top,
                calibration.canvas.right,
                calibration.canvas.bottom
        );
    }

    private static GridAnalyzer.LuminanceSource bitmapSource(Bitmap bitmap) {
        return new GridAnalyzer.LuminanceSource() {
            @Override
            public int width() {
                return bitmap.getWidth();
            }

            @Override
            public int height() {
                return bitmap.getHeight();
            }

            @Override
            public double luminanceAt(int x, int y) {
                return luminance(bitmap.getPixel(x, y));
            }
        };
    }

    private static int sampleMedianColor(Bitmap bitmap, float centerX, float centerY, int radius) {
        int left = clamp(Math.round(centerX) - radius, 0, bitmap.getWidth() - 1);
        int right = clamp(Math.round(centerX) + radius, 0, bitmap.getWidth() - 1);
        int top = clamp(Math.round(centerY) - radius, 0, bitmap.getHeight() - 1);
        int bottom = clamp(Math.round(centerY) + radius, 0, bitmap.getHeight() - 1);
        int count = (right - left + 1) * (bottom - top + 1);
        int[] reds = new int[count];
        int[] greens = new int[count];
        int[] blues = new int[count];
        int cursor = 0;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int color = bitmap.getPixel(x, y);
                reds[cursor] = Color.red(color);
                greens[cursor] = Color.green(color);
                blues[cursor] = Color.blue(color);
                cursor++;
            }
        }
        java.util.Arrays.sort(reds);
        java.util.Arrays.sort(greens);
        java.util.Arrays.sort(blues);
        int middle = count / 2;
        return Color.rgb(reds[middle], greens[middle], blues[middle]);
    }

    private static double colorDistance(int first, int second) {
        double red = Color.red(first) - Color.red(second);
        double green = Color.green(first) - Color.green(second);
        double blue = Color.blue(first) - Color.blue(second);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static double luminance(int color) {
        return 0.2126 * Color.red(color)
                + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static final class VerificationException extends Exception {
        public VerificationException(String message) {
            super(message);
        }
    }
}
