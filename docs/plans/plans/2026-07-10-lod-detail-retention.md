# LOD Detail Retention Implementation Plan


**Goal:** Retain more map detail when zooming out — per-tile finest-available Voxy LOD with coarse upsampled fallback (no holes), a display grid capped at 8-block cells (never 16-block soup), finer LOD held longer, and a coverage-first zoom-out floor.

**Architecture:** A pure `LodUpsampler` replicates a coarse 32³ section's octant into the display grid. `MapWorker.renderJob` walks from the display level to coarser Voxy levels until data is found, upsampling. `MapGeometry.lvlForView` caps at level 3 and holds finer levels to larger views. The fullscreen min-zoom is raised for a legible-but-wide coverage-first extreme.

**Tech Stack:** Fabric 1.21.11, Java 21, JUnit 5.

**Build/test (PowerShell):** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Develop directly on `main` (project convention; no worktrees).

---

### Task 1: LodUpsampler (pure octant upsampling)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/LodUpsampler.java`
- Test: `src/test/java/com/mia/aperture/map/LodUpsamplerTest.java`

- [ ] **Step 1: Write the failing test**

Create `LodUpsamplerTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodUpsamplerTest {

    private static int idx(int x, int y, int z) { return (y << 10) | (z << 5) | x; }

    @Test
    void uniformCoarseGivesUniformFine() {
        long[] coarse = new long[32 * 32 * 32];
        java.util.Arrays.fill(coarse, 7L);
        long[] fine = LodUpsampler.upsampleOctant(coarse, 0, 0, 0, 1);
        assertEquals(32 * 32 * 32, fine.length);
        for (long v : fine) assertEquals(7L, v);
    }

    @Test
    void k1ReplicatesSelectedOctantAlongX() {
        long[] coarse = new long[32 * 32 * 32];
        for (int i = 0; i < coarse.length; i++) coarse[i] = i;
        // octant sx=1 selects the +X half (coarse x in [16,32)); fine x -> coarse 16 + x/2
        long[] fine = LodUpsampler.upsampleOctant(coarse, 1, 0, 0, 1);
        assertEquals(coarse[idx(16, 0, 0)], fine[idx(0, 0, 0)]);
        assertEquals(coarse[idx(16, 0, 0)], fine[idx(1, 0, 0)]);
        assertEquals(coarse[idx(17, 0, 0)], fine[idx(2, 0, 0)]);
        assertEquals(coarse[idx(17, 0, 0)], fine[idx(3, 0, 0)]);
    }

    @Test
    void octantOffsetSelectsDifferentSubcube() {
        long[] coarse = new long[32 * 32 * 32];
        for (int i = 0; i < coarse.length; i++) coarse[i] = i;
        long[] lo = LodUpsampler.upsampleOctant(coarse, 0, 0, 0, 1); // -X half
        long[] hi = LodUpsampler.upsampleOctant(coarse, 1, 0, 0, 1); // +X half
        assertEquals(coarse[idx(0, 0, 0)], lo[idx(0, 0, 0)]);
        assertEquals(coarse[idx(16, 0, 0)], hi[idx(0, 0, 0)]);
        assertNotEquals(lo[idx(0, 0, 0)], hi[idx(0, 0, 0)]);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test`
Expected: FAIL — `LodUpsampler` does not exist.

- [ ] **Step 3: Create LodUpsampler**

```java
package com.mia.aperture.map;

public final class LodUpsampler {
    private LodUpsampler() {}

    // Fill a 32^3 display-level section from a coarser section k levels up. The coarse
    // section covers 2^k display sections per axis; this display section occupies octant
    // (sx & (2^k-1), secY & (2^k-1), sz & (2^k-1)) of it, and each coarse cell in that
    // (32>>k)^3 sub-cube replicates to a 2^k cube of fine cells. Voxel index layout
    // matches MapTileRenderer.cellAt: (y<<10)|(z<<5)|x.
    public static long[] upsampleOctant(long[] coarse, int sx, int secY, int sz, int k) {
        int scale = 1 << k;
        int span = 32 >> k;
        int ox = (sx & (scale - 1)) * span;
        int oy = (secY & (scale - 1)) * span;
        int oz = (sz & (scale - 1)) * span;
        long[] out = new long[32 * 32 * 32];
        for (int y = 0; y < 32; y++) {
            int cy = oy + (y >> k);
            for (int z = 0; z < 32; z++) {
                int cz = oz + (z >> k);
                for (int x = 0; x < 32; x++) {
                    int cx = ox + (x >> k);
                    out[(y << 10) | (z << 5) | x] = coarse[(cy << 10) | (cz << 5) | cx];
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/LodUpsampler.java src/test/java/com/mia/aperture/map/LodUpsamplerTest.java
git commit -m "feat(map): pure LodUpsampler for coarse-to-fine octant replication"
```

