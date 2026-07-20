# Smooth Map Rendering Implementation Plan


**Goal:** Render the 3D orbit view as a smooth Marching-Cubes iso-surface (instead of hard cubes) at all zooms, and smooth the 2D map with linear filtering + interpolated relief — all on the existing CPU rasterizer (Phase 1; GPU is a later Phase 2).

**Architecture:** A pure, unit-tested `OrbitMesher` turns the voxel occupancy grid `VoxelCloud` already computes into a smooth triangle mesh (density field → Marching Cubes → gradient normals → optional Laplacian smoothing). `OrbitScene` projects the mesh's vertices and rasterizes its triangles through the existing `fillTri`. The 2D map gets a LINEAR blit filter + interpolated relief shading.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5.

**Spec:** `docs/plans/specs/2026-07-20-smooth-map-rendering-design.md`

**Branch:** Work on `main`. Do NOT create branches/worktrees. Build:
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

## Background an engineer needs

- **Current 3D render:** `OrbitScene.rasterizeInto` loops a `List<VoxelCloud.Point>` and calls
  `drawCube` per point; `drawCube` projects cube corners with
  `BeaconGeometry.project(dx,dy,dz, b[0..8], focal, sz, sz)` → `Screen{x(),y(),depth()}` and fills
  faces via `fillTri(NativeImage img, float[] depth, int sz, double x0,y0,x1,y1,x2,y2, float z, int color, float alpha)`.
  We keep `fillTri` and the projection; we replace the per-cube geometry with a mesh.
- **Occupancy grid:** `VoxelCloud.sample(engine, colors, focusX,focusY,focusZ, extentXZ,extentUp,extentDown, lvl, maxPoints)`
  builds `boolean[] opaque` + `int[] argb` over a `gX*gY*gZ` grid (`cell = 1 << lvl`,
  `gX = extentXZ/cell`, origin `originCellX/Y/Z`), index `(y*gZ+z)*gX+x`. We add a method that
  returns that grid so the mesher can consume it.
- **Whole-Abyss path:** `OrbitScene.buildWholeCloud` builds points from `AbyssSpanStore`; it also has
  the occupancy per column (spans). The mesher takes the same grid form.
- **Marching Cubes tables:** use the **canonical public-domain** `edgeTable[256]` (int) and
  `triTable[256][16]` (int, -1 terminated) — the standard Paul Bourke / widely-published lookup
  tables. They are reference *data*, verified by the watertight-sphere test below (do not hand-invent
  them). Cube-corner and edge numbering must match those tables.
- **Grid → world:** a grid cell `(x,y,z)` maps to world/shifted position
  `((originCellX + x) * cell, (originCellY + y) * cell, (originCellZ + z) * cell)` (the `Point`
  path uses `+0.5`; the mesher works in the same cell space and multiplies by `cell`).

## File Structure

| File | Responsibility | New? | Tested? |
|---|---|---|---|
| `map/OrbitMesher.java` | pure MC mesher: grid → `Mesh` (density, MC, normals, smoothing, colors) | new | yes |
| `map/VoxelCloud.java` | expose occupancy+color grid (`Grid` record + `sampleGrid`) | modify | existing |
| `map/OrbitScene.java` | render the mesh via `fillTri`; drop decimation; `smooth3d` path | modify | no |
| `map/MapSettings.java`, `map/MapConfig.java` | `smooth3d` toggle | modify | yes |
| `map/Interp2D.java` | pure bilinear helper for 2D relief interpolation | new | yes |
| `map/MapTileRenderer.java` | interpolated relief shading | modify | partial |
| `map/MapCompositor.java` | LINEAR texture filter on the map/HUD blit | modify | no |

---

### Task 1: OrbitMesher — pure Marching-Cubes mesher

**Files:**
- Create: `src/main/java/com/mia/aperture/map/OrbitMesher.java`
- Test: `src/test/java/com/mia/aperture/map/OrbitMesherTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/OrbitMesherTest.java`:

