package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelCloudTest {

    @Test
    void columnTopSolidMarksHighestOpaquePerColumn() {
        int gX = 2, gY = 4, gZ = 1;
        boolean[] op = new boolean[gX * gY * gZ];
        // index = (y*gZ + z)*gX + x
        op[((1) * gZ + 0) * gX + 0] = true; // column x=0: solid at y=1
        op[((3) * gZ + 0) * gX + 0] = true; // column x=0: solid at y=3 (highest)
        // column x=1: all air
        int[] top = VoxelCloud.columnTopSolid(op, gX, gY, gZ);
        assertEquals(3, top[0 * gX + 0]); // x=0 -> highest solid y=3
        assertEquals(-1, top[0 * gX + 1]); // x=1 -> none
        // a voxel at y=1 in column 0 is "covered" (below the top solid at 3)
        assertTrue(1 < top[0 * gX + 0]);
    }

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

    @Test
    void normalPointsTowardTheExposedFace() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true;
        g[idx(3, 2, 1, 1)] = false; // open (x=2,y=1,z=1), the +X neighbour of (1,1,1)
        float[] n = VoxelCloud.surfaceNormal(g, 3, 3, 3, 1, 1, 1);
        assertEquals(1f, n[0], 1e-4);
        assertEquals(0f, n[1], 1e-4);
        assertEquals(0f, n[2], 1e-4);
    }

    @Test
    void normalDefaultsUpWhenNoInBoundsAir() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true; // corner cell only exposed via OOB
        float[] n = VoxelCloud.surfaceNormal(g, 3, 3, 3, 0, 0, 0);
        assertEquals(0f, n[0], 1e-4);
        assertEquals(1f, n[1], 1e-4);
        assertEquals(0f, n[2], 1e-4);
    }

    private static int idx(int g, int x, int y, int z) { return (y * g + z) * g + x; }
}