---

### Task 2: Cap display LOD at 3 + hold finer LOD longer

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/MapGeometryTest.java`

- [ ] **Step 1: Update the lvlForView tests**

In `MapGeometryTest.java`, replace `lvlForViewPicksZeroForSmallViews` and
`lvlForViewScalesUpAndClamps` with:

```java
    @Test
    void lvlForViewPicksZeroForSmallViews() {
        assertEquals(0, MapGeometry.lvlForView(256));
        assertEquals(0, MapGeometry.lvlForView(512));
        assertEquals(0, MapGeometry.lvlForView(768));
    }

    @Test
    void lvlForViewScalesUpAndClampsAtDisplayMax() {
        assertEquals(1, MapGeometry.lvlForView(769));
        assertEquals(1, MapGeometry.lvlForView(1536));
        assertEquals(2, MapGeometry.lvlForView(1537));
        assertEquals(2, MapGeometry.lvlForView(3072));
        assertEquals(3, MapGeometry.lvlForView(3073));
        assertEquals(3, MapGeometry.lvlForView(8192));
        assertEquals(3, MapGeometry.lvlForView(100000)); // never returns 4 (display cap)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — current thresholds (512-step, clamp 4) give `lvlForView(768)==1`, `lvlForView(100000)==4`.

- [ ] **Step 3: Add the constants and update lvlForView**

In `MapGeometry.java`, the constants at the top currently read:

```java
    public static final int MAX_LVL = 4;
    public static final int TILE_CELLS = 32;
    public static final int BAND_QUANT = 16;
```

Replace with (add `MAX_DISPLAY_LVL` and `DETAIL_TILES`):

```java
    public static final int MAX_LVL = 4;
    public static final int MAX_DISPLAY_LVL = 3;
    public static final int TILE_CELLS = 32;
    public static final int DETAIL_TILES = 24;
    public static final int BAND_QUANT = 16;
```

Then replace `lvlForView`:

```java
    // *DETAIL_TILES keeps the view within DETAIL_TILES tiles across; capped at
    // MAX_DISPLAY_LVL (8-block cells) so a fully zoomed-out view stays legible.
    public static int lvlForView(int blocksAcross) {
        int lvl = 0;
        while (lvl < MAX_DISPLAY_LVL && (TILE_CELLS << lvl) * DETAIL_TILES < blocksAcross) {
            lvl++;
        }
        return lvl;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapGeometry.java src/test/java/com/mia/aperture/map/MapGeometryTest.java
git commit -m "feat(map): cap display LOD at level 3 and hold finer LOD longer"
```

---

### Task 3: Per-section finest-available fallback + bigger cache

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapWorker.java`

No unit test (needs a live `WorldEngine`); the pure upsampling is covered by Task 1.

- [ ] **Step 1: Enlarge the tile cache**

In `MapWorker.java`, the cache field currently reads:

```java
    public static final MapTileCache CACHE = new MapTileCache(1024);
```

Change the capacity to 4096 (holds the larger visible tile set without thrashing):

```java
    public static final MapTileCache CACHE = new MapTileCache(4096);
```

- [ ] **Step 2: Add the fallback constant + helper**

In `MapWorker.java`, add this constant near the other private static fields (e.g. after
`private static volatile Thread thread;`):

```java
    private static final int MAX_FALLBACK_K = 4;
```

Add this private static method (e.g. just above `renderJob`):

```java
    // Return a 32^3 section for the display-level coords, falling back to coarser Voxy
    // levels (upsampled) when the fine data is missing. Returns null if nothing exists.
    private static long[] acquireFinest(WorldEngine engine, int lvl, int sx, int secY, int sz, long[] scratch) {
        for (int k = 0; k <= MAX_FALLBACK_K; k++) {
            WorldSection cs = engine.acquireIfExists(lvl + k, sx >> k, secY >> k, sz >> k);
            if (cs == null) continue;
            try {
                cs.copyDataTo(scratch);
                return k == 0 ? scratch.clone() : LodUpsampler.upsampleOctant(scratch, sx, secY, sz, k);
            } finally {
                cs.release();
            }
        }
        return null;
    }
```

- [ ] **Step 3: Use the fallback in renderJob**

In `renderJob`, the section-acquisition loop currently reads:

```java
        long[][] sections = new long[count][];
        for (int i = 0; i < count; i++) {
            int secY = topSecY - i;
            WorldSection section = job.engine().acquireIfExists(lvl, key.sx(), secY, key.sz());
            if (section == null) continue;
            try {
                section.copyDataTo(scratch);
                sections[i] = scratch.clone();
            } finally {
                section.release();
            }
        }
```

Replace it with:

```java
        long[][] sections = new long[count][];
        for (int i = 0; i < count; i++) {
            int secY = topSecY - i;
            sections[i] = acquireFinest(job.engine(), lvl, key.sx(), secY, key.sz(), scratch);
        }
```

- [ ] **Step 4: Build to verify it compiles**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL (all existing tests still pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapWorker.java
git commit -m "feat(map): per-section finest-available LOD fallback with coarse upsampling"
```

---

### Task 4: Coverage-first zoom-out floor

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

No unit test (input handler); verified in-game.

- [ ] **Step 1: Add the MIN_ZOOM constant**

In `AbyssWorldMapScreen.java`, next to the other fields at the top of the class
(e.g. after `private int lastBlocksAcrossZ = 1;`), add:

```java
    private static final float MIN_ZOOM = 0.03f;
```

- [ ] **Step 2: Use it in the zoom clamp**

In `mouseScrolled`, the zoom clamp currently reads:

```java
            if (AbyssMapState.mapZoom < 0.0125f) AbyssMapState.mapZoom = 0.0125f;
            if (AbyssMapState.mapZoom > 20.0f) AbyssMapState.mapZoom = 20.0f;
```

Replace the lower clamp with the constant:

```java
            if (AbyssMapState.mapZoom < MIN_ZOOM) AbyssMapState.mapZoom = MIN_ZOOM;
            if (AbyssMapState.mapZoom > 20.0f) AbyssMapState.mapZoom = 20.0f;
```

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(map): coverage-first zoom-out floor (MIN_ZOOM 0.03)"
```

---

### Task 5: Version bump, build, install

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change `mod_version=1.9.1` to `mod_version=1.10.0`.

- [ ] **Step 2: Build + all tests**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass (63 existing + 3 new LodUpsampler = 66).

- [ ] **Step 3: Install to the Modrinth instance (swap old jar for the mia-maps jar)**

```powershell
$dest="<mods-dir>"
Remove-Item "$dest\mia-aperture-mod-*.jar","$dest\mia-maps-*.jar" -ErrorAction SilentlyContinue
Copy-Item "build\libs\mia-maps-1.10.0.jar" $dest
```
(This removes the old `mia-aperture-mod-1.9.1.jar` so there is no duplicate-mod conflict. If the jar is locked, close Minecraft first.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "chore: bump version to 1.10.0"
```

---

## In-game verification (after install)

1. Open the fullscreen map (`M`) and zoom out slowly: detail persists further before it coarsens (crisp past the old 512-block cutoff).
2. Zoom further: distant/unvisited areas show coarse-but-present (blocky, not black holes).
3. Fully zoomed out: the view tops out at legible 8-block cells over a wide area, not the 16-block soup, and won't zoom past the coverage-first floor.
4. Near zoom and the minimap look unchanged; no lag returns while panning/zooming.
