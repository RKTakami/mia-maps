package com.mia.aperture.lod;

/**
 * Assembles the 32³ sections the map's tile renderer expects out of the store's 16³ sections.
 *
 * <p>The two geometries divide evenly rather than needing resampling: at the same cell size, one of
 * the renderer's sections is exactly <b>2×2×2</b> of ours. That is the whole reason a 16³ section —
 * chosen to match a vanilla chunk section so indexing is 1:1 — costs nothing on the read side.
 *
 * <p>Cells are packed as the map's mapping ids, block in the low bits and biome in the high, so
 * {@link com.mia.aperture.map.MapTileRenderer} works unmodified.
 */
public final class LodTileSource {
    /** Cells per axis in a renderer section. */
    public static final int BIG = 32;
    /** Longs in a renderer section. */
    public static final int BIG_CELLS = BIG * BIG * BIG;

    private final long handle;
    private final int[] ids = new int[LodNative.CELLS];
    private final int[] biomes = new int[LodNative.BIOME_CELLS];

    public LodTileSource(long handle) {
        this.handle = handle;
    }

    /**
     * Build one 32³ section at {@code level}, whose minimum corner is the store section
     * ({@code sx*2}, {@code sy*2}, {@code sz*2}).
     *
     * @param out {@link #BIG_CELLS} longs, zeroed by this call
     * @return false if none of the eight contributing sections exist — the caller should treat the
     *         section as missing rather than as empty, which the renderer already distinguishes
     */
    public boolean buildSection(int level, int sx, int sy, int sz, long[] out) {
        if (out.length != BIG_CELLS) return false;
        java.util.Arrays.fill(out, 0L);
        boolean any = false;

        for (int oy = 0; oy < 2; oy++) {
            for (int oz = 0; oz < 2; oz++) {
                for (int ox = 0; ox < 2; ox++) {
                    if (!LodNative.nGet(handle, level, sx * 2 + ox, sy * 2 + oy, sz * 2 + oz,
                            ids, biomes)) {
                        continue;   // never seen; its eighth of the section stays empty
                    }
                    any = true;
                    copyOctant(ox, oy, oz, out);
                }
            }
        }
        return any;
    }

    /**
     * Copy one 16³ section into its octant of the 32³ output.
     *
     * <p>The index conventions differ and must not be conflated: ours is
     * {@code (y * 16 + z) * 16 + x}, the renderer's is {@code (y << 10) | (z << 5) | x}. Getting
     * this wrong does not fail — it transposes terrain, which is far harder to notice than a crash.
     */
    private void copyOctant(int ox, int oy, int oz, long[] out) {
        for (int y = 0; y < LodNative.EDGE; y++) {
            int bigY = oy * LodNative.EDGE + y;
            for (int z = 0; z < LodNative.EDGE; z++) {
                int bigZ = oz * LodNative.EDGE + z;
                int srcRow = (y * LodNative.EDGE + z) * LodNative.EDGE;
                int dstRow = (bigY << 10) | (bigZ << 5) | (ox * LodNative.EDGE);
                for (int x = 0; x < LodNative.EDGE; x++) {
                    int block = ids[srcRow + x];
                    if (block == LodNative.AIR) continue;   // leave as 0; the renderer skips it
                    int biome = biomeAt(x, y, z);
                    out[dstRow + x] = LodColorSource.mappingId(block, biome);
                }
            }
        }
    }

    /** Biome for a block cell, mapping the 16-cell axis onto the 4-cell biome grid. */
    private int biomeAt(int x, int y, int z) {
        return biomes[((y / 4) * LodNative.BIOME_EDGE + (z / 4)) * LodNative.BIOME_EDGE + (x / 4)];
    }
}
