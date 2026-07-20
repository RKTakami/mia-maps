package com.mia.aperture.map;

// Pure bilinear sampling of a w*h float grid (row-major, index y*w+x), edge-clamped.
public final class Interp2D {
    private Interp2D() {}

    // Allocation-free bilinear blend of four corner samples: a=(0,0) b=(1,0) c=(0,1) d=(1,1),
    // fractions fx (toward b/d) and fy (toward c/d) in [0,1]. Used per colour channel in the map
    // compositor's per-pixel sub-cell sampling, where a float[] + index would churn GC.
    public static double bilerp(double a, double b, double c, double d, double fx, double fy) {
        double top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    public static float bilinear(float[] f, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double tx = x - x0, ty = y - y0;
        float a = f[y0 * w + x0], b = f[y0 * w + x1], c = f[y1 * w + x0], d = f[y1 * w + x1];
        double top = a + (b - a) * tx, bot = c + (d - c) * tx;
        return (float) (top + (bot - top) * ty);
    }
}
