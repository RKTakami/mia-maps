package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayList;
import java.util.List;

public final class VoxelCloud {
    private static final int MAX_FALLBACK_K = 4;
    // How many levels FINER we'll synthesize a coarse section from when Voxy lacks the aggregate.
    // Voxy hard-codes MAX_LOD_LAYER = 4 and never stores anything coarser, so the only synthesis
    // we ever need is the widest view (level 5) from level 4 — a single step, 8 child reads.
    // Deeper was only for the 8192/16384 settings, which are gone: those needed 64-512 reads per
    // section and still came back mostly empty, since no amount of traversal creates level 5+.
    private static final int MAX_FINER_DEPTH = 1;

    private VoxelCloud() {}

    // A 32^3 section at the requested display level. Prefers this level or coarser (upsampled);
    // if neither exists, synthesizes it by downsampling finer levels — Voxy often lacks coarse
    // aggregates, which would otherwise leave a wide (coarse-LOD) 3D view empty.
    // The k==0 result aliases `scratch`, so the caller must consume it before the next call.
    private static long[] acquireFinest(WorldEngine engine, int lvl, int sx, int secY, int sz,
                                        long[] scratch, long[][] synth,
                                        java.util.function.LongPredicate renderable) {
        long[] direct = acquireCoarser(engine, lvl, sx, secY, sz, scratch);
        if (direct != null) return direct;
        return synthesizeFromFiner(engine, lvl, sx, secY, sz, scratch, synth, 0, renderable);
    }

    // This level, then progressively coarser Voxy levels (upsampled).
    // NOTE: the k == 0 result ALIASES `scratch` (unlike MapWorker, which clones) — callers must
    // consume it before the next acquire.
    private static long[] acquireCoarser(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch) {
        for (int k = 0; k <= MAX_FALLBACK_K; k++) {
            WorldSection cs = engine.acquireIfExists(lvl + k, sx >> k, secY >> k, sz >> k);
            if (cs == null) continue;
            try {
                cs.copyDataTo(scratch);
                return k == 0 ? scratch : LodUpsampler.upsampleOctant(scratch, sx, secY, sz, k);
            } finally {
                cs.release();
            }
        }
        return null;
    }

    // Exactly this level, no coarser fallback. Used for synthesis children: the parent already
    // proved every coarser level empty for this region, so re-querying them is pure waste.
    // ALIASES `scratch` — consume before the next acquire.
    private static long[] acquireExact(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch) {
        WorldSection cs = engine.acquireIfExists(lvl, sx, secY, sz);
        if (cs == null) return null;
        try {
            cs.copyDataTo(scratch);
            return scratch;
        } finally {
            cs.release();
        }
    }

