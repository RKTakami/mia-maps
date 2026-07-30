package com.mia.aperture.lod;

import com.mia.aperture.map.MapGeometry;

/**
 * Conversion between Voxy's sections and this store's, in both directions.
 *
 * <p>Three things differ and all three have to be translated. None of them fails loudly if you get
 * it wrong — the result is terrain that looks real and is in the wrong place, or the wrong colour.
 *
 * <ol>
 *   <li><b>Geometry.</b> A Voxy section is 32³ cells, one of ours is 16³, so a Voxy section is
 *       exactly 2×2×2 of ours. Same power-of-two lattice, so this is index arithmetic — no
 *       resampling, nothing lost. Note the two index conventions are NOT the same:
 *       Voxy's is {@code (y << 10) | (z << 5) | x}, ours is {@code (y * 16 + z) * 16 + x}.
 *   <li><b>Coordinates.</b> Voxy is keyed in Abyss-<i>shifted</i> space; we key in <i>vanilla</i>
 *       chunk space, because {@link LodIndexer} captures from {@code LevelChunk}. See
 *       {@link LodTileAddress} for the same conversion on the read path.
 *   <li><b>Identity.</b> Voxy ids are local to its database; ours are interned canonical block-state
 *       strings that survive across installs. Neither can be copied as a number — every cell has to
 *       go through a block state.
 * </ol>
 *
 * <p>The pure parts take id translation as functions rather than importing Voxy, so they can be
 * tested without a game.
 */
public final class StoreTransfer {
    private StoreTransfer() {}

    /** Cells per axis in a Voxy section. */
    public static final int VOXY_EDGE = 32;
    /** Cells per axis in one of ours. */
    public static final int EDGE = LodNative.EDGE;

    /** Reads a Voxy packed cell without this class depending on Voxy. */
    public interface CellReader {
        boolean isAir(long cell);
        /** @return our interned id for the cell's block, or {@link LodNative#AIR} if unresolvable */
        int block(long cell);
        /** @return our interned id for the cell's biome, or 0 if none */
        int biome(long cell);
    }

    /**
     * Copy one octant of a Voxy 32³ section into our 16³ layout.
     *
     * @param voxy   32768 packed Voxy cells
     * @param ox,oy,oz octant, each 0 or 1
     * @param cells  {@link LodNative#CELLS} out
     * @param biomes {@link LodNative#BIOME_CELLS} out
     * @return true if any cell was non-air — a fully empty octant is not worth storing
     */
    public static boolean octantToOurs(long[] voxy, int ox, int oy, int oz, CellReader reader,
                                       int[] cells, int[] biomes) {
        java.util.Arrays.fill(cells, LodNative.AIR);
        java.util.Arrays.fill(biomes, 0);
        boolean any = false;
        for (int y = 0; y < EDGE; y++) {
            int vy = oy * EDGE + y;
            for (int z = 0; z < EDGE; z++) {
                int vz = oz * EDGE + z;
                for (int x = 0; x < EDGE; x++) {
                    long cell = voxy[(vy << 10) | (vz << 5) | (ox * EDGE + x)];
                    if (reader.isAir(cell)) continue;
                    int block = reader.block(cell);
                    if (block == LodNative.AIR) continue;
                    cells[(y * EDGE + z) * EDGE + x] = block;
                    any = true;
                    // Our biome grid is one entry per 4x4x4 block of cells, so 64 cells share one
                    // slot. First writer wins: picking a representative is inherent to the coarser
                    // grid, and a later pass would only trade one arbitrary choice for another.
                    int bi = ((y / 4) * LodNative.BIOME_EDGE + (z / 4)) * LodNative.BIOME_EDGE + (x / 4);
                    if (biomes[bi] == 0) biomes[bi] = reader.biome(cell);
                }
            }
        }
        return any;
    }

    /**
     * Vanilla section coordinates for a Voxy section, whose own coordinates are Abyss-shifted.
     *
     * <p>The sector has to be recovered from the shifted <i>Y</i>, because shifted X has already had
     * the sector's 16384-block band removed and cannot distinguish one layer from another.
     *
     * @param lvl store level; a section spans {@code 16 << lvl} blocks per axis at this level
     * @return {x, y, z} in our section units, or null if the shifted Y sits outside the Abyss band
     */
    public static int[] voxyToOurs(int lvl, int vsx, int vsy, int vsz) {
        int span = EDGE << lvl;                     // blocks per axis in ONE of our sections
        int voxySpan = VOXY_EDGE << lvl;            // ...and in a Voxy section
        long shiftedY = (long) vsy * voxySpan;
        if (shiftedY > MapGeometry.ABYSS_SHIFTED_Y_TOP
                || shiftedY < MapGeometry.ABYSS_SHIFTED_Y_BOTTOM) {
            return null;
        }
        int sector = MapGeometry.sectorForShiftedY(shiftedY, -1);
        long worldX = (long) vsx * voxySpan + (long) sector * MapGeometry.SECTOR_SPAN_X;
        long worldY = shiftedY + LodTileAddress.verticalOffset(sector);
        long worldZ = (long) vsz * voxySpan;
        return new int[]{
                (int) Math.floorDiv(worldX, span),
                (int) Math.floorDiv(worldY, span),
                (int) Math.floorDiv(worldZ, span),
        };
    }

    /**
     * The inverse, for export: shifted Voxy section coordinates for one of ours.
     *
     * <p>Not a perfect round trip. Our sections are a quarter the size, and the overlap band where
     * two Abyss sectors both contain a Y means the sector recovered above is one of two valid
     * answers. Export therefore lands terrain in a consistent place, not necessarily the same place
     * Voxy originally had it.
     *
     * @return {x, y, z} in Voxy section units
     */
    public static int[] oursToVoxy(int lvl, int sx, int sy, int sz, int sector) {
        int span = EDGE << lvl;
        int voxySpan = VOXY_EDGE << lvl;
        long worldX = (long) sx * span;
        long worldY = (long) sy * span;
        long shiftedX = worldX - (long) sector * MapGeometry.SECTOR_SPAN_X;
        long shiftedY = worldY - LodTileAddress.verticalOffset(sector);
        return new int[]{
                (int) Math.floorDiv(shiftedX, voxySpan),
                (int) Math.floorDiv(shiftedY, voxySpan),
                (int) Math.floorDiv((long) sz * span, voxySpan),
        };
    }

    /** Which octant of a Voxy section one of our sections occupies, or null if it is not inside one. */
    public static int[] octantOf(int lvl, int sx, int sy, int sz, int sector) {
        int[] v = oursToVoxy(lvl, sx, sy, sz, sector);
        int span = EDGE << lvl;
        int voxySpan = VOXY_EDGE << lvl;
        long worldX = (long) sx * span, worldY = (long) sy * span, worldZ = (long) sz * span;
        long shiftedX = worldX - (long) sector * MapGeometry.SECTOR_SPAN_X;
        long shiftedY = worldY - LodTileAddress.verticalOffset(sector);
        return new int[]{
                (int) (Math.floorMod(shiftedX, voxySpan) / span),
                (int) (Math.floorMod(shiftedY, voxySpan) / span),
                (int) (Math.floorMod(worldZ, voxySpan) / span),
                v[0], v[1], v[2],
        };
    }
}
