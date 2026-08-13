package com.eraser2333.arkpaint.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ScreenCoordinateMapperTest {
    @Test
    public void mapsDisplayCoordinatesIntoScreenshotPixels() {
        assertEquals(
                1600f,
                ScreenCoordinateMapper.mapAxis(1200f, 0, 2400, 3200),
                0.001f
        );
        assertEquals(
                1068f,
                ScreenCoordinateMapper.mapAxis(500f, 0, 1000, 2136),
                0.001f
        );
    }

    @Test
    public void honorsNonZeroDisplayBoundsOrigin() {
        assertEquals(
                500f,
                ScreenCoordinateMapper.mapAxis(1100f, 100, 2000, 1000),
                0.001f
        );
    }

    @Test
    public void rejectsInvalidCoordinateSpaces() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScreenCoordinateMapper.mapAxis(10f, 0, 0, 100)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ScreenCoordinateMapper.mapAxis(Float.NaN, 0, 100, 100)
        );
    }
}
