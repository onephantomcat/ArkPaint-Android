package com.eraser2333.arkpaint.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eraser2333.arkpaint.imaging.Palette;

import org.junit.Test;

public final class CanvasMatcherTest {
    @Test
    public void exactPaletteColorsMatch() {
        int[] expected = {0, 3, 4, 29, 39};
        int[] observed = {
                Palette.COLORS[0],
                Palette.COLORS[3],
                Palette.COLORS[4],
                Palette.COLORS[29],
                Palette.COLORS[39]
        };

        CanvasMatcher.Result result = CanvasMatcher.compare(
                observed,
                expected,
                Palette.COLORS
        );

        assertEquals(0, result.mismatchCount);
        assertEquals(expected.length, result.matchCount());
    }

    @Test
    public void smallScreenColorShiftStillMatchesNearestPaletteColor() {
        int shiftedBlack = 0xFF343434;

        CanvasMatcher.Result result = CanvasMatcher.compare(
                new int[]{shiftedBlack},
                new int[]{0},
                Palette.COLORS
        );

        assertEquals(0, result.mismatchCount);
    }

    @Test
    public void wrongDistantColorIsMarkedForRepair() {
        CanvasMatcher.Result result = CanvasMatcher.compare(
                new int[]{Palette.COLORS[4], Palette.COLORS[3]},
                new int[]{29, 3},
                Palette.COLORS
        );

        boolean[] mask = result.mismatchMask();
        assertEquals(1, result.mismatchCount);
        assertTrue(mask[0]);
        assertFalse(mask[1]);
    }

    @Test
    public void blankWhiteDoesNotHideExpectedPalePaint() {
        CanvasMatcher.Result result = CanvasMatcher.compare(
                new int[]{Palette.COLORS[3]},
                new int[]{10},
                Palette.COLORS
        );

        assertEquals(1, result.mismatchCount);
        assertTrue(result.mismatchMask()[0]);
    }
}
