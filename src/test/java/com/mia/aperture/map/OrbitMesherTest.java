package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitMesherTest {

    private static boolean[] solidBall(int g, double r) {
        boolean[] o = new boolean[g * g * g];
        double c = (g - 1) / 2.0;
        for (int y = 0; y < g; y++)
            for (int z = 0; z < g; z++)
                for (int x = 0; x < g; x++) {
                    double dx = x - c, dy = y - c, dz = z - c;
                    if (dx * dx + dy * dy + dz * dz <= r * r) o[(y * g + z) * g + x] = true;
                }
        return o;
    }

    @Test
    void allAirProducesNoTriangles() {
        OrbitMesher.Mesh m = OrbitMesher.build(new boolean[8 * 8 * 8], new int[8 * 8 * 8],
                8, 8, 8, 1, 0, 0, 0);
        assertEquals(0, m.tris().length);
    }

    @Test
    void allSolidInteriorProducesNoSurface() {
        // A fully solid grid has no iso-crossing (density stays 1 everywhere, in-bounds averaging),
        // so no surface.
        boolean[] o = new boolean[6 * 6 * 6];
        java.util.Arrays.fill(o, true);
        OrbitMesher.Mesh m = OrbitMesher.build(o, new int[6 * 6 * 6], 6, 6, 6, 1, 0, 0, 0);
        assertEquals(0, m.tris().length);
    }

    @Test
    void ballProducesAWatertightSmoothShell() {
        int g = 24;
        boolean[] o = solidBall(g, 8);
        int[] col = new int[g * g * g];
        java.util.Arrays.fill(col, 0xFF3366CC);
        OrbitMesher.Mesh m = OrbitMesher.build(o, col, g, g, g, 1, 0, 0, 0);

        assertTrue(m.tris().length >= 3, "ball should produce triangles");
        assertEquals(0, m.tris().length % 3, "triangle indices come in triples");
        int verts = m.positions().length / 3;
        assertEquals(verts * 3, m.normals().length);
        assertEquals(verts, m.colors().length);
        for (int t : m.tris()) assertTrue(t >= 0 && t < verts, "index in range");

        // Watertight: every edge shared by exactly two triangles (closed manifold).
        java.util.HashMap<Long, Integer> edges = new java.util.HashMap<>();
        int[] tr = m.tris();
        for (int i = 0; i < tr.length; i += 3) {
            addEdge(edges, tr[i], tr[i + 1]);
            addEdge(edges, tr[i + 1], tr[i + 2]);
            addEdge(edges, tr[i + 2], tr[i]);
        }
        for (var e : edges.entrySet()) assertEquals(2, e.getValue(), "edge shared by 2 tris (watertight)");

        // Normals point outward: dot(normal, vertex - center) > 0 for the vast majority of vertices.
        double c = (g - 1) / 2.0;
        int outward = 0;
        for (int v = 0; v < verts; v++) {
            double px = m.positions()[v * 3] - c, py = m.positions()[v * 3 + 1] - c, pz = m.positions()[v * 3 + 2] - c;
            double nx = m.normals()[v * 3], ny = m.normals()[v * 3 + 1], nz = m.normals()[v * 3 + 2];
            if (px * nx + py * ny + pz * nz > 0) outward++;
        }
        assertTrue(outward > verts * 0.9, "≥90% of normals point outward, got " + outward + "/" + verts);
    }

    private static void addEdge(java.util.HashMap<Long, Integer> edges, int a, int b) {
        long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
        edges.merge(key, 1, Integer::sum);
    }
}
