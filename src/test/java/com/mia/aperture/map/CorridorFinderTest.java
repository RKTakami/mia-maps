package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorridorFinderTest {

    // Helper: an all-air grid with an optional solid slab. index = (y*gz+z)*gx+x.
    private static boolean[] air(int gx, int gy, int gz) {
        return new boolean[gx * gy * gz];
    }
    private static int idx(int x, int y, int z, int gx, int gz) {
        return (y * gz + z) * gx + x;
    }

    @Test
    void straightOpenShaftIsFound() {
        int g = 5;
        boolean[] o = air(g, g, g);
        CorridorFinder.Result r = CorridorFinder.find(o, g, g, g,
                new CorridorFinder.Cell(2, 4, 2), new CorridorFinder.Cell(2, 0, 2), 10_000);
        assertEquals(CorridorFinder.Status.FOUND, r.status());
        assertEquals(new CorridorFinder.Cell(2, 4, 2), r.path().get(0));
        assertEquals(new CorridorFinder.Cell(2, 0, 2), r.path().get(r.path().size() - 1));
    }

    @Test
    void aFullSolidSlabBlocksTheDescentAsPartial() {
        int gx = 5, gy = 5, gz = 5;
        boolean[] o = air(gx, gy, gz);
        for (int x = 0; x < gx; x++)          // solid floor across the whole y=2 layer
            for (int z = 0; z < gz; z++) o[idx(x, 2, z, gx, gz)] = true;
        CorridorFinder.Result r = CorridorFinder.find(o, gx, gy, gz,
                new CorridorFinder.Cell(2, 4, 2), new CorridorFinder.Cell(2, 0, 2), 10_000);
        assertEquals(CorridorFinder.Status.PARTIAL, r.status());
        assertTrue(r.path().get(r.path().size() - 1).y() >= 3, "stops above the slab");
    }

    @Test
    void aDiagonalGapIsThreadedByThe26Connectivity() {
        // Slab at y=2 with a single open cell offset diagonally from the start column.
        int gx = 5, gy = 5, gz = 5;
        boolean[] o = air(gx, gy, gz);
        for (int x = 0; x < gx; x++)
            for (int z = 0; z < gz; z++) o[idx(x, 2, z, gx, gz)] = true;
        o[idx(3, 2, 3, gx, gz)] = false;   // one hole, diagonal from (2,*,2)
        CorridorFinder.Result r = CorridorFinder.find(o, gx, gy, gz,
                new CorridorFinder.Cell(2, 4, 2), new CorridorFinder.Cell(3, 0, 3), 10_000);
        assertEquals(CorridorFinder.Status.FOUND, r.status());
    }

    @Test
    void aStartInsideRockYieldsNoRoute() {
        int g = 3;
        boolean[] o = air(g, g, g);
        o[idx(1, 1, 1, g, g)] = true;         // start cell solid, and everything around it too
        for (int x = 0; x < g; x++) for (int y = 0; y < g; y++) for (int z = 0; z < g; z++)
            o[idx(x, y, z, g, g)] = true;
        CorridorFinder.Result r = CorridorFinder.find(o, g, g, g,
                new CorridorFinder.Cell(1, 1, 1), new CorridorFinder.Cell(0, 0, 0), 10_000);
        assertEquals(CorridorFinder.Status.NO_ROUTE, r.status());
    }
}
