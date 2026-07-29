package com.mia.aperture.map;

/**
 * Depth shading and bounds for {@link MapMode#CAVES}, shared by the 2D slice and the 3D view.
 *
 * <p>Nearest your own level is brightest; further below dims progressively and cools toward a deep
 * blue. In a cave the useful question is <i>how far below me is that floor</i>, so depth is measured
 * from the reference level you are standing at — not from the camera, which would make the same
 * passage change colour as you orbit around it.
 *
 * <p>Both views share this class so a floor 20 blocks down reads identically on the map and in the
 * model. They had drifted the moment one of them tuned a constant.
 */
public final class CaveShading {
    private CaveShading() {}

    /** How far below the reference level the slice reaches at all. */
    public static final int SLICE_BLOCKS = 48;

    /**
     * How far a scan may tunnel through UNBROKEN rock. This is what stops the slice seeing a lower
     * cave system through a solid ceiling. Small on purpose — it exists so a floor just under a thin
     * lip still registers, not so you can see through a wall.
     */
    public static final int PENETRATION_BLOCKS = 4;

    private static final float NEAR = 1.10f;
    private static final float FAR = 0.40f;
    // Cool cast that deepens with distance: the brightness ramp alone reads as "dim", the tint
    // reads as "further down".
    private static final int DEEP_TINT = 0xFF1A2340;
    private static final float DEEP_TINT_MAX = 0.40f;

    /**
     * Shade a colour for a surface {@code depthBlocks} below the reference level.
     *
     * @param depthBlocks blocks below; at or above the reference (negative) is treated as nearest
     */
    public static int shade(int argb, int depthBlocks) {
        float t = Math.min(1.0f, Math.max(0, depthBlocks) / (float) SLICE_BLOCKS);
        return scale(blend(argb, DEEP_TINT, DEEP_TINT_MAX * t), NEAR + (FAR - NEAR) * t);
    }

    private static int scale(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }
}
