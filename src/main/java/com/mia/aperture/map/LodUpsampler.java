package com.mia.aperture.map;

public final class LodUpsampler {
    private LodUpsampler() {}

    // Fill a 32^3 display-level section from a coarser section k levels up. The coarse
    // section covers 2^k display sections per axis; this display section occupies octant
    // (sx & (2^k-1), secY & (2^k-1), sz & (2^k-1)) of it, and each coarse cell in that
    // (32>>k)^3 sub-cube replicates to a 2^k cube of fine cells. Voxel index layout
    // matches MapTileRenderer.cellAt: (y<<10)|(z<<5)|x.
    // Downsample a finer child section (32^3) by 2x into octant (octX,octY,octZ) of `out`
    // (a 32^3 coarse section). Each 2x2x2 block of fine cells collapses to one coarse cell,
    // choosing the topmost non-air voxel (falls back to air only if all 8 are air) so surfaces
    // survive the reduction — matching how the map's top-down scan wants the highest solid.
    // Inverse of upsampleOctant at k=1. Voxel index layout: (y<<10)|(z<<5)|x.
    public static void mipInto(long[] out, long[] child, int octX, int octY, int octZ) {
        int bx = octX * 16, by = octY * 16, bz = octZ * 16;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    out[((by + y) << 10) | ((bz + z) << 5) | (bx + x)] =
                            representative(child, x << 1, y << 1, z << 1);
                }
            }
        }
    }

    // Topmost non-air voxel of the 2x2x2 block at (x0,y0,z0); 0 (air) if all empty.
    private static long representative(long[] s, int x0, int y0, int z0) {
        for (int y = y0 + 1; y >= y0; y--) {
            for (int z = z0; z < z0 + 2; z++) {
                for (int x = x0; x < x0 + 2; x++) {
                    long v = s[(y << 10) | (z << 5) | x];
                    if (v != 0) return v;
                }
            }
        }
        return 0;
    }

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
