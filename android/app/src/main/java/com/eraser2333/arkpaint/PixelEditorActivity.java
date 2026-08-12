package com.eraser2333.arkpaint;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eraser2333.arkpaint.editing.PatternEditorModel;
import com.eraser2333.arkpaint.imaging.Palette;
import com.eraser2333.arkpaint.ui.OfficialPaletteView;
import com.eraser2333.arkpaint.ui.PixelEditorView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

public final class PixelEditorActivity extends AppCompatActivity {
    public static final String EXTRA_PATTERN = "com.eraser2333.arkpaint.PATTERN";

    private static final String STATE_ORIGINAL = "editor_original";
    private static final String STATE_CURRENT = "editor_current";
    private static final String STATE_SELECTED = "editor_selected";
    private static final String STATE_SHOW_NUMBERS = "editor_show_numbers";

    private PatternEditorModel model;
    private PixelEditorView editorView;
    private OfficialPaletteView paletteView;
    private MaterialCardView selectedSwatch;
    private TextView selectedColorLabel;
    private TextView editorStats;
    private Button undoButton;
    private Button redoButton;
    private Button eyedropperButton;
    private MaterialSwitch numberSwitch;
    private int selectedPaletteIndex;
    private boolean eyedropper;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_pixel_editor);
        applySystemBarInsets(findViewById(R.id.editorRoot));

        int[] original = savedInstanceState == null
                ? getIntent().getIntArrayExtra(EXTRA_PATTERN)
                : savedInstanceState.getIntArray(STATE_ORIGINAL);
        int[] current = savedInstanceState == null
                ? original
                : savedInstanceState.getIntArray(STATE_CURRENT);
        try {
            model = new PatternEditorModel(original, current);
        } catch (IllegalArgumentException exception) {
            Toast.makeText(this, R.string.editor_invalid_pattern, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        bindViews();
        selectedPaletteIndex = savedInstanceState == null
                ? model.getPixel(0, 0)
                : savedInstanceState.getInt(STATE_SELECTED, model.getPixel(0, 0));
        boolean showNumbers = savedInstanceState == null
                || savedInstanceState.getBoolean(STATE_SHOW_NUMBERS, true);
        setupEditor(showNumbers);
        updateSelectedColor();
        updateEditorState();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmCancelIfNeeded();
            }
        });
    }

    private void bindViews() {
        editorView = findViewById(R.id.pixelEditor);
        paletteView = findViewById(R.id.officialPalette);
        selectedSwatch = findViewById(R.id.selectedColorSwatch);
        selectedColorLabel = findViewById(R.id.selectedColorLabel);
        editorStats = findViewById(R.id.editorStats);
        undoButton = findViewById(R.id.undoButton);
        redoButton = findViewById(R.id.redoButton);
        eyedropperButton = findViewById(R.id.eyedropperButton);
        numberSwitch = findViewById(R.id.editorNumberSwitch);
    }

    private void setupEditor(boolean showNumbers) {
        editorView.setModel(model);
        editorView.setSelectedPaletteIndex(selectedPaletteIndex);
        editorView.setShowNumbers(showNumbers);
        editorView.setEyedropper(false);
        editorView.setListener(new PixelEditorView.Listener() {
            @Override
            public void onPatternChanged() {
                updateStats();
            }

            @Override
            public void onStrokeCommitted() {
                updateEditorState();
            }

            @Override
            public void onColorPicked(int paletteIndex) {
                selectColor(paletteIndex);
                setEyedropper(false);
                Toast.makeText(
                        PixelEditorActivity.this,
                        getString(R.string.editor_color_picked, Palette.number(paletteIndex)),
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        paletteView.setSelectedPaletteIndex(selectedPaletteIndex);
        paletteView.setOnColorSelectedListener(this::selectColor);

        numberSwitch.setChecked(showNumbers);
        numberSwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> editorView.setShowNumbers(checked)
        );
        undoButton.setOnClickListener(view -> {
            if (model.undo()) {
                editorView.invalidate();
                updateEditorState();
            }
        });
        redoButton.setOnClickListener(view -> {
            if (model.redo()) {
                editorView.invalidate();
                updateEditorState();
            }
        });
        eyedropperButton.setOnClickListener(view -> setEyedropper(!eyedropper));
        findViewById(R.id.editorResetButton).setOnClickListener(view -> {
            if (model.resetToOriginal()) {
                editorView.invalidate();
                updateEditorState();
            }
        });
        findViewById(R.id.editorSaveButton).setOnClickListener(view -> saveAndFinish());
        findViewById(R.id.editorCancelButton).setOnClickListener(
                view -> confirmCancelIfNeeded()
        );
    }

    private void selectColor(int paletteIndex) {
        selectedPaletteIndex = paletteIndex;
        editorView.setSelectedPaletteIndex(paletteIndex);
        paletteView.setSelectedPaletteIndex(paletteIndex);
        updateSelectedColor();
    }

    private void updateSelectedColor() {
        selectedSwatch.setCardBackgroundColor(Palette.COLORS[selectedPaletteIndex]);
        selectedColorLabel.setText(getString(
                R.string.editor_selected_color,
                Palette.number(selectedPaletteIndex),
                Palette.hex(selectedPaletteIndex)
        ));
    }

    private void updateEditorState() {
        undoButton.setEnabled(model.canUndo());
        redoButton.setEnabled(model.canRedo());
        updateStats();
    }

    private void updateStats() {
        editorStats.setText(getString(R.string.editor_stats, model.colorCount()));
    }

    private void setEyedropper(boolean enabled) {
        eyedropper = enabled;
        editorView.setEyedropper(enabled);
        eyedropperButton.setSelected(enabled);
        eyedropperButton.setText(
                enabled ? R.string.editor_eyedropper_active : R.string.editor_eyedropper
        );
    }

    private void saveAndFinish() {
        model.endStroke();
        Intent result = new Intent().putExtra(EXTRA_PATTERN, model.copyPixels());
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void confirmCancelIfNeeded() {
        if (!model.hasChanges()) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_discard_title)
                .setMessage(R.string.editor_discard_message)
                .setNegativeButton(R.string.editor_keep_editing, null)
                .setPositiveButton(R.string.editor_discard, (dialog, which) -> finish())
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntArray(STATE_ORIGINAL, model.copyOriginal());
        outState.putIntArray(STATE_CURRENT, model.copyPixels());
        outState.putInt(STATE_SELECTED, selectedPaletteIndex);
        outState.putBoolean(STATE_SHOW_NUMBERS, numberSwitch.isChecked());
    }

    private static void applySystemBarInsets(View root) {
        int start = root.getPaddingStart();
        int top = root.getPaddingTop();
        int end = root.getPaddingEnd();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPaddingRelative(
                    start + bars.left,
                    top + bars.top,
                    end + bars.right,
                    bottom + bars.bottom
            );
            return windowInsets;
        });
    }
}
