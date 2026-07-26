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

    @Test
    void mipRoundTripsUpsampleForOctant() {
        // Upsampling replicates each coarse cell to a 2x2x2 block; downsampling that block back
        // (topmost non-air representative) must recover the coarse octant it came from.
        long[] coarse = new long[32 * 32 * 32];
        for (int i = 0; i < coarse.length; i++) coarse[i] = i + 1; // non-air everywhere
        long[] child = LodUpsampler.upsampleOctant(coarse, 1, 1, 1, 1); // octant (1,1,1)
        long[] out = new long[32 * 32 * 32];
        LodUpsampler.mipInto(out, child, 1, 1, 1);
        for (int y = 16; y < 32; y++)
            for (int z = 16; z < 32; z++)
                for (int x = 16; x < 32; x++)
                    assertEquals(coarse[idx(x, y, z)], out[idx(x, y, z)]);
    }

    @Test
    void mipKeepsTopmostSolidOverAir() {
        long[] child = new long[32 * 32 * 32];
        child[idx(0, 1, 0)] = 42L; // one solid voxel atop an otherwise-air 2x2x2 block
        long[] out = new long[32 * 32 * 32];
        LodUpsampler.mipInto(out, child, 0, 0, 0);
        assertEquals(42L, out[idx(0, 0, 0)]);
    }

    // Voxy gives lit/biome AIR a non-zero mapping id, so "first non-zero" picks the air sitting above
    // a surface and the sampler then drops the whole parent cell as non-opaque — punching holes across
    // every terrain surface at coarse LOD while solid interiors survive.
    @Test
    void mipPrefersRenderableChildOverLitAir() {
        long AIR = 900L, STONE = 42L;
        long[] child = new long[32 * 32 * 32];
        child[idx(0, 1, 0)] = AIR;   // lit air occupies the top of the 2x2x2
        child[idx(1, 1, 0)] = AIR;
        child[idx(0, 0, 0)] = STONE; // solid ground underneath
        child[idx(1, 0, 0)] = STONE;
        long[] out = new long[32 * 32 * 32];
        LodUpsampler.mipInto(out, child, 0, 0, 0, id -> id != AIR);
        assertEquals(STONE, out[idx(0, 0, 0)], "surface cell must mip to the solid block, not lit air");
    }

    @Test
    void mipFallsBackToTopmostWhenNothingIsRenderable() {
        long AIR = 900L;
        long[] child = new long[32 * 32 * 32];
        child[idx(0, 1, 0)] = AIR;
        long[] out = new long[32 * 32 * 32];
        LodUpsampler.mipInto(out, child, 0, 0, 0, id -> id != AIR);
        assertEquals(AIR, out[idx(0, 0, 0)], "with no renderable child the old representative stands");
    }
}
