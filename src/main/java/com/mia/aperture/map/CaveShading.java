package com.mia.aperture.map;

/**
 * Depth shading and bounds for {@link MapMode#CAVES}, shared by the 2D slice and the 3D view.
 *
 * <p>Your own level is brightest. Distance in either direction dims progressively and tints: <b>blue
 * below you, red above you</b>. Height reads as a signed quantity rather than a magnitude, so a
 * passage climbing over your head is never confused with one dropping under your feet.
 *
 * <p>Depth is measured from the reference level you are standing at, not from the camera, which
 * would make the same passage change colour as you orbit around it.
 *
 * <p><b>Red only appears where something above the reference is drawn.</b> The 2D map scans downward
 * from the band top by construction, so it has nothing above to tint unless the depth cut is engaged
 * above you; the 3D slab surrounds the focus and shows both.
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
    // The brightness ramp alone reads only as "dim" and cannot say which way. The tints carry the
    // direction: cool below, warm above. Matched in luminance so neither side reads as nearer than
    // the other at equal distance.
    private static final int BELOW_TINT = 0xFF1A2340;
    private static final int ABOVE_TINT = 0xFF451A1A;
    private static final float TINT_MAX = 0.40f;

    /**
     * Shade a colour for a surface {@code depthBlocks} from the reference level.
     *
     * @param depthBlocks blocks BELOW the reference when positive, above it when negative; both
     *                    directions dim with distance, and the sign picks the tint
     */
    public static int shade(int argb, int depthBlocks) {
        float t = Math.min(1.0f, Math.abs(depthBlocks) / (float) SLICE_BLOCKS);
        int tint = depthBlocks < 0 ? ABOVE_TINT : BELOW_TINT;
        return scale(blend(argb, tint, TINT_MAX * t), NEAR + (FAR - NEAR) * t);
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
