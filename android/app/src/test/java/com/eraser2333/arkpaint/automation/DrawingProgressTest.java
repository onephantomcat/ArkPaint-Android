package com.eraser2333.arkpaint.automation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DrawingProgressTest {
    @Test
    public void estimatesRateAndRemainingTime() {
        DrawingProgress.Snapshot snapshot = DrawingProgress.estimate(120, 360, 10_000L);

        assertEquals(12.0, snapshot.cellsPerSecond, 0.0001);
        assertEquals(20L, snapshot.etaSeconds);
    }

    @Test
    public void formatsShortAndLongDurations() {
        assertEquals("00:09", DrawingProgress.formatDuration(9L));
        assertEquals("02:05", DrawingProgress.formatDuration(125L));
        assertEquals("1:02:03", DrawingProgress.formatDuration(3723L));
    }
}
