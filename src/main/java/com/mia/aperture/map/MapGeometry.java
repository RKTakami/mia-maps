package com.mia.aperture.map;

public final class MapGeometry {
    public static final int MAX_LVL = 4;
    public static final int MAX_DISPLAY_LVL = 3;
    public static final int TILE_CELLS = 32;
    public static final int DETAIL_TILES = 32;
    public static final int BAND_QUANT = 16;

    private MapGeometry() {}

    // *DETAIL_TILES keeps the view within DETAIL_TILES tiles across; capped at
    // MAX_DISPLAY_LVL (8-block cells) so a fully zoomed-out view stays legible.
    public static int lvlForView(int blocksAcross) {
        int lvl = 0;
        while (lvl < MAX_DISPLAY_LVL && (TILE_CELLS << lvl) * DETAIL_TILES < blocksAcross) {
            lvl++;
        }
        return lvl;
    }

    public static int tileSpanBlocks(int lvl) {
        return TILE_CELLS << lvl;
    }

    public static int blockToTile(int block, int lvl) {
        return Math.floorDiv(block, tileSpanBlocks(lvl));
    }

    public static int bandKey(int bandTopY) {
        return Math.floorDiv(bandTopY, BAND_QUANT);
    }

    public static int shiftX(int worldX, int sector) {
        return worldX - (sector << 14);
    }

    // Voxy MIA DB space: each 16384-block sector band is lifted by (240 - sector*30)*16 blocks
    public static int shiftY(int worldY, int sector) {
        return worldY + (240 - sector * 30) * 16;
    }

    // Screen pixel for a point offset deltaBlocks from the view centre (centre = dim/2,
    // edges at +/- half the span). Used by the player marker and waypoint markers.
    public static int screenOffsetPixel(double deltaBlocks, int blocksAcross, int dim) {
        return (int) Math.round(dim * (0.5 + deltaBlocks / blocksAcross));
    }

    // Player's screen X on the fullscreen map. The map centers on player+pan, so the
    // player sits at screen center when unpanned and shifts by the pan otherwise
    // (the player is the point at delta = -pan).
    public static int playerMarkerX(double mapX, int blocksAcrossX, int width) {
        return screenOffsetPixel(-mapX, blocksAcrossX, width);
    }

    public static int playerMarkerY(double mapZ, int blocksAcrossZ, int height) {
        return screenOffsetPixel(-mapZ, blocksAcrossZ, height);
    }
}
