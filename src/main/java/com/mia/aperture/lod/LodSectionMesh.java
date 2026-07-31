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
        // Greedy meshing. The obvious version emits one quad per visible cell face, which on Abyss
        // rock — great flat walls of one material — is enormously wasteful: a 16x16 wall costs 256
        // quads to say what one quad says. Merging coplanar neighbouring faces of the same colour
        // into the largest rectangles that will fit costs a couple of passes over a 16x16 mask and
        // pays for itself many times over, because every quad saved is four vertices not submitted
        // every frame for as long as the section is in view.
        int[] mask = new int[E * E];
        boolean[] used = new boolean[E * E];
        Out out = new Out();

        for (int f = 0; f < DIRS.length; f++) {
            int[] d = DIRS[f];
            int n = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;      // the axis this face looks along
            int u = (n + 1) % 3, v = (n + 2) % 3;           // the two the quad spans
            for (int slice = 0; slice < E; slice++) {
                java.util.Arrays.fill(mask, 0);
                java.util.Arrays.fill(used, false);
                boolean any = false;
                int[] c = new int[3];
                for (int i = 0; i < E; i++) {
                    for (int j = 0; j < E; j++) {
                        c[n] = slice; c[u] = i; c[v] = j;
                        long id = mapping(ids, biomes, c[0], c[1], c[2]);
                        if (id == 0 || !colors.isOpaque(id)) continue;
                        if (!isOpen(ids, colors, biomes, c[0] + d[0], c[1] + d[1], c[2] + d[2],
                                neighbours)) {
                            continue;
                        }
                        // TOP for the upward face, SIDE otherwise — the same distinction the map
                        // makes, so a grass top does not colour its walls green.
                        int col = colors.baseColor(id, f == 0 ? Face.TOP : Face.SIDE);
                        // 0 marks "no face". A drawn colour is opaque, so it can never be 0 and the
                        // sentinel cannot collide with real terrain.
                        mask[i * E + j] = col == 0 ? 1 : col;
                        any = true;
                    }
                }
                if (!any) continue;

                for (int i = 0; i < E; i++) {
                    for (int j = 0; j < E; j++) {
                        int col = mask[i * E + j];
                        if (col == 0 || used[i * E + j]) continue;
                        // Widen along v, then grow along u while the whole row still matches.
                        int w = 1;
                        while (j + w < E && mask[i * E + j + w] == col && !used[i * E + j + w]) w++;
                        int h = 1;
                        grow:
                        while (i + h < E) {
                            for (int k = 0; k < w; k++) {
                                int idx = (i + h) * E + j + k;
                                if (mask[idx] != col || used[idx]) break grow;
                            }
                            h++;
                        }
                        for (int a = 0; a < h; a++) {
                            for (int b = 0; b < w; b++) used[(i + a) * E + j + b] = true;
                        }
                        emit(out, f, n, u, v, slice, i, j, h, w, col, cellSize, ox, oy, oz);
                    }
                }
            }
        }
        return out.toMesh();
    }

    /**
     * Emit one merged quad, spanning {@code du} cells along axis {@code u} and {@code dv} along
     * {@code v}.
     *
     * <p>The corner pattern comes from {@link #CORNERS} unchanged, with the unit offsets scaled to
     * the merged size. Scaling rather than rewriting the winding per face is deliberate: the
     * existing patterns are already wound outward along each normal, and a quad wound the wrong way
     * is invisible from the side you are looking at — a failure that looks like missing terrain.
     */
    private static void emit(Out out, int f, int n, int u, int v, int slice, int i, int j,
                             int du, int dv, int col, int cellSize,
                             float ox, float oy, float oz) {
        int[] d = DIRS[f];
        int[][] cs = CORNERS[f];
        float[] origin = {ox, oy, oz};
        for (int c = 0; c < 4; c++) {
            int[] off = cs[c];
            for (int axis = 0; axis < 3; axis++) {
                int base = axis == n ? slice : axis == u ? i : j;
                int extent = axis == n ? 1 : axis == u ? du : dv;
                out.pos(origin[axis] + (base + (off[axis] == 0 ? 0 : extent)) * cellSize);
            }
        }
        out.nrm(d[0], d[1], d[2]);
        out.col(col);
    }

    /** Growable output. Quad counts are not known ahead of time once faces can merge. */
    private static final class Out {
        private float[] pos = new float[4096];
        private float[] nrm = new float[512];
        private int[] col = new int[256];
        private int pi, ni, ci;

        void pos(float value) {
            if (pi == pos.length) pos = java.util.Arrays.copyOf(pos, pi * 2);
            pos[pi++] = value;
        }

        void nrm(int x, int y, int z) {
            if (ni + 3 > nrm.length) nrm = java.util.Arrays.copyOf(nrm, nrm.length * 2);
            nrm[ni++] = x; nrm[ni++] = y; nrm[ni++] = z;
        }

        void col(int c) {
            if (ci == col.length) col = java.util.Arrays.copyOf(col, ci * 2);
            col[ci++] = c;
        }

        Mesh toMesh() {
            if (ci == 0) return EMPTY;
            return new Mesh(java.util.Arrays.copyOf(pos, pi), java.util.Arrays.copyOf(nrm, ni),
                    java.util.Arrays.copyOf(col, ci), ci);
        }
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