```java
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
        // A fully solid grid has no iso-crossing in its interior; the surface (if any) is only at
        // the boundary. Fill fully -> density is 1 everywhere -> no 0.5 crossing -> no triangles.
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
        // positions/normals/colors are consistent length
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

        // Normals point outward: dot(normal, vertex - center) > 0 for most vertices.
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
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*OrbitMesherTest*'
```

Expected: FAIL — `cannot find symbol: class OrbitMesher`.

- [ ] **Step 3: Implement `OrbitMesher`**

Create `src/main/java/com/mia/aperture/map/OrbitMesher.java`. No Voxy/Minecraft imports. Include the
canonical Marching-Cubes `EDGE_TABLE` (256 ints) and `TRI_TABLE` (256×16 ints, `-1`-terminated) — the
standard published tables (Paul Bourke). Corner/edge numbering must match them (corner order: the 8
cube corners; edges 0–11 connecting them, per the canonical convention). Structure:

```java
package com.mia.aperture.map;

// Pure Marching-Cubes surfacer: a voxel occupancy grid -> a smooth triangle mesh. No Voxy/Minecraft
// imports so it stays unit-testable. Smoothness comes from meshing a low-pass DENSITY field (not the
// hard 0/1 occupancy) at iso 0.5, gradient normals, and an optional Laplacian relaxation pass.
public final class OrbitMesher {
    // Flat, rasterizer-friendly output. positions/normals: 3 floats per vertex (cell units, caller
    // multiplies by cellSize). colors: 1 ARGB per vertex. tris: index triples into the vertex arrays.
    public record Mesh(float[] positions, float[] normals, int[] colors, int[] tris) {}

    private static final int[] EDGE_TABLE = { /* 256 canonical Marching-Cubes edge masks */ };
    private static final int[][] TRI_TABLE = { /* 256 canonical rows, each up to 16 ints, -1 terminated */ };

    // Canonical corner offsets (match EDGE_TABLE/TRI_TABLE numbering) and the 12 edges as corner pairs.
    private static final int[][] CORNER = {
        {0,0,0},{1,0,0},{1,0,1},{0,0,1},{0,1,0},{1,1,0},{1,1,1},{0,1,1}
    };
    private static final int[][] EDGE = {
        {0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}
    };

    private static final int SMOOTH_ITERS = 1;   // Laplacian relaxation passes (0 disables)
    private static final float ISO = 0.5f;

    private OrbitMesher() {}

    public static Mesh build(boolean[] opaque, int[] argb, int gX, int gY, int gZ,
                             int cellSize, int originX, int originY, int originZ) {
        // 1) Density field at CELL CENTRES from a 2x2x2 low-pass of the binary occupancy.
        //    density[x][y][z] in [0,1]. Border cells sample only in-bounds neighbours.
        float[] density = new float[gX * gY * gZ];
        for (int y = 0; y < gY; y++)
            for (int z = 0; z < gZ; z++)
                for (int x = 0; x < gX; x++) {
                    int solid = 0, n = 0;
                    for (int dy = 0; dy <= 1; dy++)
                        for (int dz = 0; dz <= 1; dz++)
                            for (int dx = 0; dx <= 1; dx++) {
                                int ax = x - dx, ay = y - dy, az = z - dz; // sample the 8 cells around the corner
                                if (ax < 0 || ay < 0 || az < 0 || ax >= gX || ay >= gY || az >= gZ) { n++; continue; }
                                if (opaque[(ay * gZ + az) * gX + ax]) solid++;
                                n++;
                            }
                    density[(y * gZ + z) * gX + x] = n == 0 ? 0f : (float) solid / n;
                }

        java.util.ArrayList<float[]> verts = new java.util.ArrayList<>(); // {x,y,z} in cell units
        java.util.ArrayList<Integer> vertColor = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> tris = new java.util.ArrayList<>();
        // Dedup vertices per shared edge so the mesh is a manifold (needed for watertightness):
        java.util.HashMap<Long, Integer> edgeVert = new java.util.HashMap<>();

        // 2) March each cell cube (corners are grid points x..x+1 etc). Loop to g-2 so corner+1 is valid.
        for (int y = 0; y + 1 < gY; y++)
            for (int z = 0; z + 1 < gZ; z++)
                for (int x = 0; x + 1 < gX; x++) {
                    int cubeIndex = 0;
                    float[] d = new float[8];
                    for (int c = 0; c < 8; c++) {
                        int cx = x + CORNER[c][0], cy = y + CORNER[c][1], cz = z + CORNER[c][2];
                        d[c] = density[(cy * gZ + cz) * gX + cx];
                        if (d[c] >= ISO) cubeIndex |= 1 << c;
                    }
                    int em = EDGE_TABLE[cubeIndex];
                    if (em == 0) continue;
                    int[] edgeVertIdx = new int[12];
                    for (int e = 0; e < 12; e++) {
                        if ((em & (1 << e)) == 0) continue;
                        edgeVertIdx[e] = getEdgeVertex(x, y, z, e, d, density, opaque, argb,
                                gX, gY, gZ, verts, vertColor, edgeVert);
                    }
                    int[] row = TRI_TABLE[cubeIndex];
                    for (int i = 0; row[i] != -1; i += 3) {
                        tris.add(edgeVertIdx[row[i]]);
                        tris.add(edgeVertIdx[row[i + 1]]);
                        tris.add(edgeVertIdx[row[i + 2]]);
                    }
                }

        int nv = verts.size();
        float[] pos = new float[nv * 3];
        int[] col = new int[nv];
        for (int i = 0; i < nv; i++) {
            pos[i * 3] = verts.get(i)[0]; pos[i * 3 + 1] = verts.get(i)[1]; pos[i * 3 + 2] = verts.get(i)[2];
            col[i] = vertColor.get(i);
        }
        int[] tri = tris.stream().mapToInt(Integer::intValue).toArray();

        // 3) Optional Laplacian smoothing: move each vertex toward the average of its edge neighbours.
        laplacian(pos, tri, nv);

        // 4) Per-vertex normals from the density gradient at the vertex position (central differences),
        //    then scale positions from cell units to world.
        float[] nrm = new float[nv * 3];
        for (int i = 0; i < nv; i++) {
            float vx = pos[i * 3], vy = pos[i * 3 + 1], vz = pos[i * 3 + 2];
            float[] gN = gradientNormal(density, gX, gY, gZ, vx, vy, vz);
            nrm[i * 3] = gN[0]; nrm[i * 3 + 1] = gN[1]; nrm[i * 3 + 2] = gN[2];
            pos[i * 3] = (originX + vx) * cellSize;
            pos[i * 3 + 1] = (originY + vy) * cellSize;
            pos[i * 3 + 2] = (originZ + vz) * cellSize;
        }
        return new Mesh(pos, nrm, col, tri);
    }

    private static int getEdgeVertex(int x, int y, int z, int e, float[] d, float[] density,
            boolean[] opaque, int[] argb, int gX, int gY, int gZ,
            java.util.ArrayList<float[]> verts, java.util.ArrayList<Integer> vertColor,
            java.util.HashMap<Long, Integer> edgeVert) {
        int a = EDGE[e][0], b = EDGE[e][1];
        int ax = x + CORNER[a][0], ay = y + CORNER[a][1], az = z + CORNER[a][2];
        int bx = x + CORNER[b][0], by = y + CORNER[b][1], bz = z + CORNER[b][2];
        long key = edgeKey(ax, ay, az, bx, by, bz, gX, gY);
        Integer existing = edgeVert.get(key);
        if (existing != null) return existing;
        float da = d[a], db = d[b];
        float t = (Math.abs(db - da) < 1e-6f) ? 0.5f : (ISO - da) / (db - da);
        t = Math.max(0f, Math.min(1f, t));
        float vx = ax + (bx - ax) * t, vy = ay + (by - ay) * t, vz = az + (bz - az) * t;
        int idx = verts.size();
        verts.add(new float[]{vx, vy, vz});
        vertColor.add(nearestColor(opaque, argb, gX, gY, gZ, vx, vy, vz));
        edgeVert.put(key, idx);
        return idx;
    }

    private static long edgeKey(int ax, int ay, int az, int bx, int by, int bz, int gX, int gY) {
        long ka = (long) ((ay * gX) + ax) * 1000003L + az; // unique per grid corner
        long kb = (long) ((by * gX) + bx) * 1000003L + bz;
        long lo = Math.min(ka, kb), hi = Math.max(ka, kb);
        return lo * 2654435761L ^ hi;
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
        // Iso-surface normal points toward DECREASING density (outward from solid): negate gradient.
        float len = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (len < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{-gx / len, -gy / len, -gz / len};
    }

    private static void laplacian(float[] pos, int[] tri, int nv) {
        if (SMOOTH_ITERS <= 0 || nv == 0) return;
        java.util.ArrayList<java.util.HashSet<Integer>> adj = new java.util.ArrayList<>();
        for (int i = 0; i < nv; i++) adj.add(new java.util.HashSet<>());
        for (int i = 0; i < tri.length; i += 3) {
            link(adj, tri[i], tri[i + 1]); link(adj, tri[i + 1], tri[i + 2]); link(adj, tri[i + 2], tri[i]);
        }
        for (int it = 0; it < SMOOTH_ITERS; it++) {
            float[] out = pos.clone();
            for (int v = 0; v < nv; v++) {
                if (adj.get(v).isEmpty()) continue;
                float sx = 0, sy = 0, sz = 0;
                for (int nb : adj.get(v)) { sx += pos[nb * 3]; sy += pos[nb * 3 + 1]; sz += pos[nb * 3 + 2]; }
                int n = adj.get(v).size();
                out[v * 3] = (pos[v * 3] + sx / n) * 0.5f;
                out[v * 3 + 1] = (pos[v * 3 + 1] + sy / n) * 0.5f;
                out[v * 3 + 2] = (pos[v * 3 + 2] + sz / n) * 0.5f;
            }
            System.arraycopy(out, 0, pos, 0, pos.length);
        }
    }

    private static void link(java.util.ArrayList<java.util.HashSet<Integer>> adj, int a, int b) {
        adj.get(a).add(b); adj.get(b).add(a);
    }
}
```

