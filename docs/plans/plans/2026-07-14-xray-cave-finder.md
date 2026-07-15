# X-Ray / Cave-Finder Implementation Plan


**Goal:** Add an X-ray / cave-finder display mode revealing air voids embedded in rock across the minimap, fullscreen map (layered: cave-floor detail + hollowness tint), and 3D voxel view (ghost-shell ↔ cave-only).

**Architecture:** A new `MapMode.XRAY` flows through the existing background tile pipeline (`MapTileRenderer` → `TileKey`/cache → compositor). The 3D view adds a per-voxel `covered` flag (interior vs outer shell) and a view-local x-ray toggle in `OrbitScene`/`OrbitView`. All heavy scanning stays on worker threads. Pure scan/classify logic is JUnit-tested.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Build with vendored JDK: `$env:JAVA_HOME="D:\Users\dev\VSCode-Projects\MIA map mod project\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`.

**Spec:** `docs/plans/specs/2026-07-14-xray-cave-finder-design.md`

---

## File Structure

- `map/MapMode.java` — add `XRAY` constant.
- `map/MapTileRenderer.java` — x-ray column scan (whole-column void detection + topmost cave floor + per-column void count) and layered render (cave shading + hollow tint). Pure.
- `map/MinimapRenderer.java` + `client/AbyssWorldMapScreen.java` — auto-CAVE suppression when mode is XRAY; V-cycle 3-way; Settings/label.
- `client/MapSettingsScreen.java` — a mode-cycle button.
- `map/VoxelCloud.java` — pure `columnTopSolid` + `covered` flag on `Point`, wired in `sample`.
- `map/OrbitScene.java` — `XrayMode`, sig inclusion, ghost/cave-only rasterization + alpha fill.
- `client/OrbitView.java` — `X` key cycles the 3D x-ray mode + on-screen label.
- Tests: `MapTileRendererTest`, `VoxelCloudTest`.

---

## Task 1: `MapMode.XRAY` + x-ray tile scan & render (pure)

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapMode.java`
- Modify: `src/main/java/com/mia/aperture/map/MapTileRenderer.java`
- Test: `src/test/java/com/mia/aperture/map/MapTileRendererTest.java`

- [ ] **Step 1: Add the enum constant**

In `MapMode.java`:

```java
public enum MapMode {
    RELIEF,
    VANILLA,
    CAVE,
    XRAY
}
```

- [ ] **Step 2: Write failing tests**

Add to `MapTileRendererTest.java` (uses the existing `colors`, `emptySection`, `fillLayer`, `idx` helpers where 0=air, 1=stone):

```java
@Test
void xraySolidColumnIsTransparent() {
    long[] sec = emptySection();
    for (int cy = 0; cy < 32; cy++) fillLayer(sec, cy, 1); // solid all the way
    int[] color = new int[1024];
    MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.XRAY, colors, color, new int[1024]);
    assertEquals(0, color[idx(0, 0)]);
}

@Test
void xrayRevealsBuriedCaveFloor() {
    // solid ground with a carved void: solid 0..10 (floor at 10), thin surface cap at 20,
    // cave air in cells 11..19, sky above -> the cave FLOOR (not the surface) is drawn.
    long[] sec = emptySection();
    for (int cy = 0; cy <= 10; cy++) fillLayer(sec, cy, 1); // rock incl. cave floor at 10
    fillLayer(sec, 20, 1);                                  // thin surface cap
    int[] color = new int[1024];
    int[] height = new int[1024];
    MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.XRAY, colors, color, height);
    assertNotEquals(0, color[idx(0, 0)]);
    assertEquals(288 + 10, height[idx(0, 0)]); // topmost cave floor, not the surface
}

@Test
void xrayOpenGroundWithNoCaveIsTransparent() {
    // a plain surface with only sky above and solid below -> no sub-surface void -> transparent
    long[] sec = emptySection();
    for (int cy = 0; cy <= 15; cy++) fillLayer(sec, cy, 1); // solid up to surface at 15, sky above
    int[] color = new int[1024];
    MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.XRAY, colors, color, new int[1024]);
    assertEquals(0, color[idx(0, 0)]);
}

