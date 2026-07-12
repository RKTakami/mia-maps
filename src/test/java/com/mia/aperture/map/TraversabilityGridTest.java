package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraversabilityGridTest {
    // grid layout matches VoxelCloud: index = (y*gz + z)*gx + x
    private static boolean[] grid(int gx, int gy, int gz) { return new boolean[gx * gy * gz]; }
    private static void set(boolean[] o, int gx, int gz, int x, int y, int z) { o[(y * gz + z) * gx + x] = true; }

    @Test
    void standableNeedsGroundAndHeadroom() {
        int gx = 3, gy = 4, gz = 3;
        boolean[] o = grid(gx, gy, gz);
        set(o, gx, gz, 1, 0, 1); // solid floor cell at y=0
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        assertTrue(g.standable(1, 1, 1));   // stand on the floor: feet y=1, head y=2 air, ground y=0
        assertFalse(g.standable(1, 0, 1));  // inside the solid block
        assertFalse(g.standable(1, 2, 1));  // floating: no ground at y=1
    }

    @Test
    void blockedHeadroomIsNotStandable() {
        int gx = 3, gy = 4, gz = 3;
        boolean[] o = grid(gx, gy, gz);
        set(o, gx, gz, 1, 0, 1); // floor
        set(o, gx, gz, 1, 2, 1); // ceiling at head height
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        assertFalse(g.standable(1, 1, 1)); // head blocked
    }
}
