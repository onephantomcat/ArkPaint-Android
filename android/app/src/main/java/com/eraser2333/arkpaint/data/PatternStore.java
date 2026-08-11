package com.eraser2333.arkpaint.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import com.eraser2333.arkpaint.automation.Calibration;

public final class PatternStore {
    private static final String PREFERENCES = "arkpaint_pattern";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_TAP_DELAY = "tap_delay";
    private static final String KEY_CALIBRATION = "calibration";
    private static final String KEY_SERVICE_ERROR = "service_error";

    private final SharedPreferences preferences;

    public PatternStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public void savePattern(int[] paletteIndices, int tapDelayMs) {
        if (paletteIndices.length != 24 * 24) {
            throw new IllegalArgumentException("Pattern must contain 576 pixels");
        }
        byte[] packed = new byte[paletteIndices.length];
        for (int index = 0; index < paletteIndices.length; index++) {
            int value = paletteIndices[index];
            if (value < 0 || value >= 40) {
                throw new IllegalArgumentException("Palette index is out of range");
            }
            packed[index] = (byte) value;
        }
        preferences.edit()
                .putString(KEY_PATTERN, Base64.encodeToString(packed, Base64.NO_WRAP))
                .putInt(KEY_TAP_DELAY, Math.max(10, Math.min(200, tapDelayMs)))
                .apply();
    }

    public int[] loadPattern() {
        String encoded = preferences.getString(KEY_PATTERN, null);
        if (encoded == null) {
            return null;
        }
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length != 24 * 24) {
                return null;
            }
            int[] pattern = new int[packed.length];
            for (int index = 0; index < packed.length; index++) {
                pattern[index] = packed[index] & 0xFF;
                if (pattern[index] >= 40) {
                    return null;
                }
            }
            return pattern;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean hasPattern() {
        return loadPattern() != null;
    }

    public int getTapDelayMs() {
        return preferences.getInt(KEY_TAP_DELAY, 50);
    }

    public void saveCalibration(Calibration calibration) {
        preferences.edit().putString(KEY_CALIBRATION, calibration.serialize()).apply();
    }

    public Calibration loadCalibration() {
        return Calibration.parse(preferences.getString(KEY_CALIBRATION, null));
    }

    public void saveServiceError(String message) {
        String safeMessage = message == null ? "" : message.trim();
        if (safeMessage.length() > 600) {
            safeMessage = safeMessage.substring(0, 600);
        }
        preferences.edit().putString(KEY_SERVICE_ERROR, safeMessage).apply();
    }

    public String getServiceError() {
        String message = preferences.getString(KEY_SERVICE_ERROR, null);
        return message == null || message.trim().isEmpty() ? null : message;
    }

    public void clearServiceError() {
        preferences.edit().remove(KEY_SERVICE_ERROR).apply();
    }
}
