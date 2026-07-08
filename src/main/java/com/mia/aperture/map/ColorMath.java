package com.mia.aperture.map;

public final class ColorMath {
    private ColorMath() {}

    // ARGB pixels; fully-transparent pixels contribute nothing. Returns 0 if total alpha 0.
    public static int alphaWeightedAverage(int[] argbPixels) {
        long wr = 0, wg = 0, wb = 0, wa = 0;
        for (int p : argbPixels) {
            int a = (p >>> 24) & 0xFF;
            if (a == 0) continue;
            wr += (long) ((p >> 16) & 0xFF) * a;
            wg += (long) ((p >> 8) & 0xFF) * a;
            wb += (long) (p & 0xFF) * a;
            wa += a;
        }
        if (wa == 0) return 0;
        int r = (int) (wr / wa);
        int g = (int) (wg / wa);
        int b = (int) (wb / wa);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Per-channel base*tint/255; keeps base alpha. tint is 0xRRGGBB.
    public static int tintMultiply(int baseArgb, int tintRgb) {
        int a = (baseArgb >>> 24) & 0xFF;
        int r = (((baseArgb >> 16) & 0xFF) * ((tintRgb >> 16) & 0xFF)) / 255;
        int g = (((baseArgb >> 8) & 0xFF) * ((tintRgb >> 8) & 0xFF)) / 255;
        int b = ((baseArgb & 0xFF) * (tintRgb & 0xFF)) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
