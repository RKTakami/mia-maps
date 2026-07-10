package com.mia.aperture.map;

public final class MapGeometry {
    public static final int MAX_LVL = 4;
    public static final int TILE_CELLS = 32;
    public static final int BAND_QUANT = 16;

    private MapGeometry() {}

    // *16 keeps the view within 16 tiles across (512 columns of 32-cell tiles)
    public static int lvlForView(int blocksAcross) {
        int lvl = 0;
        while (lvl < MAX_LVL && (TILE_CELLS << lvl) * 16 < blocksAcross) {
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

    // Player's screen X on the fullscreen map. The map centers on player+pan, so the
    // player sits at screen center when unpanned and shifts by the pan otherwise.
    public static int playerMarkerX(double mapX, int blocksAcrossX, int width) {
        return (int) Math.round(width * (0.5 - mapX / blocksAcrossX));
    }

    public static int playerMarkerY(double mapZ, int blocksAcrossZ, int height) {
        return (int) Math.round(height * (0.5 - mapZ / blocksAcrossZ));
    }
}
