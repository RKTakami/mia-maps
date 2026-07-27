package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitLodTest {

    // Quality tiers' gpuGrid budgets, mirroring MapSettings.OrbitQuality.
    private static final int POTATO = 128, LOW = 208, MEDIUM = 288, HIGH = 416, ULTRA = 576;

    private static OrbitLod.Plan planFor(int area, int grid) {
        return OrbitLod.planForArea(area, grid, 4);
    }

    @Test
    void lowQualityAtLargeAreaUnderDeliversCoverage() {
        // The camera samples 3x the area for the frustum footprint, but the grid budget caps it at
        // gpuGrid << level. At Low/4096 that is 208<<4 = 3328 — LESS than the 4096 the user asked for.
        OrbitLod.Plan p = planFor(4096, LOW);
        assertEquals(4, p.level());
        assertEquals(16, p.cellBlocks());
        assertEquals(3328, p.coverageBlocks());
        assertTrue(p.clamped(), "Low at 4096 cannot cover the requested area");
        assertTrue(p.coverageBlocks() < 4096, "coverage falls short of the requested area");
    }

    @Test
    void lowQualityNeverGoesFinerThanSixteenBlockVoxels() {
        // The 3x frustum multiplier always pushes Low to the level-4 ceiling, at every area step.
        for (int area : new int[]{1024, 2048, 4096}) {
            assertEquals(16, planFor(area, LOW).cellBlocks(), "Low should stay at 16-block voxels at area " + area);
        }
    }

    @Test
    void highQualityAtSmallAreaReachesEightBlockVoxels() {
        OrbitLod.Plan p = planFor(1024, HIGH);
        assertEquals(3, p.level());
        assertEquals(8, p.cellBlocks());
        assertEquals(3072, p.coverageBlocks());
        assertFalse(p.clamped(), "High at 1024 covers its full requested area");
    }

    @Test
    void coverageIsNotClampedWhenTheBudgetFits() {
        OrbitLod.Plan p = planFor(1024, LOW);
        assertEquals(3072, p.coverageBlocks());
        assertFalse(p.clamped());
    }

    @Test
    void ultraCoversMoreThanLowAtTheSameArea() {
        assertTrue(planFor(4096, ULTRA).coverageBlocks() > planFor(4096, LOW).coverageBlocks());
    }

    @Test
    void levelNeverExceedsTheCeiling() {
        // Voxy stores nothing past level 4, so the search must stop there however large the area.
        assertEquals(4, planFor(4096, POTATO).level());
        assertTrue(planFor(4096, POTATO).level() <= 4);
    }

    @Test
    void aShortVerticalBandDoesNotForceACoarserLevel() {
        // Near the top/bottom of the Abyss the vertical extent is clamped, so the horizontal extent
        // governs the level. Same horizontal request, smaller vertical -> never coarser.
        OrbitLod.Plan tall = OrbitLod.plan(1024, 1536, 1536, MEDIUM, 4);
        OrbitLod.Plan shallow = OrbitLod.plan(1024, 64, 64, MEDIUM, 4);
        assertTrue(shallow.level() <= tall.level());
    }

    @Test
    void shippedDefaultCoversItsRequestedArea() {
        // Ships as MEDIUM / 2048. Coverage exceeds the request (the surplus is frustum headroom), so
        // the settings screen must NOT flag a shortfall here — only when coverage < the area asked for.
        OrbitLod.Plan p = planFor(2048, MEDIUM);
        assertTrue(p.coverageBlocks() >= 2048, "default must reach its requested area");
        assertTrue(p.clamped(), "it is still clamped against the 3x frustum request");
    }

    @Test
    void onlyTheTopTiersAtTheSmallestAreaReachEightBlockVoxels() {
        // Detail is dominated by area, not quality: every other combination sits at the level-4
        // ceiling, so the quality slider barely changes what the view looks like.
        assertEquals(8, planFor(1024, HIGH).cellBlocks());
        assertEquals(8, planFor(1024, ULTRA).cellBlocks());
        assertEquals(16, planFor(1024, MEDIUM).cellBlocks());
        assertEquals(16, planFor(2048, ULTRA).cellBlocks());
        assertEquals(16, planFor(2048, HIGH).cellBlocks());
    }

    @Test
    void baseQuantisesUpToSixtyFourBlockBuckets() {
        assertEquals(64, OrbitLod.baseFor(16));
        assertEquals(64, OrbitLod.baseFor(64));
        assertEquals(128, OrbitLod.baseFor(65));
        assertEquals(1024, OrbitLod.baseFor(1024));
    }
}
