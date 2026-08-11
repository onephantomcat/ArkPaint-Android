package com.eraser2333.arkpaint.automation;

final class GridAnalyzer {
    static final int CELL_COUNT = 24;
    static final int REQUIRED_BOUNDARY_HITS = 12;

    private static final double GRID_EDGE_THRESHOLD = 3.0;
    private static final double CANVAS_BORDER_THRESHOLD = 8.0;

    private GridAnalyzer() {
    }

    interface LuminanceSource {
        int width();

        int height();

        double luminanceAt(int x, int y);
    }

    static Result analyze(LuminanceSource source, Bounds canvas) {
        if (!canvas.isUsable(source)) {
            return new Result(0, 0);
        }
        float cellWidth = canvas.width() / CELL_COUNT;
        float cellHeight = canvas.height() / CELL_COUNT;
        int horizontalSearch = clamp(Math.round(cellWidth * 0.18f), 3, 20);
        int verticalSearch = clamp(Math.round(cellHeight * 0.18f), 3, 20);
        int verticalHits = 0;
        int horizontalHits = 0;

        for (int boundary = 1; boundary < CELL_COUNT; boundary++) {
            int x = Math.round(canvas.left + boundary * cellWidth);
            if (bestVerticalEdge(source, canvas, x, horizontalSearch) >= GRID_EDGE_THRESHOLD) {
                verticalHits++;
            }
            int y = Math.round(canvas.top + boundary * cellHeight);
            if (bestHorizontalEdge(source, canvas, y, verticalSearch) >= GRID_EDGE_THRESHOLD) {
                horizontalHits++;
            }
        }
        return new Result(verticalHits, horizontalHits);
    }

    static Bounds snapToCanvasBorder(LuminanceSource source, Bounds approximate) {
        if (!approximate.isUsable(source)) {
            return null;
        }
        float cellWidth = approximate.width() / CELL_COUNT;
        float cellHeight = approximate.height() / CELL_COUNT;
        int horizontalRadius = clamp(Math.round(cellWidth * 0.95f), 8, 96);
        int verticalRadius = clamp(Math.round(cellHeight * 0.95f), 8, 96);
        int verticalInset = Math.max(4, Math.round(approximate.height() * 0.08f));
        int horizontalInset = Math.max(4, Math.round(approximate.width() * 0.08f));

        Edge left = strongestVerticalEdge(
                source,
                Math.round(approximate.left),
                horizontalRadius,
                Math.round(approximate.top) + verticalInset,
                Math.round(approximate.bottom) - verticalInset
        );
        Edge right = strongestVerticalEdge(
                source,
                Math.round(approximate.right),
                horizontalRadius,
                Math.round(approximate.top) + verticalInset,
                Math.round(approximate.bottom) - verticalInset
        );
        Edge top = strongestHorizontalEdge(
                source,
                Math.round(approximate.top),
                verticalRadius,
                Math.round(approximate.left) + horizontalInset,
                Math.round(approximate.right) - horizontalInset
        );
        Edge bottom = strongestHorizontalEdge(
                source,
                Math.round(approximate.bottom),
                verticalRadius,
                Math.round(approximate.left) + horizontalInset,
                Math.round(approximate.right) - horizontalInset
        );
        if (left.strength < CANVAS_BORDER_THRESHOLD
                || right.strength < CANVAS_BORDER_THRESHOLD
                || top.strength < CANVAS_BORDER_THRESHOLD
                || bottom.strength < CANVAS_BORDER_THRESHOLD) {
            return null;
        }

        Bounds snapped = new Bounds(left.position, top.position, right.position, bottom.position);
        if (!snapped.isUsable(source)
                || !similarSize(snapped.width(), approximate.width())
                || !similarSize(snapped.height(), approximate.height())) {
            return null;
        }
        float ratio = snapped.width() / snapped.height();
        if (ratio < 0.85f || ratio > 1.15f) {
            return null;
        }
        return analyze(source, snapped).isSufficient() ? snapped : null;
    }

    private static boolean similarSize(float refined, float approximate) {
        float ratio = refined / approximate;
        return ratio >= 0.82f && ratio <= 1.18f;
    }

