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
                                        long[] scratch, long[][] synth) {
        long[] direct = acquireCoarser(engine, lvl, sx, secY, sz, scratch);
        if (direct != null) return direct;
        return synthesizeFromFiner(engine, lvl, sx, secY, sz, scratch, synth, 0);
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
                                              long[] scratch, long[][] synth, int depth) {
        if (lvl <= 0 || depth >= MAX_FINER_DEPTH) return null;
        long[] out = null;
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = (sx << 1) + dx, cy = (secY << 1) + dy, cz = (sz << 1) + dz;
                    long[] child = acquireExact(engine, lvl - 1, cx, cy, cz, scratch);
                    if (child == null) {
                        child = synthesizeFromFiner(engine, lvl - 1, cx, cy, cz, scratch, synth, depth + 1);
                    }
                    if (child == null) continue;
                    // out lives at `depth`, a recursed child at `depth + 1` -> never the same array.
                    if (out == null) out = synthBuf(synth, depth);
                    LodUpsampler.mipInto(out, child, dx, dy, dz);
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
    private static boolean[] scOpaque, scOutside;
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
    public static List<Point> sample(WorldEngine engine, MapColorSource colors,
                                     int focusX, int focusY, int focusZ, int extentXZ, int extentUp, int extentDown,
                                     int lvl, int maxPoints) {
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
        if (pts.size() > maxPoints) {
            int stride = (pts.size() + maxPoints - 1) / maxPoints;
            List<Point> trimmed = new ArrayList<>(maxPoints);
            for (int i = 0; i < pts.size(); i += stride) trimmed.add(pts.get(i));
            return trimmed;
        }
        return pts;
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
            for (int secZ = secZ0; secZ <= secZ1; secZ++) {
                for (int secX = secX0; secX <= secX1; secX++) {
                    long[] data = acquireFinest(engine, lvl, secX, secY, secZ, scratch, synth);
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
}