For `EDGE_TABLE` and `TRI_TABLE`, paste the canonical 256-entry Marching-Cubes tables (public domain,
Paul Bourke's `edgeTable`/`triTable`). The watertight-sphere test is the correctness gate for them.

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew test --tests '*OrbitMesherTest*'
```

Expected: PASS (3 tests). If the ball isn't watertight, the table/edge numbering doesn't match the
tables — re-check `CORNER`/`EDGE` against the pasted tables' convention.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/OrbitMesher.java src/test/java/com/mia/aperture/map/OrbitMesherTest.java
git commit -m "feat(3d): pure Marching-Cubes mesher for the orbit view

```

---

### Task 2: VoxelCloud — expose the occupancy grid

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/VoxelCloud.java`

`sample(...)` already builds `opaque`/`argb` over the grid then converts to points. Add a sibling that
returns the grid so the mesher can consume it directly.

- [ ] **Step 1: Add a `Grid` record + `sampleGrid`**

Add near the `Point` record:

```java
    // The raw occupancy/colour grid behind sample(): opaque[i]/argb[i] over gX*gY*gZ,
    // index (y*gZ+z)*gX+x, cell = 1<<lvl, origin in cells.
    public record Grid(boolean[] opaque, int[] argb, int gX, int gY, int gZ,
                       int cell, int originCellX, int originCellY, int originCellZ) {}
```

Add this method (mirrors the front half of `sample`, returns the grid instead of points; uses
per-call buffers so it is thread-safe for the orbit worker):

```java
    public static Grid sampleGrid(WorldEngine engine, MapColorSource colors,
            int focusX, int focusY, int focusZ, int extentXZ, int extentUp, int extentDown, int lvl) {
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
        boolean[] opaque = new boolean[n];
        int[] argb = new int[n];
        fill(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl, opaque, argb);
        return new Grid(opaque, argb, gX, gY, gZ, cell, originCellX, originCellY, originCellZ);
    }
```

(Uses the existing private `fill(...)`. Note this allocates fresh buffers per call — acceptable at the
orbit worker's cadence; the static scratch remains reserved for `sample`'s single-thread invariant.)

- [ ] **Step 2: Build**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL (existing tests pass).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/map/VoxelCloud.java
git commit -m "feat(3d): expose occupancy grid (Grid/sampleGrid) for the mesher

```

---

### Task 3: MapSettings.smooth3d toggle

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/map/MapConfig.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Write the failing test**

Add to `MapSettingsTest`:

```java
    @Test
    void smooth3dDefaultsOn() {
        assertTrue(new MapSettings().smooth3d);
        assertTrue(MapConfig.fromJson("{}").smooth3d);
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*MapSettingsTest*'
```

Expected: FAIL — `cannot find symbol: smooth3d`.

- [ ] **Step 3: Add the field**

In `MapSettings.java`, near the other boolean settings, add:

```java
    // Smooth (Marching-Cubes) 3D orbit rendering vs the legacy hard-cube splatting.
    public boolean smooth3d = true;
```

(`boolean` field initializer defaults to `true` for absent JSON keys — the existing Gson pattern, no
`MapConfig` guard needed.)

- [ ] **Step 4: Run the tests**

```bash
./gradlew test --tests '*MapSettingsTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/test/java/com/mia/aperture/map/MapSettingsTest.java
git commit -m "feat(3d): smooth3d setting (default on)

```

---

### Task 4: OrbitScene — render the mesh via fillTri

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/OrbitScene.java`

No unit test (raster/Voxy-facing). Build + Task 6 verify.

- [ ] **Step 1: Add mesh fields + a builder next to the cloud fields**

After `private static List<VoxelCloud.Point> cloud;` add:

```java
    private static OrbitMesher.Mesh mesh;
    private static long meshSig = Long.MIN_VALUE;
```

- [ ] **Step 2: Build the mesh alongside the cloud in `buildFrame`**

In `buildFrame`, where the live path currently does
`cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, shiftedFocusY, focusZ, extentXZ, extentUp, extentDown, lvl, quality.maxPoints);`,
gate on the setting: when `MiaApertureModClient.mapSettings.smooth3d`, build a mesh from the grid;
otherwise keep the existing cloud path. Insert, in the block that rebuilds the cloud (both the whole
and non-whole branches), after the cloud is (re)built:

```java
        if (com.mia.aperture.client.MiaApertureModClient.mapSettings.smooth3d) {
            VoxelCloud.Grid grid = whole
                    ? null  // whole-Abyss grid built below
                    : VoxelCloud.sampleGrid(engine, colors, shiftedFocusX, shiftedFocusY, focusZ,
                            extentXZ, extentUp, extentDown, lvl);
            if (grid != null) {
                mesh = OrbitMesher.build(grid.opaque(), grid.argb(), grid.gX(), grid.gY(), grid.gZ(),
                        grid.cell(), grid.originCellX(), grid.originCellY(), grid.originCellZ());
            }
        } else {
            mesh = null;
        }
