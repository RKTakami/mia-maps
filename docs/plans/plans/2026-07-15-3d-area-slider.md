# 3D Area Slider Implementation Plan


**Goal:** A settings slider that raises the 3D view's coverage ceiling beyond 2048 blocks (up to 8192) by letting the sampler use coarser voxels, keeping the grid/perf budget constant.

**Architecture:** A new snapped `MapSettings.orbitAreaBlocks` drives `OrbitView`'s zoom ceiling; `OrbitScene` is allowed a coarser LOD (level 6) so the grid stays at `G_MAX`; `VoxelCloud` gains the downsample-from-finer fallback so coarse levels aren't empty.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Build: `$env:JAVA_HOME="D:\Users\dev\VSCode-Projects\MIA map mod project\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`.

**Spec:** `docs/plans/specs/2026-07-15-3d-area-slider-design.md`

---

## File Structure

- Modify: `map/MapSettings.java` — `orbitAreaBlocks`, `ORBIT_AREA_STEPS`, snapping setter.
- Modify: `map/MapConfig.java` — snap/default guard on load.
- Modify: `map/VoxelCloud.java` — downsample-from-finer fallback in `acquireFinest`.
- Modify: `map/OrbitScene.java` — `ORBIT_MAX_LVL = 6`; public `maxZoom(areaBlocks)`.
- Modify: `client/OrbitView.java` — zoom ceiling from the setting + clamp live zoom.
- Modify: `client/MapSettingsScreen.java` — the slider.
- Test: `test/.../MapSettingsTest.java` — snapping/clamping.

---

## Task 1: `MapSettings.orbitAreaBlocks` + snapping + config guard

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/map/MapConfig.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `MapSettingsTest.java`:

```java
    @Test
    void orbitAreaDefaultsTo2048() {
        assertEquals(2048, new MapSettings().orbitAreaBlocks);
    }

    @Test
    void orbitAreaSnapsToNearestStep() {
        MapSettings s = new MapSettings();
        s.setOrbitAreaBlocks(2048);
        assertEquals(2048, s.orbitAreaBlocks);   // exact step kept
        s.setOrbitAreaBlocks(3000);
        assertEquals(2048, s.orbitAreaBlocks);   // nearer 2048 than 4096
        s.setOrbitAreaBlocks(5000);
        assertEquals(4096, s.orbitAreaBlocks);   // nearer 4096 than 8192
    }

    @Test
    void orbitAreaClampsOutOfRange() {
        MapSettings s = new MapSettings();
        s.setOrbitAreaBlocks(0);
        assertEquals(1024, s.orbitAreaBlocks);   // below the lowest step
        s.setOrbitAreaBlocks(99999);
        assertEquals(8192, s.orbitAreaBlocks);   // above the highest step
    }
```

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `.\gradlew test --tests com.mia.aperture.map.MapSettingsTest`
Expected: FAIL to compile (`orbitAreaBlocks` / `setOrbitAreaBlocks` do not exist).

- [ ] **Step 3: Add the field, steps, and snapping setter**

In `MapSettings.java`, next to the other orbit/safe-drop settings (after `safeDropBlocks` and its MIN/MAX constants):

```java
    // How much area (blocks across) the 3D view may cover at full zoom-out. Wider settings use
    // coarser voxels so the sampled grid — and therefore performance — stays about the same.
    public int orbitAreaBlocks = 2048;

    public static final int[] ORBIT_AREA_STEPS = {1024, 2048, 4096, 8192};

    // Snaps to the nearest allowed step (also clamps out-of-range/legacy values).
    public void setOrbitAreaBlocks(int blocks) {
        int best = ORBIT_AREA_STEPS[0];
        int bestD = Integer.MAX_VALUE;
        for (int step : ORBIT_AREA_STEPS) {
            int d = Math.abs(step - blocks);
            if (d < bestD) { bestD = d; best = step; }
        }
        this.orbitAreaBlocks = best;
    }
```

- [ ] **Step 4: Guard the value on config load**

In `MapConfig.fromJson`, beside the existing `setSafeDropBlocks` guard:

```java
            s.setOrbitAreaBlocks(s.orbitAreaBlocks == 0 ? 2048 : s.orbitAreaBlocks);
```

