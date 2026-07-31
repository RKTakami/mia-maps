package com.mia.aperture.lod;

import com.mia.aperture.map.Face;
import com.mia.aperture.map.MapColorSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodSectionMeshTest {

    private static final int E = LodNative.EDGE;
    private static final int STONE = 7;

    // Block id 7 is opaque grey; everything else is air. Biome is ignored.
    private final MapColorSource colors = new MapColorSource() {
        @Override public int baseColor(long id, Face face) {
            return face == Face.TOP ? 0xFF9A9A9A : 0xFF6A6A6A;
        }
        @Override public boolean isWater(long id) { return false; }
        @Override public boolean isOpaque(long id) { return LodColorSource.blockOf(id) == STONE; }
    };

    private static int[] air() { return new int[LodNative.CELLS]; }
    private static int[] noBiomes() { return new int[LodNative.BIOME_CELLS]; }
    private static int idx(int x, int y, int z) { return (y * E + z) * E + x; }

    @Test
    void emptySectionProducesNothing() {
        LodSectionMesh.Mesh m = LodSectionMesh.build(air(), noBiomes(), colors, 1, 0, 0, 0, null);
        assertTrue(m.isEmpty());
        assertSame(LodSectionMesh.EMPTY, m, "an empty section should not allocate");
    }

    @Test
    void aFullySolidSectionEmitsOnlyItsShell() {
        // The whole point of surface extraction: 4096 solid cells, but only the 6 outer faces are
        // visible, so cost is surface area rather than volume. Interior faces must not be emitted.
        //
        // Six quads, not 6*E*E: coplanar faces of one colour merge, and a solid cube's side IS one
        // rectangle. That merging is worth a factor of 256 here, and the reason 314k quads a frame
        // was ever plausible on terrain that is mostly flat wall.
        int[] ids = air();
        java.util.Arrays.fill(ids, STONE);
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        assertEquals(6, m.quads(), "one merged quad per side of a solid cube");
        assertEquals(6 * E * E, surfaceArea(m), "merging must not lose or gain any surface");
    }

    /** Total area of every quad, in cells. Merging changes the count but must preserve this. */
    private static double surfaceArea(LodSectionMesh.Mesh m) {
        float[] p = m.positions();
        double total = 0;
        for (int q = 0; q < m.quads(); q++) {
            int b = q * 12;
            // Two edges from the first corner; the quads are axis-aligned rectangles.
            double[] e1 = {p[b + 3] - p[b], p[b + 4] - p[b + 1], p[b + 5] - p[b + 2]};
            double[] e2 = {p[b + 9] - p[b], p[b + 10] - p[b + 1], p[b + 11] - p[b + 2]};
            double cx = e1[1] * e2[2] - e1[2] * e2[1];
            double cy = e1[2] * e2[0] - e1[0] * e2[2];
            double cz = e1[0] * e2[1] - e1[1] * e2[0];
            total += Math.sqrt(cx * cx + cy * cy + cz * cz);
        }
        return total;
    }

    @Test
    void twoColoursDoNotMergeIntoOneQuad() {
        // Merging is by colour, so a checkerboard cannot merge at all. Without this a bug that
        // merged regardless of colour would pass every other test here and paint whole walls in
        // whichever material happened to be found first.
        int[] ids = air();
        for (int x = 0; x < E; x++) {
            for (int z = 0; z < E; z++) ids[idx(x, 0, z)] = ((x + z) % 2 == 0) ? STONE : STONE + 1;
        }
        MapColorSource two = new MapColorSource() {
            @Override public int baseColor(long id, Face face) {
                return LodColorSource.blockOf(id) == STONE ? 0xFF111111 : 0xFF222222;
            }
            @Override public boolean isWater(long id) { return false; }
            @Override public boolean isOpaque(long id) {
                int b = LodColorSource.blockOf(id);
                return b == STONE || b == STONE + 1;
            }
        };
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), two, 1, 0, 0, 0, null);
        // Every top face is its own quad, because no two adjacent ones share a colour.
        int tops = 0;
        for (int q = 0; q < m.quads(); q++) if (m.normals()[q * 3 + 1] > 0) tops++;
        assertEquals(E * E, tops, "alternating colours must not merge");
    }

    @Test
    void aMergedRunSpansTheCellsItReplaces() {
        // A 1x4 strip should become ONE top quad four cells long, not four — and it must actually
        // cover those four cells, not stretch from the wrong corner.
        int[] ids = air();
        for (int z = 0; z < 4; z++) ids[idx(5, 0, z)] = STONE;
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        int tops = 0;
        double topArea = 0;
        for (int q = 0; q < m.quads(); q++) {
            if (m.normals()[q * 3 + 1] <= 0) continue;
            tops++;
            float[] p = m.positions();
            int b = q * 12;
            double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                minZ = Math.min(minZ, p[b + c * 3 + 2]);
                maxZ = Math.max(maxZ, p[b + c * 3 + 2]);
            }
            topArea = maxZ - minZ;
        }
        assertEquals(1, tops, "four cells in a row are one quad");
        assertEquals(4.0, topArea, 1e-6, "and it spans all four");
    }

    @Test
    void aSingleCellEmitsSixFaces() {
        int[] ids = air();
        ids[idx(8, 8, 8)] = STONE;
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        assertEquals(6, m.quads());
        assertEquals(6 * 4 * 3, m.positions().length);
    }

    @Test
    void enclosedRockIsInvisibleAndCostsNoGeometry() {
        // A cell buried inside rock has no open side, so it contributes nothing. This is what makes
        // the underground affordable: the Abyss is mostly solid, and solid interiors are free.
        int[] ids = air();
        for (int y = 7; y <= 9; y++)
            for (int z = 7; z <= 9; z++)
                for (int x = 7; x <= 9; x++) ids[idx(x, y, z)] = STONE;
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        // A 3x3x3 block has 27 cells but only its 6 outer 3x3 faces are open: 54 quads. The centre
        // cell contributes zero.
        assertEquals(6, m.quads(), "a solid 3x3x3 is six merged faces");
        assertEquals(54, surfaceArea(m), 1e-6, "and still exactly its shell's area");
    }

    @Test
    void neighboursSuppressTheFaceBetweenTwoSections() {
        // Without a neighbour lookup every section is sealed in its own shell, which draws as a grid
        // of boxes rather than continuous terrain. With one, the shared face disappears.
        int[] ids = air();
        for (int y = 0; y < E; y++)
            for (int z = 0; z < E; z++) ids[idx(E - 1, y, z)] = STONE;   // a slab on the +X face

        LodSectionMesh.Mesh sealed = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        // Solid neighbour on +X only.
        LodSectionMesh.Mesh joined = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0,
                (x, y, z) -> x >= E ? LodColorSource.mappingId(STONE, 0) : 0);
        // Stated as AREA, not as a quad count. The face being culled is a fixed E*E of surface
        // whether it merges into one quad or stays as 256, so the area is the durable claim — the
        // count changed the moment faces began merging and the test would have to be rewritten
        // again on the next meshing change.
        assertEquals(E * E, surfaceArea(sealed) - surfaceArea(joined), 1e-6,
                "the whole +X face should be culled against the neighbour");
        assertEquals(1, sealed.quads() - joined.quads(), "and it was a single merged quad");
    }

    @Test
    void positionsAreScaledByCellSizeAndOffsetToWorld() {
        int[] ids = air();
        ids[idx(0, 0, 0)] = STONE;
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 4, 1000, -64, 32, null);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float[] p = m.positions();
        for (int i = 0; i < p.length; i += 3) {
            minX = Math.min(minX, p[i]);     maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i + 1]); maxY = Math.max(maxY, p[i + 1]);
            minZ = Math.min(minZ, p[i + 2]); maxZ = Math.max(maxZ, p[i + 2]);
        }
        // Cell (0,0,0) at cellSize 4 spans one 4-block cube at the section origin.
        assertEquals(1000f, minX); assertEquals(1004f, maxX);
        assertEquals(-64f, minY);  assertEquals(-60f, maxY);
        assertEquals(32f, minZ);   assertEquals(36f, maxZ);
    }

    @Test
    void upwardFacesUseTheTopColourAndSidesDoNot() {
        // The map draws grass green from above and dirt brown at the side; geometry has to make the
        // same distinction or every block wears its top colour on all six faces.
        int[] ids = air();
        ids[idx(4, 4, 4)] = STONE;
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        int top = 0, side = 0;
        for (int i = 0; i < m.quads(); i++) {
            if (m.colors()[i] == 0xFF9A9A9A) top++;
            else if (m.colors()[i] == 0xFF6A6A6A) side++;
        }
        assertEquals(1, top, "exactly the upward face");
        assertEquals(5, side);
    }

    @Test
    void normalsPointOutward() {
        int[] ids = air();
        ids[idx(4, 4, 4)] = STONE;
        LodSectionMesh.Mesh m = LodSectionMesh.build(ids, noBiomes(), colors, 1, 0, 0, 0, null);
        float[] n = m.normals();
        // Each normal is a unit axis vector, and all six are distinct.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < m.quads(); i++) {
            float x = n[i * 3], y = n[i * 3 + 1], z = n[i * 3 + 2];
            assertEquals(1.0f, Math.abs(x) + Math.abs(y) + Math.abs(z), 1e-6, "unit axis normal");
            seen.add(x + "," + y + "," + z);
        }
        assertEquals(6, seen.size(), "one face per direction, no duplicates");
    }
}