```

For the WHOLE-Abyss path, build an occupancy grid from `AbyssSpanStore` over the composited base map
(same cell coords the cloud uses) and pass it to `OrbitMesher.build`. If that grid assembly is
non-trivial, keep the whole-Abyss path on the cloud renderer for now and mesh only the live path —
**note this in the commit** and file a follow-up; the live path is the primary "chunky" complaint.

- [ ] **Step 3: Rasterize the mesh in `rasterizeInto`**

In `rasterizeInto`, before the cloud loop, add a mesh branch:

```java
        if (mesh != null) {
            drawMesh(img, depth, sz, cel, b, focal, mesh);
            return;
        }
```

Add the `drawMesh` method (projects each triangle's 3 vertices and fills via the existing `fillTri`,
with smooth per-vertex-normal flat-ish shading using the triangle's averaged normal):

```java
    private static void drawMesh(NativeImage img, float[] depth, int sz,
                                 double[] cel, double[] b, double focal, OrbitMesher.Mesh m) {
        float[] pos = m.positions(), nrm = m.normals();
        int[] col = m.colors(), tri = m.tris();
        double[] sx = new double[3], sy = new double[3];
        for (int i = 0; i < tri.length; i += 3) {
            int a = tri[i], bb = tri[i + 1], c = tri[i + 2];
            double depthSum = 0;
            boolean ok = true;
            int[] vi = {a, bb, c};
            for (int k = 0; k < 3; k++) {
                int v = vi[k];
                BeaconGeometry.Screen s = BeaconGeometry.project(
                        pos[v * 3] - cel[0], pos[v * 3 + 1] - cel[1], pos[v * 3 + 2] - cel[2],
                        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, sz, sz);
                if (s.depth() <= 0.01) { ok = false; break; }
                sx[k] = s.x(); sy[k] = s.y(); depthSum += s.depth();
            }
            if (!ok) continue;
            // averaged normal for flat shading of the triangle
            float nx = (nrm[a*3]+nrm[bb*3]+nrm[c*3]) / 3f;
            float ny = (nrm[a*3+1]+nrm[bb*3+1]+nrm[c*3+1]) / 3f;
            float nz = (nrm[a*3+2]+nrm[bb*3+2]+nrm[c*3+2]) / 3f;
            float ndotl = Math.max(0f, nx * LX + ny * LY + nz * LZ);
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int base = ColorMath.punch(col[a], SATURATION, CONTRAST);
            int color = 0xFF000000 | (ColorMath.shade(base, light) & 0xFFFFFF);
            float z = (float) (depthSum / 3.0);
            fillTri(img, depth, sz, sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], z, color, 1.0f);
        }
    }
