package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayList;
import java.util.List;

public final class VoxelCloud {
    private static final int MAX_FALLBACK_K = 4;

    private VoxelCloud() {}

    // A 32^3 section at the requested display level, falling back to coarser Voxy levels
    // (upsampled) when fine data is missing — the same coverage strategy the map uses.
    // The k==0 result aliases `scratch`, so the caller must consume it before the next call.
    private static long[] acquireFinest(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch) {
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

    // A cloud point in world coords, ARGB colour, cell size (blocks, for point sizing),
    // and an outward surface normal (nx,ny,nz) for directional shading.
    public record Point(double x, double y, double z, int argb, int cellSize,
                        float nx, float ny, float nz) {}

    // Index into a gx*gy*gz opaque grid (y-major, then z, then x).
    private static int gi(int gx, int gz, int x, int y, int z) { return (y * gz + z) * gx + x; }

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

    // Sample a box of extentXZ blocks horizontally and extentY blocks vertically around
    // focus at the given Voxy level (taller box = more of the Abyss's vertical face),
    // returning surface voxels as world-space points. Bounded by maxPoints via stride.
    public static List<Point> sample(WorldEngine engine, MapColorSource colors,
                                     int focusX, int focusY, int focusZ, int extentXZ, int extentY, int lvl, int maxPoints) {
        int cell = 1 << lvl;
        int gX = Math.max(1, extentXZ / cell);
        int gY = Math.max(1, extentY / cell);
        int gZ = gX;
        int originCellX = Math.floorDiv(focusX, cell) - gX / 2;
        int originCellY = Math.floorDiv(focusY, cell) - gY / 2;
        int originCellZ = Math.floorDiv(focusZ, cell) - gZ / 2;

        boolean[] opaque = new boolean[gX * gY * gZ];
        int[] argb = new int[gX * gY * gZ];
        long[] scratch = new long[32 * 32 * 32];

        int secX0 = Math.floorDiv(originCellX, 32), secX1 = Math.floorDiv(originCellX + gX - 1, 32);
        int secY0 = Math.floorDiv(originCellY, 32), secY1 = Math.floorDiv(originCellY + gY - 1, 32);
        int secZ0 = Math.floorDiv(originCellZ, 32), secZ1 = Math.floorDiv(originCellZ + gZ - 1, 32);

        for (int secY = secY0; secY <= secY1; secY++) {
            for (int secZ = secZ0; secZ <= secZ1; secZ++) {
                for (int secX = secX0; secX <= secX1; secX++) {
                    long[] data = acquireFinest(engine, lvl, secX, secY, secZ, scratch);
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
                                long id = data[(ly << 10) | (lz << 5) | lx];
                                if (id == 0 || !colors.isOpaque(id)) continue;
                                int idx = (gy * gZ + gz) * gX + gx;
                                opaque[idx] = true;
                                argb[idx] = colors.baseColor(id, Face.TOP);
                            }
                        }
                    }
                }
            }
        }

        List<Point> pts = new ArrayList<>();
        for (int y = 0; y < gY; y++) {
            for (int z = 0; z < gZ; z++) {
                for (int x = 0; x < gX; x++) {
                    if (!isSurface(opaque, gX, gY, gZ, x, y, z)) continue;
                    int idx = (y * gZ + z) * gX + x;
                    float[] nrm = surfaceNormal(opaque, gX, gY, gZ, x, y, z);
                    pts.add(new Point(
                            (originCellX + x + 0.5) * cell,
                            (originCellY + y + 0.5) * cell,
                            (originCellZ + z + 0.5) * cell,
                            argb[idx], cell, nrm[0], nrm[1], nrm[2]));
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
}
