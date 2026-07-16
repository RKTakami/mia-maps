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

    // Every Abyss layer lives in ONE continuous shifted-Y column: shiftedY = abyssDepth + 3840
    // (sector-invariant), rim at abyssDepth 0 -> shiftedY 3840, deepest mapped layer 7200 blocks
    // down -> shiftedY -3360. Orth sits above the rim (worldY up to the build limit in sector 0),
    // hence the headroom. There is NO terrain outside this band.
    public static final int ABYSS_SHIFTED_Y_TOP = 3840 + 512;
    public static final int ABYSS_SHIFTED_Y_BOTTOM = 3840 - 7200 - 256;

    // Trim a vertical sample extent (blocks up/down from shiftedFocusY) to the Abyss band. Wide
    // views otherwise sample tens of thousands of blocks of empty sky/void, and every empty
    // section still pays a full coarser+downsample probe to prove it is empty. Returns {up, down};
    // never below minExtent, so a focus outside the band still yields a thin valid slab.
    public static int[] clampVerticalToAbyss(int shiftedFocusY, int extentUp, int extentDown, int minExtent) {
        int up = Math.max(minExtent, Math.min(extentUp, ABYSS_SHIFTED_Y_TOP - shiftedFocusY));
        int down = Math.max(minExtent, Math.min(extentDown, shiftedFocusY - ABYSS_SHIFTED_Y_BOTTOM));
        return new int[]{up, down};
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
