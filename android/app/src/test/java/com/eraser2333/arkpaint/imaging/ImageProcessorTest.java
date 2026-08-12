package com.eraser2333.arkpaint.imaging;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImageProcessorTest {
    private static final double[][] SAMPLES = {
            {0, 0, 0},
            {255, 255, 255},
            {210, 45, 55},
            {50, 160, 180},
            {120, 90, 40},
            {90, 70, 160}
    };

    @Test
    public void paletteMatchesDesktopSource() {
        assertEquals(40, Palette.size());
        assertEquals("#FFFFFF", Palette.hex(Palette.WHITE_INDEX));
        assertEquals(0xFF222222, Palette.COLORS[0]);
        assertEquals(0xFF253660, Palette.COLORS[39]);
        assertEquals("01", Palette.number(0));
        assertEquals("40", Palette.number(39));
        assertEquals("05  #D32F36", Palette.label(4));
    }

    @Test
    public void ciede2000MatchesPythonReference() {
        assertArrayEquals(
                new int[]{0, 3, 4, 34, 26, 30},
                map(ProcessingOptions.MappingMethod.CIEDE2000)
        );
    }

    @Test
    public void oklabMatchesPythonReference() {
        assertArrayEquals(
                new int[]{0, 3, 4, 34, 26, 30},
                map(ProcessingOptions.MappingMethod.OKLAB)
        );
    }

    @Test
    public void labMatchesPythonReference() {
        assertArrayEquals(
                new int[]{0, 3, 4, 34, 26, 30},
                map(ProcessingOptions.MappingMethod.LAB)
        );
    }

    @Test
    public void weightedRgbMatchesPythonReference() {
        assertArrayEquals(
                new int[]{0, 3, 4, 37, 26, 30},
                map(ProcessingOptions.MappingMethod.WEIGHTED_RGB)
        );
    }

    @Test
    public void rgbMatchesPythonReference() {
        assertArrayEquals(
                new int[]{0, 3, 4, 37, 26, 30},
                map(ProcessingOptions.MappingMethod.RGB)
        );
    }

    @Test
    public void sharpnessKeepsFlatColorUnchanged() {
        double[][][] pixels = solidPixels(3, 3, 120.0);

        ImageProcessor.applyUnsharpMask(pixels, 70);

        for (double[][] row : pixels) {
            for (double[] pixel : row) {
                assertEquals(120.0, pixel[0], 0.0001);
                assertEquals(120.0, pixel[1], 0.0001);
                assertEquals(120.0, pixel[2], 0.0001);
            }
        }
    }

    @Test
    public void sharpnessIncreasesEdgeContrast() {
        double[][][] pixels = solidPixels(3, 3, 80.0);
        pixels[1][1][0] = 180.0;
        pixels[1][1][1] = 180.0;
        pixels[1][1][2] = 180.0;

        ImageProcessor.applyUnsharpMask(pixels, 35);

        assertTrue(pixels[1][1][0] > 180.0);
        assertTrue(pixels[1][0][0] < 80.0);
    }

    private static int[] map(ProcessingOptions.MappingMethod method) {
        int[] output = new int[SAMPLES.length];
        for (int index = 0; index < SAMPLES.length; index++) {
            output[index] = ImageProcessor.nearestIndex(
                    SAMPLES[index][0],
                    SAMPLES[index][1],
                    SAMPLES[index][2],
                    method
            );
        }
        return output;
    }

    private static double[][][] solidPixels(int width, int height, double value) {
        double[][][] pixels = new double[height][width][3];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                pixels[row][column][0] = value;
                pixels[row][column][1] = value;
                pixels[row][column][2] = value;
            }
        }
        return pixels;
    }
}
