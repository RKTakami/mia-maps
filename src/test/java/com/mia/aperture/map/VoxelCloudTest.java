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

    // --- MapMode.CAVES: carveToReachable ---------------------------------------------------
    // The 3D half of the depth slice. These pin the property that makes it not the see-through
    // view removed in dfeb3e5: a cave is drawn only if AIR connects it to where you stand.

    private static boolean[] solid(int g) {
        boolean[] op = new boolean[g * g * g];
        java.util.Arrays.fill(op, true);
        return op;
    }

    private static int gi(int g, int x, int y, int z) { return (y * g + z) * g + x; }

    @Test
    void carveKeepsThePocketYouStandIn() {
        int g = 9;
        boolean[] op = solid(g);
        for (int y = 3; y <= 5; y++)                     // a chamber around the centre
            for (int z = 3; z <= 5; z++)
                for (int x = 3; x <= 5; x++) op[gi(g, x, y, z)] = false;
        assertTrue(VoxelCloud.carveToReachable(op, g, g, g, 4, 4, 4, 4, 4,
                new int[g * g * g], new boolean[g * g * g]));
        assertFalse(op[gi(g, 4, 4, 4)], "the chamber stays open");
        assertFalse(op[gi(g, 3, 3, 3)]);
        assertTrue(op[gi(g, 0, 0, 0)], "the surrounding rock stays solid");
    }

    @Test
    void carveHidesASealedChamberBehindAWall() {
        int g = 11;
        boolean[] op = solid(g);
        for (int z = 1; z <= 3; z++) op[gi(g, 5, 5, z)] = false;   // where you are
        for (int z = 7; z <= 9; z++) op[gi(g, 5, 5, z)] = false;   // sealed, one cell of rock away
        assertTrue(VoxelCloud.carveToReachable(op, g, g, g, 5, 5, 2, 5, 5,
                new int[g * g * g], new boolean[g * g * g]));
        assertFalse(op[gi(g, 5, 5, 2)], "your own passage survives");
        assertTrue(op[gi(g, 5, 5, 8)], "the sealed chamber must be filled in — this is the whole rule");
    }

    @Test
    void carveFollowsAConnectingTunnel() {
        int g = 11;
        boolean[] op = solid(g);
        for (int z = 1; z <= 9; z++) op[gi(g, 5, 5, z)] = false;   // now they are joined
        assertTrue(VoxelCloud.carveToReachable(op, g, g, g, 5, 5, 2, 5, 5,
                new int[g * g * g], new boolean[g * g * g]));
        assertFalse(op[gi(g, 5, 5, 8)], "air you could walk to is drawn");
    }

    @Test
    void carveStopsAtTheSlab() {
        int g = 11;
        boolean[] op = solid(g);
        for (int y = 0; y < g; y++) op[gi(g, 5, y, 5)] = false;    // a shaft the full height
        assertTrue(VoxelCloud.carveToReachable(op, g, g, g, 5, 5, 5, 2, 2,
                new int[g * g * g], new boolean[g * g * g]));
        assertFalse(op[gi(g, 5, 5, 5)]);
        assertFalse(op[gi(g, 5, 7, 5)], "inside the slab");
        assertTrue(op[gi(g, 5, 9, 5)], "beyond it the shaft is cut off");
        assertTrue(op[gi(g, 5, 1, 5)]);
    }

    @Test
    void carveLeavesOpenSkyAlone() {
        int g = 9;
        boolean[] op = new boolean[g * g * g];             // all air, as above ground
        for (int z = 0; z < g; z++)
            for (int x = 0; x < g; x++) op[gi(g, x, 0, z)] = true;   // ground plane
        boolean[] before = op.clone();
        assertTrue(VoxelCloud.carveToReachable(op, g, g, g, 4, 4, 4, 4, 4,
                new int[g * g * g], new boolean[g * g * g]));
        assertArrayEquals(before, op, "on open ground the carve must change nothing");
    }

    @Test
    void carveReportsWhenThereIsNoOpenAir() {
        int g = 5;
        boolean[] op = solid(g);
        assertFalse(VoxelCloud.carveToReachable(op, g, g, g, 2, 2, 2, 2, 2,
                new int[g * g * g], new boolean[g * g * g]));
    }
}
