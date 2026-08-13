package com.eraser2333.arkpaint.automation;

/** Maps full-display touch coordinates into the pixel space returned by a screenshot. */
final class ScreenCoordinateMapper {
    private ScreenCoordinateMapper() {
    }

    static float mapAxis(
            float coordinate,
            int sourceStart,
            int sourceLength,
            int targetLength
    ) {
        if (!Float.isFinite(coordinate) || sourceLength <= 0 || targetLength <= 0) {
            throw new IllegalArgumentException("Coordinate spaces must be finite and non-empty");
        }
        return (coordinate - sourceStart) * targetLength / sourceLength;
    }
}
