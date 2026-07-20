package com.mia.aperture.map;

import java.util.ArrayList;

// Pure Naive Surface Nets surfacer: a voxel occupancy grid -> a smooth triangle mesh. No Voxy or
// Minecraft imports, so it stays unit-testable. Smoothness comes from meshing a center-weighted
// DENSITY field (not the hard 0/1 occupancy) at iso 0.5, placing one vertex per surface cell at the
// centroid of its edge crossings, and shading by the density gradient. One vertex per cell + quads
// between the four cells around each crossing grid-edge yields a watertight, smooth manifold.
public final class OrbitMesher {
    // Flat, rasterizer-friendly output. positions/normals: 3 floats per vertex (world/shifted units).
    // colors: 1 ARGB per vertex. tris: index triples into the vertex arrays.
    public record Mesh(float[] positions, float[] normals, int[] colors, int[] tris) {}

    private static final float ISO = 0.5f;
    private static final int CENTER_W = 7;   // center weight in the density blur (keeps lone cells solid)

    // 8 cube corners as (dx,dy,dz), index c = dx | dy<<1 | dz<<2.
    private static final int[][] CORNER = {
        {0,0,0},{1,0,0},{0,1,0},{1,1,0},{0,0,1},{1,0,1},{0,1,1},{1,1,1}
    };
    // 12 cube edges as corner-index pairs (each pair differs in exactly one axis).
    private static final int[][] EDGE = {
        {0,1},{2,3},{4,5},{6,7},   // along x
        {0,2},{1,3},{4,6},{5,7},   // along y
        {0,4},{1,5},{2,6},{3,7}    // along z
    };

    private OrbitMesher() {}

    public static Mesh build(boolean[] opaque, int[] argb, int gX, int gY, int gZ,
                             int cellSize, int originX, int originY, int originZ) {
        int n = gX * gY * gZ;
        float[] density = new float[n];
        for (int y = 0; y < gY; y++)
            for (int z = 0; z < gZ; z++)
                for (int x = 0; x < gX; x++) {
                    int i = (y * gZ + z) * gX + x;
                    int sum = CENTER_W * (opaque[i] ? 1 : 0);
                    int cnt = CENTER_W;
                    if (x > 0)      { sum += opaque[i - 1] ? 1 : 0; cnt++; }
                    if (x < gX - 1) { sum += opaque[i + 1] ? 1 : 0; cnt++; }
                    if (z > 0)      { sum += opaque[i - gX] ? 1 : 0; cnt++; }
                    if (z < gZ - 1) { sum += opaque[i + gX] ? 1 : 0; cnt++; }
                    if (y > 0)      { sum += opaque[i - gX * gZ] ? 1 : 0; cnt++; }
                    if (y < gY - 1) { sum += opaque[i + gX * gZ] ? 1 : 0; cnt++; }
                    density[i] = (float) sum / cnt;
                }

        // Pass 1: one vertex per surface cell (min-corner at (x,y,z), cube spans +1 in each axis).
        int[] cellVert = new int[n];
        java.util.Arrays.fill(cellVert, -1);
        ArrayList<float[]> verts = new ArrayList<>();   // grid coords {x,y,z}
        ArrayList<Integer> vertColor = new ArrayList<>();
        float[] d = new float[8];
        for (int y = 0; y + 1 < gY; y++)
            for (int z = 0; z + 1 < gZ; z++)
                for (int x = 0; x + 1 < gX; x++) {
                    int mask = 0;
                    for (int c = 0; c < 8; c++) {
                        int cx = x + CORNER[c][0], cy = y + CORNER[c][1], cz = z + CORNER[c][2];
                        d[c] = density[(cy * gZ + cz) * gX + cx];
                        if (d[c] >= ISO) mask |= 1 << c;
                    }
                    if (mask == 0 || mask == 0xFF) continue;   // no crossing
                    double lx = 0, ly = 0, lz = 0;
                    int crossings = 0;
                    for (int[] e : EDGE) {
                        int a = e[0], b = e[1];
                        boolean sa = d[a] >= ISO, sb = d[b] >= ISO;
                        if (sa == sb) continue;
                        double t = Math.abs(d[b] - d[a]) < 1e-6f ? 0.5 : (ISO - d[a]) / (d[b] - d[a]);
                        t = Math.max(0, Math.min(1, t));
                        lx += CORNER[a][0] + (CORNER[b][0] - CORNER[a][0]) * t;
                        ly += CORNER[a][1] + (CORNER[b][1] - CORNER[a][1]) * t;
                        lz += CORNER[a][2] + (CORNER[b][2] - CORNER[a][2]) * t;
                        crossings++;
                    }
                    float vx = (float) (x + lx / crossings);
                    float vy = (float) (y + ly / crossings);
                    float vz = (float) (z + lz / crossings);
                    cellVert[(y * gZ + z) * gX + x] = verts.size();
                    verts.add(new float[]{vx, vy, vz});
                    vertColor.add(nearestColor(opaque, argb, gX, gY, gZ, vx, vy, vz));
                }

        // Pass 2: quads. For each grid edge along +x/+y/+z that crosses the iso, connect the four
        // cells sharing it (each has a vertex). Emitted as two triangles; winding is irrelevant (the
        // rasterizer z-buffers and shades from gradient normals, no back-face cull).
        ArrayList<Integer> tris = new ArrayList<>();
        for (int y = 0; y < gY; y++)
            for (int z = 0; z < gZ; z++)
                for (int x = 0; x < gX; x++) {
                    int i = (y * gZ + z) * gX + x;
                    boolean s = density[i] >= ISO;
                    // +x edge -> ring of cells varying in (y,z)
                    if (x + 1 < gX && (density[i + 1] >= ISO) != s
                            && y > 0 && y + 1 < gY && z > 0 && z + 1 < gZ) {
                        quad(tris, cellVert, gX, gZ,
                                x, y - 1, z - 1,  x, y, z - 1,  x, y, z,  x, y - 1, z);
                    }
                    // +y edge -> ring of cells varying in (x,z)
                    if (y + 1 < gY && (density[i + gX * gZ] >= ISO) != s
                            && x > 0 && x + 1 < gX && z > 0 && z + 1 < gZ) {
                        quad(tris, cellVert, gX, gZ,
                                x - 1, y, z - 1,  x, y, z - 1,  x, y, z,  x - 1, y, z);
                    }
                    // +z edge -> ring of cells varying in (x,y)
                    if (z + 1 < gZ && (density[i + gX] >= ISO) != s
                            && x > 0 && x + 1 < gX && y > 0 && y + 1 < gY) {
                        quad(tris, cellVert, gX, gZ,
                                x - 1, y - 1, z,  x, y - 1, z,  x, y, z,  x - 1, y, z);
                    }
                }

        int nv = verts.size();
        float[] pos = new float[nv * 3];
        float[] nrm = new float[nv * 3];
        int[] col = new int[nv];
        for (int v = 0; v < nv; v++) {
            float[] p = verts.get(v);
            float[] nn = gradientNormal(density, gX, gY, gZ, p[0], p[1], p[2]);
            nrm[v * 3] = nn[0]; nrm[v * 3 + 1] = nn[1]; nrm[v * 3 + 2] = nn[2];
            pos[v * 3] = (originX + p[0]) * cellSize;
            pos[v * 3 + 1] = (originY + p[1]) * cellSize;
            pos[v * 3 + 2] = (originZ + p[2]) * cellSize;
            col[v] = vertColor.get(v);
        }
        int[] tri = tris.stream().mapToInt(Integer::intValue).toArray();
        return new Mesh(pos, nrm, col, tri);
    }

