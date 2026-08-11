package com.eraser2333.arkpaint.imaging;

import java.util.Locale;

public final class Palette {
    private static final String[] HEX_VALUES = {
            "#222222", "#B4B4B4", "#EAE7DF", "#FFFFFF",
            "#D32F36", "#9C0A00", "#D60C4A", "#E6968D",
            "#FE9875", "#F7D0C0", "#FCEFEA", "#FBF6E8",
            "#DCD2C8", "#E2CEAB", "#D56322", "#D48C42",
            "#F29900", "#F9C933", "#FCE499", "#B3B47A",
            "#C2DA72", "#6C6E00", "#B19155", "#A98F74",
            "#AA9228", "#3F2B12", "#74491F", "#534658",
            "#2A2446", "#394599", "#5A459D", "#BAA3D7",
            "#B6BCDF", "#A9ACBE", "#63ABB9", "#B4D2DC",
            "#91D8E6", "#47AEA0", "#B6D3C8", "#253660"
    };

    public static final int[] COLORS = new int[HEX_VALUES.length];
    public static final int WHITE_INDEX = 3;

    static {
        for (int index = 0; index < HEX_VALUES.length; index++) {
            COLORS[index] = 0xFF000000
                    | Integer.parseInt(HEX_VALUES[index].substring(1), 16);
        }
    }

    private Palette() {
    }

    public static int size() {
        return COLORS.length;
    }

    public static String hex(int index) {
        return HEX_VALUES[index];
    }

    public static String label(int index) {
        return String.format(Locale.ROOT, "%02d  %s", index + 1, HEX_VALUES[index]);
    }

    public static String[] labels() {
        String[] labels = new String[size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = label(index);
        }
        return labels;
    }
}
