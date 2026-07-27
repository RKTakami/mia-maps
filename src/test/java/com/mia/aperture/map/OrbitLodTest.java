package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitLodTest {

    // Mirrors MapSettings.OrbitQuality: {gpuGrid (cells/axis), maxCells (total volume)}.
    private static final int POTATO_G = 128, LOW_G = 208, MEDIUM_G = 288, HIGH_G = 416, ULTRA_G = 576;
    private static final long POTATO_C = 1_000_000L, LOW_C = 2_000_000L, MEDIUM_C = 4_000_000L,
            HIGH_C = 8_000_000L, ULTRA_C = 16_000_000L;

    private static OrbitLod.Plan planFor(int area, int grid, long cells) {
        return OrbitLod.planForArea(area, grid, OrbitLod.MAX_LEVEL, cells);
    }

    // Grid the renderer will allocate for a plan, matching VoxelCloud.sampleGrid's arithmetic.
    private static long cellsOf(OrbitLod.Plan p, int vertUp, int vertDown) {
        long gX = Math.max(1, p.coverageBlocks() / p.cellBlocks());
        long gY = Math.max(1, (vertUp + vertDown) / p.cellBlocks());
        return gX * gY * gX;
    }

    @Test
    void volumeStaysWithinTheTierBudget() {
        // The whole point: cost is cubic, so capping width alone let Ultra reach 191M cells (911 MB,
        // 1.23 s per rebuild). Every tier and area must now fit its volume budget.
        int[] areas = {1024, 2048, 4096};
        int[] grids = {POTATO_G, LOW_G, MEDIUM_G, HIGH_G, ULTRA_G};
        long[] budgets = {POTATO_C, LOW_C, MEDIUM_C, HIGH_C, ULTRA_C};
        for (int area : areas) {
            for (int i = 0; i < grids.length; i++) {
                OrbitLod.Plan p = planFor(area, grids[i], budgets[i]);
                int half = (OrbitLod.baseFor(area) * 3) / 2;
                long cells = cellsOf(p, half, half);
                assertTrue(cells <= budgets[i],
                        "area " + area + " grid " + grids[i] + " produced " + cells + " cells, budget " + budgets[i]);
            }
        }
    }

    @Test
    void theWorstMeasuredCaseIsNowBounded() {
        // Ultra at close zoom measured 576x576x576 = 191,102,976 cells. It must now fit 16M.
        OrbitLod.Plan p = OrbitLod.plan(576, 864, 864, ULTRA_G, OrbitLod.MAX_LEVEL, ULTRA_C);
        assertTrue(cellsOf(p, 864, 864) <= ULTRA_C);
    }

    @Test
    void aTallVerticalBandShrinksCoverageRatherThanBlowingTheBudget() {
        // The Abyss band is tall; when it dominates, horizontal coverage must give way so the volume
        // still fits, instead of silently allocating hundreds of MB.
        OrbitLod.Plan tall = OrbitLod.plan(2048, 4096, 4096, MEDIUM_G, OrbitLod.MAX_LEVEL, MEDIUM_C);
        assertTrue(cellsOf(tall, 4096, 4096) <= MEDIUM_C);
        OrbitLod.Plan shallow = OrbitLod.plan(2048, 128, 128, MEDIUM_G, OrbitLod.MAX_LEVEL, MEDIUM_C);
        assertTrue(shallow.coverageBlocks() >= tall.coverageBlocks(),
                "a shorter band should afford at least as much horizontal coverage");
    }

    @Test
    void higherTiersBuyMoreCoverage() {
        assertTrue(planFor(2048, ULTRA_G, ULTRA_C).coverageBlocks()
                > planFor(2048, POTATO_G, POTATO_C).coverageBlocks());
    }

    @Test
    void levelNeverExceedsTheCeiling() {
        // Voxy stores nothing past level 4, so the search must stop there however large the request.
        for (int area : new int[]{1024, 2048, 4096}) {
            assertTrue(planFor(area, POTATO_G, POTATO_C).level() <= OrbitLod.MAX_LEVEL);
        }
    }

    @Test
    void coverageAndCellAreAlwaysPositive() {
        for (int area : new int[]{1024, 2048, 4096}) {
            OrbitLod.Plan p = planFor(area, POTATO_G, POTATO_C);
            assertTrue(p.coverageBlocks() > 0, "coverage must stay positive");
            assertTrue(p.cellBlocks() > 0);
        }
    }

    @Test
    void baseQuantisesUpToSixtyFourBlockBuckets() {
        assertEquals(64, OrbitLod.baseFor(16));
        assertEquals(64, OrbitLod.baseFor(64));
        assertEquals(128, OrbitLod.baseFor(65));
        assertEquals(1024, OrbitLod.baseFor(1024));
    }
}