    // Build this coarse section from the 8 child sections one level finer (recursive, bounded).
    // Each child is mip'd into its octant IMMEDIATELY, before the next acquire can clobber
    // `scratch` (acquireExact returns an alias of it).
    // Writes into the caller-owned per-depth buffer rather than allocating: at wide areas EVERY
    // section falls through to synthesis, and a fresh 262 KB array per section per level was
    // ~450 MB of garbage per resample — a GC storm that hung the client.
    private static long[] synthesizeFromFiner(WorldEngine engine, int lvl, int sx, int secY, int sz,
                                              long[] scratch, long[][] synth, int depth,
                                              java.util.function.LongPredicate renderable) {
        if (lvl <= 0 || depth >= MAX_FINER_DEPTH) return null;
        long[] out = null;
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = (sx << 1) + dx, cy = (secY << 1) + dy, cz = (sz << 1) + dz;
                    long[] child = acquireExact(engine, lvl - 1, cx, cy, cz, scratch);
                    if (child == null) {
                        child = synthesizeFromFiner(engine, lvl - 1, cx, cy, cz, scratch, synth, depth + 1, renderable);
                    }
                    if (child == null) continue;
                    // out lives at `depth`, a recursed child at `depth + 1` -> never the same array.
                    if (out == null) out = synthBuf(synth, depth);
                    LodUpsampler.mipInto(out, child, dx, dy, dz, renderable);
                }
            }
        }
        return out;
    }

    // Per-depth reusable synthesis buffer, cleared on reuse (octants with no child must not keep
    // the previous section's terrain). Caller-owned so concurrent fill() callers stay independent.
    private static long[] synthBuf(long[][] synth, int depth) {
        if (synth[depth] == null) {
            synth[depth] = new long[32 * 32 * 32]; // fresh arrays are already zeroed
        } else {
            java.util.Arrays.fill(synth[depth], 0L);
        }
        return synth[depth];
    }

    // A cloud point in world coords, ARGB colour, cell size (blocks), an outward surface normal
    // (nx,ny,nz) for shading, and a 6-bit exposed-face mask (bit order +X,-X,+Y,-Y,+Z,-Z).
    public record Point(double x, double y, double z, int argb, int cellSize,
                        float nx, float ny, float nz, int faces, boolean covered) {}

    // The raw occupancy/colour grid behind sample(): opaque[i]/argb[i] over gX*gY*gZ,
    // index (y*gZ+z)*gX+x, cell = 1<<lvl, origin in cells. Consumed by OrbitMesher.
    public record Grid(boolean[] opaque, int[] argb, int gX, int gY, int gZ,
                       int cell, int originCellX, int originCellY, int originCellZ) {}

    /**
     * Reduce an occupancy grid to the open space reachable from the focus, inside a vertical slab
     * around it. Everything else — rock, sealed pockets, anything outside the slab — becomes solid.
     *
     * <p>This is the 3D half of {@link MapMode#CAVES}, and it is what keeps that mode from being
     * the see-through-terrain view removed in {@code dfeb3e5}. The rule is reachability, not
     * transparency: a cave shows only if air connects it to where you are standing, so a sealed
     * chamber on the far side of a wall stays invisible no matter how close it is. Solid rock has
     * no reachable air to bound it, produces no surface, and renders as nothing — which is what
     * makes the passage you are in legible instead of buried inside a solid block of stone.
     *
     * <p>Above ground every cell of open sky is reachable, so nothing is carved and the view is the
     * ordinary one. The mode only bites underground.
     *
     * <p>Colours need no fixing afterwards: a cell that was air and is now solid can never end up
     * on the surface, because a solid cell only becomes a surface by touching reachable air, and
     * air touching reachable air would itself have been reached. Every surface cell was therefore
     * opaque to begin with and already carries its real colour.
     *
     * @param stack scratch of at least gX*gY*gZ ints
     * @param reach scratch of at least gX*gY*gZ booleans; need not be cleared
     * @return false if no open air was found near the focus — nothing was carved
     */
    public static boolean carveToReachable(boolean[] opaque, int gX, int gY, int gZ,
                                           int fx, int fy, int fz,
                                           int slabDown, int slabUp, int[] stack, boolean[] reach) {
        int planeYZ = gX * gZ;
        int n = planeYZ * gY;
        int loY = Math.max(0, fy - slabDown);
        int hiY = Math.min(gY - 1, fy + slabUp);

        int seed = findAirSeed(opaque, gX, gZ, fx, fy, fz, loY, hiY);
        if (seed < 0) return false;

        java.util.Arrays.fill(reach, 0, n, false);
        int sp = 0;
        reach[seed] = true;
        stack[sp++] = seed;
        while (sp > 0) {
            int i = stack[--sp];
            int y = i / planeYZ, r = i - y * planeYZ, z = r / gX, x = r - z * gX;
            if (x + 1 < gX)  sp = push(opaque, reach, stack, sp, i + 1);
            if (x - 1 >= 0)  sp = push(opaque, reach, stack, sp, i - 1);
            if (z + 1 < gZ)  sp = push(opaque, reach, stack, sp, i + gX);
            if (z - 1 >= 0)  sp = push(opaque, reach, stack, sp, i - gX);
            // The slab is a hard wall, so the flood cannot escape upward into open sky and pull the
            // whole surface back in, nor run away down a shaft into the next cavern system.
            if (y + 1 <= hiY) sp = push(opaque, reach, stack, sp, i + planeYZ);
            if (y - 1 >= loY) sp = push(opaque, reach, stack, sp, i - planeYZ);
        }

        for (int i = 0; i < n; i++) {
            if (!reach[i]) opaque[i] = true;
        }
        return true;
    }

    /**
     * Dim the grid's colours by how far each cell sits below the focus, using the same ramp as the
     * 2D slice — nearest your own level brightest, further below dimming and cooling progressively.
     *
     * <p>Applied to the colour grid rather than at draw time so BOTH 3D renderers pick it up
     * unchanged: the cube path reads argb per point, the mesher reads it per cell. Depth is measured
     * from the focus, not from the camera, so orbiting does not change what a passage looks like.
     */
    public static void shadeByDepth(int[] argb, boolean[] opaque, int gX, int gY, int gZ,
                                    int originCellY, int focusBlockY, int cell) {
        int planeYZ = gX * gZ;
        for (int y = 0; y < gY; y++) {
            int depth = focusBlockY - (originCellY + y) * cell;
            int base = y * planeYZ;
            for (int i = base; i < base + planeYZ; i++) {
                if (opaque[i]) argb[i] = CaveShading.shade(argb[i], depth);
            }
        }
    }

    private static int push(boolean[] opaque, boolean[] reach, int[] stack, int sp, int j) {
        if (!opaque[j] && !reach[j]) {
            reach[j] = true;
            stack[sp++] = j;
        }
        return sp;
    }

    /**
     * Nearest open cell to the focus, searched outward in cube shells. The focus itself is often
     * solid at coarse LOD — a 3-block passage vanishes into an 8-block cell — so seeding on it
     * alone would give up exactly where the view is already hardest.
     */
    private static int findAirSeed(boolean[] opaque, int gX, int gZ,
                                   int fx, int fy, int fz, int loY, int hiY) {
        for (int r = 0; r <= SEED_SEARCH_CELLS; r++) {
            for (int dy = -r; dy <= r; dy++) {
                int y = fy + dy;
                if (y < loY || y > hiY) continue;
                for (int dz = -r; dz <= r; dz++) {
                    int z = fz + dz;
                    if (z < 0 || z >= gZ) continue;
                    for (int dx = -r; dx <= r; dx++) {
                        if (r > 0 && Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) {
                            continue;   // shell only; the interior was covered by a smaller r
                        }
                        int x = fx + dx;
                        if (x < 0 || x >= gX) continue;
                        int i = (y * gZ + z) * gX + x;
                        if (!opaque[i]) return i;
                    }
                }
            }
        }
        return -1;
    }

    private static final int SEED_SEARCH_CELLS = 8;

    // Bitmask of which of a cell's 6 faces are exposed (neighbour is air OR out of bounds).
    // Bit order matches the +X,-X,+Y,-Y,+Z,-Z convention used by the cube renderer.
    public static int faceMask(boolean[] opaque, int gx, int gy, int gz, int x, int y, int z) {
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int mask = 0;
        for (int i = 0; i < 6; i++) {
            int nx = x + n[i][0], ny = y + n[i][1], nz = z + n[i][2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= gx || ny >= gy || nz >= gz
                    || !opaque[gi(gx, gz, nx, ny, nz)]) {
                mask |= (1 << i);
            }
        }
        return mask;
    }

    // Index into a gx*gy*gz opaque grid (y-major, then z, then x).
    private static int gi(int gx, int gz, int x, int y, int z) { return (y * gz + z) * gx + x; }

    // Air cells reachable from the sample-box boundary through air neighbours are "outside" (open
    // sky / the Abyss void). A surface voxel is INTERIOR (a cave wall) only when its exposed air is
    // enclosed — not connected to the outside. This distinguishes real caves from the outer cliff
    // faces of the Abyss, where a simple "solid above" test fails (rim rock sits above everything).
    // Pure; allocating wrapper for tests. Index layout: (y*gZ+z)*gX+x.
    public static boolean[] outsideAir(boolean[] opaque, int gX, int gY, int gZ) {
        boolean[] outside = new boolean[gX * gY * gZ];
        floodOutside(opaque, outside, new int[gX * gY * gZ], gX, gY, gZ);
        return outside;
    }

    // Flood-fill `outside` (must be pre-cleared) from every boundary air cell through air
    // neighbours, using `stack` (length >= gX*gY*gZ) as scratch. No allocation.
    static void floodOutside(boolean[] opaque, boolean[] outside, int[] stack, int gX, int gY, int gZ) {
        int sp = 0;
        for (int y = 0; y < gY; y++) {
            for (int z = 0; z < gZ; z++) {
                for (int x = 0; x < gX; x++) {
                    if (x != 0 && y != 0 && z != 0 && x != gX - 1 && y != gY - 1 && z != gZ - 1) continue;
                    int i = (y * gZ + z) * gX + x;
                    if (!opaque[i] && !outside[i]) { outside[i] = true; stack[sp++] = i; }
                }
            }
        }
        int planeYZ = gX * gZ;
        while (sp > 0) {
            int i = stack[--sp];
            int x = i % gX, r = i / gX, z = r % gZ, y = r / gZ;
            if (x + 1 < gX) { int j = i + 1; if (!opaque[j] && !outside[j]) { outside[j] = true; stack[sp++] = j; } }
            if (x - 1 >= 0) { int j = i - 1; if (!opaque[j] && !outside[j]) { outside[j] = true; stack[sp++] = j; } }
            if (z + 1 < gZ) { int j = i + gX; if (!opaque[j] && !outside[j]) { outside[j] = true; stack[sp++] = j; } }
            if (z - 1 >= 0) { int j = i - gX; if (!opaque[j] && !outside[j]) { outside[j] = true; stack[sp++] = j; } }
            if (y + 1 < gY) { int j = i + planeYZ; if (!opaque[j] && !outside[j]) { outside[j] = true; stack[sp++] = j; } }
            if (y - 1 >= 0) { int j = i - planeYZ; if (!opaque[j] && !outside[j]) { outside[j] = true; stack[sp++] = j; } }
        }
    }

    // A surface voxel (opaque, with an air neighbour) is INTERIOR when all its exposed air is
    // enclosed (not outside-connected) and it does not touch the box edge. Pure.
    public static boolean isInteriorSurface(boolean[] opaque, boolean[] outside,
            int gX, int gY, int gZ, int x, int y, int z) {
        boolean touchedAir = false;
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) {
            int ax = x + d[0], ay = y + d[1], az = z + d[2];
            if (ax < 0 || ay < 0 || az < 0 || ax >= gX || ay >= gY || az >= gZ) return false; // box edge = exposed
            int j = (ay * gZ + az) * gX + ax;
            if (!opaque[j]) { touchedAir = true; if (outside[j]) return false; } // open air = shell
        }
        return touchedAir;
    }

    // Reusable per-sample scratch, avoiding the ~30 MB of fresh arrays that constant resampling
    // would otherwise churn through GC (that churn was the STW-GC stall behind the old 3D freeze).
    // INVARIANT: sample() must be called from a SINGLE thread only — today the MIA-Orbit-Raster
    // worker. These buffers are NOT thread-safe; if another caller is ever added, give it its own
    // buffers (or make sample() synchronized) rather than sharing these.
    /** Whether the last sample() had to drop surfaces to fit the point budget. */
    public static volatile boolean lastDecimated;

    private static boolean[] scOpaque, scOutside, scReach;
    private static int[] scArgb, scStack;

    private static boolean[] ensureBool(boolean[] a, int n) { return (a == null || a.length < n) ? new boolean[n] : a; }
    private static int[] ensureInt(int[] a, int n) { return (a == null || a.length < n) ? new int[n] : a; }

    // Pure: is cell (x,y,z) a surface cell in a gx*gy*gz opaque grid? True if the cell is
    // opaque and any 6-neighbour is air OR out of bounds (grid edges count as exposed).
    public static boolean isSurface(boolean[] opaque, int gx, int gy, int gz, int x, int y, int z) {
        if (!opaque[gi(gx, gz, x, y, z)]) return false;
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= gx || ny >= gy || nz >= gz) return true;
            if (!opaque[gi(gx, gz, nx, ny, nz)]) return true;
        }
        return false;
    }

    // Pure: outward surface normal (points toward air) for a surface cell, from which of
    // its 6 in-bounds neighbours are air. Out-of-bounds neighbours are treated as solid
    // (so sampling-box faces don't get a false lit skin); a cell with no in-bounds air
    // neighbour defaults to pointing up.
    public static float[] surfaceNormal(boolean[] opaque, int gx, int gy, int gz, int x, int y, int z) {
        float nx = 0, ny = 0, nz = 0;
        int[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : n) {
            int ax = x + d[0], ay = y + d[1], az = z + d[2];
            if (ax < 0 || ay < 0 || az < 0 || ax >= gx || ay >= gy || az >= gz) continue;
            if (!opaque[gi(gx, gz, ax, ay, az)]) { nx += d[0]; ny += d[1]; nz += d[2]; }
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-4f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    // Sample a box of extentXZ blocks horizontally, and extentUp above / extentDown below
    // the focus vertically (bias toward the descent), at the given Voxy level. Taller box =
    // more of the Abyss's vertical face. Returns surface voxels, bounded by maxPoints.
    // caveSlabBlocks > 0 carves the grid to the open space reachable from the focus within that
    // many blocks below (and half that above) — see carveToReachable. 0 leaves the grid untouched.
    public static List<Point> sample(WorldEngine engine, MapColorSource colors,
                                     int focusX, int focusY, int focusZ, int extentXZ, int extentUp, int extentDown,
                                     int lvl, int maxPoints, int caveSlabBlocks) {
        int cell = 1 << lvl;
        int gX = Math.max(1, extentXZ / cell);
        int gYup = Math.max(0, extentUp / cell);
        int gYdown = Math.max(0, extentDown / cell);
        int gY = Math.max(1, gYup + gYdown);
        int gZ = gX;
        int originCellX = Math.floorDiv(focusX, cell) - gX / 2;
        int originCellY = Math.floorDiv(focusY, cell) - gYdown;
        int originCellZ = Math.floorDiv(focusZ, cell) - gZ / 2;

        int n = gX * gY * gZ;
        boolean[] opaque = scOpaque = ensureBool(scOpaque, n);
        java.util.Arrays.fill(opaque, 0, n, false);
        int[] argb = scArgb = ensureInt(scArgb, n);        // only read where opaque, no clear needed
        fill(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl, opaque, argb);
        boolean[] outside = scOutside = ensureBool(scOutside, n);
        java.util.Arrays.fill(outside, 0, n, false);
        scStack = ensureInt(scStack, n);
        if (caveSlabBlocks > 0) {
            scReach = ensureBool(scReach, n);
            carveToReachable(opaque, gX, gY, gZ, gX / 2, gYdown, gZ / 2,
                    Math.max(1, caveSlabBlocks / cell), Math.max(1, caveSlabBlocks / cell),
                    scStack, scReach);
            shadeByDepth(argb, opaque, gX, gY, gZ, originCellY, focusY, cell);
        }
        floodOutside(opaque, outside, scStack, gX, gY, gZ);

        List<Point> pts = new ArrayList<>();
        for (int y = 0; y < gY; y++) {
            for (int z = 0; z < gZ; z++) {
                for (int x = 0; x < gX; x++) {
                    if (!isSurface(opaque, gX, gY, gZ, x, y, z)) continue;
                    int idx = (y * gZ + z) * gX + x;
                    float[] nrm = surfaceNormal(opaque, gX, gY, gZ, x, y, z);
                    int faces = faceMask(opaque, gX, gY, gZ, x, y, z);
                    pts.add(new Point(
                            (originCellX + x + 0.5) * cell,
                            (originCellY + y + 0.5) * cell,
                            (originCellZ + z + 0.5) * cell,
                            argb[idx], cell, nrm[0], nrm[1], nrm[2], faces,
                            isInteriorSurface(opaque, outside, gX, gY, gZ, x, y, z)));
                }
            }
        }
        lastDecimated = pts.size() > maxPoints;
        if (pts.size() > maxPoints) {
            // NOTE this keeps every Nth surface voxel, which punches holes in surfaces that were
            // closed. It is a budget backstop, not a level-of-detail strategy: a coarser level has
            // fewer, larger cells and stays watertight. Reported so the difference is visible.
            int stride = (pts.size() + maxPoints - 1) / maxPoints;
            List<Point> trimmed = new ArrayList<>(maxPoints);
            for (int i = 0; i < pts.size(); i += stride) trimmed.add(pts.get(i));
            return trimmed;
        }
        return pts;
    }

    // The occupancy+colour grid behind sample(), returned directly for the mesher instead of a
    // decimated point list. Per-call buffers (not the sample() scratch) so the orbit worker can
    // build a mesh while sample()'s single-thread invariant is preserved.
    public static Grid sampleGrid(WorldEngine engine, MapColorSource colors,
            int focusX, int focusY, int focusZ, int extentXZ, int extentUp, int extentDown, int lvl,
            int caveSlabBlocks) {
        int cell = 1 << lvl;
        int gX = Math.max(1, extentXZ / cell);
        int gYup = Math.max(0, extentUp / cell);
        int gYdown = Math.max(0, extentDown / cell);
        int gY = Math.max(1, gYup + gYdown);
        int gZ = gX;
        int originCellX = Math.floorDiv(focusX, cell) - gX / 2;
        int originCellY = Math.floorDiv(focusY, cell) - gYdown;
        int originCellZ = Math.floorDiv(focusZ, cell) - gZ / 2;
        int n = gX * gY * gZ;
        // DIAG (3D lag hunt): split ALLOCATION from SAMPLING. The grid is allocated fresh on every
        // rebuild with no size cap, so a wide view churns hundreds of MB through the GC; the fill
        // loop separately touches every cell. These need different fixes, so measure them apart.
        boolean[] opaque = new boolean[n];
        int[] argb = new int[n];
        fillIntoParallel(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl, opaque, argb);
        if (caveSlabBlocks > 0) {
            carveToReachable(opaque, gX, gY, gZ, gX / 2, gYdown, gZ / 2,
                    Math.max(1, caveSlabBlocks / cell), Math.max(1, caveSlabBlocks / cell),
                    new int[n], new boolean[n]);
            shadeByDepth(argb, opaque, gX, gY, gZ, originCellY, focusY, cell);
        }
        return new Grid(opaque, argb, gX, gY, gZ, cell, originCellX, originCellY, originCellZ);
    }

    // Fill an opaque grid (gX x gY x gZ cells, origin in CELLS) from Voxy at the given level.
    // Shared with RouteService. For routing (no colours), call fillOpaque().
    public static boolean[] fillOpaque(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl) {
        boolean[] opaque = new boolean[gX * gY * gZ];
        fill(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl, opaque, null);
        return opaque;
    }

    private static void fill(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb) {
        fillInto(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl,
                opaque, argb, new long[32 * 32 * 32], new long[MAX_FINER_DEPTH][]);
    }

    // As fill(), but with caller-owned scratch/synthesis buffers. The Abyss model builder probes
    // ~17k sections in a burst; a fresh 256 KB scratch per probe would be gigabytes of garbage.
    // Per-call buffers stay the rule for one-shot callers (route/corridor grids).
    public static void fillInto(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb, long[] scratch, long[][] synth) {
        int secX0 = Math.floorDiv(originCellX, 32), secX1 = Math.floorDiv(originCellX + gX - 1, 32);
        int secY0 = Math.floorDiv(originCellY, 32), secY1 = Math.floorDiv(originCellY + gY - 1, 32);
        int secZ0 = Math.floorDiv(originCellZ, 32), secZ1 = Math.floorDiv(originCellZ + gZ - 1, 32);

        for (int secY = secY0; secY <= secY1; secY++) {
            fillYSlice(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl,
                    opaque, argb, scratch, synth, secY, secZ0, secZ1, secX0, secX1);
        }
    }

    // Per-thread scratch for the parallel fill. acquireFinest writes into these, so they cannot be
    // shared across threads; a fresh 256 KB pair per section would be gigabytes of garbage.
    private static final ThreadLocal<long[]> PAR_SCRATCH =
            ThreadLocal.withInitial(() -> new long[32 * 32 * 32]);
    private static final ThreadLocal<long[][]> PAR_SYNTH =
            ThreadLocal.withInitial(() -> new long[MAX_FINER_DEPTH][]);

    // Y-slices touch disjoint grid rows (gy is derived from secY), so they can be filled in parallel
    // with no coordination — the Java memory model guarantees no word tearing between array elements.
    // Sampling was single-threaded while every other core idled; this is where the headroom for finer
    // voxels comes from. Falls back to serial for small grids, where thread hand-off costs more than
    // it saves.
    static final int PARALLEL_MIN_CELLS = 500_000;

    public static void fillIntoParallel(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb) {
        int secX0 = Math.floorDiv(originCellX, 32), secX1 = Math.floorDiv(originCellX + gX - 1, 32);
        int secY0 = Math.floorDiv(originCellY, 32), secY1 = Math.floorDiv(originCellY + gY - 1, 32);
        int secZ0 = Math.floorDiv(originCellZ, 32), secZ1 = Math.floorDiv(originCellZ + gZ - 1, 32);

        if ((long) gX * gY * gZ < PARALLEL_MIN_CELLS || secY1 <= secY0) {
            fillInto(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl,
                    opaque, argb, new long[32 * 32 * 32], new long[MAX_FINER_DEPTH][]);
            return;
        }
        java.util.stream.IntStream.rangeClosed(secY0, secY1).parallel().forEach(secY ->
                fillYSlice(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl,
                        opaque, argb, PAR_SCRATCH.get(), PAR_SYNTH.get(),
                        secY, secZ0, secZ1, secX0, secX1));
    }

    private static void fillYSlice(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb, long[] scratch, long[][] synth,
            int secY, int secZ0, int secZ1, int secX0, int secX1) {
        for (int secZ = secZ0; secZ <= secZ1; secZ++) {
            for (int secX = secX0; secX <= secX1; secX++) {
                long[] data = acquireFinest(engine, lvl, secX, secY, secZ, scratch, synth,
                        colors::isOpaque);
                if (data == null) continue;
                int baseX = secX * 32, baseY = secY * 32, baseZ = secZ * 32;
                for (int ly = 0; ly < 32; ly++) {
                    int gy = baseY + ly - originCellY;
                    if (gy < 0 || gy >= gY) continue;
                    for (int lz = 0; lz < 32; lz++) {
                        int gz = baseZ + lz - originCellZ;
                        if (gz < 0 || gz >= gZ) continue;
                        for (int lx = 0; lx < 32; lx++) {
                            int gx = baseX + lx - originCellX;
                            if (gx < 0 || gx >= gX) continue;
                            // A sector (one Abyss layer) spans shifted X [-8192, 8192); cells
                            // beyond it belong to OTHER layers and must stay empty rather than
                            // alias their terrain into this view. Matters once the sampled box
                            // is wide enough to cross a sector edge. Mirrors MapCompositor.
                            int shiftedX = (baseX + lx) << lvl;
                            if (shiftedX < -8192 || shiftedX >= 8192) continue;
                            long id = data[(ly << 10) | (lz << 5) | lx];
                            if (id == 0 || !colors.isOpaque(id)) continue;
                            int idx = (gy * gZ + gz) * gX + gx;
                            opaque[idx] = true;
                            if (argb != null) argb[idx] = colors.baseColor(id, Face.TOP);
                        }
                    }
                }
            }
        }
    }
}