@Test
void xrayBiggerVoidTintsHotter() {
    // SAME floor height (10) so the base shading is identical; only the void size differs
    // (surface cap higher => bigger cave). Larger void must blend further toward the cyan tint.
    long[] small = emptySection();
    for (int cy = 0; cy <= 10; cy++) fillLayer(small, cy, 1);
    fillLayer(small, 12, 1); // cap at 12 -> 1-cell void (cell 11)
    long[] big = emptySection();
    for (int cy = 0; cy <= 10; cy++) fillLayer(big, cy, 1);
    fillLayer(big, 25, 1);   // cap at 25 -> 14-cell void (cells 11..24)
    int[] sc = new int[1024];
    int[] bc = new int[1024];
    MapTileRenderer.renderTile(new long[][]{small}, 320, 320, 288, 1, MapMode.XRAY, colors, sc, new int[1024]);
    MapTileRenderer.renderTile(new long[][]{big}, 320, 320, 288, 1, MapMode.XRAY, colors, bc, new int[1024]);
    // XRAY tint is cyan-white (0xFF88FFFF, blue=0xFF): more hollow -> higher blue channel
    assertTrue((bc[idx(0, 0)] & 0xFF) > (sc[idx(0, 0)] & 0xFF));
    assertNotEquals(0, sc[idx(0, 0)]);
}
```

- [ ] **Step 3: Run tests, verify they fail**

Run: `.\gradlew test --tests com.mia.aperture.map.MapTileRendererTest`
Expected: FAIL (XRAY treated like default; buried-floor + transparency assertions fail).

- [ ] **Step 4: Add x-ray constants**

In `MapTileRenderer.java`, after the `CAVE_*` constants (near line 19):

```java
    // X-ray: cyan-white "air" tint blended over the cave-floor detail, scaled by how hollow the
    // column is (air cells below the surface), clamped to XRAY_TINT_MAX cells.
    private static final int XRAY_TINT_COLOR = 0xFF88FFFF;
    private static final int XRAY_TINT_MAX = 24;
    private static final float XRAY_TINT_STRENGTH = 0.6f;
```

- [ ] **Step 5: Add the x-ray column scan**

In `renderTile`, replace the scan setup and first loop. Change:

```java
        boolean caveScan = mode == MapMode.CAVE;
```

to:

```java
        boolean caveScan = mode == MapMode.CAVE;
        boolean xray = mode == MapMode.XRAY;
        int[] voidCount = xray ? new int[CELLS * CELLS] : null;
```

Then replace the inner scan body (the `for (int cy = startCell; cy >= 0; cy--)` block) with a branch. The full first loop becomes:

```java
        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int out = z * CELLS + x;
                outColor[out] = 0;
                outHeight[out] = Integer.MIN_VALUE;
                surfaceId[out] = 0;

                int startCell = Math.min(totalCellsY - 1,
                        Math.floorDiv(bandTopY - stackBaseY, cellSize));

                if (xray) {
                    boolean sawSurface = false;   // passed the first solid (the ground surface)
                    boolean sawVoidBelow = false; // entered air beneath the surface (a cave)
                    int vc = 0;
                    for (int cy = startCell; cy >= 0; cy--) {
                        long id = cellAt(sections, cy, x, z, totalCellsY);
                        boolean opaque = id != 0 && colors.isOpaque(id);
                        if (!sawSurface) {
                            if (opaque) sawSurface = true; // found + skip the surface
                            continue;
                        }
                        if (!opaque) { vc++; sawVoidBelow = true; continue; } // cave air
                        if (sawVoidBelow && surfaceId[out] == 0) {
                            surfaceId[out] = id; // topmost cave floor (kept for the detail layer)
                            outHeight[out] = stackBaseY + cy * cellSize;
                        }
                        // keep scanning the whole column to count every void
                    }
                    voidCount[out] = vc;
                    continue;
                }

                boolean sawAir = false;
                for (int cy = startCell; cy >= 0; cy--) {
                    long id = cellAt(sections, cy, x, z, totalCellsY);
                    boolean opaque = id != 0 && colors.isOpaque(id);
                    if (!opaque) {
                        sawAir = true;
                        continue;
                    }
                    if (caveScan && !sawAir) continue;
                    surfaceId[out] = id;
                    outHeight[out] = stackBaseY + cy * cellSize;
                    break;
                }
            }
        }
