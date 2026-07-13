package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathfinderTest {
    private static void floor(boolean[] o, int gx, int gz, int y, int x0, int x1, int z) {
        for (int x = x0; x <= x1; x++) o[(y * gz + z) * gx + x] = true;
    }
    private static final Pathfinder.Params P = new Pathfinder.Params(1, 3, 1);

    @Test
    void findsFlatWalk() {
        int gx = 8, gy = 4, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        floor(o, gx, gz, 0, 0, 7, 1); // solid floor row at y=0, z=1
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g, new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(7, 1, 1), P, 10000);
        assertEquals(Pathfinder.Status.FOUND, r.status());
        assertEquals(new Pathfinder.Cell(0, 1, 1), r.path().get(0));
        assertEquals(new Pathfinder.Cell(7, 1, 1), r.path().get(r.path().size() - 1));
    }

    @Test
    void jumpsAOneWideGapButNotTwo() {
        int gx = 8, gy = 4, gz = 3;
        boolean[] o1 = new boolean[gx * gy * gz];
        floor(o1, gx, gz, 0, 0, 7, 1);
        o1[(0 * gz + 1) * gx + 3] = false; // remove x=3 -> 1-wide gap
        Pathfinder.Result r1 = Pathfinder.find(new TraversabilityGrid(o1, gx, gy, gz),
                new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(7, 1, 1), P, 10000);
        assertEquals(Pathfinder.Status.FOUND, r1.status());

        boolean[] o2 = new boolean[gx * gy * gz];
        floor(o2, gx, gz, 0, 0, 7, 1);
        o2[(0 * gz + 1) * gx + 3] = false;
        o2[(0 * gz + 1) * gx + 4] = false; // 2-wide gap -> unreachable at maxJumpGap=1
        Pathfinder.Result r2 = Pathfinder.find(new TraversabilityGrid(o2, gx, gy, gz),
                new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(7, 1, 1), P, 10000);
        assertNotEquals(Pathfinder.Status.FOUND, r2.status());
    }

    @Test
    void dropWithinMaxFall() {
        int gx = 4, gy = 8, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        o[(5 * gz + 1) * gx + 0] = true;  // high floor under x=0 (stand at y=6)
        o[(5 * gz + 1) * gx + 1] = true;
        o[(3 * gz + 1) * gx + 2] = true;  // lower floor under x=2 (stand at y=4) -> drop of 2
        o[(3 * gz + 1) * gx + 3] = true;
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g, new Pathfinder.Cell(0, 6, 1), new Pathfinder.Cell(3, 4, 1), P, 10000);
        assertEquals(Pathfinder.Status.FOUND, r.status());
    }

    @Test
    void descendsFourBlockDropUnderMaxFallFour() {
        int gx = 4, gy = 10, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        floor(o, gx, gz, 7, 0, 1, 1);   // top ledge, stand at y=8
        floor(o, gx, gz, 3, 2, 3, 1);   // lower ledge 4 below, stand at y=4
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Cell start = new Pathfinder.Cell(0, 8, 1), goal = new Pathfinder.Cell(3, 4, 1);

        Pathfinder.Result okFall = Pathfinder.find(g, start, goal, new Pathfinder.Params(1, 4, 1), 100000);
        assertEquals(Pathfinder.Status.FOUND, okFall.status());

        Pathfinder.Result noFall = Pathfinder.find(g, start, goal, new Pathfinder.Params(1, 3, 1), 100000);
        assertNotEquals(Pathfinder.Status.FOUND, noFall.status());
    }

    @Test
    void dropBlockedByOverhangRock() {
        // A standable air pocket sits below the player, but solid rock fills the fall column
        // (an overhang). The pathfinder must NOT route into it — you'd have to dig.
        int gx = 3, gy = 12, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        floor(o, gx, gz, 7, 0, 0, z);   // player ledge at x=0, stand y=8
        floor(o, gx, gz, 3, 1, 1, z);   // pocket floor at x=1 -> stand y=4 (pocket cells y=4,5 air)
        for (int y = 6; y <= 9; y++) o[(y * gz + z) * gx + 1] = true; // overhang rock above the pocket
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g,
                new Pathfinder.Cell(0, 8, z), new Pathfinder.Cell(1, 4, z),
                new Pathfinder.Params(1, 4, 1), 100000);
        assertNotEquals(Pathfinder.Status.FOUND, r.status());
    }

    @Test
    void noRouteWhenIsolated() {
        int gx = 5, gy = 4, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        o[(0 * gz + 1) * gx + 0] = true;  // lone start floor
        o[(0 * gz + 1) * gx + 4] = true;  // lone goal floor, far, unreachable
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g, new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(4, 1, 1), P, 10000);
        assertNotEquals(Pathfinder.Status.FOUND, r.status());
    }
}
