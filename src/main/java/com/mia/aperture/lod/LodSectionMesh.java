package com.mia.aperture.lod;

import com.mia.aperture.map.Face;
import com.mia.aperture.map.MapColorSource;

/**
 * Extracts the visible faces of ONE stored section into a drawable mesh.
 *
 * <p>This is the sparse half of level-of-detail rendering, and the reason it matters is cost. The
 * orbit view builds a dense grid over the whole view volume and pays for every cell — solid rock
 * interior, empty void, everything — so its cost is cubic in span÷cell and detail is capped by the
 * budget rather than by what is visible. Measured on this world: at 512 blocks across, the finest
 * affordable cell is 4 blocks on Low and 2 on Ultra, which erases a one-block passage by definition.
 *
 * <p>Working a section at a time changes the shape of that. Each section is a fixed 4096 cells, so
 * the work is proportional to <b>how many sections exist and have surfaces</b>, not to the volume
 * they span. Sections the store has never seen cost nothing. Sections that are entirely air or
 * entirely enclosed rock produce no faces and cost one pass. And because the unit is a section, a
 * mesh can be <b>cached and reused</b> — moving the camera re-derives nothing, which is the other
 * half of why the dense path is forced coarse.
 *
 * <p>Pure: no Minecraft or Voxy types, so the extraction can be tested directly.
 */
public final class LodSectionMesh {
    private LodSectionMesh() {}

    /** 4 vertices per quad, 3 floats per position. Colour is per quad, normals are per quad. */
    public record Mesh(float[] positions, float[] normals, int[] colors, int quads) {
        public boolean isEmpty() { return quads == 0; }
    }

    public static final Mesh EMPTY = new Mesh(new float[0], new float[0], new int[0], 0);

    /**
     * The store has never seen this section — as opposed to {@link #EMPTY}, which means it was seen
     * and holds nothing worth drawing.
     *
     * <p>A separate instance, compared by identity. Both draw the same nothing, so folding them
     * together costs no pixels and hides the only question worth asking when terrain looks
     * incomplete: whether the renderer failed to draw the data, or the data was never captured.
     * Those have completely different fixes and looked identical from outside.
     */
    public static final Mesh MISSING = new Mesh(new float[0], new float[0], new int[0], 0);

    private static final int E = LodNative.EDGE;

    // (dx, dy, dz) per face, then the four corner offsets of the quad on that face, wound so the
    // front side faces outward along the normal.
    private static final int[][] DIRS = {
        { 0,  1,  0}, { 0, -1,  0}, { 1,  0,  0}, {-1,  0,  0}, { 0,  0,  1}, { 0,  0, -1},
    };
    private static final int[][][] CORNERS = {
        {{0,1,0},{0,1,1},{1,1,1},{1,1,0}},   // +Y
        {{0,0,0},{1,0,0},{1,0,1},{0,0,1}},   // -Y
        {{1,0,0},{1,1,0},{1,1,1},{1,0,1}},   // +X
        {{0,0,0},{0,0,1},{0,1,1},{0,1,0}},   // -X
        {{0,0,1},{1,0,1},{1,1,1},{0,1,1}},   // +Z
        {{0,0,0},{0,1,0},{1,1,0},{1,0,0}},   // -Z
    };

    /**
     * @param ids     {@link LodNative#CELLS} block ids, indexed {@code (y*E + z)*E + x}
     * @param biomes  {@link LodNative#BIOME_CELLS} biome ids on the 4-cell grid
     * @param cellSize blocks per cell at this level
     * @param ox,oy,oz world position of the section's minimum corner, in blocks
     * @param neighbours out-of-section neighbour lookup, or null to treat outside as air. Passing
     *        null seals every section in its own shell, which shows as a grid of boxes rather than
     *        continuous terrain — correct per section, wrong as a whole.
     */
    public static Mesh build(int[] ids, int[] biomes, MapColorSource colors, int cellSize,
                             float ox, float oy, float oz, Neighbours neighbours) {
        // Two passes: count, then fill. One pass into growable lists allocated ~1500 quads of
        // boxing per section, and this runs for every section in view.
        int quads = 0;
        for (int y = 0; y < E; y++) {
            for (int z = 0; z < E; z++) {
                for (int x = 0; x < E; x++) {
                    if (!opaque(ids, colors, biomes, x, y, z)) continue;
                    for (int[] d : DIRS) {
                        if (isOpen(ids, colors, biomes, x + d[0], y + d[1], z + d[2], neighbours)) quads++;
                    }
                }
            }
        }
        if (quads == 0) return EMPTY;

        float[] pos = new float[quads * 4 * 3];
        float[] nrm = new float[quads * 3];
        int[] col = new int[quads];
        int q = 0;
        for (int y = 0; y < E; y++) {
            for (int z = 0; z < E; z++) {
                for (int x = 0; x < E; x++) {
                    long id = mapping(ids, biomes, x, y, z);
                    if (id == 0 || !colors.isOpaque(id)) continue;
                    for (int f = 0; f < DIRS.length; f++) {
                        int[] d = DIRS[f];
                        if (!isOpen(ids, colors, biomes, x + d[0], y + d[1], z + d[2], neighbours)) continue;
                        // TOP for the upward face, SIDE otherwise — the same distinction the map
                        // makes, so a grass top does not colour its walls green.
                        col[q] = colors.baseColor(id, f == 0 ? Face.TOP : Face.SIDE);
                        nrm[q * 3] = d[0];
                        nrm[q * 3 + 1] = d[1];
                        nrm[q * 3 + 2] = d[2];
                        int[][] cs = CORNERS[f];
                        for (int v = 0; v < 4; v++) {
                            int base = (q * 4 + v) * 3;
                            pos[base] = ox + (x + cs[v][0]) * cellSize;
                            pos[base + 1] = oy + (y + cs[v][1]) * cellSize;
                            pos[base + 2] = oz + (z + cs[v][2]) * cellSize;
                        }
                        q++;
                    }
                }
            }
        }
        return new Mesh(pos, nrm, col, q);
    }

    /** Resolves cells outside the section being meshed, so neighbouring sections share a surface. */
    public interface Neighbours {
        /** @return the mapping id at a section-local coordinate that is outside [0,EDGE), or 0 for air. */
        long at(int x, int y, int z);
    }

    private static long mapping(int[] ids, int[] biomes, int x, int y, int z) {
        int block = ids[(y * E + z) * E + x];
        if (block == LodNative.AIR) return 0;
        int biome = biomes[((y / 4) * LodNative.BIOME_EDGE + (z / 4)) * LodNative.BIOME_EDGE + (x / 4)];
        return LodColorSource.mappingId(block, biome);
    }

    private static boolean opaque(int[] ids, MapColorSource colors, int[] biomes, int x, int y, int z) {
        long id = mapping(ids, biomes, x, y, z);
        return id != 0 && colors.isOpaque(id);
    }

    /** Whether a face should be drawn toward this cell: true when it is air or a non-opaque block. */
    private static boolean isOpen(int[] ids, MapColorSource colors, int[] biomes,
                                  int x, int y, int z, Neighbours neighbours) {
        if (x < 0 || y < 0 || z < 0 || x >= E || y >= E || z >= E) {
            if (neighbours == null) return true;
            long id = neighbours.at(x, y, z);
            return id == 0 || !colors.isOpaque(id);
        }
        return !opaque(ids, colors, biomes, x, y, z);
    }
}