```

- [ ] **Step 6: Add the x-ray render branch**

In the second loop, add an XRAY branch before the `if (mode == MapMode.CAVE)` chain. Replace:

```java
                if (mode == MapMode.CAVE) {
```

with:

```java
                if (mode == MapMode.XRAY) {
                    double t = Math.max(0.0, Math.min(1.0,
                            (h - (bandTopY - CAVE_DEPTH_RANGE)) / (double) CAVE_DEPTH_RANGE));
                    float depth = CAVE_MIN_BRIGHT + (CAVE_MAX_BRIGHT - CAVE_MIN_BRIGHT) * (float) t;
                    float relief = 1.0f + CAVE_RELIEF_K * (h - hNorth);
                    relief = Math.max(CAVE_RELIEF_MIN, Math.min(CAVE_RELIEF_MAX, relief));
                    int shaded = scale(base, depth * relief);
                    float hollow = Math.min(1.0f, voidCount[out] / (float) XRAY_TINT_MAX);
                    outColor[out] = blend(shaded, XRAY_TINT_COLOR, hollow * XRAY_TINT_STRENGTH);
                } else if (mode == MapMode.CAVE) {
```

(The rest of the chain — `else if (mode == MapMode.VANILLA)` / `else` — is unchanged.)

- [ ] **Step 7: Run tests, verify pass**

Run: `.\gradlew test --tests com.mia.aperture.map.MapTileRendererTest`
Expected: PASS (all existing + 4 new).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapMode.java src/main/java/com/mia/aperture/map/MapTileRenderer.java src/test/java/com/mia/aperture/map/MapTileRendererTest.java
git commit -m "feat(map): MapMode.XRAY — whole-column cave detection + layered floor/hollow-tint render"
```

---

## Task 2: 2D wiring — V-cycle, auto-CAVE suppression, Settings

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`
- Modify: `src/main/java/com/mia/aperture/map/MinimapRenderer.java`
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: V key cycles three modes**

In `AbyssWorldMapScreen.keyPressed`, replace the `GLFW_KEY_V` block:

```java
        if (event.key() == GLFW.GLFW_KEY_V) {
            AbyssMapState.mapRenderMode = AbyssMapState.mapRenderMode == com.mia.aperture.map.MapMode.RELIEF
                    ? com.mia.aperture.map.MapMode.VANILLA
                    : com.mia.aperture.map.MapMode.RELIEF;
            return true;
        }
```

with:

```java
        if (event.key() == GLFW.GLFW_KEY_V) {
            AbyssMapState.mapRenderMode = nextRenderMode(AbyssMapState.mapRenderMode);
            return true;
        }
```

And add a helper method to the class (a shared cycle used by Settings too):

```java
    static com.mia.aperture.map.MapMode nextRenderMode(com.mia.aperture.map.MapMode m) {
        return switch (m) {
            case RELIEF -> com.mia.aperture.map.MapMode.VANILLA;
            case VANILLA -> com.mia.aperture.map.MapMode.XRAY;
            default -> com.mia.aperture.map.MapMode.RELIEF; // XRAY or CAVE -> RELIEF
        };
    }
```

- [ ] **Step 2: Suppress auto-CAVE when the user picked XRAY (fullscreen)**

In `AbyssWorldMapScreen.render`, replace the `composeMap(...)` mode argument:

```java
                    bandTop, bandBottom, caveActive ? com.mia.aperture.map.MapMode.CAVE : AbyssMapState.mapRenderMode);
```

with:

```java
                    bandTop, bandBottom,
                    AbyssMapState.mapRenderMode == com.mia.aperture.map.MapMode.XRAY
                            ? com.mia.aperture.map.MapMode.XRAY
                            : (caveActive ? com.mia.aperture.map.MapMode.CAVE : AbyssMapState.mapRenderMode));
