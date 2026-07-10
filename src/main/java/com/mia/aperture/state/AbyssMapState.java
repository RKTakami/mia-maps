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

    // Blocks above the reference line where the top-down scan begins. Small so the
    // scan starts at the player (or cut) and finds the surface below, instead of
    // catching a ceiling far overhead.
    public static final int PLAYER_CEILING_OFFSET = 2;
    // Blocks the depth cut moves per scroll notch.
    public static final double SCROLL_STEP = 8.0;
    // Abyss coords lift each 16384-block sector band by this many blocks.
    private static final int SECTOR_ABYSS_LIFT = 480;

    // true = the depth cut is an absolute abyss-Y line (engaged by Ctrl/Alt+scroll);
    // false = the cut follows the player at eye level.
    public static boolean mapDepthActive = false;

    // Cave mode: stable enclosure flag (debounced) + the roof world-Y above the player.
    public static volatile boolean caveEnclosed = false;
    public static volatile boolean caveRoofFound = false;
    public static volatile int caveRoofWorldY = 0;

    public static float mapZoom = 1.0f;
    public static double mapX = 0.0;
    public static double mapZ = 0.0;

    public static com.mia.aperture.map.MapMode mapRenderMode = com.mia.aperture.map.MapMode.RELIEF;

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

    // Shifted block Y of the band top both maps scan down from. When the depth cut is
    // engaged it is an absolute abyss-Y line; otherwise it follows the player. A cut
    // equal to the player's abyss depth yields the same result as follow mode, so
    // resetDepth returns seamlessly to the player's layer.
    public static int mapBandTopShifted(int playerWorldY, int sector, boolean depthActive, double cutAbyssY) {
        int referenceWorldY = depthActive
                ? (int) (cutAbyssY + (long) sector * SECTOR_ABYSS_LIFT)
                : playerWorldY;
        return com.mia.aperture.map.MapGeometry.shiftY(referenceWorldY, sector) + PLAYER_CEILING_OFFSET;
    }

    // Cut placed one block under the roof above the player, so scanning down reveals
    // the whole chamber below the roof.
    public static int caveCutShiftedY(int roofWorldY, int sector) {
        return com.mia.aperture.map.MapGeometry.shiftY(roofWorldY - 1, sector);
    }

    // Precedence: manual slice > cave roof-cut > eye-level follow.
    public static int effectiveBandTop(int playerWorldY, int sector, boolean caveActive,
            boolean depthActive, double cutAbyssY, boolean roofFound, int roofWorldY) {
        if (depthActive) {
            return mapBandTopShifted(playerWorldY, sector, true, cutAbyssY);
        }
        if (caveActive && roofFound) {
            return caveCutShiftedY(roofWorldY, sector);
        }
        return mapBandTopShifted(playerWorldY, sector, false, cutAbyssY);
    }

    public static void resetDepth(double playerWorldX, double playerWorldY) {
        scrollTargetCenterY = me.cortex.voxy.client.core.util.AbyssUtil.toAbyss(playerWorldX, playerWorldY).y;
        mapDepthActive = false;
        scrollActive = false;
        mapX = 0.0;
        mapZ = 0.0;
    }

    public static int bandHeight() {
        return 320;
    }
}