(0 means the field was absent from a pre-existing config → default to 2048, preserving today's behaviour.)

- [ ] **Step 5: Run the tests to confirm they pass**

Run: `.\gradlew test --tests com.mia.aperture.map.MapSettingsTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/map/MapConfig.java src/test/java/com/mia/aperture/map/MapSettingsTest.java
git commit -m "feat(3d): orbitAreaBlocks setting with snapped steps + config guard"
```

---

## Task 2: `VoxelCloud` downsample-from-finer fallback

Without this, coarse levels (5-6) return null where Voxy lacks aggregates and the wide 3D view renders empty.

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/VoxelCloud.java`

- [ ] **Step 1: Replace `acquireFinest` with the coarser-then-finer chain**

Replace the existing method:

```java
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
```

with:

```java
    // This level or coarser (upsampled); if neither exists, synthesize it by downsampling finer
    // levels — Voxy often lacks coarse aggregates, which would otherwise leave a wide (coarse-LOD)
    // 3D view empty. Mirrors MapWorker.acquireFinest. Null if no data at all.
    private static long[] acquireFinest(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch) {
        long[] direct = acquireCoarser(engine, lvl, sx, secY, sz, scratch);
        if (direct != null) return direct;
        return synthesizeFromFiner(engine, lvl, sx, secY, sz, scratch, 0);
    }

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

    // Build this coarse section from the 8 child sections one level finer (recursive, bounded).
    // Each child is mip'd into its octant IMMEDIATELY, before the next acquire can clobber
    // `scratch` (acquireCoarser may return an alias of it).
    private static long[] synthesizeFromFiner(WorldEngine engine, int lvl, int sx, int secY, int sz,
                                              long[] scratch, int depth) {
        if (lvl <= 0 || depth >= MAX_FINER_DEPTH) return null;
        long[] out = null;
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    int cx = (sx << 1) + dx, cy = (secY << 1) + dy, cz = (sz << 1) + dz;
                    long[] child = acquireCoarser(engine, lvl - 1, cx, cy, cz, scratch);
                    if (child == null) {
                        child = synthesizeFromFiner(engine, lvl - 1, cx, cy, cz, scratch, depth + 1);
                    }
                    if (child == null) continue;
                    if (out == null) out = new long[32 * 32 * 32];
                    LodUpsampler.mipInto(out, child, dx, dy, dz);
                }
            }
        }
        return out;
    }
```

- [ ] **Step 2: Add the depth bound constant**

Next to `MAX_FALLBACK_K`:

```java
    // How many levels FINER we'll synthesize a coarse section from when Voxy lacks the aggregate.
    private static final int MAX_FINER_DEPTH = 2;
```

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL. (`LodUpsampler.mipInto` is already public and unit-tested from the 2D map fix.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/map/VoxelCloud.java
git commit -m "fix(3d): synthesize coarse sections by downsampling finer Voxy LODs"
```

---

## Task 3: `OrbitScene` — coarser LOD ceiling + max-zoom helper

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/OrbitScene.java`

- [ ] **Step 1: Add the 3D level ceiling constant**

Next to `G_MAX`:

```java
    // The 3D view may go coarser than the 2D map's MapGeometry.MAX_LVL (4): level 6 = 64-block
    // voxels, which keeps the grid at G_MAX while covering 8192 blocks. Do NOT raise
    // MapGeometry.MAX_LVL — that governs the 2D map's display level.
    private static final int ORBIT_MAX_LVL = 6;
```

- [ ] **Step 2: Use it when choosing the LOD**

In `buildFrame`, replace:

```java
        int lvl = 0;
        while ((extentXZ >> lvl) > G_MAX && lvl < MapGeometry.MAX_LVL) lvl++;
```

with:

```java
        int lvl = 0;
        while ((extentXZ >> lvl) > G_MAX && lvl < ORBIT_MAX_LVL) lvl++;
```

- [ ] **Step 3: Expose the zoom ceiling derived from an area setting**

Add beside `cameraDistance`:

```java
    // Highest zoom that keeps the sampled area within `areaBlocks` (extentXZ = EXTENT * zoom).
    public static double maxZoom(int areaBlocks) {
        return Math.max(1.0, areaBlocks / (double) EXTENT);
    }
```

- [ ] **Step 4: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL. (`OrbitScene` and `MapGeometry` share the `com.mia.aperture.map` package, so there is no import to clean up after dropping the `MapGeometry.MAX_LVL` reference.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/OrbitScene.java
git commit -m "feat(3d): allow coarser orbit LOD (level 6) + maxZoom helper"
```

---

