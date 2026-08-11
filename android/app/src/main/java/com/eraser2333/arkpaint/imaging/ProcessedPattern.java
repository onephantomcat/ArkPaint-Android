package com.eraser2333.arkpaint.imaging;

import android.graphics.Bitmap;

import java.util.Arrays;

public final class ProcessedPattern {
    private final int[] paletteIndices;
    private final Bitmap preview;
    private final int colorCount;

    public ProcessedPattern(int[] paletteIndices, Bitmap preview) {
        if (paletteIndices.length != 24 * 24) {
            throw new IllegalArgumentException("Pattern must contain 576 pixels");
        }
        this.paletteIndices = Arrays.copyOf(paletteIndices, paletteIndices.length);
        this.preview = preview;
        this.colorCount = (int) Arrays.stream(paletteIndices).distinct().count();
    }

    public int[] getPaletteIndices() {
        return Arrays.copyOf(paletteIndices, paletteIndices.length);
    }

    public Bitmap getPreview() {
        return preview;
    }

    public int getColorCount() {
        return colorCount;
    }
}