    private static void quad(ArrayList<Integer> tris, int[] cellVert, int gX, int gZ,
            int ax, int ay, int az, int bx, int by, int bz,
            int cx, int cy, int cz, int dx, int dy, int dz) {
        int a = cellVert[(ay * gZ + az) * gX + ax];
        int b = cellVert[(by * gZ + bz) * gX + bx];
        int c = cellVert[(cy * gZ + cz) * gX + cx];
        int d = cellVert[(dy * gZ + dz) * gX + dx];
        if (a < 0 || b < 0 || c < 0 || d < 0) return;   // a bordering cell had no vertex; skip
        tris.add(a); tris.add(b); tris.add(c);
        tris.add(a); tris.add(c); tris.add(d);
    }

    private static int nearestColor(boolean[] opaque, int[] argb, int gX, int gY, int gZ,
                                    float vx, float vy, float vz) {
        int best = 0xFF888888;
        double bestD = Double.MAX_VALUE;
        int cx = Math.round(vx), cy = Math.round(vy), cz = Math.round(vz);
        for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (x < 0 || y < 0 || z < 0 || x >= gX || y >= gY || z >= gZ) continue;
                    if (!opaque[(y * gZ + z) * gX + x]) continue;
                    double dd = (x - vx) * (x - vx) + (y - vy) * (y - vy) + (z - vz) * (z - vz);
                    if (dd < bestD) { bestD = dd; best = argb[(y * gZ + z) * gX + x]; }
                }
        return best;
    }

    private static float[] gradientNormal(float[] density, int gX, int gY, int gZ,
                                          float vx, float vy, float vz) {
        int x = Math.max(1, Math.min(gX - 2, Math.round(vx)));
        int y = Math.max(1, Math.min(gY - 2, Math.round(vy)));
        int z = Math.max(1, Math.min(gZ - 2, Math.round(vz)));
        float gx = density[(y * gZ + z) * gX + (x + 1)] - density[(y * gZ + z) * gX + (x - 1)];
        float gy = density[((y + 1) * gZ + z) * gX + x] - density[((y - 1) * gZ + z) * gX + x];
        float gz = density[(y * gZ + (z + 1)) * gX + x] - density[(y * gZ + (z - 1)) * gX + x];
        // Normal points toward DECREASING density (outward from solid): negate the gradient.
        float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (len < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{-gx / len, -gy / len, -gz / len};
    }
}
