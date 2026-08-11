package com.eraser2333.arkpaint.imaging;

public final class ProcessingOptions {
    public enum ResizeMode {
        CROP,
        STRETCH
    }

    public enum MappingMethod {
        CIEDE2000,
        OKLAB,
        LAB,
        WEIGHTED_RGB,
        RGB
    }

    public enum ResamplingMethod {
        LANCZOS,
        BOX,
        NEAREST,
        BILINEAR
    }

    public ResizeMode resizeMode = ResizeMode.CROP;
    public ResamplingMethod resamplingMethod = ResamplingMethod.LANCZOS;
    public MappingMethod mappingMethod = MappingMethod.LAB;
    public boolean dither = false;
    public int transparentPaletteIndex = Palette.WHITE_INDEX;
    public int mergePixels = 1;
    public int sharpness = 35;
    public int brightness = 0;
    public int contrast = 0;
    public int saturation = 0;
    public int colorTemperature = 0;
    public int hue = 0;
}