```

- [ ] **Step 3: Suppress auto-CAVE when the user picked XRAY (minimap)**

In `MinimapRenderer.draw`, replace:

```java
        MapMode mode = caveActive ? MapMode.CAVE : AbyssMapState.mapRenderMode;
```

with:

```java
        MapMode mode = AbyssMapState.mapRenderMode == MapMode.XRAY ? MapMode.XRAY
                : (caveActive ? MapMode.CAVE : AbyssMapState.mapRenderMode);
```

- [ ] **Step 4: Settings mode-cycle button**

In `MapSettingsScreen.init`, add a new row near the top of the scroll content (after the existing appearance rows; place before the mob rows). Use the existing `addScroll(widget, row)` + `settings()`/`persist()` pattern. Add:

```java
        int modeRow = r++;
        addScroll(Button.builder(renderModeLabel(), b -> {
            com.mia.aperture.state.AbyssMapState.mapRenderMode =
                    AbyssWorldMapScreen.nextRenderMode(com.mia.aperture.state.AbyssMapState.mapRenderMode);
            b.setMessage(renderModeLabel());
        }).bounds(cx - 100, 0, 200, 20).build(), modeRow);
```

Add the label helper to the class:

```java
    private static Component renderModeLabel() {
        return Component.literal("Map mode: " + com.mia.aperture.state.AbyssMapState.mapRenderMode);
    }
```

(Note: like the `V` key today, the mode is session state — not persisted. No `persist()` call here.)

Reference: match the exact `int r`, `cx`, `addScroll` names already used in `MapSettingsScreen.init` (see the mob-toggle rows). If the local for the row index is not `r`, use whatever that method uses.

- [ ] **Step 5: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java src/main/java/com/mia/aperture/map/MinimapRenderer.java src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(map): X-ray in V-cycle + Settings; suppress auto-CAVE when XRAY is selected"
```

---

