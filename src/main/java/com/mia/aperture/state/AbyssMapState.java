package com.mia.aperture.state;

public class AbyssMapState {
    // volatile: key events arrive on the input thread since the 1.21.9 rework,
    // but scroll handlers read this on the render thread.
    // Ctrl is the primary slice modifier: Alt key events never reach this client
    // (verified 2026-07-06 — zero Alt events through mixin, screen and polling).
    public static volatile boolean altHeld = false;
    public static volatile boolean ctrlHeld = false;
    public static boolean scrollActive = false;
    public static double scrollTargetCenterY = 0.0;
    public static double apertureThickness = 64.0;

    public static float mapZoom = 1.0f;
    public static double mapX = 0.0;
    public static double mapZ = 0.0;

    public static com.mia.aperture.map.MapMode mapRenderMode = com.mia.aperture.map.MapMode.RELIEF;
    public static boolean mapBandCustom = false;

    public static boolean isSectionVisible(int lvl, int y) {
        // y is the Y index at level lvl
        // Convert Y index to block coordinates in Voxy's translated space
        double minY = (double) ((y << lvl) * 16);
        double maxY = (double) (((y + 1) << lvl) * 16);

        // Convert to Abyss Y coordinates (subtracting the 3840 block offset)
        double minAbyssY = minY - 3840.0;
        double maxAbyssY = maxY - 3840.0;

        // Compute the aperture window boundaries
        double minApertureY = scrollTargetCenterY - (apertureThickness / 2.0);
        double maxApertureY = scrollTargetCenterY + (apertureThickness / 2.0);

        // Check if the section overlaps with the aperture window
        return minAbyssY <= maxApertureY && maxAbyssY >= minApertureY;
    }

    public static int defaultBandTopY(double playerWorldY, int sector) {
        return com.mia.aperture.map.MapGeometry.shiftY((int) playerWorldY, sector) + 96;
    }

    public static int bandHeight() {
        return 320;
    }
}
