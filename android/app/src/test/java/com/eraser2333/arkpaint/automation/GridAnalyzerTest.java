package com.eraser2333.arkpaint.automation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GridAnalyzerTest {
    @Test
    public void centeredMultiPixelGridLinesAreDetected() {
        ArraySource source = gridSource(760, 720, 100, 70, 676, 646, true);
        GridAnalyzer.Result result = GridAnalyzer.analyze(
                source,
                new GridAnalyzer.Bounds(100, 70, 676, 646)
        );

        assertTrue(result.isSufficient());
        assertTrue(result.verticalHits >= 22);
        assertTrue(result.horizontalHits >= 22);
    }

    @Test
    public void outerCornerGuideCalibrationSnapsToActualCanvas() {
        ArraySource source = gridSource(760, 720, 100, 70, 676, 646, true);
        GridAnalyzer.Bounds approximate = new GridAnalyzer.Bounds(76, 46, 700, 670);

        GridAnalyzer.Bounds snapped = GridAnalyzer.snapToCanvasBorder(source, approximate);

        assertNotNull(snapped);
        assertTrue(Math.abs(snapped.left - 100) <= 4);
        assertTrue(Math.abs(snapped.top - 70) <= 4);
        assertTrue(Math.abs(snapped.right - 676) <= 4);
        assertTrue(Math.abs(snapped.bottom - 646) <= 4);
        assertTrue(GridAnalyzer.analyze(source, snapped).isSufficient());
    }

    @Test
    public void plainSquareWithoutGridIsRejected() {
        ArraySource source = gridSource(760, 720, 100, 70, 676, 646, false);
        GridAnalyzer.Result result = GridAnalyzer.analyze(
                source,
                new GridAnalyzer.Bounds(100, 70, 676, 646)
        );

        assertFalse(result.isSufficient());
    }

    private static ArraySource gridSource(
            int width,
            int height,
            int left,
            int top,
            int right,
            int bottom,
            boolean includeGrid
    ) {
        ArraySource source = new ArraySource(width, height, 212.0);
        source.fill(left, top, right, bottom, 252.0);
        source.drawVerticalLine(left, top, bottom, 4, 80.0);
        source.drawVerticalLine(right, top, bottom, 4, 80.0);
        source.drawHorizontalLine(top, left, right, 4, 80.0);
        source.drawHorizontalLine(bottom, left, right, 4, 80.0);
        if (includeGrid) {
            for (int boundary = 1; boundary < GridAnalyzer.CELL_COUNT; boundary++) {
                int x = Math.round(left + boundary * (right - left) / 24f);
                int y = Math.round(top + boundary * (bottom - top) / 24f);
                source.drawVerticalLine(x, top, bottom, 3, 230.0);
                source.drawHorizontalLine(y, left, right, 3, 230.0);
            }
        }
        return source;
    }

    private static final class ArraySource implements GridAnalyzer.LuminanceSource {
        private final double[][] values;

        private ArraySource(int width, int height, double value) {
            values = new double[height][width];
            fill(0, 0, width - 1, height - 1, value);
        }

        private void fill(int left, int top, int right, int bottom, double value) {
            for (int y = top; y <= bottom; y++) {
                for (int x = left; x <= right; x++) {
                    values[y][x] = value;
                }
            }
        }

        private void drawVerticalLine(
                int center,
                int top,
                int bottom,
                int thickness,
                double value
        ) {
            int start = center - thickness / 2;
            fill(start, top, start + thickness - 1, bottom, value);
        }

        private void drawHorizontalLine(
                int center,
                int left,
                int right,
                int thickness,
                double value
        ) {
            int start = center - thickness / 2;
            fill(left, start, right, start + thickness - 1, value);
        }

        @Override
        public int width() {
            return values[0].length;
        }

        @Override
        public int height() {
            return values.length;
        }

        @Override
        public double luminanceAt(int x, int y) {
            return values[y][x];
        }
    }
}
