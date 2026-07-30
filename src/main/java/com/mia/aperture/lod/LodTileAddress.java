package com.mia.aperture.lod;

import com.mia.aperture.map.MapGeometry;

/**
 * Translates the map's tile coordinates into store section coordinates.
 *
 * <p><b>The two live in different spaces, and confusing them renders the wrong terrain rather than
 * failing.</b> The map works in Abyss-shifted coordinates: each 16384-block sector is stacked into
 * one continuous column, lifted by {@code 480} blocks per sector and offset by the rim at
 * {@code 3840}. The store is indexed straight off vanilla chunk coordinates, because
 * {@code LodIndexer} captures from {@code LevelChunk} and never sees the shift. So a map tile's
 * coordinates point somewhere entirely different in the store.
 *
 * <p>Inverting the shift is arithmetic. The catch is <b>alignment</b>: the vertical offset is
 * {@code 480 * sector - 3840}, and that is only a whole number of sections when it divides by the
 * section height. At level 0 (32-block sections) it always does. At coarser levels it usually does
 * not — a level-1 tile in sector 1 straddles two store sections vertically, so no single section read
 * can serve it.
 *
 * <pre>
 *   lvl 0: section  32 blocks   480 % 32 = 0     aligned for every sector
 *   lvl 1: section  64 blocks   480 % 64 = 32    misaligned unless the sector cancels it
 *   lvl 2: section 128 blocks   480 % 128 = 96   likewise
 * </pre>
 *
 * <p>Rather than sample across the straddle — which is real work and easy to get subtly wrong — this
 * reports whether a clean mapping exists and lets the caller fall back to the existing data path.
 * A tile drawn from the wrong depth would look plausible, so the failure has to be explicit.
 */
public final class LodTileAddress {
    private LodTileAddress() {}

    /** Cells across one of the map renderer's sections. */
    public static final int MAP_SECTION_CELLS = 32;

    /** Blocks spanned by one map section at this display level. */
    public static int sectionBlocks(int lvl) {
        return MAP_SECTION_CELLS << lvl;
    }

    /**
     * The vertical offset from shifted to vanilla coordinates: {@code worldY = shiftedY + offset}.
     * Mirrors {@link MapGeometry#shiftY} inverted.
     */
    public static int verticalOffset(int sector) {
        return sector * MapGeometry.SECTOR_DEPTH - MapGeometry.RIM_SHIFTED_Y;
    }

    /**
     * Whether a map tile at this level and sector maps onto whole store sections.
     *
     * <p>Horizontally always true: sectors are 16384 apart, which every section size up to 16384
     * divides. Vertically only when the sector's lift cancels against the section height.
     */
    public static boolean aligned(int lvl, int sector) {
        int h = sectionBlocks(lvl);
        if (h <= 0) return false;
        return Math.floorMod(MapGeometry.SECTOR_SPAN_X, h) == 0
                && Math.floorMod(verticalOffset(sector), h) == 0;
    }

    /**
     * Where a map tile lives in the store: the section it <i>starts</i> in, plus how many cells up
     * within that section it begins.
     *
     * <p>A non-zero offset means the tile straddles two sections vertically — it takes the top
     * {@code 32 - offset} cells of {@code baseY} and the bottom {@code offset} cells of
     * {@code baseY + 1}. That is a clean blit rather than a resample, because the vertical offset is
     * always a whole number of cells: 480 and 3840 are both multiples of 32, so every cell size up
     * to 32 divides them. Verified for every level and sector.
     *
     * @return {x, baseY, z, cellOffsetY} with cellOffsetY in [0, 32)
     */
    public static int[] address(int lvl, int sx, int secY, int sz, int sector) {
        int h = sectionBlocks(lvl);
        int cell = 1 << lvl;
        int off = verticalOffset(sector);
        long worldX = (long) sx * h + (long) sector * MapGeometry.SECTOR_SPAN_X;
        return new int[]{
                (int) Math.floorDiv(worldX, h),
                (int) Math.floorDiv((long) secY * h + off, h),
                sz,                                   // Z is never shifted
                Math.floorMod(off, h) / cell,
        };
    }

    /** Cells along one axis of a map renderer section. */
    private static final int C = MAP_SECTION_CELLS;

    /**
     * Stitch two vertically adjacent store sections into one tile, starting {@code offsetCells} up
     * inside {@code lower}.
     *
     * <p>Separated out and tested because an off-by-one here shifts terrain vertically by up to a
     * whole section, which looks like real terrain at the wrong depth rather than like a bug.
     *
     * @param lower section at {@code baseY}, or null if the store has never seen it
     * @param upper section at {@code baseY + 1}, or null likewise
     */
    public static void stitch(long[] lower, long[] upper, int offsetCells, long[] out) {
        java.util.Arrays.fill(out, 0L);
        int plane = C * C;                            // one Y layer: index is (y << 10) | (z << 5) | x
        for (int y = 0; y < C; y++) {
            int src = offsetCells + y;
            long[] from = src < C ? lower : upper;
            if (from == null) continue;
            int localY = src < C ? src : src - C;
            System.arraycopy(from, localY * plane, out, y * plane, plane);
        }
    }

    /**
     * Store section coordinates for a map tile, in the units {@link LodTileSource#buildSection}
     * expects — one of its sections being 2×2×2 of the store's own.
     *
     * @return {x, y, z}, or null when no clean mapping exists (see {@link #aligned})
     */
    public static int[] bigSection(int lvl, int sx, int secY, int sz, int sector) {
        if (!aligned(lvl, sector)) return null;
        int h = sectionBlocks(lvl);
        // Shifted block coordinates of the tile's minimum corner...
        long shiftedX = (long) sx * h;
        long shiftedY = (long) secY * h;
        // ...lifted back into vanilla space. X gains the sector's band, Y the sector's depth.
        long worldX = shiftedX + (long) sector * MapGeometry.SECTOR_SPAN_X;
        long worldY = shiftedY + verticalOffset(sector);
        long worldZ = (long) sz * h;   // Z is never shifted
        return new int[]{
                (int) Math.floorDiv(worldX, h),
                (int) Math.floorDiv(worldY, h),
                (int) Math.floorDiv(worldZ, h),
        };
    }
}