```

Leave the existing `drawCube` path intact for when `mesh == null` (smooth3d off or whole-Abyss
fallback). X-ray modes stay on the cube path (they depend on `covered`); when `smooth3d` is on and a
mesh exists, X-ray is not applied (document in Task 6 / the HUD label if needed).

- [ ] **Step 4: Drop decimation for the mesh path**

The mesh already has far fewer primitives than cube faces, and it comes from `sampleGrid` (no
`maxPoints` decimation). No stride is applied on the mesh path — nothing to change beyond not routing
through `VoxelCloud.sample`'s decimated point list.

- [ ] **Step 5: Build + commit**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
git add src/main/java/com/mia/aperture/map/OrbitScene.java
git commit -m "feat(3d): render the orbit view as a smooth mesh via fillTri

```

---

### Task 5: 2D map smoothing (linear filter + interpolated relief)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/Interp2D.java`
- Test: `src/test/java/com/mia/aperture/map/Interp2DTest.java`
- Modify: `src/main/java/com/mia/aperture/map/MapCompositor.java`
- Modify: `src/main/java/com/mia/aperture/map/MapTileRenderer.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/Interp2DTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Interp2DTest {
    @Test
    void bilinearAtCornersReturnsCornerValues() {
        float[] f = {0, 10, 20, 30}; // 2x2: (0,0)=0 (1,0)=10 (0,1)=20 (1,1)=30
        assertEquals(0, Interp2D.bilinear(f, 2, 2, 0, 0), 1e-5);
        assertEquals(10, Interp2D.bilinear(f, 2, 2, 1, 0), 1e-5);
        assertEquals(30, Interp2D.bilinear(f, 2, 2, 1, 1), 1e-5);
    }

    @Test
    void bilinearAtCentreAveragesFour() {
        float[] f = {0, 10, 20, 30};
        assertEquals(15, Interp2D.bilinear(f, 2, 2, 0.5, 0.5), 1e-5);
    }

    @Test
    void clampsOutOfRange() {
        float[] f = {0, 10, 20, 30};
        assertEquals(0, Interp2D.bilinear(f, 2, 2, -1, -1), 1e-5);
        assertEquals(30, Interp2D.bilinear(f, 2, 2, 5, 5), 1e-5);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*Interp2DTest*'
```

