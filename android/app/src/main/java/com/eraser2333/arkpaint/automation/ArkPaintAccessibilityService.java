package com.eraser2333.arkpaint.automation;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.eraser2333.arkpaint.MainActivity;
import com.eraser2333.arkpaint.R;
import com.eraser2333.arkpaint.data.PatternStore;
import com.eraser2333.arkpaint.imaging.Palette;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ArkPaintAccessibilityService extends AccessibilityService {
    private static final String TAG = "ArkPaintService";
    public static final String ACTION_PATTERN_UPDATED =
            "com.eraser2333.arkpaint.action.PATTERN_UPDATED";
    public static final String ACTION_SHOW_CONTROLLER =
            "com.eraser2333.arkpaint.action.SHOW_CONTROLLER";

    private static final AtomicBoolean SERVICE_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CONTROLLER_READY = new AtomicBoolean(false);
    private static final int PALETTE_TOP_START_ROW = 0;
    private static final int PALETTE_BOTTOM_START_ROW = 4;
    private static final int MAX_PALETTE_SCROLL_ATTEMPTS = 2;
    private static final int MAX_REPAIR_PASSES = 2;
    private static final int MAX_AUTOMATIC_REPAIR_CELLS = 96;
    private static final long RECENT_VERIFICATION_MS = 15_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AutomationRunState runState = new AutomationRunState();

    private PatternStore patternStore;
    private Context overlayContext;
    private WindowManager windowManager;
    private View controllerView;
    private WindowManager.LayoutParams controllerParams;
    private int controllerWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    private TextView controllerStatus;
    private View openAppButton;
    private Button calibrateButton;
    private Button verifyButton;
    private Button drawButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;
    private CalibrationOverlayView calibrationOverlay;
    private boolean receiverRegistered;
    private boolean destroyed;
    private int controllerRetryCount;
    private volatile VerifiedLayout recentVerification;
    private volatile ProgressSnapshot latestProgress;

    private final BroadcastReceiver patternReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_SHOW_CONTROLLER.equals(intent.getAction())) {
                showControllerSafely();
            } else {
                refreshController();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        SERVICE_RUNNING.set(true);
        CONTROLLER_READY.set(false);
        destroyed = false;
        patternStore = new PatternStore(this);
        patternStore.clearServiceError();
        try {
            registerPatternReceiver();
        } catch (RuntimeException exception) {
            reportServiceError("注册控制通道失败", exception);
        }
        // HyperOS 2 can report the service as faulty when a window is attached
        // synchronously while the accessibility binding is still settling.
        mainHandler.postDelayed(this::showControllerSafely, 320L);
    }

    private void registerPatternReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_PATTERN_UPDATED);
        filter.addAction(ACTION_SHOW_CONTROLLER);
        ContextCompat.registerReceiver(
                this,
                patternReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
    }

    public static boolean isRunning() {
        return SERVICE_RUNNING.get();
    }

    public static boolean isControllerReady() {
        return CONTROLLER_READY.get();
    }

    private void showControllerSafely() {
        if (destroyed) {
            return;
        }
        try {
            showController();
            controllerRetryCount = 0;
            CONTROLLER_READY.set(true);
            if (patternStore != null) {
                patternStore.clearServiceError();
            }
        } catch (RuntimeException exception) {
            CONTROLLER_READY.set(false);
            tearDownController();
            reportServiceError("悬浮条启动失败", exception);
            if (controllerRetryCount < 2) {
                controllerRetryCount++;
                long delay = 700L * controllerRetryCount;
                mainHandler.postDelayed(this::showControllerSafely, delay);
            }
        }
    }

    private void showController() {
        if (controllerView != null && controllerView.isAttachedToWindow()) {
            controllerView.setVisibility(View.VISIBLE);
            refreshController();
            return;
        }
        tearDownController();
        RuntimeException accessibilityFailure;
        try {
            attachController(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true);
            return;
        } catch (RuntimeException windowContextFailure) {
            tearDownController();
            try {
                attachController(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, false);
                return;
            } catch (RuntimeException serviceContextFailure) {
                serviceContextFailure.addSuppressed(windowContextFailure);
                accessibilityFailure = serviceContextFailure;
                tearDownController();
            }
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                attachController(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, true);
                return;
            } catch (RuntimeException windowContextFailure) {
                tearDownController();
                try {
                    attachController(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false);
                    return;
                } catch (RuntimeException serviceContextFailure) {
                    serviceContextFailure.addSuppressed(windowContextFailure);
                    serviceContextFailure.addSuppressed(accessibilityFailure);
                    throw serviceContextFailure;
                }
            }
        }
        throw new IllegalStateException(
                "HyperOS 拒绝悬浮层，请回到 ArkPaint 开启“显示悬浮窗”权限",
                accessibilityFailure
        );
    }

    @SuppressLint("ClickableViewAccessibility")
    private void attachController(int windowType, boolean useWindowContext) {
        Context windowContext = createOverlayContext(windowType, useWindowContext);
        WindowManager manager = (WindowManager) windowContext.getSystemService(WINDOW_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("系统未提供悬浮窗口服务");
        }
        FrameLayout inflationRoot = new FrameLayout(windowContext);
        View newController = LayoutInflater.from(windowContext).inflate(
                R.layout.overlay_controller,
                inflationRoot,
                false
        );
        TextView newStatus = newController.findViewById(R.id.overlayStatus);
        Button newCalibrate = newController.findViewById(R.id.overlayCalibrate);
        Button newVerify = newController.findViewById(R.id.overlayVerify);
        Button newDraw = newController.findViewById(R.id.overlayDraw);
        Button newPause = newController.findViewById(R.id.overlayPause);
        Button newResume = newController.findViewById(R.id.overlayResume);
        Button newStop = newController.findViewById(R.id.overlayStop);
        View newOpenApp = newController.findViewById(R.id.overlayOpenApp);

        WindowManager.LayoutParams newParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        newParams.gravity = Gravity.TOP | Gravity.START;
        newParams.x = dp(16);
        newParams.y = dp(92);
        newParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        manager.addView(newController, newParams);

        overlayContext = windowContext;
        windowManager = manager;
        controllerView = newController;
        controllerParams = newParams;
        controllerWindowType = windowType;
        controllerStatus = newStatus;
        openAppButton = newOpenApp;
        calibrateButton = newCalibrate;
        verifyButton = newVerify;
        drawButton = newDraw;
        pauseButton = newPause;
        resumeButton = newResume;
        stopButton = newStop;

        calibrateButton.setOnClickListener(view -> beginCalibration());
        verifyButton.setOnClickListener(view -> verifyCurrentLayout());
        drawButton.setOnClickListener(view -> drawPattern());
        pauseButton.setOnClickListener(view -> pauseDrawing());
        resumeButton.setOnClickListener(view -> resumeDrawing());
        stopButton.setOnClickListener(view -> requestStop());
        pauseButton.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                view.performClick();
                view.setPressed(false);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
            }
            return true;
        });
        stopButton.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                view.performClick();
                view.setPressed(false);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
            }
            return true;
        });
        openAppButton.setOnClickListener(view -> openMainActivityFromOverlay());
        installDragHandler(controllerView.findViewById(R.id.overlayDragHandle));
        refreshController();
    }

    private Context createOverlayContext(int windowType, boolean useWindowContext) {
        Context base = this;
        if (useWindowContext) {
            try {
                DisplayManager displayManager = getSystemService(DisplayManager.class);
                Display display = displayManager == null
                        ? null
                        : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) {
                    base = createDisplayContext(display);
                }
                base = base.createWindowContext(windowType, null);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to create the OEM window context", exception);
                throw exception;
            }
        }
        return new ContextThemeWrapper(base, R.style.Theme_ArkPaint);
    }

    private void openMainActivityFromOverlay() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        try {
            pendingIntent.send();
        } catch (PendingIntent.CanceledException exception) {
            reportServiceError("返回应用失败", exception);
        }
    }

    private void installDragHandler(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startRawX;
            private float startRawY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (runState.isBusy()) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = event.getRawX();
                        startRawY = event.getRawY();
                        startX = controllerParams.x;
                        startY = controllerParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        controllerParams.x = Math.max(
                                0,
                                startX + Math.round(event.getRawX() - startRawX)
                        );
                        controllerParams.y = Math.max(
                                0,
                                startY + Math.round(event.getRawY() - startRawY)
                        );
                        safeUpdateControllerLayout();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.performClick();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void refreshController() {
        if (controllerView == null || runState.isBusy()) {
            return;
        }
        boolean hasPattern = patternStore != null && patternStore.hasPattern();
        boolean calibrated = patternStore != null && patternStore.loadCalibration() != null;
        if (!hasPattern) {
            controllerStatus.setText(R.string.overlay_no_pattern);
        } else if (!calibrated) {
            controllerStatus.setText(R.string.overlay_need_calibration);
        } else {
            controllerStatus.setText(R.string.overlay_idle);
        }
        calibrateButton.setEnabled(true);
        verifyButton.setEnabled(calibrated);
        drawButton.setEnabled(hasPattern && calibrated);
        calibrateButton.setVisibility(View.VISIBLE);
        verifyButton.setVisibility(View.VISIBLE);
        drawButton.setVisibility(View.VISIBLE);
        pauseButton.setVisibility(View.GONE);
        resumeButton.setVisibility(View.GONE);
        stopButton.setVisibility(View.GONE);
    }

    private void beginCalibration() {
        if (runState.isBusy() || calibrationOverlay != null) {
            return;
        }
        Rect displayBounds = windowManager.getCurrentWindowMetrics().getBounds();
        if (displayBounds.width() <= displayBounds.height()) {
            setStatus(R.string.verification_not_landscape);
            return;
        }
        recentVerification = null;
        controllerView.setVisibility(View.GONE);
        Context calibrationContext = overlayContext == null ? this : overlayContext;
        calibrationOverlay = new CalibrationOverlayView(
                calibrationContext,
                new CalibrationOverlayView.Callback() {
            @Override
            public void onCompleted(List<PointF> points) {
                int width = calibrationOverlay.getWidth();
                int height = calibrationOverlay.getHeight();
                Calibration calibration = Calibration.fromFivePoints(
                        width,
                        height,
                        points.get(0),
                        points.get(1),
                        points.get(2),
                        points.get(3),
                        points.get(4)
                );
                finishCalibrationOverlay();
                if (!calibration.isValid()) {
                    setStatus(R.string.calibration_invalid);
                    return;
                }
                patternStore.saveCalibration(calibration);
                recentVerification = null;
                setStatus(R.string.calibration_done);
                refreshControllerButtonsOnly();
            }

            @Override
            public void onCancelled() {
                finishCalibrationOverlay();
                refreshController();
            }
                }
        );
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                controllerWindowType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(calibrationOverlay, params);
        } catch (RuntimeException exception) {
            calibrationOverlay = null;
            controllerView.setVisibility(View.VISIBLE);
            reportServiceError("校准层启动失败", exception);
        }
    }

    private void finishCalibrationOverlay() {
        if (calibrationOverlay != null) {
            try {
                windowManager.removeView(calibrationOverlay);
            } catch (RuntimeException ignored) {
                // The system may already have detached the overlay.
            }
            calibrationOverlay = null;
        }
        if (controllerView != null) {
            controllerView.setVisibility(View.VISIBLE);
        }
    }

    private void verifyCurrentLayout() {
        if (!beginJob(false)) {
            return;
        }
        worker.execute(() -> {
            Bitmap screenshot = null;
            try {
                screenshot = captureScreenshotBlocking();
                Calibration calibration = resolveCalibration(screenshot);
                LayoutVerifier.verifyPalette(screenshot, calibration, 0);
                rememberVerification(calibration);
                setStatus(R.string.overlay_verified);
            } catch (Exception exception) {
                setError(exception);
            } finally {
                if (screenshot != null) {
                    screenshot.recycle();
                }
                endJob();
            }
        });
    }

    private void drawPattern() {
        int[] pattern = patternStore.loadPattern();
        if (pattern == null) {
            setStatus(R.string.overlay_no_pattern);
            return;
        }
        if (!beginJob(true)) {
            return;
        }
        worker.execute(() -> runDrawing(pattern));
    }

    private void runDrawing(int[] pattern) {
        Bitmap screenshot = null;
        long jobStartedMs = SystemClock.elapsedRealtime();
        try {
            setStatus(R.string.overlay_preparing);
            screenshot = captureScreenshotBlocking();
            Calibration calibration = resolveCalibrationForDrawing(screenshot);
            PaletteSession paletteSession = new PaletteSession();

            Bitmap candidate = screenshot;
            screenshot = null;
            Bitmap topScreenshot = ensurePalettePage(
                    calibration,
                    paletteSession,
                    PALETTE_TOP_START_ROW,
                    candidate
            );
            if (topScreenshot == null) {
                throw new AutomationException("无法取得顶部调色板截图");
            }

            CanvasMatcher.Result initialComparison;
            try {
                rememberVerification(calibration);
                initialComparison = LayoutVerifier.compareCanvas(
                        topScreenshot,
                        calibration,
                        pattern,
                        paletteSession.paletteSamples
                );
            } finally {
                topScreenshot.recycle();
            }

            if (initialComparison.mismatchCount == 0) {
                setStatus(R.string.overlay_already_complete);
                return;
            }

            boolean[] paintMask = initialComparison.mismatchMask();
            int total = initialComparison.mismatchCount;
            paintMaskedCells(
                    pattern,
                    paintMask,
                    calibration,
                    paletteSession,
                    total,
                    0
            );
            awaitRunPermission();

            CanvasMatcher.Result comparison = captureCanvasComparison(
                    pattern,
                    calibration,
                    paletteSession.paletteSamples
            );
            int previousMismatchCount = comparison.mismatchCount;
            for (int pass = 1;
                    pass <= MAX_REPAIR_PASSES && comparison.mismatchCount > 0;
                    pass++) {
                if (comparison.mismatchCount > MAX_AUTOMATIC_REPAIR_CELLS) {
                    break;
                }
                paintMaskedCells(
                        pattern,
                        comparison.mismatchMask(),
                        calibration,
                        paletteSession,
                        comparison.mismatchCount,
                        pass
                );
                awaitRunPermission();
                CanvasMatcher.Result repaired = captureCanvasComparison(
                        pattern,
                        calibration,
                        paletteSession.paletteSamples
                );
                comparison = repaired;
                if (comparison.mismatchCount >= previousMismatchCount) {
                    break;
                }
                previousMismatchCount = comparison.mismatchCount;
            }

            long elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(
                    Math.max(0L, SystemClock.elapsedRealtime() - jobStartedMs)
            );
            if (comparison.mismatchCount == 0) {
                setStatusText(getString(
                        R.string.overlay_complete_verified,
                        DrawingProgress.formatDuration(elapsedSeconds)
                ));
            } else {
                setStatusText(getString(
                        R.string.overlay_incomplete,
                        comparison.mismatchCount
                ));
            }
        } catch (CancelledException exception) {
            setStatus(R.string.overlay_cancelled);
        } catch (Exception exception) {
            setError(exception);
        } finally {
            if (screenshot != null) {
                screenshot.recycle();
            }
            endJob();
        }
    }

    private void paintMaskedCells(
            int[] pattern,
            boolean[] paintMask,
            Calibration calibration,
            PaletteSession paletteSession,
            int total,
            int repairPass
    ) throws AutomationException, CancelledException, LayoutVerifier.VerificationException {
        int completed = 0;
        ProgressClock progressClock = new ProgressClock(
                SystemClock.elapsedRealtime(),
                runState.getPausedDurationMillis()
        );
        setDrawingProgress(completed, total, progressClock, repairPass);
        if (hasColorInRange(pattern, paintMask, 0, 16)) {
            Bitmap pageScreenshot = ensurePalettePage(
                    calibration,
                    paletteSession,
                    PALETTE_TOP_START_ROW,
                    null
            );
            if (pageScreenshot != null) {
                pageScreenshot.recycle();
            }
            completed = paintColorRange(
                    pattern,
                    paintMask,
                    calibration,
                    PALETTE_TOP_START_ROW,
                    0,
                    16,
                    completed,
                    total,
                    progressClock,
                    repairPass
            );
        }
        if (hasColorInRange(pattern, paintMask, 16, 40)) {
            Bitmap pageScreenshot = ensurePalettePage(
                    calibration,
                    paletteSession,
                    PALETTE_BOTTOM_START_ROW,
                    null
            );
            if (pageScreenshot != null) {
                pageScreenshot.recycle();
            }
            paintColorRange(
                    pattern,
                    paintMask,
                    calibration,
                    PALETTE_BOTTOM_START_ROW,
                    16,
                    40,
                    completed,
                    total,
                    progressClock,
                    repairPass
            );
        }
    }

    private int paintColorRange(
            int[] pattern,
            boolean[] paintMask,
            Calibration calibration,
            int paletteStartRow,
            int firstColor,
            int lastColor,
            int completed,
            int total,
            ProgressClock progressClock,
            int repairPass
    ) throws AutomationException, CancelledException {
        int tapDelay = patternStore.getTapDelayMs();
        for (int paletteIndex = firstColor; paletteIndex < lastColor; paletteIndex++) {
            awaitRunPermission();
            List<PointF> cells = new ArrayList<>();
            for (int cell = 0; cell < pattern.length; cell++) {
                if (paintMask[cell] && pattern[cell] == paletteIndex) {
                    cells.add(calibration.canvasCellCenter(cell % 24, cell / 24));
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            PointF palettePoint = calibration.paletteCenter(paletteIndex, paletteStartRow);
            dispatchTap(palettePoint.x, palettePoint.y);
            sleepCancellable(150L);
            for (PointF cell : cells) {
                awaitRunPermission();
                // Each cell is a separate one-pointer gesture. Packing several strokes into one
                // GestureDescription can be interpreted as pinch/zoom by the game's canvas.
                dispatchTap(cell.x, cell.y);
                sleepCancellable(tapDelay);
                completed++;
                setDrawingProgress(
                        completed,
                        total,
                        progressClock,
                        repairPass
                );
            }
        }
        return completed;
    }

    private Bitmap ensurePalettePage(
            Calibration calibration,
            PaletteSession paletteSession,
            int expectedStartRow,
            Bitmap candidate
    ) throws AutomationException, CancelledException, LayoutVerifier.VerificationException {
        LayoutVerifier.VerificationException lastFailure = null;
        if (candidate != null) {
            try {
                verifyPalettePage(candidate, calibration, paletteSession, expectedStartRow);
                return candidate;
            } catch (LayoutVerifier.VerificationException exception) {
                lastFailure = exception;
                paletteSession.currentStartRow = -1;
                candidate.recycle();
            }
        }
        if (paletteSession.currentStartRow == expectedStartRow) {
            return null;
        }
        boolean toTop = expectedStartRow == PALETTE_TOP_START_ROW;
        for (int attempt = 0; attempt < MAX_PALETTE_SCROLL_ATTEMPTS; attempt++) {
            scrollPaletteOnce(calibration, toTop);
            Bitmap screenshot = captureScreenshotBlocking();
            try {
                verifyPalettePage(
                        screenshot,
                        calibration,
                        paletteSession,
                        expectedStartRow
                );
                return screenshot;
            } catch (LayoutVerifier.VerificationException exception) {
                lastFailure = exception;
                paletteSession.currentStartRow = -1;
                screenshot.recycle();
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AutomationException("无法确认调色板位置");
    }

    private void verifyPalettePage(
            Bitmap screenshot,
            Calibration calibration,
            PaletteSession paletteSession,
            int expectedStartRow
    ) throws LayoutVerifier.VerificationException {
        LayoutVerifier.verifyPalette(screenshot, calibration, expectedStartRow);
        LayoutVerifier.updatePaletteSamples(
                screenshot,
                calibration,
                expectedStartRow,
                paletteSession.paletteSamples
        );
        paletteSession.currentStartRow = expectedStartRow;
        if (expectedStartRow == PALETTE_TOP_START_ROW) {
            rememberVerification(calibration);
        }
    }

    private void scrollPaletteOnce(Calibration calibration, boolean toTop)
            throws AutomationException, CancelledException {
        float x = calibration.paletteColumns[3];
        float startY = toTop ? calibration.paletteRows[0] : calibration.paletteRows[5];
        float endY = toTop ? calibration.paletteRows[5] : calibration.paletteRows[0];
        awaitRunPermission();
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 700L))
                .build();
        dispatchGestureBlocking(gesture);
        sleepCancellable(700L);
    }

    private void dispatchTap(float x, float y) throws AutomationException, CancelledException {
        awaitRunPermission();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 20L))
                .build();
        dispatchGestureBlocking(gesture);
    }

    private void dispatchGestureBlocking(GestureDescription gesture)
            throws AutomationException, CancelledException {
        while (true) {
            awaitRunPermission();
            long pauseGeneration = runState.getPauseGeneration();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean succeeded = new AtomicBoolean(false);
            AtomicBoolean deferredByControl = new AtomicBoolean(false);
            mainHandler.post(() -> {
                if (runState.isPaused() || runState.isStopRequested()) {
                    deferredByControl.set(true);
                    latch.countDown();
                    return;
                }
                boolean accepted = dispatchGesture(
                        gesture,
                        new GestureResultCallback() {
                            @Override
                            public void onCompleted(GestureDescription gestureDescription) {
                                succeeded.set(true);
                                latch.countDown();
                            }

                            @Override
                            public void onCancelled(GestureDescription gestureDescription) {
                                latch.countDown();
                            }
                        },
                        null
                );
                if (!accepted) {
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(15, TimeUnit.SECONDS)) {
                    throw new AutomationException("系统手势执行超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            boolean retryAfterPause = !succeeded.get()
                    && (deferredByControl.get()
                    || runState.isPaused()
                    || runState.getPauseGeneration() != pauseGeneration);
            awaitRunPermission();
            if (succeeded.get()) {
                return;
            }
            if (!retryAfterPause) {
                throw new AutomationException("系统拒绝了点击手势");
            }
        }
    }

    private Bitmap captureScreenshotBlocking() throws AutomationException, CancelledException {
        awaitRunPermission();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        mainHandler.post(() -> {
            if (controllerView != null) {
                controllerView.setVisibility(View.INVISIBLE);
            }
            mainHandler.postDelayed(() -> takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            try (HardwareBuffer buffer = screenshot.getHardwareBuffer()) {
                                ColorSpace colorSpace = screenshot.getColorSpace();
                                if (colorSpace == null) {
                                    colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
                                }
                                Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                                if (hardwareBitmap == null) {
                                    error.set("系统未返回可读取的截图");
                                } else {
                                    result.set(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } catch (RuntimeException exception) {
                                error.set("无法读取系统截图：" + exception.getMessage());
                            } finally {
                                restoreControllerAfterCapture();
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            error.set("截图失败，系统错误码 " + errorCode);
                            restoreControllerAfterCapture();
                            latch.countDown();
                        }
                    }
            ), 140L);
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AutomationException("系统截图超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        Bitmap bitmap = result.get();
        try {
            awaitRunPermission();
        } catch (CancelledException exception) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            throw exception;
        }
        if (bitmap == null) {
            throw new AutomationException(error.get() == null ? "系统截图失败" : error.get());
        }
        return bitmap;
    }

    private CanvasMatcher.Result captureCanvasComparison(
            int[] pattern,
            Calibration calibration,
            int[] paletteSamples
    ) throws AutomationException, CancelledException, LayoutVerifier.VerificationException {
        setStatus(R.string.overlay_checking);
        Bitmap screenshot = captureScreenshotBlocking();
        try {
            LayoutVerifier.verifyCanvas(screenshot, calibration);
            return LayoutVerifier.compareCanvas(
                    screenshot,
                    calibration,
                    pattern,
                    paletteSamples
            );
        } finally {
            screenshot.recycle();
        }
    }

    private void restoreControllerAfterCapture() {
        if (controllerView != null && calibrationOverlay == null) {
            controllerView.setVisibility(View.VISIBLE);
        }
    }

    private Calibration resolveCalibration(Bitmap screenshot)
            throws AutomationException, LayoutVerifier.VerificationException {
        Calibration saved = patternStore.loadCalibration();
        if (saved == null) {
            throw new AutomationException(getString(R.string.calibration_missing));
        }
        Calibration scaled = saved.scaledTo(screenshot.getWidth(), screenshot.getHeight());
        if (scaled == null) {
            throw new AutomationException(getString(R.string.calibration_resolution_changed));
        }
        boolean resolutionScaled = scaled.screenWidth != saved.screenWidth
                || scaled.screenHeight != saved.screenHeight;
        try {
            LayoutVerifier.verifyCanvas(screenshot, scaled);
            if (resolutionScaled) {
                patternStore.saveCalibration(scaled);
            }
            return scaled;
        } catch (LayoutVerifier.VerificationException originalFailure) {
            Calibration refined = LayoutVerifier.refineCanvasCalibration(screenshot, scaled);
            if (refined == scaled) {
                throw originalFailure;
            }
            LayoutVerifier.verifyCanvas(screenshot, refined);
            patternStore.saveCalibration(refined);
            return refined;
        }
    }

    private Calibration resolveCalibrationForDrawing(Bitmap screenshot)
            throws AutomationException, LayoutVerifier.VerificationException {
        VerifiedLayout verified = recentVerification;
        long now = SystemClock.elapsedRealtime();
        if (verified != null
                && now - verified.verifiedAtMs <= RECENT_VERIFICATION_MS
                && verified.calibration.screenWidth == screenshot.getWidth()
                && verified.calibration.screenHeight == screenshot.getHeight()) {
            try {
                LayoutVerifier.verifyCanvas(screenshot, verified.calibration);
                return verified.calibration;
            } catch (LayoutVerifier.VerificationException ignored) {
                recentVerification = null;
            }
        }
        return resolveCalibration(screenshot);
    }

    private void rememberVerification(Calibration calibration) {
        recentVerification = new VerifiedLayout(calibration, SystemClock.elapsedRealtime());
    }

    private boolean beginJob(boolean pausable) {
        if (!runState.begin(pausable)) {
            return false;
        }
        latestProgress = null;
        mainHandler.post(() -> {
            calibrateButton.setVisibility(View.GONE);
            verifyButton.setVisibility(View.GONE);
            drawButton.setVisibility(View.GONE);
            pauseButton.setVisibility(pausable ? View.VISIBLE : View.GONE);
            resumeButton.setVisibility(View.GONE);
            stopButton.setVisibility(View.VISIBLE);
            stopButton.setEnabled(true);
            openAppButton.setEnabled(false);
        });
        return true;
    }

    private void endJob() {
        runState.finish();
        latestProgress = null;
        mainHandler.post(() -> {
            calibrateButton.setVisibility(View.VISIBLE);
            verifyButton.setVisibility(View.VISIBLE);
            drawButton.setVisibility(View.VISIBLE);
            pauseButton.setVisibility(View.GONE);
            resumeButton.setVisibility(View.GONE);
            stopButton.setVisibility(View.GONE);
            stopButton.setEnabled(true);
            openAppButton.setEnabled(true);
            refreshControllerButtonsOnly();
        });
    }

    private void pauseDrawing() {
        if (!runState.requestPause()) {
            return;
        }
        mainHandler.post(() -> {
            if (pauseButton != null) {
                pauseButton.setVisibility(View.GONE);
                resumeButton.setVisibility(View.VISIBLE);
                stopButton.setVisibility(View.VISIBLE);
                stopButton.setEnabled(true);
            }
        });
        showPausedProgress();
    }

    private void resumeDrawing() {
        if (!runState.requestResume()) {
            return;
        }
        mainHandler.post(() -> {
            if (pauseButton != null) {
                pauseButton.setVisibility(View.VISIBLE);
                resumeButton.setVisibility(View.GONE);
                stopButton.setVisibility(View.VISIBLE);
                stopButton.setEnabled(true);
            }
        });
        ProgressSnapshot snapshot = latestProgress;
        if (snapshot == null) {
            setStatus(R.string.overlay_preparing);
        } else {
            renderDrawingProgress(snapshot);
        }
    }

    private void requestStop() {
        if (!runState.requestStop()) {
            return;
        }
        setStatus(R.string.overlay_stopping);
        mainHandler.post(() -> {
            if (pauseButton != null) {
                pauseButton.setVisibility(View.GONE);
                resumeButton.setVisibility(View.GONE);
                stopButton.setVisibility(View.VISIBLE);
                stopButton.setEnabled(false);
            }
        });
    }

    private void refreshControllerButtonsOnly() {
        if (patternStore == null) {
            return;
        }
        boolean hasPattern = patternStore.hasPattern();
        boolean calibrated = patternStore.loadCalibration() != null;
        verifyButton.setEnabled(calibrated);
        drawButton.setEnabled(hasPattern && calibrated);
    }

    private void safeUpdateControllerLayout() {
        try {
            if (controllerView != null && controllerView.isAttachedToWindow()) {
                windowManager.updateViewLayout(controllerView, controllerParams);
            }
        } catch (RuntimeException ignored) {
            // Window manager state can change while the service is being disabled.
        }
    }

    private void tearDownController() {
        if (controllerView != null && windowManager != null) {
            try {
                windowManager.removeView(controllerView);
            } catch (RuntimeException ignored) {
                // The OEM window manager may already have rejected or detached it.
            }
        }
        controllerView = null;
        controllerParams = null;
        controllerStatus = null;
        openAppButton = null;
        calibrateButton = null;
        verifyButton = null;
        drawButton = null;
        pauseButton = null;
        resumeButton = null;
        stopButton = null;
        controllerWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        windowManager = null;
        overlayContext = null;
        CONTROLLER_READY.set(false);
    }

    private void setDrawingProgress(
            int completed,
            int total,
            ProgressClock progressClock,
            int repairPass
    ) {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                completed,
                total,
                progressClock,
                repairPass
        );
        latestProgress = snapshot;
        if (runState.isStopRequested()) {
            return;
        }
        if (runState.isPaused()) {
            showPausedProgress();
            return;
        }
        renderDrawingProgress(snapshot);
    }

    private void showPausedProgress() {
        ProgressSnapshot snapshot = latestProgress;
        if (snapshot == null) {
            setStatus(R.string.overlay_paused_preparing);
        } else {
            setStatusText(getString(
                    R.string.overlay_paused,
                    snapshot.completed,
                    snapshot.total
            ));
        }
    }

    private void renderDrawingProgress(ProgressSnapshot snapshot) {
        long pausedDuringProgressMs = Math.max(
                0L,
                runState.getPausedDurationMillis()
                        - snapshot.progressClock.pausedAtStartMs
        );
        long elapsedMs = Math.max(
                0L,
                SystemClock.elapsedRealtime()
                        - snapshot.progressClock.startedAtMs
                        - pausedDuringProgressMs
        );
        DrawingProgress.Snapshot progress = DrawingProgress.estimate(
                snapshot.completed,
                snapshot.total,
                elapsedMs
        );
        String eta = progress.etaSeconds < 0L
                ? "--:--"
                : DrawingProgress.formatDuration(progress.etaSeconds);
        String message = snapshot.repairPass > 0
                ? getString(
                        R.string.overlay_repairing,
                        snapshot.completed,
                        snapshot.total,
                        snapshot.repairPass,
                        MAX_REPAIR_PASSES,
                        eta
                )
                : getString(
                        R.string.overlay_drawing,
                        snapshot.completed,
                        snapshot.total,
                        progress.cellsPerSecond,
                        eta
                );
        setStatusText(message);
    }

    private void setStatus(int stringResource) {
        mainHandler.post(() -> {
            if (controllerStatus != null) {
                controllerStatus.setText(stringResource);
            }
        });
    }

    private void setStatusText(String message) {
        mainHandler.post(() -> {
            if (controllerStatus != null) {
                controllerStatus.setText(message);
            }
        });
    }

    private void setError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getClass().getSimpleName();
        }
        String finalMessage = message;
        mainHandler.post(() -> {
            if (controllerStatus != null) {
                controllerStatus.setText(getString(R.string.overlay_error, finalMessage));
            }
        });
    }

    private void reportServiceError(String stage, Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = exception.getClass().getSimpleName();
        } else {
            detail = exception.getClass().getSimpleName() + "：" + detail.trim();
        }
        String diagnostic = stage + "（" + detail + "）";
        Throwable cause = exception.getCause();
        if (cause != null && cause != exception) {
            String causeMessage = cause.getMessage();
            diagnostic += "；底层：" + cause.getClass().getSimpleName();
            if (causeMessage != null && !causeMessage.trim().isEmpty()) {
                diagnostic += "：" + causeMessage.trim();
            }
        }
        String finalDiagnostic = diagnostic;
        Log.e(TAG, finalDiagnostic, exception);
        if (patternStore != null) {
            patternStore.saveServiceError(finalDiagnostic);
        }
        if (controllerRetryCount == 0) {
            mainHandler.post(() -> Toast.makeText(
                    getApplicationContext(),
                    finalDiagnostic,
                    Toast.LENGTH_LONG
            ).show());
        }
    }

    private static boolean hasColorInRange(
            int[] pattern,
            boolean[] paintMask,
            int start,
            int end
    ) {
        for (int index = 0; index < pattern.length; index++) {
            if (paintMask[index] && pattern[index] >= start && pattern[index] < end) {
                return true;
            }
        }
        return false;
    }

    private void sleepCancellable(long durationMs) throws CancelledException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        while (System.nanoTime() < deadline) {
            awaitRunPermission();
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            try {
                Thread.sleep(Math.max(1L, Math.min(50L, remainingMs)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }

    private void awaitRunPermission() throws CancelledException {
        try {
            if (!runState.awaitRunnable() || Thread.currentThread().isInterrupted()) {
                throw new CancelledException();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (runState.isBusy()
                && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            requestStop();
            return true;
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The service reacts only to explicit overlay actions, not ambient UI events.
    }

    @Override
    public void onInterrupt() {
        requestStop();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        SERVICE_RUNNING.set(false);
        CONTROLLER_READY.set(false);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        SERVICE_RUNNING.set(false);
        CONTROLLER_READY.set(false);
        recentVerification = null;
        runState.requestStop();
        worker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        finishCalibrationOverlay();
        tearDownController();
        if (receiverRegistered) {
            try {
                unregisterReceiver(patternReceiver);
            } catch (RuntimeException ignored) {
                // HyperOS can revoke a receiver while tearing the service down.
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class PaletteSession {
        final int[] paletteSamples = Palette.COLORS.clone();
        int currentStartRow = -1;
    }

    private static final class ProgressClock {
        final long startedAtMs;
        final long pausedAtStartMs;

        ProgressClock(long startedAtMs, long pausedAtStartMs) {
            this.startedAtMs = startedAtMs;
            this.pausedAtStartMs = pausedAtStartMs;
        }
    }

    private static final class ProgressSnapshot {
        final int completed;
        final int total;
        final ProgressClock progressClock;
        final int repairPass;

        ProgressSnapshot(
                int completed,
                int total,
                ProgressClock progressClock,
                int repairPass
        ) {
            this.completed = completed;
            this.total = total;
            this.progressClock = progressClock;
            this.repairPass = repairPass;
        }
    }

    private static final class VerifiedLayout {
        final Calibration calibration;
        final long verifiedAtMs;

        VerifiedLayout(Calibration calibration, long verifiedAtMs) {
            this.calibration = calibration;
            this.verifiedAtMs = verifiedAtMs;
        }
    }

    private static final class AutomationException extends Exception {
        AutomationException(String message) {
            super(message);
        }
    }

    private static final class CancelledException extends Exception {
    }
}
