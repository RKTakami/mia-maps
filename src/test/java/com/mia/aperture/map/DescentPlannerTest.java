package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DescentPlannerTest {
    private static int idx(int gx, int gz, int x, int y, int z) { return (y * gz + z) * gx + x; }

    @Test
    void straightDownReachesLedge() {
        int gx = 5, gy = 16, gz = 3, z = 1;
        boolean[] op = new boolean[gx * gy * gz];
        // frontier pillar at x=0: solid floor y=8..11 (stand at y=12)
        for (int y = 8; y <= 11; y++) op[idx(gx, gz, 0, y, z)] = true;
        // a ledge one step toward the goal (x=1) at stand-level y=8: floor at y=7
        op[idx(gx, gz, 1, 7, z)] = true;
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        DescentPlanner.Plan p = DescentPlanner.plan(g, 0, 12, z, 4, 0, z, 24, 8);
        assertNotNull(p);
        for (int[] c : p.cells()) assertEquals(0, c[0]);   // vertical only, no tunnel
        assertFalse(p.cells().isEmpty());
    }

    @Test
    void overhangNeedsTunnel() {
        int gx = 6, gy = 16, gz = 3, z = 1;
        boolean[] op = new boolean[gx * gy * gz];
        // frontier pillar x=0 solid y=10..11 (stand y=12); below y<=9 is void under an overhang
        op[idx(gx, gz, 0, 11, z)] = true;
        op[idx(gx, gz, 0, 10, z)] = true;
        // overhang mass one step toward goal at x=1: solid at body/head (y=10,11) with floor at y=9
        op[idx(gx, gz, 1, 9, z)] = true;
        op[idx(gx, gz, 1, 10, z)] = true;
        op[idx(gx, gz, 1, 11, z)] = true;
        // x>=2 open (air) -> break-out to the open face
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        DescentPlanner.Plan p = DescentPlanner.plan(g, 0, 12, z, 5, 0, z, 24, 8);
        assertNotNull(p);
        boolean hasVertical = p.cells().stream().anyMatch(c -> c[0] == 0);
        boolean hasTunnel = p.cells().stream().anyMatch(c -> c[0] == 1);
        assertTrue(hasVertical && hasTunnel);
    }

    @Test
    void voidWithNoBreakoutReturnsNull() {
        int gx = 5, gy = 16, gz = 3, z = 1;
        boolean[] op = new boolean[gx * gy * gz];
        // frontier floor only at x=0,y=11 (stand y=12); everything below/around is air
        op[idx(gx, gz, 0, 11, z)] = true;
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        DescentPlanner.Plan p = DescentPlanner.plan(g, 0, 12, z, 4, 0, z, 24, 8);
        assertNull(p);
    }
}
