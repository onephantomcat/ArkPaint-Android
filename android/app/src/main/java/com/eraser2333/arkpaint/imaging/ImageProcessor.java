package com.eraser2333.arkpaint.imaging;

import android.graphics.Bitmap;
import android.graphics.Color;

public final class ImageProcessor {
    public static final int TARGET_SIZE = 24;

    private ImageProcessor() {
    }

    public static ProcessedPattern process(Bitmap source, ProcessingOptions options) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IllegalArgumentException("Source image is empty");
        }
        validate(options);

        Bitmap framed = frameSource(source, options.resizeMode);
        int logicalSize = TARGET_SIZE / options.mergePixels;
        int background = Palette.COLORS[options.transparentPaletteIndex];
        double[][][] work = resample(
                framed,
                logicalSize,
                logicalSize,
                background,
                options.resamplingMethod
        );
        if (framed != source) {
            framed.recycle();
        }
        applyUnsharpMask(work, options.sharpness);

        for (int row = 0; row < logicalSize; row++) {
            for (int column = 0; column < logicalSize; column++) {
                double red = work[row][column][0];
                double green = work[row][column][1];
                double blue = work[row][column][2];
                double[] adjusted = adjust(red, green, blue, options);
                work[row][column][0] = adjusted[0];
                work[row][column][1] = adjusted[1];
                work[row][column][2] = adjusted[2];
            }
        }

        int[] logicalIndices = mapPixels(work, options.mappingMethod, options.dither);
        int[] output = new int[TARGET_SIZE * TARGET_SIZE];
        for (int row = 0; row < TARGET_SIZE; row++) {
            for (int column = 0; column < TARGET_SIZE; column++) {
                int logicalRow = row / options.mergePixels;
                int logicalColumn = column / options.mergePixels;
                output[row * TARGET_SIZE + column] =
                        logicalIndices[logicalRow * logicalSize + logicalColumn];
            }
        }

        Bitmap preview = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888);
        int[] colors = new int[output.length];
        for (int index = 0; index < output.length; index++) {
            colors[index] = Palette.COLORS[output[index]];
        }
        preview.setPixels(colors, 0, TARGET_SIZE, 0, 0, TARGET_SIZE, TARGET_SIZE);
        return new ProcessedPattern(output, preview);
    }

    private static void validate(ProcessingOptions options) {
        if (options.transparentPaletteIndex < 0
                || options.transparentPaletteIndex >= Palette.size()) {
            throw new IllegalArgumentException("Transparent palette index is out of range");
        }
        if (options.mergePixels < 1 || options.mergePixels > 4
                || TARGET_SIZE % options.mergePixels != 0) {
            throw new IllegalArgumentException("Pixel merge must divide 24 and be between 1 and 4");
        }
        if (options.resamplingMethod == null) {
            throw new IllegalArgumentException("Resampling method is required");
        }
        if (options.sharpness < 0 || options.sharpness > 100) {
            throw new IllegalArgumentException("Sharpness must be between 0 and 100");
        }
        int[] adjustments = {
                options.brightness,
                options.contrast,
                options.saturation,
                options.colorTemperature,
                options.hue
        };
        for (int adjustment : adjustments) {
            if (adjustment < -100 || adjustment > 100) {
                throw new IllegalArgumentException("Color adjustments must be between -100 and 100");
            }
        }
    }

    private static Bitmap frameSource(Bitmap source, ProcessingOptions.ResizeMode mode) {
        if (mode == ProcessingOptions.ResizeMode.STRETCH) {
            return source;
        }
        int side = Math.min(source.getWidth(), source.getHeight());
        int left = (source.getWidth() - side) / 2;
        int top = (source.getHeight() - side) / 2;
        return Bitmap.createBitmap(source, left, top, side, side);
    }

    private static double[][][] resample(
            Bitmap source,
            int targetWidth,
            int targetHeight,
            int background,
            ProcessingOptions.ResamplingMethod method
    ) {
        int[] sourcePixels = new int[source.getWidth() * source.getHeight()];
        source.getPixels(
                sourcePixels,
                0,
                source.getWidth(),
                0,
                0,
                source.getWidth(),
                source.getHeight()
        );
        switch (method) {
            case NEAREST:
                return resampleNearest(
                        sourcePixels,
                        source.getWidth(),
                        source.getHeight(),
                        targetWidth,
                        targetHeight,
                        background
                );
            case BILINEAR:
                return resampleBilinear(
                        sourcePixels,
                        source.getWidth(),
                        source.getHeight(),
                        targetWidth,
                        targetHeight,
                        background
                );
            case BOX:
                return resampleBox(
                        sourcePixels,
                        source.getWidth(),
                        source.getHeight(),
                        targetWidth,
                        targetHeight,
                        background
                );
            case LANCZOS:
                int intermediateWidth = Math.min(
                        source.getWidth(),
                        Math.max(targetWidth, targetWidth * 4)
                );
                int intermediateHeight = Math.min(
                        source.getHeight(),
                        Math.max(targetHeight, targetHeight * 4)
                );
                double[][][] intermediate = resampleBox(
                        sourcePixels,
                        source.getWidth(),
                        source.getHeight(),
                        intermediateWidth,
                        intermediateHeight,
                        background
                );
                return resampleLanczos(intermediate, targetWidth, targetHeight);
            default:
                throw new IllegalArgumentException("Unknown resampling method");
        }
    }

    private static double[][][] resampleNearest(
            int[] source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            int background
    ) {
        double[][][] output = new double[targetHeight][targetWidth][3];
        for (int row = 0; row < targetHeight; row++) {
            int sourceRow = Math.min(
                    sourceHeight - 1,
                    (int) Math.floor((row + 0.5) * sourceHeight / targetHeight)
            );
            for (int column = 0; column < targetWidth; column++) {
                int sourceColumn = Math.min(
                        sourceWidth - 1,
                        (int) Math.floor((column + 0.5) * sourceWidth / targetWidth)
                );
                composite(source[sourceRow * sourceWidth + sourceColumn], background,
                        output[row][column]);
            }
        }
        return output;
    }

    private static double[][][] resampleBilinear(
            int[] source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            int background
    ) {
        double[][][] output = new double[targetHeight][targetWidth][3];
        double[] first = new double[3];
        double[] second = new double[3];
        double[] third = new double[3];
        double[] fourth = new double[3];
        for (int row = 0; row < targetHeight; row++) {
            double sourceY = clampCoordinate(
                    (row + 0.5) * sourceHeight / targetHeight - 0.5,
                    sourceHeight
            );
            int top = (int) Math.floor(sourceY);
            int bottom = clampIndex(top + 1, sourceHeight);
            double vertical = sourceY - top;
            for (int column = 0; column < targetWidth; column++) {
                double sourceX = clampCoordinate(
                        (column + 0.5) * sourceWidth / targetWidth - 0.5,
                        sourceWidth
                );
                int left = (int) Math.floor(sourceX);
                int right = clampIndex(left + 1, sourceWidth);
                double horizontal = sourceX - left;
                composite(source[top * sourceWidth + left], background, first);
                composite(source[top * sourceWidth + right], background, second);
                composite(source[bottom * sourceWidth + left], background, third);
                composite(source[bottom * sourceWidth + right], background, fourth);
                for (int channel = 0; channel < 3; channel++) {
                    double upper = first[channel] * (1.0 - horizontal)
                            + second[channel] * horizontal;
                    double lower = third[channel] * (1.0 - horizontal)
                            + fourth[channel] * horizontal;
                    output[row][column][channel] = upper * (1.0 - vertical)
                            + lower * vertical;
                }
            }
        }
        return output;
    }

    private static double[][][] resampleBox(
            int[] source,
            int sourceWidth,
            int sourceHeight,
            int targetWidth,
            int targetHeight,
            int background
    ) {
        if (targetWidth > sourceWidth || targetHeight > sourceHeight) {
            return resampleBilinear(
                    source,
                    sourceWidth,
                    sourceHeight,
                    targetWidth,
                    targetHeight,
                    background
            );
        }
        SampleWeights[] horizontal = buildAreaWeights(sourceWidth, targetWidth);
        SampleWeights[] vertical = buildAreaWeights(sourceHeight, targetHeight);
        double[][][] output = new double[targetHeight][targetWidth][3];
        double[] pixel = new double[3];
        for (int row = 0; row < targetHeight; row++) {
            SampleWeights rows = vertical[row];
            for (int column = 0; column < targetWidth; column++) {
                SampleWeights columns = horizontal[column];
                double[] target = output[row][column];
                for (int rowIndex = 0; rowIndex < rows.indices.length; rowIndex++) {
                    int sourceRow = rows.indices[rowIndex];
                    double verticalWeight = rows.weights[rowIndex];
                    for (int columnIndex = 0;
                            columnIndex < columns.indices.length;
                            columnIndex++) {
                        int sourceColumn = columns.indices[columnIndex];
                        double weight = verticalWeight * columns.weights[columnIndex];
                        composite(source[sourceRow * sourceWidth + sourceColumn],
                                background, pixel);
                        target[0] += pixel[0] * weight;
                        target[1] += pixel[1] * weight;
                        target[2] += pixel[2] * weight;
                    }
                }
            }
        }
        return output;
    }

    private static SampleWeights[] buildAreaWeights(int sourceSize, int targetSize) {
        SampleWeights[] result = new SampleWeights[targetSize];
        double scale = sourceSize / (double) targetSize;
        for (int target = 0; target < targetSize; target++) {
            double start = target * scale;
            double end = (target + 1) * scale;
            int first = Math.max(0, (int) Math.floor(start));
            int last = Math.min(sourceSize - 1, (int) Math.ceil(end) - 1);
            int count = last - first + 1;
            int[] indices = new int[count];
            double[] weights = new double[count];
            double total = 0.0;
            for (int offset = 0; offset < count; offset++) {
                int source = first + offset;
                double overlap = Math.max(0.0,
                        Math.min(end, source + 1.0) - Math.max(start, source));
                indices[offset] = source;
                weights[offset] = overlap;
                total += overlap;
            }
            normalize(weights, total);
            result[target] = new SampleWeights(indices, weights);
        }
        return result;
    }

    private static double[][][] resampleLanczos(
            double[][][] source,
            int targetWidth,
            int targetHeight
    ) {
        int sourceHeight = source.length;
        int sourceWidth = source[0].length;
        if (sourceWidth == targetWidth && sourceHeight == targetHeight) {
            return copyPixels(source);
        }
        SampleWeights[] horizontal = buildLanczosWeights(sourceWidth, targetWidth);
        SampleWeights[] vertical = buildLanczosWeights(sourceHeight, targetHeight);
        double[][][] intermediate = new double[sourceHeight][targetWidth][3];
        for (int row = 0; row < sourceHeight; row++) {
            for (int column = 0; column < targetWidth; column++) {
                SampleWeights weights = horizontal[column];
                for (int index = 0; index < weights.indices.length; index++) {
                    double[] pixel = source[row][weights.indices[index]];
                    double weight = weights.weights[index];
                    intermediate[row][column][0] += pixel[0] * weight;
                    intermediate[row][column][1] += pixel[1] * weight;
                    intermediate[row][column][2] += pixel[2] * weight;
                }
            }
        }
        double[][][] output = new double[targetHeight][targetWidth][3];
        for (int row = 0; row < targetHeight; row++) {
            SampleWeights weights = vertical[row];
            for (int column = 0; column < targetWidth; column++) {
                for (int index = 0; index < weights.indices.length; index++) {
                    double[] pixel = intermediate[weights.indices[index]][column];
                    double weight = weights.weights[index];
                    output[row][column][0] += pixel[0] * weight;
                    output[row][column][1] += pixel[1] * weight;
                    output[row][column][2] += pixel[2] * weight;
                }
                output[row][column][0] = clamp(output[row][column][0]);
                output[row][column][1] = clamp(output[row][column][1]);
                output[row][column][2] = clamp(output[row][column][2]);
            }
        }
        return output;
    }

    private static SampleWeights[] buildLanczosWeights(int sourceSize, int targetSize) {
        SampleWeights[] result = new SampleWeights[targetSize];
        double scale = Math.max(1.0, sourceSize / (double) targetSize);
        double support = 3.0 * scale;
        for (int target = 0; target < targetSize; target++) {
            double center = (target + 0.5) * sourceSize / targetSize - 0.5;
            int first = Math.max(0, (int) Math.ceil(center - support));
            int last = Math.min(sourceSize - 1, (int) Math.floor(center + support));
            int[] indices = new int[last - first + 1];
            double[] weights = new double[indices.length];
            double total = 0.0;
            for (int offset = 0; offset < indices.length; offset++) {
                int source = first + offset;
                double weight = lanczos((center - source) / scale);
                indices[offset] = source;
                weights[offset] = weight;
                total += weight;
            }
            normalize(weights, total);
            result[target] = new SampleWeights(indices, weights);
        }
        return result;
    }

    private static double lanczos(double value) {
        double absolute = Math.abs(value);
        if (absolute < 1.0e-9) {
            return 1.0;
        }
        if (absolute >= 3.0) {
            return 0.0;
        }
        double piValue = Math.PI * value;
        return (Math.sin(piValue) / piValue)
                * (Math.sin(piValue / 3.0) / (piValue / 3.0));
    }

    private static void normalize(double[] values, double total) {
        if (Math.abs(total) < 1.0e-12) {
            double equal = 1.0 / values.length;
            for (int index = 0; index < values.length; index++) {
                values[index] = equal;
            }
            return;
        }
        for (int index = 0; index < values.length; index++) {
            values[index] /= total;
        }
    }

    static void applyUnsharpMask(double[][][] pixels, int sharpness) {
        if (sharpness <= 0 || pixels.length == 0 || pixels[0].length == 0) {
            return;
        }
        double amount = sharpness / 50.0;
        double[][][] source = copyPixels(pixels);
        int height = pixels.length;
        int width = pixels[0].length;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                double[] blurred = new double[3];
                double totalWeight = 0.0;
                for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
                    int neighborRow = clampIndex(row + rowOffset, height);
                    for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                        int neighborColumn = clampIndex(column + columnOffset, width);
                        double weight = (rowOffset == 0 ? 2.0 : 1.0)
                                * (columnOffset == 0 ? 2.0 : 1.0);
                        for (int channel = 0; channel < 3; channel++) {
                            blurred[channel] += source[neighborRow][neighborColumn][channel]
                                    * weight;
                        }
                        totalWeight += weight;
                    }
                }
                for (int channel = 0; channel < 3; channel++) {
                    blurred[channel] /= totalWeight;
                }
                double luminanceDelta = Math.abs(
                        luminance(source[row][column]) - luminance(blurred)
                );
                double edgeAmount = luminanceDelta < 2.0 ? 0.0 : amount;
                for (int channel = 0; channel < 3; channel++) {
                    pixels[row][column][channel] = clamp(
                            source[row][column][channel]
                                    + edgeAmount * (source[row][column][channel] - blurred[channel])
                    );
                }
            }
        }
    }

    private static double luminance(double[] pixel) {
        return 0.2126 * pixel[0] + 0.7152 * pixel[1] + 0.0722 * pixel[2];
    }

    private static double[][][] copyPixels(double[][][] source) {
        double[][][] copy = new double[source.length][source[0].length][3];
        for (int row = 0; row < source.length; row++) {
            for (int column = 0; column < source[row].length; column++) {
                System.arraycopy(source[row][column], 0, copy[row][column], 0, 3);
            }
        }
        return copy;
    }

    private static void composite(int argb, int background, double[] output) {
        double alpha = Color.alpha(argb) / 255.0;
        output[0] = Color.red(argb) * alpha + Color.red(background) * (1.0 - alpha);
        output[1] = Color.green(argb) * alpha + Color.green(background) * (1.0 - alpha);
        output[2] = Color.blue(argb) * alpha + Color.blue(background) * (1.0 - alpha);
    }

    private static int clampIndex(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static double clampCoordinate(double value, int size) {
        return Math.max(0.0, Math.min(size - 1.0, value));
    }

    private static final class SampleWeights {
        final int[] indices;
        final double[] weights;

        SampleWeights(int[] indices, double[] weights) {
            this.indices = indices;
            this.weights = weights;
        }
    }

    private static double[] adjust(
            double red,
            double green,
            double blue,
            ProcessingOptions options
    ) {
        double brightnessFactor = Math.max(0.0, 1.0 + options.brightness / 100.0);
        red *= brightnessFactor;
        green *= brightnessFactor;
        blue *= brightnessFactor;

        double contrastFactor = Math.max(0.0, 1.0 + options.contrast / 100.0);
        red = (red - 127.5) * contrastFactor + 127.5;
        green = (green - 127.5) * contrastFactor + 127.5;
        blue = (blue - 127.5) * contrastFactor + 127.5;

        double saturationFactor = Math.max(0.0, 1.0 + options.saturation / 100.0);
        double luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
        red = luminance + (red - luminance) * saturationFactor;
        green = luminance + (green - luminance) * saturationFactor;
        blue = luminance + (blue - luminance) * saturationFactor;

        double temperature = options.colorTemperature / 100.0;
        red *= 1.0 + 0.25 * temperature;
        green *= 1.0 + 0.05 * Math.abs(temperature);
        blue *= 1.0 - 0.25 * temperature;

        if (options.hue != 0) {
            float[] hsv = new float[3];
            Color.RGBToHSV((int) clamp(red), (int) clamp(green), (int) clamp(blue), hsv);
            hsv[0] = (float) ((hsv[0] + options.hue * 1.8 + 360.0) % 360.0);
            int shifted = Color.HSVToColor(hsv);
            red = Color.red(shifted);
            green = Color.green(shifted);
            blue = Color.blue(shifted);
        }
        return new double[]{clamp(red), clamp(green), clamp(blue)};
    }

    private static int[] mapPixels(
            double[][][] pixels,
            ProcessingOptions.MappingMethod method,
            boolean dither
    ) {
        int height = pixels.length;
        int width = pixels[0].length;
        int[] indices = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                double[] pixel = pixels[row][column];
                int paletteIndex = nearestIndex(pixel[0], pixel[1], pixel[2], method);
                indices[row * width + column] = paletteIndex;
                if (!dither) {
                    continue;
                }
                int color = Palette.COLORS[paletteIndex];
                double errorRed = clamp(pixel[0]) - Color.red(color);
                double errorGreen = clamp(pixel[1]) - Color.green(color);
                double errorBlue = clamp(pixel[2]) - Color.blue(color);
                diffuse(pixels, row, column + 1, errorRed, errorGreen, errorBlue, 7.0 / 16.0);
                diffuse(pixels, row + 1, column - 1, errorRed, errorGreen, errorBlue, 3.0 / 16.0);
                diffuse(pixels, row + 1, column, errorRed, errorGreen, errorBlue, 5.0 / 16.0);
                diffuse(pixels, row + 1, column + 1, errorRed, errorGreen, errorBlue, 1.0 / 16.0);
            }
        }
        return indices;
    }

    private static void diffuse(
            double[][][] pixels,
            int row,
            int column,
            double red,
            double green,
            double blue,
            double weight
    ) {
        if (row < 0 || row >= pixels.length || column < 0 || column >= pixels[0].length) {
            return;
        }
        pixels[row][column][0] += red * weight;
        pixels[row][column][1] += green * weight;
        pixels[row][column][2] += blue * weight;
    }

    static int nearestIndex(
            double red,
            double green,
            double blue,
            ProcessingOptions.MappingMethod method
    ) {
        int bestIndex = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        double[] pixelLab = null;
        double[] pixelOklab = null;
        if (method == ProcessingOptions.MappingMethod.LAB
                || method == ProcessingOptions.MappingMethod.CIEDE2000) {
            pixelLab = rgbToLab(red, green, blue);
        } else if (method == ProcessingOptions.MappingMethod.OKLAB) {
            pixelOklab = rgbToOklab(red, green, blue);
        }

        for (int index = 0; index < Palette.size(); index++) {
            int candidate = Palette.COLORS[index];
            double candidateRed = red(candidate);
            double candidateGreen = green(candidate);
            double candidateBlue = blue(candidate);
            double distance;
            switch (method) {
                case RGB:
                    distance = square(red - candidateRed)
                            + square(green - candidateGreen)
                            + square(blue - candidateBlue);
                    break;
                case WEIGHTED_RGB:
                    double redMean = (red + candidateRed) / 2.0;
                    distance = (2.0 + redMean / 256.0) * square(red - candidateRed)
                            + 4.0 * square(green - candidateGreen)
                            + (2.0 + (255.0 - redMean) / 256.0) * square(blue - candidateBlue);
                    break;
                case LAB:
                    distance = squaredDistance(pixelLab, rgbToLab(
                            candidateRed, candidateGreen, candidateBlue));
                    break;
                case CIEDE2000:
                    distance = deltaECiede2000(pixelLab, rgbToLab(
                            candidateRed, candidateGreen, candidateBlue));
                    break;
                case OKLAB:
                    distance = squaredDistance(pixelOklab, rgbToOklab(
                            candidateRed, candidateGreen, candidateBlue));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mapping method");
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static double[] rgbToLab(double red, double green, double blue) {
        double r = srgbToLinear(red / 255.0);
        double g = srgbToLinear(green / 255.0);
        double b = srgbToLinear(blue / 255.0);
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = labTransform(x);
        double fy = labTransform(y);
        double fz = labTransform(z);
        return new double[]{116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz)};
    }

    private static double[] rgbToOklab(double red, double green, double blue) {
        double r = srgbToLinear(red / 255.0);
        double g = srgbToLinear(green / 255.0);
        double b = srgbToLinear(blue / 255.0);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995450 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        };
    }

    private static double deltaECiede2000(double[] first, double[] second) {
        double l1 = first[0];
        double a1 = first[1];
        double b1 = first[2];
        double l2 = second[0];
        double a2 = second[1];
        double b2 = second[2];
        double c1 = Math.hypot(a1, b1);
        double c2 = Math.hypot(a2, b2);
        double meanC = (c1 + c2) / 2.0;
        double meanC7 = Math.pow(meanC, 7.0);
        double compensation = 0.5 * (1.0 - Math.sqrt(
                meanC7 / (meanC7 + Math.pow(25.0, 7.0))));
        double adjustedA1 = (1.0 + compensation) * a1;
        double adjustedA2 = (1.0 + compensation) * a2;
        double adjustedC1 = Math.hypot(adjustedA1, b1);
        double adjustedC2 = Math.hypot(adjustedA2, b2);
        double h1 = positiveDegrees(Math.toDegrees(Math.atan2(b1, adjustedA1)));
        double h2 = positiveDegrees(Math.toDegrees(Math.atan2(b2, adjustedA2)));

        double deltaL = l2 - l1;
        double deltaC = adjustedC2 - adjustedC1;
        double hueDifference = h2 - h1;
        if (hueDifference > 180.0) {
            hueDifference -= 360.0;
        } else if (hueDifference < -180.0) {
            hueDifference += 360.0;
        }
        if (adjustedC1 * adjustedC2 == 0.0) {
            hueDifference = 0.0;
        }
        double deltaH = 2.0 * Math.sqrt(adjustedC1 * adjustedC2)
                * Math.sin(Math.toRadians(hueDifference / 2.0));
        double meanL = (l1 + l2) / 2.0;
        double meanAdjustedC = (adjustedC1 + adjustedC2) / 2.0;
        double hueSum = h1 + h2;
        double meanHue;
        if (adjustedC1 * adjustedC2 == 0.0) {
            meanHue = hueSum;
        } else if (Math.abs(h1 - h2) <= 180.0) {
            meanHue = hueSum / 2.0;
        } else if (hueSum < 360.0) {
            meanHue = (hueSum + 360.0) / 2.0;
        } else {
            meanHue = (hueSum - 360.0) / 2.0;
        }
        double hueWeight = 1.0
                - 0.17 * cosDegrees(meanHue - 30.0)
                + 0.24 * cosDegrees(2.0 * meanHue)
                + 0.32 * cosDegrees(3.0 * meanHue + 6.0)
                - 0.20 * cosDegrees(4.0 * meanHue - 63.0);
        double rotationAngle = 30.0 * Math.exp(-square((meanHue - 275.0) / 25.0));
        double adjustedC7 = Math.pow(meanAdjustedC, 7.0);
        double chromaRotation = 2.0 * Math.sqrt(
                adjustedC7 / (adjustedC7 + Math.pow(25.0, 7.0)));
        double lightnessScale = 1.0 + 0.015 * square(meanL - 50.0)
                / Math.sqrt(20.0 + square(meanL - 50.0));
        double chromaScale = 1.0 + 0.045 * meanAdjustedC;
        double hueScale = 1.0 + 0.015 * meanAdjustedC * hueWeight;
        double rotation = -Math.sin(Math.toRadians(2.0 * rotationAngle)) * chromaRotation;
        double lightnessTerm = deltaL / lightnessScale;
        double chromaTerm = deltaC / chromaScale;
        double hueTerm = deltaH / hueScale;
        return Math.sqrt(Math.max(0.0,
                square(lightnessTerm)
                        + square(chromaTerm)
                        + square(hueTerm)
                        + rotation * chromaTerm * hueTerm));
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double labTransform(double value) {
        double delta = 6.0 / 29.0;
        return value > Math.pow(delta, 3.0)
                ? Math.cbrt(value)
                : value / (3.0 * square(delta)) + 4.0 / 29.0;
    }

    private static double squaredDistance(double[] first, double[] second) {
        return square(first[0] - second[0])
                + square(first[1] - second[1])
                + square(first[2] - second[2]);
    }

    private static double positiveDegrees(double value) {
        return (value % 360.0 + 360.0) % 360.0;
    }

    private static double cosDegrees(double value) {
        return Math.cos(Math.toRadians(value));
    }

    private static double square(double value) {
        return value * value;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(255.0, value));
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
}
