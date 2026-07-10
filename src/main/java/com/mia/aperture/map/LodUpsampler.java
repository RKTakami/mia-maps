package com.mia.aperture.map;

public final class LodUpsampler {
    private LodUpsampler() {}

    // Fill a 32^3 display-level section from a coarser section k levels up. The coarse
    // section covers 2^k display sections per axis; this display section occupies octant
    // (sx & (2^k-1), secY & (2^k-1), sz & (2^k-1)) of it, and each coarse cell in that
    // (32>>k)^3 sub-cube replicates to a 2^k cube of fine cells. Voxel index layout
    // matches MapTileRenderer.cellAt: (y<<10)|(z<<5)|x.
    public static long[] upsampleOctant(long[] coarse, int sx, int secY, int sz, int k) {
        int scale = 1 << k;
        int span = 32 >> k;
        int ox = (sx & (scale - 1)) * span;
        int oy = (secY & (scale - 1)) * span;
        int oz = (sz & (scale - 1)) * span;
        long[] out = new long[32 * 32 * 32];
        for (int y = 0; y < 32; y++) {
            int cy = oy + (y >> k);
            for (int z = 0; z < 32; z++) {
                int cz = oz + (z >> k);
                for (int x = 0; x < 32; x++) {
                    int cx = ox + (x >> k);
                    out[(y << 10) | (z << 5) | x] = coarse[(cy << 10) | (cz << 5) | cx];
                }
            }
        }
        return out;
    }
}
