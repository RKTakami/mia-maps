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

    // The MIA world lays each 480-block-deep Abyss section out as its own 16384-block band along
    // world X. Mirrors of Voxy's AbyssUtil constants (abyss_dx / abyss_dy) — mirrored rather than
    // called because voxy is compileOnly and this class must stay pure/unit-testable.
    public static final int SECTOR_SPAN_X = 16384;
    public static final int SECTOR_DEPTH = 480;
    public static final int RIM_SHIFTED_Y = 3840;

    // Mirrors Voxy's AbyssUtil.getSection. The truncation toward zero is Voxy's behaviour, and the
    // DB is keyed on it, so it is reproduced exactly rather than "corrected" to a floor.
    public static int sectorForX(double worldX) {
        return (int) (worldX / SECTOR_SPAN_X + 0.5);
    }

    public static int shiftX(int worldX, int sector) {
        return worldX - sector * SECTOR_SPAN_X;
    }

    // Voxy MIA DB space: each 16384-block sector band is lifted by (240 - sector*30)*16 blocks
    public static int shiftY(int worldY, int sector) {
        return worldY + RIM_SHIFTED_Y - sector * SECTOR_DEPTH;
    }

    // A world point placed in the Abyss's unified shifted column, using the point's OWN sector.
    // Sections sit side by side in world X but stack vertically here, so this is the only space in
    // which points from different sections are comparable — and the space the 3D camera and the
    // voxel cloud both live in. Taking a world-space delta against a shifted focus instead is wrong
    // by a whole section (16384 blocks of X, 480 of Y) for any point off the focus's own layer.
    public static double[] toShiftedColumn(double worldX, double worldY, double worldZ) {
        int sector = sectorForX(worldX);
        return new double[]{
                worldX - (double) sector * SECTOR_SPAN_X,
                worldY + RIM_SHIFTED_Y - (double) sector * SECTOR_DEPTH,
                worldZ};
    }

    // Abyss depth for a shifted Y, negative going down — the same convention the HUD readout uses
    // (it displays the negation), since shiftedY = abyssDepth + RIM_SHIFTED_Y.
    public static double abyssDepth(double shiftedY) {
        return shiftedY - RIM_SHIFTED_Y;
    }

    // A section's world Y band. 512 tall but stepping SECTOR_DEPTH (480) means adjacent sections
    // overlap by 32 blocks — the band you walk down through from one layer to the next. Mirrors
    // Voxy's AbyssUtil abyss_wy/abyss_wh/abyss_sections.
    public static final int SECTION_COUNT = 15;
    public static final int SECTION_WORLD_Y_MIN = -256;
    public static final int SECTION_WORLD_Y_HEIGHT = 512;

    public static boolean sectorContainsShiftedY(int sector, double shiftedY) {
        double worldY = shiftedY - RIM_SHIFTED_Y + (double) sector * SECTOR_DEPTH;
        return worldY >= SECTION_WORLD_Y_MIN && worldY < SECTION_WORLD_Y_MIN + SECTION_WORLD_Y_HEIGHT;
    }

    // The section owning a shifted Y. Inside the 32-block overlap TWO sections legitimately contain
    // it, so `preferred` breaks the tie — callers pass the layer they are already on, which keeps a
    // descending route from flickering between two equally correct answers.
    public static int sectorForShiftedY(double shiftedY, int preferred) {
        if (preferred >= 0 && preferred < SECTION_COUNT && sectorContainsShiftedY(preferred, shiftedY)) {
            return preferred;
        }
        int approx = (int) Math.round((RIM_SHIFTED_Y - shiftedY) / (double) SECTOR_DEPTH);
        for (int d = 0; d <= 1; d++) {
            for (int s : new int[]{approx - d, approx + d}) {
                if (s >= 0 && s < SECTION_COUNT && sectorContainsShiftedY(s, shiftedY)) return s;
            }
        }
        return Math.max(0, Math.min(SECTION_COUNT - 1, approx));
    }

    // Inverse of toShiftedColumn for a known section.
    public static double[] toWorld(double sx, double sy, double sz, int sector) {
        return new double[]{
                sx + (double) sector * SECTOR_SPAN_X,
                sy - RIM_SHIFTED_Y + (double) sector * SECTOR_DEPTH,
                sz};
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
