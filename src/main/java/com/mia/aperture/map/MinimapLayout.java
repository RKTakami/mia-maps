package com.mia.aperture.map;

import com.mia.aperture.map.MapSettings.MinimapCorner;

public final class MinimapLayout {
    public static final int MARGIN = 10;

    private MinimapLayout() {}

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static int rangeX(int screenW, int size, int margin) { return Math.max(0, screenW - size - 2 * margin); }
    private static int rangeY(int screenH, int size, int margin) { return Math.max(0, screenH - size - 2 * margin); }

    public static int originX(double fx, int screenW, int size, int margin) {
        return margin + (int) Math.round(clamp01(fx) * rangeX(screenW, size, margin));
    }

    public static int originY(double fy, int screenH, int size, int margin) {
        return margin + (int) Math.round(clamp01(fy) * rangeY(screenH, size, margin));
    }

    public static double fractionFromPixelX(int px, int screenW, int size, int margin) {
        int r = rangeX(screenW, size, margin);
        if (r <= 0) return 0.0;
        return clamp01((px - margin) / (double) r);
    }

    public static double fractionFromPixelY(int py, int screenH, int size, int margin) {
        int r = rangeY(screenH, size, margin);
        if (r <= 0) return 0.0;
        return clamp01((py - margin) / (double) r);
    }

    public static double[] cornerFraction(MinimapCorner corner) {
        return switch (corner) {
            case TOP_LEFT -> new double[]{0.0, 0.0};
            case TOP_RIGHT -> new double[]{1.0, 0.0};
            case BOTTOM_LEFT -> new double[]{0.0, 1.0};
            case BOTTOM_RIGHT -> new double[]{1.0, 1.0};
        };
    }
}
