package com.eraser2333.arkpaint.automation;

import java.util.Locale;

final class DrawingProgress {
    private DrawingProgress() {
    }

    static Snapshot estimate(int completed, int total, long elapsedMs) {
        int safeTotal = Math.max(0, total);
        int safeCompleted = Math.max(0, Math.min(completed, safeTotal));
        if (safeCompleted == 0 || elapsedMs <= 0L) {
            return new Snapshot(0.0, -1L);
        }
        double rate = safeCompleted * 1000.0 / elapsedMs;
        long remaining = safeTotal - safeCompleted;
        long etaSeconds = remaining == 0
                ? 0L
                : Math.max(1L, (long) Math.ceil(remaining / rate));
        return new Snapshot(rate, etaSeconds);
    }

    static String formatDuration(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long hours = safeSeconds / 3600L;
        long minutes = (safeSeconds % 3600L) / 60L;
        long remainder = safeSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, remainder);
    }

    static final class Snapshot {
        final double cellsPerSecond;
        final long etaSeconds;

        Snapshot(double cellsPerSecond, long etaSeconds) {
            this.cellsPerSecond = cellsPerSecond;
            this.etaSeconds = etaSeconds;
        }
    }
}
