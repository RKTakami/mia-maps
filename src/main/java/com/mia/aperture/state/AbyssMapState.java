package com.mia.aperture.state;

public class AbyssMapState {
    public static boolean altHeld = false;
    public static boolean scrollActive = false;
    public static double scrollTargetCenterY = 0.0;
    public static double apertureThickness = 64.0;

    public enum Perspective {
        TOP_DOWN,
        SIDE_VIEW
    }

    public static Perspective mapPerspective = Perspective.TOP_DOWN;
    public static float mapZoom = 1.0f;
    public static double mapX = 0.0;
    public static double mapY = 0.0;
    public static double mapZ = 0.0;

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
}
