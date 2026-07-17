package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgePlannerTest {
    // solid block at (x,y,z)
    private static void set(boolean[] o, int gx, int gz, int x, int y, int z) {
        o[(y * gz + z) * gx + x] = true;
    }

    @Test
    void tunnelsThroughAWallToAShaftThenResumesByDropping() {
        // Frontier stands on a floor at x=0..1; a solid wall at x=2 (full height); beyond it at
        // x=3 an open shaft down to a deep floor. The bridge must tunnel through the x=2 wall to
        // x=3, where a survivable drop resumes.
        int gx = 6, gy = 20, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        for (int x = 0; x <= 1; x++) set(o, gx, gz, x, 9, z);     // frontier floor, stand y=10
        for (int y = 0; y < gy; y++) set(o, gx, gz, 2, y, z);     // solid wall at x=2
        set(o, gx, gz, 3, 3, z);                                  // shaft landing at x=3, stand y=4
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);

        BridgePlanner.Plan plan = BridgePlanner.plan(g, 1, 10, z, 4, 4, z, 4, 12, 32);
        assertNotNull(plan);
        assertArrayEquals(new int[]{1, 10, z}, plan.entry());
        assertFalse(plan.cells().isEmpty(), "must mine the wall");
        for (int[] c : plan.cells()) assertTrue(g.opaque(c[0], c[1], c[2]), "mines only rock");
    }

    @Test
    void nullWhenNoResumeWithinBudget() {
        // Frontier boxed in solid with no reachable drop within the dig budget.
        int gx = 5, gy = 8, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        for (int x = 0; x < gx; x++) for (int y = 0; y < gy; y++) set(o, gx, gz, x, y, z);
        // carve a 1-cell standing pocket for the frontier at (2, 4)
        o[(4 * gz + z) * gx + 2] = false;
        o[(5 * gz + z) * gx + 2] = false;
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        assertNull(BridgePlanner.plan(g, 2, 4, z, 0, 0, z, 4, 12, 4));
    }

    @Test
    void bridgesPastABlockingLipToReachAShaftEdge() {
        // Frontier on a ledge; the cell straight east is solid rock (a lip that blocks a plain
        // walk), so A* stalled. One block east of that is an open shaft with a survivable landing.
        // Mining through the lip reaches the shaft edge, from which you step off and drop.
        int gx = 4, gy = 16, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        set(o, gx, gz, 0, 9, z);     // frontier floor, stand y=10
        set(o, gx, gz, 1, 9, z);     // lip floor (so the mined cell above it is standable)
        set(o, gx, gz, 1, 10, z);    // lip block at frontier level — blocks the walk, gets mined
        set(o, gx, gz, 2, 3, z);     // shaft landing east, stand y=4 (drop of 6)
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        BridgePlanner.Plan plan = BridgePlanner.plan(g, 0, 10, z, 2, 4, z, 4, 12, 32);
        assertNotNull(plan);
        assertFalse(plan.cells().isEmpty());
        for (int[] c : plan.cells()) assertTrue(g.opaque(c[0], c[1], c[2]), "mines only rock");
    }
}
