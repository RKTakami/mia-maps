package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelCloudTest {

    @Test
    void enclosedCellIsNotSurface() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true; // solid 3x3x3
        assertFalse(VoxelCloud.isSurface(g, 3, 3, 3, 1, 1, 1)); // centre fully enclosed
    }

    @Test
    void cellWithAnAirNeighbourIsSurface() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true;
        g[idx(3, 1, 1, 0)] = false; // open cell (1,1,0), a neighbour of (1,1,1)
        assertTrue(VoxelCloud.isSurface(g, 3, 3, 3, 1, 1, 1));
    }

    @Test
    void edgeCellIsSurface() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true;
        assertTrue(VoxelCloud.isSurface(g, 3, 3, 3, 0, 1, 1)); // x-1 is out of bounds -> exposed
    }

    private static int idx(int g, int x, int y, int z) { return (y * g + z) * g + x; }
}