Expected: FAIL — `cannot find symbol: class Interp2D`.

- [ ] **Step 3: Implement `Interp2D`**

Create `src/main/java/com/mia/aperture/map/Interp2D.java`:

```java
package com.mia.aperture.map;

// Pure bilinear sampling of a w*h float grid (row-major, index y*w+x), edge-clamped.
public final class Interp2D {
    private Interp2D() {}

    public static float bilinear(float[] f, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double tx = x - x0, ty = y - y0;
        float a = f[y0 * w + x0], b = f[y0 * w + x1], c = f[y1 * w + x0], d = f[y1 * w + x1];
        double top = a + (b - a) * tx, bot = c + (d - c) * tx;
        return (float) (top + (bot - top) * ty);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew test --tests '*Interp2DTest*'
```

Expected: PASS (3 tests).

- [ ] **Step 5: Linear filter on the map/HUD textures**

In `MapCompositor.ensure(...)` (where each `DynamicTexture` is created), set the texture's MIN/MAG
filter to **LINEAR** so upscaling is smooth (locate the correct API for this MC/Fabric version —
`DynamicTexture.setFilter(boolean bilinear, boolean mipmap)` or a `RenderSystem`/`GlStateManager`
`texParameter` on the texture's id right after creation). Apply to both `mapTexture` and `hudTexture`.

- [ ] **Step 6: Interpolated relief in `MapTileRenderer`**

In `MapTileRenderer`, the relief term uses the neighbouring cell height (`h - hNorth`). Replace the
per-cell step with a bilinearly-interpolated height sample so the shading gradient is smooth: build
the row/neighbour heights into a small float grid and sample via
`Interp2D.bilinear(...)` at sub-cell positions when composing at higher resolution. Keep the existing
`CAVE_RELIEF_K/MIN/MAX` clamp. (Exact wiring depends on the compose loop; the goal is smooth relief
gradients rather than per-cell jumps.)

- [ ] **Step 7: Build + commit**

```bash
./gradlew build
git add src/main/java/com/mia/aperture/map/Interp2D.java src/test/java/com/mia/aperture/map/Interp2DTest.java src/main/java/com/mia/aperture/map/MapCompositor.java src/main/java/com/mia/aperture/map/MapTileRenderer.java
git commit -m "feat(map): smooth 2D map — linear filtering + interpolated relief

```

---

### Task 6: Build, install, in-game verify + measure

**Files:** none.

- [ ] **Step 1: Build + install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```

- [ ] **Step 2: In-game checks**

1. **3D orbit is smooth** at every zoom — no hard cubes, no holes, no decimation gaps. Colours
   correct. Toggling **smooth3d off** returns the old cube look (before/after).
2. **2D map** is smooth when zoomed in (linear filtering), relief gradients are smooth.
3. **Whole-Abyss view** renders (mesh or cube fallback per Task 4 note) without regressions.
4. **Frame time:** with 3D Stats on, note the frame time on a whole-Abyss frame and a mid-zoom frame.
   **This number decides whether Phase 2 (GPU) is scheduled.**

- [ ] **Step 3: Report findings**

Report each check + the frame-time numbers. If the mesh has holes, it's the MC table/edge numbering
(re-check against the watertight-sphere test).

---

## Notes for the implementer

- Do NOT create a branch/worktree. Work on `main`. Commit only; push when the owner asks.
- The `EDGE_TABLE`/`TRI_TABLE` are canonical public-domain data — the watertight-sphere test is their
  gate; don't hand-invent them.
- Keep the legacy `drawCube` path working for `smooth3d == false` and the whole-Abyss fallback.
- No inline narrating comments; comments explain constraints/why.
- If the whole-Abyss occupancy-grid assembly (Task 4 Step 2) balloons scope, mesh only the live path
  this pass and flag a follow-up — the live path is the primary complaint.