## Task 3: 3D `covered` classifier (pure) + `VoxelCloud.Point`

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/VoxelCloud.java`
- Test: `src/test/java/com/mia/aperture/map/VoxelCloudTest.java`

- [ ] **Step 1: Write failing test**

Add to `VoxelCloudTest.java`:

```java
@Test
void columnTopSolidMarksHighestOpaquePerColumn() {
    int gX = 2, gY = 4, gZ = 1;
    boolean[] op = new boolean[gX * gY * gZ];
    // index = (y*gZ + z)*gX + x
    op[((1) * gZ + 0) * gX + 0] = true; // column x=0: solid at y=1
    op[((3) * gZ + 0) * gX + 0] = true; // column x=0: solid at y=3 (highest)
    // column x=1: all air
    int[] top = VoxelCloud.columnTopSolid(op, gX, gY, gZ);
    assertEquals(3, top[0 * gX + 0]); // x=0 -> highest solid y=3
    assertEquals(-1, top[0 * gX + 1]); // x=1 -> none
    // a voxel at y=1 in column 0 is "covered" (below the top solid at 3)
    assertTrue(1 < top[0 * gX + 0]);
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `.\gradlew test --tests com.mia.aperture.map.VoxelCloudTest`
Expected: FAIL (`columnTopSolid` not defined).

- [ ] **Step 3: Add the pure helper**

In `VoxelCloud.java`, add (near `gi`):

```java
    // Highest opaque cell Y per (x,z) column, or -1 for an all-air column. Pure. A surface voxel
    // is "covered" (interior/cave) when its Y is below its column's top solid; == top means it's
    // the outer shell (open sky above). Index layout: (y*gZ+z)*gX+x.
    public static int[] columnTopSolid(boolean[] opaque, int gX, int gY, int gZ) {
        int[] top = new int[gX * gZ];
        java.util.Arrays.fill(top, -1);
        for (int y = 0; y < gY; y++)
            for (int z = 0; z < gZ; z++)
                for (int x = 0; x < gX; x++)
                    if (opaque[(y * gZ + z) * gX + x]) top[z * gX + x] = y;
        return top;
    }
```

- [ ] **Step 4: Run test, verify pass**

Run: `.\gradlew test --tests com.mia.aperture.map.VoxelCloudTest`
Expected: PASS.

- [ ] **Step 5: Add `covered` to `Point` and set it in `sample`**

Change the `Point` record:

```java
    public record Point(double x, double y, double z, int argb, int cellSize,
                        float nx, float ny, float nz, int faces, boolean covered) {}
```

In `sample`, after `fill(...)` populates `opaque`, compute the top-solid map once:

```java
        int[] topSolid = columnTopSolid(opaque, gX, gY, gZ);
```

And in the point-construction loop, add the `covered` argument (last):

```java
                    pts.add(new Point(
                            (originCellX + x + 0.5) * cell,
                            (originCellY + y + 0.5) * cell,
                            (originCellZ + z + 0.5) * cell,
                            argb[idx], cell, nrm[0], nrm[1], nrm[2], faces,
                            y < topSolid[z * gX + x]));
```

- [ ] **Step 6: Build (catches the record-arg update site)**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL. If `OrbitScene` fails to compile, it does not construct `Point` (only reads it) — no change needed there for this step.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mia/aperture/map/VoxelCloud.java src/test/java/com/mia/aperture/map/VoxelCloudTest.java
git commit -m "feat(3d): VoxelCloud covered classifier (interior vs outer-shell voxels)"
```

---

## Task 4: 3D x-ray rasterization (ghost / cave-only)

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/OrbitScene.java`

- [ ] **Step 1: Add the mode enum + state + accessor**

In `OrbitScene`, add near the top (after the class-level fields, e.g. by `desiredTex`):

```java
    public enum XrayMode { OFF, GHOST, CAVE_ONLY }
    private static volatile XrayMode xrayMode = XrayMode.OFF;
    private static final float GHOST_ALPHA = 0.28f;

    public static XrayMode xrayMode() { return xrayMode; }
    public static void setXrayMode(XrayMode m) { xrayMode = m; }
```

- [ ] **Step 2: Include the mode in the frame signature (so toggling re-renders)**

In `computeSig`, add `xrayMode.ordinal()` to the hash:

```java
        return Objects.hash(fx, fy, fz, extentXZ, desiredTex,
                (int) Math.round(cam.yawDeg), (int) Math.round(cam.pitchDeg), (int) Math.round(cam.distance),
                xrayMode.ordinal());
```

- [ ] **Step 3: Route rasterization through the mode**

Replace the body of `rasterizeInto` with mode dispatch, extracting the per-point cube draw into `drawCube`:

```java
    private static void rasterizeInto(NativeImage img, float[] depth, int sz,
                                      double[] cel, double[] b, double focal) {
        List<VoxelCloud.Point> pts = cloud;
        if (pts == null) return;
        XrayMode mode = xrayMode;
        if (mode == XrayMode.CAVE_ONLY) {
            for (VoxelCloud.Point p : pts) if (p.covered()) drawCube(img, depth, sz, cel, b, focal, p, 1.0f);
        } else if (mode == XrayMode.GHOST) {
            for (VoxelCloud.Point p : pts) if (p.covered()) drawCube(img, depth, sz, cel, b, focal, p, 1.0f);
            for (VoxelCloud.Point p : pts) if (!p.covered()) drawCube(img, depth, sz, cel, b, focal, p, GHOST_ALPHA);
        } else {
            for (VoxelCloud.Point p : pts) drawCube(img, depth, sz, cel, b, focal, p, 1.0f);
        }
    }

    private static void drawCube(NativeImage img, float[] depth, int sz,
                                 double[] cel, double[] b, double focal, VoxelCloud.Point p, float alpha) {
        double[] sx = new double[4], sy = new double[4];
        double h = p.cellSize() * 0.5;
        int base = ColorMath.punch(p.argb(), SATURATION, CONTRAST);
        int faceBits = p.faces();
        for (int fi = 0; fi < FACES.length; fi++) {
            if ((faceBits & (1 << fi)) == 0) continue;
            double[] f = FACES[fi];
            double nfx = f[0], nfy = f[1], nfz = f[2];
            if (nfx * (cel[0] - p.x()) + nfy * (cel[1] - p.y()) + nfz * (cel[2] - p.z()) <= 0) continue;
            double fcx = p.x() + nfx * h, fcy = p.y() + nfy * h, fcz = p.z() + nfz * h;
            double t1x = f[3], t1y = f[4], t1z = f[5], t2x = f[6], t2y = f[7], t2z = f[8];
            double depthSum = 0;
            boolean ok = true;
            for (int k = 0; k < 4; k++) {
                double su = ((k == 1 || k == 2) ? h : -h);
                double sv = ((k >= 2) ? h : -h);
                double wx = fcx + t1x * su + t2x * sv;
                double wy = fcy + t1y * su + t2y * sv;
                double wz = fcz + t1z * su + t2z * sv;
                BeaconGeometry.Screen s = BeaconGeometry.project(wx - cel[0], wy - cel[1], wz - cel[2],
                        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, sz, sz);
                if (s.depth() <= 0.01) { ok = false; break; }
                sx[k] = s.x(); sy[k] = s.y(); depthSum += s.depth();
            }
            if (!ok) continue;
            float z = (float) (depthSum / 4.0);
            float ndotl = Math.max(0f, (float) (nfx * LX + nfy * LY + nfz * LZ));
            float light = AMBIENT + (1f - AMBIENT) * ndotl;
            int col = 0xFF000000 | (ColorMath.shade(base, light) & 0xFFFFFF);
            fillTri(img, depth, sz, sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], z, col, alpha);
            fillTri(img, depth, sz, sx[0], sy[0], sx[2], sy[2], sx[3], sy[3], z, col, alpha);
        }
    }
```

- [ ] **Step 4: Add alpha to `fillTri`**

Replace the `fillTri` signature and the write section to support alpha blending:

```java
    private static void fillTri(NativeImage img, float[] depth, int sz,
                                double x0, double y0, double x1, double y1,
                                double x2, double y2, float z, int color, float alpha) {
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = (int) Math.min(sz - 1, Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = (int) Math.min(sz - 1, Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6) return;
        for (int py = minY; py <= maxY; py++) {
            for (int px = minX; px <= maxX; px++) {
                double w0 = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
                double w1 = (x0 - x2) * (py - y2) - (y0 - y2) * (px - x2);
                double w2 = (x1 - x0) * (py - y0) - (y1 - y0) * (px - x0);
                boolean inside = (w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0);
                if (!inside) continue;
                int di = py * sz + px;
                if (z >= depth[di]) continue;
                depth[di] = z;
                if (alpha >= 1.0f) {
                    img.setPixel(px, py, color);
                } else {
                    img.setPixel(px, py, blendArgb(img.getPixel(px, py), color, alpha));
                }
            }
        }
    }

    // Lerp src over dst by alpha, keeping full opacity. Channel order is whatever the buffer uses;
    // the mix is order-agnostic since both operands share it.
    private static int blendArgb(int dst, int src, float a) {
        int dr = (dst >> 16) & 0xFF, dg = (dst >> 8) & 0xFF, db = dst & 0xFF;
        int sr = (src >> 16) & 0xFF, sg = (src >> 8) & 0xFF, sb = src & 0xFF;
        int r = (int) (dr * (1 - a) + sr * a);
        int g = (int) (dg * (1 - a) + sg * a);
        int bl = (int) (db * (1 - a) + sb * a);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
```

- [ ] **Step 5: Reset x-ray mode on scene reset**

In `OrbitScene.reset()`, add:

```java
        xrayMode = XrayMode.OFF;
```

- [ ] **Step 6: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mia/aperture/map/OrbitScene.java
git commit -m "feat(3d): x-ray rasterization — ghost-shell (alpha) and cave-only modes"
```

---

## Task 5: 3D `X` key toggle + label

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`

- [ ] **Step 1: Cycle x-ray mode on `X`**

In `OrbitView.keyPressed` (add alongside the existing key handling; the method already handles keys — match its `KeyEvent event` signature and `event.key()` accessor):

```java
        if (event.key() == GLFW.GLFW_KEY_X) {
            OrbitScene.setXrayMode(switch (OrbitScene.xrayMode()) {
                case OFF -> OrbitScene.XrayMode.GHOST;
                case GHOST -> OrbitScene.XrayMode.CAVE_ONLY;
                case CAVE_ONLY -> OrbitScene.XrayMode.OFF;
            });
            return true;
        }
```

If `OrbitView` does not already import `OrbitScene`, it is referenced fully-qualified elsewhere or imported — it already imports `com.mia.aperture.map.OrbitScene` (used in `render`). Use the simple name.

- [ ] **Step 2: Show the current mode on-screen**

In `OrbitView.render`, near the other HUD text/labels (after the compass labels, before the closing of the player-present block), add a small readout:

```java
            String xrayLabel = switch (OrbitScene.xrayMode()) {
                case OFF -> "X-ray: off";
                case GHOST -> "X-ray: ghost shell";
                case CAVE_ONLY -> "X-ray: caves only";
            };
            guiGraphics.drawString(this.font, xrayLabel + "  (X)", 10, 10, 0xFF88FFFF);
```

(Place it where it won't overlap existing overlay text; if `10,10` is used, pick the next free line such as `10, 22`.)

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/OrbitView.java
git commit -m "feat(3d): X key cycles 3D x-ray (off / ghost shell / caves only) + label"
```

---

## Task 6: Full build, install, in-game verification

**Files:** none (build + manual verify).

- [ ] **Step 1: Full test + build**

Run: `$env:JAVA_HOME="D:\Users\dev\VSCode-Projects\MIA map mod project\libs\jdk21\jdk-21.0.11+10"; .\gradlew clean test build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Install to the instance**

```bash
cp -v "build/libs/mia-maps-0.1.4-beta.jar" "/c/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```
(The dev jar is still labeled 0.1.4 until the release step; the version bump to 0.1.5 happens when cutting the release, after owner verification.)

- [ ] **Step 3: Owner verifies in-game**

- Fullscreen map: press `V` to reach **X-ray** (Relief → Vanilla → X-ray). Over explored terrain, caves under rock show as terrain-colored floors with a cyan-white glow that intensifies for larger voids; solid ground stays dark.
- Minimap: same X-ray view follows; walking into a real cave does NOT flip it back (auto-CAVE suppressed while XRAY is selected).
- Settings: the "Map mode" button cycles the three modes.
- 3D view (`J`/`3D View`): press `X` to cycle **off → ghost shell → caves only**; the label updates; ghost shows caves through a translucent surface, cave-only hides the shell.

- [ ] **Step 4: Report results.** If good, proceed to cut **v0.1.5-beta** (bump `gradle.properties`, clean build, install, `gh release` prerelease, push) — done outside this plan by the controller. If issues, capture the symptom and return to the relevant task.

---

## Notes / Deviations from spec

- **Persistence:** `mapRenderMode` lives in `AbyssMapState` and has never been persisted (the `V` key today is session-scoped). The Settings control matches that — it cycles the mode but does not write `MapConfig`. Adding mode persistence is a separate, optional follow-up.
- **Tint color** (`XRAY_TINT_COLOR = 0xFF88FFFF`) and **3D key** (`X`) are single constants — trivial to change if the owner wants a different hue/key after seeing it live.
