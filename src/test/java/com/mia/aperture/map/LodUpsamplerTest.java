package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodUpsamplerTest {

    private static int idx(int x, int y, int z) { return (y << 10) | (z << 5) | x; }

    @Test
    void uniformCoarseGivesUniformFine() {
        long[] coarse = new long[32 * 32 * 32];
        java.util.Arrays.fill(coarse, 7L);
        long[] fine = LodUpsampler.upsampleOctant(coarse, 0, 0, 0, 1);
        assertEquals(32 * 32 * 32, fine.length);
        for (long v : fine) assertEquals(7L, v);
    }

    @Test
    void k1ReplicatesSelectedOctantAlongX() {
        long[] coarse = new long[32 * 32 * 32];
        for (int i = 0; i < coarse.length; i++) coarse[i] = i;
        long[] fine = LodUpsampler.upsampleOctant(coarse, 1, 0, 0, 1);
        assertEquals(coarse[idx(16, 0, 0)], fine[idx(0, 0, 0)]);
        assertEquals(coarse[idx(16, 0, 0)], fine[idx(1, 0, 0)]);
        assertEquals(coarse[idx(17, 0, 0)], fine[idx(2, 0, 0)]);
        assertEquals(coarse[idx(17, 0, 0)], fine[idx(3, 0, 0)]);
    }

    @Test
    void octantOffsetSelectsDifferentSubcube() {
        long[] coarse = new long[32 * 32 * 32];
        for (int i = 0; i < coarse.length; i++) coarse[i] = i;
        long[] lo = LodUpsampler.upsampleOctant(coarse, 0, 0, 0, 1);
        long[] hi = LodUpsampler.upsampleOctant(coarse, 1, 0, 0, 1);
        assertEquals(coarse[idx(0, 0, 0)], lo[idx(0, 0, 0)]);
        assertEquals(coarse[idx(16, 0, 0)], hi[idx(0, 0, 0)]);
        assertNotEquals(lo[idx(0, 0, 0)], hi[idx(0, 0, 0)]);
    }
}
