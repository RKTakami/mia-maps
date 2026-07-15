package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelCloudTest {

    @Test
    void outsideAirFloodsAllAirWhenNoWalls() {
        int g = 5;
        boolean[] op = new boolean[g * g * g]; // all air
        boolean[] out = VoxelCloud.outsideAir(op, g, g, g);
        for (boolean b : out) assertTrue(b); // everything reachable from the boundary
    }

    @Test
    void enclosedAirIsNotOutside() {
        int g = 5;
        boolean[] op = new boolean[g * g * g];
        java.util.Arrays.fill(op, true);       // solid block
        int c = (2 * g + 2) * g + 2;           // carve one enclosed air cell at the centre
        op[c] = false;
        boolean[] out = VoxelCloud.outsideAir(op, g, g, g);
        assertFalse(out[c]); // sealed off from the boundary -> not outside
    }

    @Test
    void wallTouchingEnclosedAirIsInterior_butBoxEdgeIsShell() {
        int g = 5;
        boolean[] op = new boolean[g * g * g];
        java.util.Arrays.fill(op, true);
        op[(2 * g + 2) * g + 2] = false;       // enclosed air pocket at (2,2,2)
        boolean[] out = VoxelCloud.outsideAir(op, g, g, g);
        // solid at (2,2,1) touches the enclosed pocket and no box edge -> interior cave wall
        assertTrue(VoxelCloud.isInteriorSurface(op, out, g, g, g, 2, 2, 1));
        // solid at (2,2,0) sits on the box edge -> treated as outer shell
        assertFalse(VoxelCloud.isInteriorSurface(op, out, g, g, g, 2, 2, 0));
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
