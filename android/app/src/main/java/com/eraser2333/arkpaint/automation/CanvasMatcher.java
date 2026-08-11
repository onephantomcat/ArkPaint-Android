package com.eraser2333.arkpaint.automation;

import java.util.Arrays;

final class CanvasMatcher {
    private static final double DIRECT_MATCH_DISTANCE = 16.0;
    private static final double CLASSIFIED_MATCH_DISTANCE = 78.0;
    private static final double AMBIGUITY_MARGIN = 9.0;

    private CanvasMatcher() {
    }

    static Result compare(
            int[] observedColors,
            int[] expectedIndices,
            int[] paletteSamples
    ) {
        if (observedColors.length != expectedIndices.length) {
            throw new IllegalArgumentException("Observed and expected cell counts differ");
        }
        if (paletteSamples.length == 0) {
            throw new IllegalArgumentException("Palette samples are empty");
        }
        boolean[] mismatches = new boolean[expectedIndices.length];
        int mismatchCount = 0;
        for (int cell = 0; cell < expectedIndices.length; cell++) {
            int expected = expectedIndices[cell];
            if (expected < 0 || expected >= paletteSamples.length) {
                throw new IllegalArgumentException("Expected palette index is out of range");
            }
            int observed = observedColors[cell];
            double expectedDistance = colorDistance(observed, paletteSamples[expected]);
            int nearest = nearestPaletteIndex(observed, paletteSamples);
            double nearestDistance = colorDistance(observed, paletteSamples[nearest]);
            boolean matches = expectedDistance <= DIRECT_MATCH_DISTANCE
                    || (expectedDistance <= CLASSIFIED_MATCH_DISTANCE
                    && (nearest == expected
                    || expectedDistance - nearestDistance <= AMBIGUITY_MARGIN));
            mismatches[cell] = !matches;
            if (!matches) {
                mismatchCount++;
            }
        }
        return new Result(mismatches, mismatchCount);
    }

    static int nearestPaletteIndex(int color, int[] paletteSamples) {
        int bestIndex = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < paletteSamples.length; index++) {
            double distance = colorDistance(color, paletteSamples[index]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double colorDistance(int first, int second) {
        double red = red(first) - red(second);
        double green = green(first) - green(second);
        double blue = blue(first) - blue(second);
        return Math.sqrt(red * red + green * green + blue * blue);
    }

    private static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    private static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    static final class Result {
        private final boolean[] mismatches;
        final int mismatchCount;

        Result(boolean[] mismatches, int mismatchCount) {
            this.mismatches = Arrays.copyOf(mismatches, mismatches.length);
            this.mismatchCount = mismatchCount;
        }

        boolean[] mismatchMask() {
            return Arrays.copyOf(mismatches, mismatches.length);
        }

        int matchCount() {
            return mismatches.length - mismatchCount;
        }
    }
}
