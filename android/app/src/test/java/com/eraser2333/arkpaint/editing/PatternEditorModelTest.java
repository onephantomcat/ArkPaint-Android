package com.eraser2333.arkpaint.editing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class PatternEditorModelTest {
    @Test
    public void strokePaintsMultiplePixelsAsOneUndoStep() {
        PatternEditorModel model = new PatternEditorModel(solidPattern(3));

        model.beginStroke();
        assertTrue(model.paint(2, 4, 12));
        assertTrue(model.paint(2, 5, 12));
        assertTrue(model.endStroke());

        assertEquals(12, model.getPixel(2, 4));
        assertEquals(12, model.getPixel(2, 5));
        assertTrue(model.undo());
        assertEquals(3, model.getPixel(2, 4));
        assertEquals(3, model.getPixel(2, 5));
        assertTrue(model.redo());
        assertEquals(12, model.getPixel(2, 4));
        assertEquals(12, model.getPixel(2, 5));
    }

    @Test
    public void newStrokeClearsRedoHistory() {
        PatternEditorModel model = new PatternEditorModel(solidPattern(0));
        paintSingle(model, 0, 0, 1);
        assertTrue(model.undo());
        assertTrue(model.canRedo());

        paintSingle(model, 1, 1, 2);

        assertFalse(model.canRedo());
        assertEquals(2, model.getPixel(1, 1));
    }

    @Test
    public void resetReturnsToConvertedPatternAndCanBeUndone() {
        int[] converted = solidPattern(7);
        PatternEditorModel model = new PatternEditorModel(converted);
        paintSingle(model, 4, 9, 21);

        assertTrue(model.resetToOriginal());
        assertArrayEquals(converted, model.copyPixels());
        assertFalse(model.hasChanges());
        assertTrue(model.undo());
        assertEquals(21, model.getPixel(4, 9));
        assertTrue(model.hasChanges());
    }

    @Test
    public void reportsUniquePaletteColorCount() {
        PatternEditorModel model = new PatternEditorModel(solidPattern(0));
        paintSingle(model, 0, 0, 1);
        paintSingle(model, 0, 1, 39);

        assertEquals(3, model.colorCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPaletteIndex() {
        PatternEditorModel model = new PatternEditorModel(solidPattern(0));
        model.beginStroke();
        model.paint(0, 0, 40);
    }

    private static void paintSingle(
            PatternEditorModel model,
            int row,
            int column,
            int color
    ) {
        model.beginStroke();
        model.paint(row, column, color);
        model.endStroke();
    }

    private static int[] solidPattern(int paletteIndex) {
        int[] pattern = new int[PatternEditorModel.PIXEL_COUNT];
        Arrays.fill(pattern, paletteIndex);
        return pattern;
    }
}
