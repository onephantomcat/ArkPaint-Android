package com.eraser2333.arkpaint;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.eraser2333.arkpaint.automation.ArkPaintAccessibilityService;
import com.eraser2333.arkpaint.automation.Calibration;
import com.eraser2333.arkpaint.data.PatternStore;
import com.eraser2333.arkpaint.imaging.ImageLoader;
import com.eraser2333.arkpaint.imaging.ImageProcessor;
import com.eraser2333.arkpaint.imaging.Palette;
import com.eraser2333.arkpaint.imaging.ProcessedPattern;
import com.eraser2333.arkpaint.imaging.ProcessingOptions;
import com.eraser2333.arkpaint.ui.CropImageView;
import com.eraser2333.arkpaint.ui.PaletteUsageView;
import com.eraser2333.arkpaint.ui.PixelPreviewView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends AppCompatActivity {
    private static final String STATE_IMAGE_URI = "image_uri";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger processingGeneration = new AtomicInteger();
    private final AtomicInteger imageLoadGeneration = new AtomicInteger();

    private PatternStore patternStore;
    private PixelPreviewView previewView;
    private PaletteUsageView paletteUsageView;
    private TextView imageStatus;
    private TextView colorCount;
    private TextView serviceStatus;
    private TextView calibrationStatus;
    private TextView sharpnessValue;
    private TextView brightnessValue;
    private TextView contrastValue;
    private TextView saturationValue;
    private TextView tapDelayValue;
    private Spinner resizeSpinner;
    private Spinner resamplingSpinner;
    private Spinner mappingSpinner;
    private Spinner mergeSpinner;
    private Spinner transparentSpinner;
    private MaterialSwitch ditherSwitch;
    private MaterialSwitch showNumbersSwitch;
    private SeekBar sharpnessSeek;
    private SeekBar brightnessSeek;
    private SeekBar contrastSeek;
    private SeekBar saturationSeek;
    private SeekBar tapDelaySeek;
    private Button exportButton;
    private Button editButton;
    private Button cropButton;
    private Button prepareButton;
    private Button overlayPermissionButton;
    private Button showOverlayButton;

    private ActivityResultLauncher<String[]> imagePicker;
    private ActivityResultLauncher<String> imageExporter;
    private ActivityResultLauncher<Intent> pixelEditor;
    private Bitmap sourceBitmap;
    private Bitmap croppedBitmap;
    private ProcessedPattern processedPattern;
    private Uri sourceUri;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        applySystemBarInsets(findViewById(R.id.mainRoot));
        patternStore = new PatternStore(this);
        bindViews();
        setupLaunchers();
        setupSpinners();
        setupControls();
        restoreStoredPattern();

        if (savedInstanceState != null) {
            String savedUri = savedInstanceState.getString(STATE_IMAGE_URI);
            if (savedUri != null) {
                loadImage(Uri.parse(savedUri), false, processedPattern == null);
            }
        }
    }

    private void bindViews() {
        previewView = findViewById(R.id.pixelPreview);
        paletteUsageView = findViewById(R.id.paletteUsage);
        imageStatus = findViewById(R.id.imageStatus);
        colorCount = findViewById(R.id.colorCount);
        serviceStatus = findViewById(R.id.serviceStatus);
        calibrationStatus = findViewById(R.id.calibrationStatus);
        sharpnessValue = findViewById(R.id.sharpnessValue);
        brightnessValue = findViewById(R.id.brightnessValue);
        contrastValue = findViewById(R.id.contrastValue);
        saturationValue = findViewById(R.id.saturationValue);
        tapDelayValue = findViewById(R.id.tapDelayValue);
        resizeSpinner = findViewById(R.id.resizeSpinner);
        resamplingSpinner = findViewById(R.id.resamplingSpinner);
        mappingSpinner = findViewById(R.id.mappingSpinner);
        mergeSpinner = findViewById(R.id.mergeSpinner);
        transparentSpinner = findViewById(R.id.transparentSpinner);
        ditherSwitch = findViewById(R.id.ditherSwitch);
        showNumbersSwitch = findViewById(R.id.showNumbersSwitch);
        sharpnessSeek = findViewById(R.id.sharpnessSeek);
        brightnessSeek = findViewById(R.id.brightnessSeek);
        contrastSeek = findViewById(R.id.contrastSeek);
        saturationSeek = findViewById(R.id.saturationSeek);
        tapDelaySeek = findViewById(R.id.tapDelaySeek);
        exportButton = findViewById(R.id.exportButton);
        editButton = findViewById(R.id.editButton);
        cropButton = findViewById(R.id.cropButton);
        prepareButton = findViewById(R.id.prepareButton);
        overlayPermissionButton = findViewById(R.id.overlayPermissionButton);
        showOverlayButton = findViewById(R.id.showOverlayButton);
    }

    private void setupLaunchers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) {
                        return;
                    }
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (SecurityException ignored) {
                        // Some document providers grant access only for the current process.
                    }
                    loadImage(uri);
                }
        );
        imageExporter = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("image/png"),
                uri -> {
                    if (uri != null) {
                        exportPreview(uri);
                    }
                }
        );
        pixelEditor = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != RESULT_OK || data == null) {
                        return;
                    }
                    int[] edited = data.getIntArrayExtra(PixelEditorActivity.EXTRA_PATTERN);
                    try {
                        setProcessedPattern(
                                ProcessedPattern.fromPaletteIndices(edited),
                                R.string.status_edited
                        );
                    } catch (IllegalArgumentException exception) {
                        Toast.makeText(this, R.string.editor_invalid_pattern, Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    private void setupSpinners() {
        setSpinnerItems(resizeSpinner, new String[]{"手动方形裁剪", "整图拉伸"});
        setSpinnerItems(resamplingSpinner, new String[]{
                "Lanczos 清晰缩放（推荐）",
                "盒式平均（干净）",
                "邻近采样（像素画）",
                "双线性（较柔和）"
        });
        setSpinnerItems(mappingSpinner, new String[]{
                "CIEDE2000 色差",
                "OKLab 欧氏距离",
                "CIE Lab 感知距离",
                "加权 RGB 感知距离",
                "RGB 欧氏距离"
        });
        mappingSpinner.setSelection(2, false);
        setSpinnerItems(mergeSpinner, new String[]{"1×1 原始格", "2×2 合并", "3×3 合并", "4×4 合并"});
        setSpinnerItems(transparentSpinner, Palette.labels());
        transparentSpinner.setSelection(Palette.WHITE_INDEX, false);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                scheduleProcessing();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        resizeSpinner.setOnItemSelectedListener(listener);
        resamplingSpinner.setOnItemSelectedListener(listener);
        mappingSpinner.setOnItemSelectedListener(listener);
        mergeSpinner.setOnItemSelectedListener(listener);
        transparentSpinner.setOnItemSelectedListener(listener);
    }

    private void setupControls() {
        findViewById(R.id.importButton).setOnClickListener(
                view -> imagePicker.launch(new String[]{"image/*"})
        );
        exportButton.setOnClickListener(view -> {
            if (processedPattern == null) {
                Toast.makeText(this, R.string.no_pattern_to_export, Toast.LENGTH_SHORT).show();
            } else {
                imageExporter.launch("arkpaint_24x24.png");
            }
        });
        editButton.setOnClickListener(view -> openPixelEditor());
        cropButton.setOnClickListener(view -> showCropEditor());
        showNumbersSwitch.setChecked(true);
        showNumbersSwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> previewView.setShowNumbers(checked)
        );
        ditherSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> scheduleProcessing());
        sharpnessValue.setText(String.valueOf(sharpnessSeek.getProgress()));
        sharpnessSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sharpnessValue.setText(String.valueOf(progress));
                if (fromUser) {
                    scheduleProcessing();
                }
            }
        });
        setupAdjustmentSeek(brightnessSeek, brightnessValue);
        setupAdjustmentSeek(contrastSeek, contrastValue);
        setupAdjustmentSeek(saturationSeek, saturationValue);
        tapDelaySeek.setProgress(Math.max(0, patternStore.getTapDelayMs() - 10));
        tapDelayValue.setText(String.format(Locale.ROOT, "%d ms", currentTapDelay()));
        tapDelaySeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tapDelayValue.setText(String.format(Locale.ROOT, "%d ms", currentTapDelay()));
                if (fromUser && processedPattern != null) {
                    persistPattern();
                }
            }
        });
        findViewById(R.id.resetButton).setOnClickListener(view -> {
            sharpnessSeek.setProgress(35);
            brightnessSeek.setProgress(100);
            contrastSeek.setProgress(100);
            saturationSeek.setProgress(100);
            ditherSwitch.setChecked(false);
            scheduleProcessing();
        });
        findViewById(R.id.accessibilityButton).setOnClickListener(
                view -> showAccessibilityDisclosure()
        );
        overlayPermissionButton.setOnClickListener(view -> openOverlayPermissionSettings());
        showOverlayButton.setOnClickListener(view -> requestOverlayController());
        prepareButton.setOnClickListener(view -> prepareForDrawing());
    }

    private void setupAdjustmentSeek(SeekBar seekBar, TextView valueView) {
        seekBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar changed, int progress, boolean fromUser) {
                valueView.setText(String.valueOf(progress - 100));
                if (fromUser) {
                    scheduleProcessing();
                }
            }
        });
    }

    private void loadImage(Uri uri) {
        loadImage(uri, true, true);
    }

    private void loadImage(Uri uri, boolean openCropEditor, boolean processAfterLoad) {
        sourceUri = uri;
        if (processAfterLoad) {
            imageStatus.setText(R.string.processing_image);
        }
        int request = imageLoadGeneration.incrementAndGet();
        processingGeneration.incrementAndGet();
        worker.execute(() -> {
            try {
                Bitmap loaded = ImageLoader.load(this, uri);
                runOnUiThread(() -> {
                    if (request != imageLoadGeneration.get()) {
                        loaded.recycle();
                        return;
                    }
                    sourceBitmap = loaded;
                    croppedBitmap = CropImageView.createCenterSquare(loaded);
                    cropButton.setEnabled(true);
                    if (processAfterLoad) {
                        scheduleProcessing();
                    }
                    if (openCropEditor) {
                        showCropEditor();
                    }
                });
            } catch (IOException | RuntimeException exception) {
                runOnUiThread(() -> {
                    if (request != imageLoadGeneration.get()) {
                        return;
                    }
                    imageStatus.setText(R.string.pick_image_failed);
                    Toast.makeText(this, R.string.pick_image_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private Bitmap processingSource() {
        if (resizeSpinner != null
                && resizeSpinner.getSelectedItemPosition() == 0
                && croppedBitmap != null) {
            return croppedBitmap;
        }
        return sourceBitmap;
    }

    private void showCropEditor() {
        Bitmap source = sourceBitmap;
        if (source == null || source.isRecycled()) {
            Toast.makeText(this, R.string.crop_no_image, Toast.LENGTH_SHORT).show();
            return;
        }
        CropImageView cropView = new CropImageView(this);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int editorHeight = Math.max(
                dp(240),
                Math.min(dp(430), Math.round(screenHeight * 0.58f))
        );
        FrameLayout container = new FrameLayout(this);
        container.addView(cropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                editorHeight
        ));
        cropView.setBitmap(source);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.crop_title)
                .setMessage(R.string.crop_instructions)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.crop_reset, null)
                .setPositiveButton(R.string.crop_apply, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    view -> cropView.resetTransform()
            );
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    croppedBitmap = cropView.createCroppedBitmap();
                    resizeSpinner.setSelection(0);
                    scheduleProcessing();
                    dialog.dismiss();
                } catch (RuntimeException exception) {
                    Toast.makeText(this, R.string.crop_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private void scheduleProcessing() {
        Bitmap source = processingSource();
        if (source == null) {
            return;
        }
        int generation = processingGeneration.incrementAndGet();
        imageStatus.setText(R.string.processing_image);
        ProcessingOptions options = readOptions();
        previewView.postDelayed(() -> {
            if (generation != processingGeneration.get()) {
                return;
            }
            worker.execute(() -> {
                try {
                    ProcessedPattern result = ImageProcessor.process(source, options);
                    runOnUiThread(() -> applyProcessingResult(generation, source, result));
                } catch (RuntimeException exception) {
                    runOnUiThread(() -> {
                        if (generation == processingGeneration.get()) {
                            imageStatus.setText(R.string.processing_failed);
                            Toast.makeText(
                                    this,
                                    getString(R.string.processing_failed) + ": " + exception.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });
        }, 160L);
    }

    private void applyProcessingResult(
            int generation,
            Bitmap source,
            ProcessedPattern result
    ) {
        if (generation != processingGeneration.get() || source != processingSource()) {
            result.getPreview().recycle();
            return;
        }
        setProcessedPattern(result, R.string.status_ready);
    }

    private void setProcessedPattern(ProcessedPattern result, int statusText) {
        if (processedPattern != null && processedPattern.getPreview() != result.getPreview()) {
            processedPattern.getPreview().recycle();
        }
        processedPattern = result;
        int[] indices = result.getPaletteIndices();
        previewView.setPattern(indices);
        paletteUsageView.setPattern(indices);
        imageStatus.setText(statusText);
        colorCount.setText(String.format(Locale.ROOT, "%02d COLORS", result.getColorCount()));
        exportButton.setEnabled(true);
        editButton.setEnabled(true);
        prepareButton.setEnabled(true);
        persistPattern();
    }

    private void openPixelEditor() {
        if (processedPattern == null) {
            Toast.makeText(this, R.string.editor_no_pattern, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, PixelEditorActivity.class)
                .putExtra(PixelEditorActivity.EXTRA_PATTERN, processedPattern.getPaletteIndices());
        pixelEditor.launch(intent);
    }

    private ProcessingOptions readOptions() {
        ProcessingOptions options = new ProcessingOptions();
        options.resizeMode = resizeSpinner.getSelectedItemPosition() == 0
                ? ProcessingOptions.ResizeMode.CROP
                : ProcessingOptions.ResizeMode.STRETCH;
        ProcessingOptions.ResamplingMethod[] resamplingMethods = {
                ProcessingOptions.ResamplingMethod.LANCZOS,
                ProcessingOptions.ResamplingMethod.BOX,
                ProcessingOptions.ResamplingMethod.NEAREST,
                ProcessingOptions.ResamplingMethod.BILINEAR
        };
        options.resamplingMethod = resamplingMethods[Math.max(
                0,
                resamplingSpinner.getSelectedItemPosition()
        )];
        ProcessingOptions.MappingMethod[] methods = {
                ProcessingOptions.MappingMethod.CIEDE2000,
                ProcessingOptions.MappingMethod.OKLAB,
                ProcessingOptions.MappingMethod.LAB,
                ProcessingOptions.MappingMethod.WEIGHTED_RGB,
                ProcessingOptions.MappingMethod.RGB
        };
        options.mappingMethod = methods[Math.max(0, mappingSpinner.getSelectedItemPosition())];
        options.mergePixels = Math.max(1, mergeSpinner.getSelectedItemPosition() + 1);
        options.transparentPaletteIndex = Math.max(0, transparentSpinner.getSelectedItemPosition());
        options.dither = ditherSwitch.isChecked();
        options.sharpness = sharpnessSeek.getProgress();
        options.brightness = brightnessSeek.getProgress() - 100;
        options.contrast = contrastSeek.getProgress() - 100;
        options.saturation = saturationSeek.getProgress() - 100;
        return options;
    }

    private void persistPattern() {
        if (processedPattern == null) {
            return;
        }
        patternStore.savePattern(processedPattern.getPaletteIndices(), currentTapDelay());
        Intent update = new Intent(ArkPaintAccessibilityService.ACTION_PATTERN_UPDATED)
                .setPackage(getPackageName());
        sendBroadcast(update);
    }

    private int currentTapDelay() {
        return tapDelaySeek.getProgress() + 10;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void prepareForDrawing() {
        if (processedPattern == null) {
            Toast.makeText(this, R.string.no_pattern_to_export, Toast.LENGTH_SHORT).show();
            return;
        }
        persistPattern();
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDisclosure();
            return;
        }
        sendShowControllerBroadcast();
        Toast.makeText(this, R.string.pattern_saved, Toast.LENGTH_LONG).show();
    }

    private void requestOverlayController() {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDisclosure();
            return;
        }
        if (!ArkPaintAccessibilityService.isRunning()) {
            Toast.makeText(this, R.string.service_restart_required, Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        sendShowControllerBroadcast();
        Toast.makeText(this, R.string.overlay_retry_sent, Toast.LENGTH_SHORT).show();
        previewView.postDelayed(this::updateAutomationStatus, 650L);
    }

    private void sendShowControllerBroadcast() {
        Intent show = new Intent(ArkPaintAccessibilityService.ACTION_SHOW_CONTROLLER)
                .setPackage(getPackageName());
        sendBroadcast(show);
    }

    private void openOverlayPermissionSettings() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        try {
            startActivity(intent);
        } catch (RuntimeException exception) {
            Intent fallback = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(fallback);
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException exception) {
            Toast.makeText(
                    this,
                    R.string.open_accessibility_settings_failed,
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void exportPreview(Uri uri) {
        ProcessedPattern pattern = processedPattern;
        if (pattern == null) {
            return;
        }
        worker.execute(() -> {
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "w")) {
                if (stream == null || !pattern.getPreview().compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Unable to encode PNG");
                }
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.export_success,
                        Toast.LENGTH_SHORT
                ).show());
            } catch (IOException exception) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        R.string.export_failed,
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showAccessibilityDisclosure() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.service_disclosure_title)
                .setMessage(R.string.service_disclosure)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.continue_to_settings,
                        (dialog, which) -> openAccessibilitySettings()
                )
                .show();
    }

    private void restoreStoredPattern() {
        int[] stored = patternStore.loadPattern();
        if (stored == null) {
            return;
        }
        setProcessedPattern(ProcessedPattern.fromPaletteIndices(stored), R.string.status_restored);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAutomationStatus();
        previewView.postDelayed(this::updateAutomationStatus, 700L);
    }

    private void updateAutomationStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        boolean running = ArkPaintAccessibilityService.isRunning();
        boolean controllerReady = ArkPaintAccessibilityService.isControllerReady();
        String serviceError = patternStore.getServiceError();
        if (!enabled) {
            serviceStatus.setText(R.string.status_service_off);
        } else if (!running) {
            serviceStatus.setText(serviceError == null
                    ? getString(R.string.status_service_stopped)
                    : getString(R.string.status_service_error, serviceError));
        } else if (!controllerReady) {
            serviceStatus.setText(serviceError == null
                    ? getString(R.string.status_service_starting)
                    : getString(R.string.status_service_error, serviceError));
        } else {
            serviceStatus.setText(R.string.status_service_on);
        }
        serviceStatus.setTextColor(ContextCompat.getColor(
                this,
                controllerReady ? R.color.success : (enabled ? R.color.signal : R.color.muted)
        ));
        overlayPermissionButton.setText(Settings.canDrawOverlays(this)
                ? R.string.overlay_permission_granted
                : R.string.overlay_permission_xiaomi);
        showOverlayButton.setEnabled(enabled);
        Calibration calibration = patternStore.loadCalibration();
        calibrationStatus.setText(
                calibration != null ? R.string.status_calibrated : R.string.status_uncalibrated
        );
        calibrationStatus.setTextColor(ContextCompat.getColor(
                this,
                calibration != null ? R.color.success : R.color.muted
        ));
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(this, ArkPaintAccessibilityService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName component = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(component)) {
                return true;
            }
        }
        return false;
    }

    private void setSpinnerItems(Spinner spinner, String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                values
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                ContextCompat.getColor(this, R.color.panel_raised)
        ));
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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (sourceUri != null) {
            outState.putString(STATE_IMAGE_URI, sourceUri.toString());
        }
    }

    @Override
    protected void onDestroy() {
        processingGeneration.incrementAndGet();
        imageLoadGeneration.incrementAndGet();
        worker.shutdownNow();
        super.onDestroy();
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
