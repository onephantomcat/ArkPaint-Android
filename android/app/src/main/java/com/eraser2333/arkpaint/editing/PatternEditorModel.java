package com.eraser2333.arkpaint.editing;

import com.eraser2333.arkpaint.imaging.Palette;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class PatternEditorModel {
    public static final int WIDTH = 24;
    public static final int HEIGHT = 24;
    public static final int PIXEL_COUNT = WIDTH * HEIGHT;
    private static final int MAX_HISTORY = 64;

    private final int[] original;
    private final Deque<int[]> undo = new ArrayDeque<>();
    private final Deque<int[]> redo = new ArrayDeque<>();
    private int[] pixels;
    private int[] strokeStart;

    public PatternEditorModel(int[] originalPixels) {
        this(originalPixels, originalPixels);
    }

    public PatternEditorModel(int[] originalPixels, int[] currentPixels) {
        original = checkedCopy(originalPixels);
        pixels = checkedCopy(currentPixels);
    }

    public void beginStroke() {
        if (strokeStart == null) {
            strokeStart = Arrays.copyOf(pixels, pixels.length);
        }
    }

    public boolean paint(int row, int column, int paletteIndex) {
        requireCell(row, column);
        Palette.requireValidIndex(paletteIndex);
        int offset = row * WIDTH + column;
        if (pixels[offset] == paletteIndex) {
            return false;
        }
        pixels[offset] = paletteIndex;
        return true;
    }

    public boolean endStroke() {
        if (strokeStart == null) {
            return false;
        }
        int[] before = strokeStart;
        strokeStart = null;
        if (Arrays.equals(before, pixels)) {
            return false;
        }
        pushHistory(undo, before);
        redo.clear();
        return true;
    }

    public boolean undo() {
        endStroke();
        if (undo.isEmpty()) {
            return false;
        }
        pushHistory(redo, pixels);
        pixels = undo.removeLast();
        return true;
    }

    public boolean redo() {
        endStroke();
        if (redo.isEmpty()) {
            return false;
        }
        pushHistory(undo, pixels);
        pixels = redo.removeLast();
        return true;
    }

    public boolean resetToOriginal() {
        endStroke();
        if (Arrays.equals(original, pixels)) {
            return false;
        }
        pushHistory(undo, pixels);
        pixels = Arrays.copyOf(original, original.length);
        redo.clear();
        return true;
    }

    public int getPixel(int row, int column) {
        requireCell(row, column);
        return pixels[row * WIDTH + column];
    }

    public int[] copyPixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    public int[] copyOriginal() {
        return Arrays.copyOf(original, original.length);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean hasChanges() {
        return !Arrays.equals(original, pixels);
    }

    public int colorCount() {
        boolean[] used = new boolean[Palette.size()];
        int count = 0;
        for (int paletteIndex : pixels) {
            if (!used[paletteIndex]) {
                used[paletteIndex] = true;
                count++;
            }
        }
        return count;
    }

    private static int[] checkedCopy(int[] values) {
        if (values == null || values.length != PIXEL_COUNT) {
            throw new IllegalArgumentException("Pattern must contain 576 pixels");
        }
        int[] copy = Arrays.copyOf(values, values.length);
        for (int paletteIndex : copy) {
            Palette.requireValidIndex(paletteIndex);
        }
        return copy;
    }

    private static void requireCell(int row, int column) {
        if (row < 0 || row >= HEIGHT || column < 0 || column >= WIDTH) {
            throw new IllegalArgumentException("Pixel coordinate is outside the 24x24 canvas");
        }
    }

    private static void pushHistory(Deque<int[]> history, int[] snapshot) {
        if (history.size() >= MAX_HISTORY) {
            history.removeFirst();
        }
        history.addLast(Arrays.copyOf(snapshot, snapshot.length));
    }
}
