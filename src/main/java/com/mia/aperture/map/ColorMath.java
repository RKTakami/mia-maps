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

    // Like alphaWeightedAverage but averages in LINEAR light (sRGB->linear, average,
    // linear->sRGB). Averaging gamma-encoded pixels darkens/desaturates; this matches
    // how the block's overall colour is actually perceived in-world.
    public static int alphaWeightedAverageLinear(int[] argbPixels) {
        double wr = 0, wg = 0, wb = 0;
        long wa = 0;
        for (int p : argbPixels) {
            int a = (p >>> 24) & 0xFF;
            if (a == 0) continue;
            wr += srgbToLinear((p >> 16) & 0xFF) * a;
            wg += srgbToLinear((p >> 8) & 0xFF) * a;
            wb += srgbToLinear(p & 0xFF) * a;
            wa += a;
        }
        if (wa == 0) return 0;
        int r = linearToSrgb(wr / wa);
        int g = linearToSrgb(wg / wa);
        int b = linearToSrgb(wb / wa);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double srgbToLinear(int c) {
        return Math.pow(c / 255.0, 2.2);
    }

    private static int linearToSrgb(double l) {
        int v = (int) Math.round(Math.pow(Math.max(0.0, Math.min(1.0, l)), 1.0 / 2.2) * 255.0);
        return Math.max(0, Math.min(255, v));
    }

    // Boost saturation (push channels away from luminance) and contrast (push away from
    // mid-grey). Keeps alpha. Grey stays grey; muted colours get punchier.
    public static int punch(int argb, float saturation, float contrast) {
        int a = (argb >>> 24) & 0xFF;
        float r = (argb >> 16) & 0xFF;
        float g = (argb >> 8) & 0xFF;
        float b = argb & 0xFF;
        float lum = 0.299f * r + 0.587f * g + 0.114f * b;
        r = lum + (r - lum) * saturation;
        g = lum + (g - lum) * saturation;
        b = lum + (b - lum) * saturation;
        r = 128f + (r - 128f) * contrast;
        g = 128f + (g - 128f) * contrast;
        b = 128f + (b - 128f) * contrast;
        return (a << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static int clampByte(float v) {
        return (int) Math.max(0f, Math.min(255f, v));
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