## Task 4: `OrbitView` — zoom ceiling from the setting

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`

- [ ] **Step 1: Clamp the scroll zoom to the configured ceiling**

Replace:

```java
        zoom *= verticalAmount > 0 ? 0.85 : 1.18;
        zoom = Math.max(0.15, Math.min(16.0, zoom)); // 16 -> ~2048-block extent (~1/8 layer, the grid ceiling)
```

with:

```java
        zoom *= verticalAmount > 0 ? 0.85 : 1.18;
        // Ceiling follows the "3D Area" setting: zoom = area / EXTENT (2048 -> 16, 8192 -> 64).
        zoom = Math.max(0.15, Math.min(
                OrbitScene.maxZoom(MiaApertureModClient.mapSettings.orbitAreaBlocks), zoom));
```

- [ ] **Step 2: Clamp the live zoom when the setting is lowered**

At the top of `render(...)`, before the camera is built, add:

```java
        double zMax = OrbitScene.maxZoom(MiaApertureModClient.mapSettings.orbitAreaBlocks);
        if (zoom > zMax) zoom = zMax;
```

(so reducing the setting while zoomed out can't leave the view beyond the new ceiling).

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/OrbitView.java
git commit -m "feat(3d): zoom-out ceiling follows the 3D Area setting"
```

---

## Task 5: The slider in Settings

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Add the slider**

In `init`, immediately after the **Orbit quality** button row, add (matching the existing minimap-size slider pattern; sliders do not `persist()` per-drag — `removed()` persists on close):

```java
        addScroll(new AbstractSliderButton(cx - 100, 0, 200, 20,
                orbitAreaLabel(), orbitAreaToValue(settings().orbitAreaBlocks)) {
            @Override protected void updateMessage() { setMessage(orbitAreaLabel()); }
            @Override protected void applyValue() {
                int n = MapSettings.ORBIT_AREA_STEPS.length;
                int idx = (int) Math.round(this.value * (n - 1));
                settings().setOrbitAreaBlocks(MapSettings.ORBIT_AREA_STEPS[idx]);
            }
        }, r++);
```

- [ ] **Step 2: Add the label + value helpers**

Beside `sizeToValue` / `sizeLabel`:

```java
    private static double orbitAreaToValue(int blocks) {
        int[] steps = MapSettings.ORBIT_AREA_STEPS;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == blocks) return i / (double) (steps.length - 1);
        }
        return 1.0 / (steps.length - 1); // fall back to the 2048 step
    }

    private static Component orbitAreaLabel() {
        return Component.literal("3D Area: " + settings().orbitAreaBlocks + " blocks");
    }
```

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(3d): 3D Area slider in map settings"
```

---

## Task 6: Full build, install, verify

- [ ] **Step 1: Full test + build**

Run: `$env:JAVA_HOME="D:\Users\dev\VSCode-Projects\MIA map mod project\libs\jdk21\jdk-21.0.11+10"; .\gradlew clean test build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Install**

```bash
cp -v "build/libs/mia-maps-0.1.7-beta.jar" "/c/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```
(Version bump to 0.1.8 happens at release time, after owner verification.)

- [ ] **Step 3: Owner verifies in-game**

- Settings shows a **"3D Area: 2048 blocks"** slider; dragging snaps through 1024 / 2048 / 4096 / 8192.
- At **2048** the 3D view behaves exactly as before (no regression).
- At **4096 / 8192**, scrolling out reaches noticeably further; terrain is chunkier but **not empty** (the downsample fallback working) and the framerate is comparable.
- **Drill-down still works:** zoom out wide → right-click a distant region → zoom in → fine detail there → `R` recentres.
- Lowering the slider while zoomed out snaps the view back within the new ceiling.
- The setting survives a restart (persisted on settings-screen close).

- [ ] **Step 4: Report.** If good, cut **v0.1.8-beta** (bump `gradle.properties`, clean build, install, `gh release` prerelease, push) and append its notes to the pending Modrinth changelog in `docs/modrinth/listing.md`. If issues, capture the symptom and return to the relevant task.

---

## Notes

- If a wide setting still shows gaps, `MAX_FINER_DEPTH` (2) in `VoxelCloud` is the dial — Voxy would have data more than 2 levels finer than the requested one. Watch worker cost if raised.
- `ORBIT_AREA_STEPS`, `ORBIT_MAX_LVL`, and the 0.15 minimum zoom are all single-point changes if the owner wants a different range later.