    private static double bestVerticalEdge(
            LuminanceSource source,
            Bounds canvas,
            int expectedX,
            int radius
    ) {
        int top = clamp(Math.round(canvas.top + 4), 0, source.height() - 1);
        int bottom = clamp(Math.round(canvas.bottom - 4), top + 1, source.height());
        double best = 0.0;
        for (int x = expectedX - radius; x <= expectedX + radius; x++) {
            best = Math.max(best, verticalEdgeStrength(source, x, top, bottom));
        }
        return best;
    }

    private static double bestHorizontalEdge(
            LuminanceSource source,
            Bounds canvas,
            int expectedY,
            int radius
    ) {
        int left = clamp(Math.round(canvas.left + 4), 0, source.width() - 1);
        int right = clamp(Math.round(canvas.right - 4), left + 1, source.width());
        double best = 0.0;
        for (int y = expectedY - radius; y <= expectedY + radius; y++) {
            best = Math.max(best, horizontalEdgeStrength(source, y, left, right));
        }
        return best;
    }

    private static Edge strongestVerticalEdge(
            LuminanceSource source,
            int expectedX,
            int radius,
            int top,
            int bottom
    ) {
        Edge best = new Edge(expectedX, 0.0);
        for (int x = expectedX - radius; x <= expectedX + radius; x++) {
            double strength = verticalEdgeStrength(source, x, top, bottom);
            if (strength > best.strength) {
                best = new Edge(x, strength);
            }
        }
        return best;
    }

    private static Edge strongestHorizontalEdge(
            LuminanceSource source,
            int expectedY,
            int radius,
            int left,
            int right
    ) {
        Edge best = new Edge(expectedY, 0.0);
        for (int y = expectedY - radius; y <= expectedY + radius; y++) {
            double strength = horizontalEdgeStrength(source, y, left, right);
            if (strength > best.strength) {
                best = new Edge(y, strength);
            }
        }
        return best;
    }

    private static double verticalEdgeStrength(
            LuminanceSource source,
            int x,
            int top,
            int bottom
    ) {
        int safeX = clamp(x, 0, source.width() - 2);
        int safeTop = clamp(top, 0, source.height() - 1);
        int safeBottom = clamp(bottom, safeTop + 1, source.height());
        double total = 0.0;
        int samples = 0;
        for (int y = safeTop; y < safeBottom; y += 3) {
            total += Math.abs(
                    source.luminanceAt(safeX, y) - source.luminanceAt(safeX + 1, y)
            );
            samples++;
        }
        return samples == 0 ? 0.0 : total / samples;
    }

    private static double horizontalEdgeStrength(
            LuminanceSource source,
            int y,
            int left,
            int right
    ) {
        int safeY = clamp(y, 0, source.height() - 2);
        int safeLeft = clamp(left, 0, source.width() - 1);
        int safeRight = clamp(right, safeLeft + 1, source.width());
        double total = 0.0;
        int samples = 0;
        for (int x = safeLeft; x < safeRight; x += 3) {
            total += Math.abs(
                    source.luminanceAt(x, safeY) - source.luminanceAt(x, safeY + 1)
            );
            samples++;
        }
        return samples == 0 ? 0.0 : total / samples;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Bounds {
        final float left;
        final float top;
        final float right;
        final float bottom;

        Bounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        float width() {
            return right - left;
        }

        float height() {
            return bottom - top;
        }

        private boolean isUsable(LuminanceSource source) {
            return left >= 0
                    && top >= 0
                    && right <= source.width() - 1
                    && bottom <= source.height() - 1
                    && width() >= CELL_COUNT * 4f
                    && height() >= CELL_COUNT * 4f;
        }
    }

    static final class Result {
        final int verticalHits;
        final int horizontalHits;

        Result(int verticalHits, int horizontalHits) {
            this.verticalHits = verticalHits;
            this.horizontalHits = horizontalHits;
        }

        boolean isSufficient() {
            return verticalHits >= REQUIRED_BOUNDARY_HITS
                    && horizontalHits >= REQUIRED_BOUNDARY_HITS;
        }
    }

    private static final class Edge {
        final int position;
        final double strength;

        private Edge(int position, double strength) {
            this.position = position;
            this.strength = strength;
        }
    }
}
