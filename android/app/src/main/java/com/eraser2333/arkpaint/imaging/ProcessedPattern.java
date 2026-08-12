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

    public static ProcessedPattern fromPaletteIndices(int[] paletteIndices) {
        if (paletteIndices == null || paletteIndices.length != 24 * 24) {
            throw new IllegalArgumentException("Pattern must contain 576 pixels");
        }
        int[] colors = new int[paletteIndices.length];
        for (int index = 0; index < paletteIndices.length; index++) {
            Palette.requireValidIndex(paletteIndices[index]);
            colors[index] = Palette.COLORS[paletteIndices[index]];
        }
        Bitmap preview = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        preview.setPixels(colors, 0, 24, 0, 0, 24, 24);
        return new ProcessedPattern(paletteIndices, preview);
    }
}
