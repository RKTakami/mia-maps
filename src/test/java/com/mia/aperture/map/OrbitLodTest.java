package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitLodTest {

    // Mirrors MapSettings.OrbitQuality: {gpuGrid (width safety rail), maxCells (total volume)}.
    private static final int POTATO_G = 192, LOW_G = 256, MEDIUM_G = 384, HIGH_G = 512, ULTRA_G = 640;
    private static final long POTATO_C = 4_000_000L, LOW_C = 9_000_000L, MEDIUM_C = 18_000_000L,
            HIGH_C = 28_000_000L, ULTRA_C = 40_000_000L;

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
        // Ultra at close zoom measured 576x576x576 = 191,102,976 cells. It must now fit the tier budget.
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
        assertTrue(cellsOf(shallow, 128, 128) <= MEDIUM_C);
        assertTrue(shallow.coverageBlocks() >= tall.coverageBlocks(),
                "a shorter band should afford at least as much horizontal coverage");
    }

    // Vertical band actually observed in-game at the surface (89 cells at 16 blocks); the nominal
    // 3x band is clamped to the Abyss, so this is what the renderer really samples.
    private static final int REAL_VERT = 712;

    @Test
    void budgetIsSpentOnDetailNotLeftUnused() {
        // Capping width BEFORE consulting the volume budget made a 4096 request collapse to ~2112
        // blocks at 16-block voxels while using a third of the budget. Every tier must now use most
        // of what it is given.
        int[] grids = {MEDIUM_G, HIGH_G, ULTRA_G};
        long[] budgets = {MEDIUM_C, HIGH_C, ULTRA_C};
        for (int i = 0; i < grids.length; i++) {
            OrbitLod.Plan p = OrbitLod.plan(4096, REAL_VERT, REAL_VERT, grids[i], OrbitLod.MAX_LEVEL, budgets[i]);
            long cells = cellsOf(p, REAL_VERT, REAL_VERT);
            assertTrue(cells <= budgets[i], "over budget: " + cells);
            assertTrue(cells * 3 >= budgets[i], "wastes budget: used " + cells + " of " + budgets[i]);
        }
    }

    @Test
    void coverageReachesTheRequestedArea() {
        // The number on the slider should mean something: at Medium and up, the view must actually
        // span the area asked for rather than silently mapping half of it.
        for (int area : new int[]{1024, 2048, 4096}) {
            OrbitLod.Plan p = OrbitLod.plan(area, REAL_VERT, REAL_VERT, MEDIUM_G, OrbitLod.MAX_LEVEL, MEDIUM_C);
            assertTrue(p.coverageBlocks() >= area,
                    "area " + area + " only covered " + p.coverageBlocks());
        }
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

    // ---- cascaded LOD (stage 1) ----------------------------------------------------------
    // The owner wants fine voxels over a WIDE area, which the single-level planner cannot give at
    // any budget. These lock the properties the renderer will depend on in stage 2+.

    private static final int CASCADE_MAX_LEVEL = 5;  // L5 is synthesized; Voxy stores only to L4
    private static final int SHELLS = 4;

    private static long totalCells(java.util.List<OrbitLod.Shell> c) {
        return c.stream().mapToLong(OrbitLod.Shell::cells).sum();
    }

    private static java.util.List<OrbitLod.Shell> cascade(int area, long budget) {
        return OrbitLod.planCascade(area, REAL_VERT, REAL_VERT, CASCADE_MAX_LEVEL, budget, SHELLS);
    }

    @Test
    void cascadeBeatsTheSingleLevelPlannerOnDetail() {
        // The entire justification. At Area 2048 every tier collapses to 16-block voxels because one
        // level must cover the whole box; shells must do better near the focus for the same budget.
        OrbitLod.Plan flat = OrbitLod.plan(2048, REAL_VERT, REAL_VERT, ULTRA_G, OrbitLod.MAX_LEVEL, ULTRA_C);
        var shells = cascade(2048, ULTRA_C);
        assertTrue(shells.get(0).cellBlocks() < flat.cellBlocks(),
                "innermost shell " + shells.get(0).cellBlocks() + "blk should beat flat " + flat.cellBlocks() + "blk");
    }

    @Test
    void cascadeStaysWithinTheTierBudget() {
        int[] areas = {512, 1024, 2048, 4096};
        long[] budgets = {POTATO_C, LOW_C, MEDIUM_C, HIGH_C, ULTRA_C};
        for (int area : areas) {
            for (long budget : budgets) {
                var c = cascade(area, budget);
                assertTrue(totalCells(c) <= budget,
                        "area " + area + " budget " + budget + " used " + totalCells(c));
            }
        }
    }

    @Test
    void outermostShellCoversTheFullRequest() {
        // A cascade must not quietly shrink coverage the way the single-level planner is forced to —
        // that is the whole point of spending the budget in shells.
        for (int area : new int[]{512, 1024, 2048, 4096}) {
            var c = cascade(area, ULTRA_C);
            int outer = c.get(c.size() - 1).spanBlocks();
            assertTrue(outer >= area, "area " + area + " only reached " + outer);
        }
    }

    @Test
    void shellsAreOrderedInnermostFirstAndStrictlyCoarsenOutward() {
        var c = cascade(2048, ULTRA_C);
        for (int i = 1; i < c.size(); i++) {
            assertTrue(c.get(i).level() > c.get(i - 1).level(), "levels must increase outward");
            assertTrue(c.get(i).spanBlocks() > c.get(i - 1).spanBlocks(), "spans must grow outward");
        }
    }

    @Test
    void aBiggerBudgetNeverBuysLessDetail() {
        // Monotonicity: a better tier must not somehow produce coarser voxels near the camera.
        int prev = Integer.MAX_VALUE;
        for (long budget : new long[]{POTATO_C, LOW_C, MEDIUM_C, HIGH_C, ULTRA_C}) {
            int cell = cascade(2048, budget).get(0).cellBlocks();
            assertTrue(cell <= prev, "budget " + budget + " gave " + cell + "blk, coarser than a smaller budget");
            prev = cell;
        }
    }

    @Test
    void neverExceedsTheLevelCeilingOrReturnsEmpty() {
        for (int area : new int[]{256, 1024, 4096}) {
            for (long budget : new long[]{POTATO_C, ULTRA_C}) {
                var c = cascade(area, budget);
                assertFalse(c.isEmpty(), "a cascade must always yield at least one shell");
                for (OrbitLod.Shell s : c) {
                    assertTrue(s.level() <= CASCADE_MAX_LEVEL, "level " + s.level() + " exceeds ceiling");
                    assertTrue(s.level() >= 0 && s.cellBlocks() > 0 && s.spanBlocks() > 0 && s.vertBlocks() > 0);
                }
            }
        }
    }

    @Test
    void degradesToASingleShellWhenTheBudgetIsTiny() {
        // A budget too small for even one full-span shell must still return something renderable
        // rather than nothing.
        var c = OrbitLod.planCascade(4096, REAL_VERT, REAL_VERT, CASCADE_MAX_LEVEL, 1000L, SHELLS);
        assertFalse(c.isEmpty());
    }

    @Test
    void theOverBudgetFallbackStillCoversRealGround() {
        // Regression: the fallback passed Integer.MAX_VALUE as plan()'s width rail, and plan()
        // computes gpuGrid << level — which overflowed negative and collapsed coverage to ONE cell
        // (Area 4096 with the unclamped vertical returned a 32-block span). Every other assertion
        // here passed while that was broken, because it only triggers when even the coarsest single
        // shell blows the budget.
        int nominalHalf = (OrbitLod.baseFor(4096) * 3) / 2;
        var c = OrbitLod.planCascade(4096, nominalHalf, nominalHalf, CASCADE_MAX_LEVEL, ULTRA_C, SHELLS);
        int outer = c.get(c.size() - 1).spanBlocks();
        assertTrue(outer >= 1024,
                "over-budget fallback collapsed to a " + outer + "-block span; it must still cover real ground");
    }

    @Test
    void shellCountIsRespected() {
        for (int max : new int[]{1, 2, 3, 4}) {
            var c = OrbitLod.planCascade(2048, REAL_VERT, REAL_VERT, CASCADE_MAX_LEVEL, ULTRA_C, max);
            assertTrue(c.size() <= max, "asked for <=" + max + " shells, got " + c.size());
        }
    }
}
