# Data-Driven Map Renderer Implementation Plan

> **Project convention:** work directly on `main` in `<project-root>` — NO worktrees, NO branches (owner rule).

**Goal:** Replace the Voxy-viewport map rendering with a CPU tile renderer that reads Voxy's world database, per `docs/plans/specs/2026-07-07-data-driven-map-design.md`.

**Architecture:** A pure tile rasterizer (`MapTileRenderer`) turns snapshots of Voxy `WorldSection` data into ARGB tiles on one worker thread; an LRU cache holds them; a compositor assembles visible tiles into `DynamicTexture`s (512² map, 128² HUD) that the existing screens blit. The old FBO/viewport path is deleted.

**Tech Stack:** Java 21, Fabric/loom, Mojang mappings (MC 1.21.11), Voxy MIA-edition API (`WorldEngine`, `WorldSection`, `Mapper`), JUnit 5 (new).

**Build/test commands (always with the vendored JDK):**
```powershell
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew build   # full build
.\gradlew test    # unit tests only
```

**Verified API facts (do not re-derive):**
- `WorldSection.getIndex(x,y,z) = ((y&31)<<10)|((z&31)<<5)|(x&31)`; 32³ cells; `copyDataTo(long[32768])`; `acquireIfExists(lvl,x,y,z)` may return null; always release in finally.
- `Mapper.getBlockId(long)`, `getBlockStateFromBlockId(int)`, `getBlockStateOpacity(long)`, mapper obtained via `worldEngine.getMapper()`.
- Shift math (world→Voxy DB space): `sector = AbyssUtil.getSection(worldX)`; `shiftedX = worldX - (sector<<14)`; `shiftedY = worldY + (240 - sector*30)*16`; Z unshifted.
- MC: `DynamicTexture(String label, int w, int h, boolean clear)`, `.getPixels()` → `NativeImage`, `.upload()`; `NativeImage.setPixel(x,y,argb)` (if colors come out red/blue swapped in-game, switch to `setPixelABGR` — single call site); `BlockState.getMapColor(BlockGetter, BlockPos).col` (RGB); `state.getFluidState().is(FluidTags.WATER)`.
- GUI blit (fixed v1.1.17): `guiGraphics.blit(id, x1, y1, x2, y2, u0, u1, v0, v1)`. CPU-composed images are already top-down: use `u0=0,u1=1,v0=0,v1=1` (NO V flip — unlike the old FBO).

---

### Task 1: JUnit setup + shipped-jar API verification

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Verify the shipped Voxy jar matches the clone for the APIs we use**

Run (Git Bash):
```bash
S=/tmp/voxycheck; mkdir -p $S && cd $S
unzip -o -q "<mods-dir>/voxy-mia-edition-2.15-fcd6dda.jar" "me/cortex/voxy/common/world/WorldSection.class" "me/cortex/voxy/common/world/WorldEngine.class" "me/cortex/voxy/common/world/other/Mapper.class"
JP="<project-root>/libs/jdk21/jdk-21.0.11+10/bin/javap.exe"
"$JP" -p -c me/cortex/voxy/common/world/WorldSection.class | grep -A 8 "getIndex"
"$JP" -p me/cortex/voxy/common/world/WorldSection.class | grep -E "copyDataTo|release|acquire"
"$JP" -p me/cortex/voxy/common/world/WorldEngine.class | grep -E "acquireIfExists|getMapper"
"$JP" -p me/cortex/voxy/common/world/other/Mapper.class | grep -E "getBlockId|getBlockStateFromBlockId|getBlockStateOpacity"
```
Expected: `getIndex` bytecode shows `<<10` and `<<5` shifts (y-major); all listed methods exist with the same signatures as the clone. If anything differs, STOP and update the spec/plan before continuing.

- [ ] **Step 2: Add JUnit 5 to build.gradle**

Append to the `dependencies` block:
```gradle
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```
Add at file end:
```gradle
tasks.named('test') {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Verify the test task runs (no tests yet)**

Run: `.\gradlew test` — Expected: `BUILD SUCCESSFUL` (NO-SOURCE for test).

- [ ] **Step 4: Commit**
```bash
git add build.gradle
git commit -m "build: add JUnit 5 test infrastructure"
```

---

### Task 2: Map geometry primitives (pure)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapMode.java`
- Create: `src/main/java/com/mia/aperture/map/TileKey.java`
- Create: `src/main/java/com/mia/aperture/map/MapGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/MapGeometryTest.java`

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/mia/aperture/map/MapGeometryTest.java`:
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapGeometryTest {

    @Test
    void lvlForViewPicksZeroForSmallViews() {
        assertEquals(0, MapGeometry.lvlForView(256));
        assertEquals(0, MapGeometry.lvlForView(512));
    }

    @Test
    void lvlForViewScalesUpAndClamps() {
        assertEquals(1, MapGeometry.lvlForView(1024));
        assertEquals(2, MapGeometry.lvlForView(1100));
        assertEquals(4, MapGeometry.lvlForView(8192));
        assertEquals(4, MapGeometry.lvlForView(100000));
    }

    @Test
    void tileSpanBlocks() {
        assertEquals(32, MapGeometry.tileSpanBlocks(0));
        assertEquals(512, MapGeometry.tileSpanBlocks(4));
    }

    @Test
    void blockToTileFloorsNegatives() {
        assertEquals(0, MapGeometry.blockToTile(0, 0));
        assertEquals(0, MapGeometry.blockToTile(31, 0));
        assertEquals(-1, MapGeometry.blockToTile(-1, 0));
        assertEquals(-1, MapGeometry.blockToTile(-512, 4));
        assertEquals(-2, MapGeometry.blockToTile(-513, 4));
    }

    @Test
    void bandKeyQuantizesTo16() {
        assertEquals(MapGeometry.bandKey(100), MapGeometry.bandKey(111));
        assertNotEquals(MapGeometry.bandKey(100), MapGeometry.bandKey(116));
        assertEquals(MapGeometry.bandKey(-1), MapGeometry.bandKey(-16));
    }

    @Test
    void shiftMathMatchesVerifiedLiveValues() {
        // Live-verified 2026-07-06: worldX=65399, sector 4 -> shifted ~ -137;
        // worldY=-137 sector 4 -> shifted -137 + (240-120)*16 = 1783
        int sector = 4;
        assertEquals(65399 - (sector << 14), MapGeometry.shiftX(65399, sector));
        assertEquals(-137 + (240 - sector * 30) * 16, MapGeometry.shiftY(-137, sector));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test` — Expected: FAIL, `MapGeometry` does not exist.

- [ ] **Step 3: Implement**

`src/main/java/com/mia/aperture/map/MapMode.java`:
```java
package com.mia.aperture.map;

public enum MapMode {
    RELIEF,
    VANILLA
}
```

`src/main/java/com/mia/aperture/map/TileKey.java`:
```java
package com.mia.aperture.map;

public record TileKey(int lvl, int sx, int sz, int bandKey, MapMode mode) {
}
```

`src/main/java/com/mia/aperture/map/MapGeometry.java`:
```java
package com.mia.aperture.map;

public final class MapGeometry {
    public static final int MAX_LVL = 4;
    public static final int TILE_CELLS = 32;
    public static final int BAND_QUANT = 16;

    private MapGeometry() {}

    public static int lvlForView(int blocksAcross) {
        int lvl = 0;
        while (lvl < MAX_LVL && (TILE_CELLS << lvl) * 16 < blocksAcross) {
            lvl++;
        }
        return lvl;
    }

    public static int tileSpanBlocks(int lvl) {
        return TILE_CELLS << lvl;
    }

    public static int blockToTile(int block, int lvl) {
        return Math.floorDiv(block, tileSpanBlocks(lvl));
    }

    public static int bandKey(int bandTopY) {
        return Math.floorDiv(bandTopY, BAND_QUANT);
    }

    public static int shiftX(int worldX, int sector) {
        return worldX - (sector << 14);
    }

    public static int shiftY(int worldY, int sector) {
        return worldY + (240 - sector * 30) * 16;
    }
}
```
Note `lvlForView`: `(32<<lvl)*16 >= blocksAcross` means the view fits in ≤16 tiles across (512 columns at 32 cells/tile) — equivalent to the spec's ≤~640-column rule.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test` — Expected: PASS (6 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map src/test
git commit -m "feat(map): geometry primitives - lvl selection, tile keys, shift math"
```

---

### Task 3: MapTileRenderer (pure rasterizer)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapColorSource.java`
- Create: `src/main/java/com/mia/aperture/map/MapTileRenderer.java`
- Test: `src/test/java/com/mia/aperture/map/MapTileRendererTest.java`

**Design:** input is the stack of section snapshots covering the band for one tile column,
top-to-bottom, plus the block Y of the top face of the first section. All Y values are in
Voxy shifted space. Output arrays are indexed `z * 32 + x`. Heights use
`Integer.MIN_VALUE` for "no surface found".

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/mia/aperture/map/MapTileRendererTest.java`:
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapTileRendererTest {

    private static final int STONE = 0xFF808080;
    private static final int WATER = 0xFF4040FF;

    // Mapping ids in tests: 0 = air, 1 = stone, 2 = water
    private final MapColorSource colors = new MapColorSource() {
        @Override public int baseColor(long id) { return id == 1 ? STONE : id == 2 ? WATER : 0; }
        @Override public boolean isWater(long id) { return id == 2; }
        @Override public boolean isOpaque(long id) { return id == 1 || id == 2; }
    };

    private static long[] emptySection() { return new long[32 * 32 * 32]; }

    private static void fillLayer(long[] section, int cellY, long id) {
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++)
                section[(cellY << 10) | (z << 5) | x] = id;
    }

    private static int idx(int x, int z) { return z * 32 + x; }

    @Test
    void findsFlatSurfaceAndHeight() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1); // stone at cellY 10
        int[] color = new int[1024];
        int[] height = new int[1024];
        // section top face at shifted Y 320 (base 288), cellSize 1, band covers it all
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(288 + 10, height[idx(5, 5)]);
        assertNotEquals(0, color[idx(5, 5)]);
    }

    @Test
    void emptyColumnIsTransparent() {
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{emptySection()}, 320, 320, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(0, color[idx(0, 0)]);
        assertEquals(Integer.MIN_VALUE, height[idx(0, 0)]);
    }

    @Test
    void bandClipsSurfacesAboveIt() {
        long[] sec = emptySection();
        fillLayer(sec, 30, 1); // high surface at 288+30=318
        fillLayer(sec, 5, 1);  // low surface at 293
        int[] color = new int[1024];
        int[] height = new int[1024];
        // band top 300: the 318 surface must be ignored, 293 found
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 300, 288, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(293, height[idx(0, 0)]);
    }

    @Test
    void nullSectionsAreSkipped() {
        long[] sec = emptySection();
        fillLayer(sec, 0, 1); // block Y 288 in the SECOND section (base 256)
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{null, sec}, 352, 352, 256, 1, MapMode.VANILLA, colors, color, height);
        assertEquals(256, height[idx(0, 0)]);
    }

    @Test
    void vanillaModeStepsBrightnessBySlope() {
        long[] sec = emptySection();
        fillLayer(sec, 10, 1);
        // one column higher: at z=16 raise to 11
        for (int x = 0; x < 32; x++) sec[(11 << 10) | (16 << 5) | x] = 1;
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, color, height);
        int flat = color[idx(5, 5)];
        int atStep = color[idx(5, 16)];      // higher than its north neighbor -> brighter
        int belowStep = color[idx(5, 17)];   // lower than its north neighbor -> darker
        assertTrue((atStep & 0xFF) > (flat & 0xFF));
        assertTrue((belowStep & 0xFF) < (flat & 0xFF));
    }

    @Test
    void reliefModeBrightensSouthFacingSlopes() {
        long[] sec = emptySection();
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++)
                sec[((z) << 10) | (z << 5) | x] = 1; // height rises with z (southward up)
        int[] color = new int[1024];
        int[] height = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.RELIEF, colors, color, height);
        int slope = color[idx(5, 10)];
        // flat reference tile
        long[] flatSec = emptySection();
        fillLayer(flatSec, 10, 1);
        int[] flatColor = new int[1024];
        MapTileRenderer.renderTile(new long[][]{flatSec}, 320, 320, 288, 1, MapMode.RELIEF, colors, flatColor, new int[1024]);
        assertTrue((slope & 0xFF) > (flatColor[idx(5, 10)] & 0xFF));
    }

    @Test
    void waterBlendsAndDarkensWithDepth() {
        long[] sec = emptySection();
        fillLayer(sec, 20, 2);  // water surface
        fillLayer(sec, 19, 2);
        fillLayer(sec, 18, 2);
        fillLayer(sec, 17, 1);  // floor
        long[] deep = emptySection();
        fillLayer(deep, 20, 2);
        for (int y = 5; y < 20; y++) fillLayer(deep, y, 2);
        fillLayer(deep, 4, 1);
        int[] shallow = new int[1024];
        int[] deepC = new int[1024];
        MapTileRenderer.renderTile(new long[][]{sec}, 320, 320, 288, 1, MapMode.VANILLA, colors, shallow, new int[1024]);
        MapTileRenderer.renderTile(new long[][]{deep}, 320, 320, 288, 1, MapMode.VANILLA, colors, deepC, new int[1024]);
        // deeper water is darker, both are water-ish (blue channel dominant)
        assertTrue((deepC[idx(0, 0)] & 0xFF) <= (shallow[idx(0, 0)] & 0xFF));
        assertNotEquals(0, shallow[idx(0, 0)]);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test` — Expected: FAIL, `MapColorSource`/`MapTileRenderer` do not exist.

- [ ] **Step 3: Implement**

`src/main/java/com/mia/aperture/map/MapColorSource.java`:
```java
package com.mia.aperture.map;

public interface MapColorSource {
    int baseColor(long mappingId);
    boolean isWater(long mappingId);
    boolean isOpaque(long mappingId);
}
```

`src/main/java/com/mia/aperture/map/MapTileRenderer.java`:
```java
package com.mia.aperture.map;

public final class MapTileRenderer {
    private static final int CELLS = 32;
    private static final float RELIEF_SLOPE_K = 0.04f;
    private static final float RELIEF_MIN = 0.55f;
    private static final float RELIEF_MAX = 1.35f;
    private static final int VANILLA_HIGH = 255;
    private static final int VANILLA_NORMAL = 220;
    private static final int VANILLA_LOW = 180;
    private static final int WATER_FLOOR_SCAN_CELLS = 32;

    private MapTileRenderer() {}

    // sections: top-to-bottom stack covering the band; entries may be null (missing).
    // topSectionTopY: shifted block Y of the TOP face of sections[0].
    // sectionsBaseY: shifted block Y of the BOTTOM face of the LAST section
    //   (= topSectionTopY - sections.length * CELLS * cellSize).
    // Caller passes bandTopY (inclusive scan start) and the base for height reporting.
    public static void renderTile(long[][] sections, int topSectionTopY, int bandTopY,
                                  int stackBaseY, int cellSize, MapMode mode,
                                  MapColorSource colors, int[] outColor, int[] outHeight) {
        long[] surfaceId = new long[CELLS * CELLS];
        int totalCellsY = sections.length * CELLS;

        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int out = z * CELLS + x;
                outColor[out] = 0;
                outHeight[out] = Integer.MIN_VALUE;
                surfaceId[out] = 0;

                int startCell = Math.min(totalCellsY - 1,
                        Math.floorDiv(bandTopY - stackBaseY, cellSize));
                for (int cy = startCell; cy >= 0; cy--) {
                    long id = cellAt(sections, cy, x, z, totalCellsY);
                    if (id == 0 || !colors.isOpaque(id)) continue;
                    surfaceId[out] = id;
                    outHeight[out] = stackBaseY + cy * cellSize;
                    break;
                }
            }
        }

        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int out = z * CELLS + x;
                long id = surfaceId[out];
                if (id == 0) continue;
                int h = outHeight[out];

                int base;
                if (colors.isWater(id)) {
                    base = waterColor(sections, colors, x, z, h, stackBaseY, cellSize,
                            colors.baseColor(id), totalCellsY);
                } else {
                    base = colors.baseColor(id);
                }

                int hNorth = z > 0 ? outHeight[out - CELLS] : h;
                if (hNorth == Integer.MIN_VALUE) hNorth = h;

                if (mode == MapMode.VANILLA) {
                    int mult = h > hNorth ? VANILLA_HIGH : h < hNorth ? VANILLA_LOW : VANILLA_NORMAL;
                    outColor[out] = scale(base, mult / 255.0f);
                } else {
                    float b = 1.0f + RELIEF_SLOPE_K * (h - hNorth);
                    b = Math.max(RELIEF_MIN, Math.min(RELIEF_MAX, b));
                    outColor[out] = scale(base, b);
                }
            }
        }
    }

    private static long cellAt(long[][] sections, int cellY, int x, int z, int totalCellsY) {
        int fromTop = totalCellsY - 1 - cellY;
        int sectionIdx = fromTop / CELLS;
        long[] section = sections[sectionIdx];
        if (section == null) return 0;
        int localY = cellY % CELLS;
        return section[(localY << 10) | (z << 5) | x];
    }

    private static int waterColor(long[][] sections, MapColorSource colors, int x, int z,
                                  int surfaceY, int stackBaseY, int cellSize,
                                  int waterBase, int totalCellsY) {
        int surfaceCell = Math.floorDiv(surfaceY - stackBaseY, cellSize);
        int depthCells = WATER_FLOOR_SCAN_CELLS;
        int floorColor = 0;
        for (int d = 1; d <= WATER_FLOOR_SCAN_CELLS; d++) {
            int cy = surfaceCell - d;
            if (cy < 0) break;
            long id = cellAt(sections, cy, x, z, totalCellsY);
            if (id == 0) continue;
            if (!colors.isWater(id) && colors.isOpaque(id)) {
                depthCells = d;
                floorColor = colors.baseColor(id);
                break;
            }
        }
        float darken = Math.max(0.4f, 1.0f - 0.05f * Math.min(depthCells, 10));
        if (floorColor == 0) return scale(waterBase, darken);
        return scale(blend(floorColor, waterBase, 0.6f), darken);
    }

    private static int scale(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
```
Note the call sites in the tests pass `(sections, topSectionTopY, bandTopY, stackBaseY, cellSize, ...)` — `stackBaseY = topSectionTopY - sections.length*32*cellSize` always; the renderer trusts the caller.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test` — Expected: PASS (13 tests total). If the slope/water assertions fail on rounding, adjust constants only (not test intent).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map src/test
git commit -m "feat(map): pure tile rasterizer with relief and vanilla shading"
```

---

### Task 4: Tile cache + worker + Voxy data source

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapTileCache.java`
- Create: `src/main/java/com/mia/aperture/map/MapTile.java`
- Create: `src/main/java/com/mia/aperture/map/VoxyColorSource.java`
- Create: `src/main/java/com/mia/aperture/map/MapWorker.java`
- Test: `src/test/java/com/mia/aperture/map/MapTileCacheTest.java`

- [ ] **Step 1: Write the failing cache test**

`src/test/java/com/mia/aperture/map/MapTileCacheTest.java`:
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapTileCacheTest {

    private static TileKey key(int sx) {
        return new TileKey(0, sx, 0, 0, MapMode.RELIEF);
    }

    @Test
    void storesAndRetrieves() {
        MapTileCache cache = new MapTileCache(4);
        MapTile tile = new MapTile(new int[1024], new int[1024], 123L);
        cache.put(key(1), tile);
        assertSame(tile, cache.get(key(1)));
        assertNull(cache.get(key(2)));
    }

    @Test
    void evictsLeastRecentlyUsed() {
        MapTileCache cache = new MapTileCache(2);
        cache.put(key(1), new MapTile(new int[0], new int[0], 0));
        cache.put(key(2), new MapTile(new int[0], new int[0], 0));
        cache.get(key(1));
        cache.put(key(3), new MapTile(new int[0], new int[0], 0));
        assertNotNull(cache.get(key(1)));
        assertNull(cache.get(key(2)));
        assertNotNull(cache.get(key(3)));
    }

    @Test
    void clearEmptiesEverything() {
        MapTileCache cache = new MapTileCache(4);
        cache.put(key(1), new MapTile(new int[0], new int[0], 0));
        cache.clear();
        assertNull(cache.get(key(1)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test` — Expected: FAIL, classes missing.

- [ ] **Step 3: Implement cache and tile record**

`src/main/java/com/mia/aperture/map/MapTile.java`:
```java
package com.mia.aperture.map;

public record MapTile(int[] colors, int[] heights, long renderedAtMs) {
}
```

`src/main/java/com/mia/aperture/map/MapTileCache.java`:
```java
package com.mia.aperture.map;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MapTileCache {
    private final LinkedHashMap<TileKey, MapTile> map;

    public MapTileCache(int capacity) {
        this.map = new LinkedHashMap<>(capacity * 4 / 3, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TileKey, MapTile> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized MapTile get(TileKey key) {
        return this.map.get(key);
    }

    public synchronized void put(TileKey key, MapTile tile) {
        this.map.put(key, tile);
    }

    public synchronized void clear() {
        this.map.clear();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test` — Expected: PASS.

- [ ] **Step 5: Implement the Voxy-facing color source (no unit test — verified in-game)**

`src/main/java/com/mia/aperture/map/VoxyColorSource.java`:
```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;

public final class VoxyColorSource implements MapColorSource {
    private final Mapper mapper;
    private final BlockGetter level;
    private final ConcurrentHashMap<Integer, int[]> blockInfo = new ConcurrentHashMap<>();

    public VoxyColorSource(Mapper mapper, BlockGetter level) {
        this.mapper = mapper;
        this.level = level;
    }

    // info[0] = base ARGB (0 for air/none), info[1] = 1 if water, info[2] = 1 if opaque
    private int[] info(long mappingId) {
        int blockId = Mapper.getBlockId(mappingId);
        return this.blockInfo.computeIfAbsent(blockId, id -> {
            if (id == 0) return new int[]{0, 0, 0};
            try {
                BlockState state = this.mapper.getBlockStateFromBlockId(id);
                int col = state.getMapColor(this.level, BlockPos.ZERO).col;
                boolean water = state.getFluidState().is(FluidTags.WATER);
                boolean opaque = this.mapper.getBlockStateOpacity(mappingId) > 0 || water;
                int argb = col == 0 ? 0 : 0xFF000000 | col;
                return new int[]{argb, water ? 1 : 0, opaque ? 1 : 0};
            } catch (Throwable t) {
                return new int[]{0, 0, 0};
            }
        });
    }

    @Override
    public int baseColor(long mappingId) {
        return info(mappingId)[0];
    }

    @Override
    public boolean isWater(long mappingId) {
        return info(mappingId)[1] == 1;
    }

    @Override
    public boolean isOpaque(long mappingId) {
        return info(mappingId)[2] == 1 && info(mappingId)[0] != 0;
    }
}
```

- [ ] **Step 6: Implement the worker (no unit test — verified in-game)**

`src/main/java/com/mia/aperture/map/MapWorker.java`:
```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

public final class MapWorker {
    public static final MapTileCache CACHE = new MapTileCache(1024);

    private static final LinkedBlockingDeque<Job> QUEUE = new LinkedBlockingDeque<>();
    private static final Set<TileKey> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile Thread thread;

    private record Job(TileKey key, int bandTopY, int bandBottomY,
                       WorldEngine engine, MapColorSource colors, int generation) {}

    private MapWorker() {}

    // Called from the render thread. Returns the cached tile (possibly stale) or null,
    // enqueueing a render when missing or expired.
    public static MapTile request(TileKey key, int bandTopY, int bandBottomY,
                                  WorldEngine engine, MapColorSource colors, long maxAgeMs) {
        MapTile tile = CACHE.get(key);
        boolean fresh = tile != null
                && (maxAgeMs <= 0 || System.currentTimeMillis() - tile.renderedAtMs() < maxAgeMs);
        if (!fresh && PENDING.add(key)) {
            ensureThread();
            QUEUE.addFirst(new Job(key, bandTopY, bandBottomY, engine, colors, GENERATION.get()));
        }
        return tile;
    }

    public static void reset() {
        GENERATION.incrementAndGet();
        QUEUE.clear();
        PENDING.clear();
        CACHE.clear();
    }

    private static void ensureThread() {
        if (thread != null) return;
        synchronized (MapWorker.class) {
            if (thread != null) return;
            Thread t = new Thread(MapWorker::runLoop, "MIA-Map-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            thread = t;
            t.start();
        }
    }

    private static void runLoop() {
        long[] scratch = new long[32 * 32 * 32];
        while (true) {
            Job job;
            try {
                job = QUEUE.takeFirst();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (job.generation() == GENERATION.get()) {
                    renderJob(job, scratch);
                }
            } catch (Throwable t) {
                System.err.println("[MIA Aperture] map tile job failed for " + job.key() + ": " + t);
            } finally {
                PENDING.remove(job.key());
            }
        }
    }

    private static void renderJob(Job job, long[] scratch) {
        TileKey key = job.key();
        int lvl = key.lvl();
        int cellSize = 1 << lvl;
        int sectionSpanY = 32 * cellSize;

        int topSecY = Math.floorDiv(job.bandTopY(), sectionSpanY);
        int bottomSecY = Math.floorDiv(job.bandBottomY(), sectionSpanY);
        int count = Math.min(12, topSecY - bottomSecY + 1);

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

        int stackBaseY = (topSecY - count + 1) * sectionSpanY;
        int topSectionTopY = (topSecY + 1) * sectionSpanY;
        int[] colors = new int[32 * 32];
        int[] heights = new int[32 * 32];
        MapTileRenderer.renderTile(sections, topSectionTopY, job.bandTopY(), stackBaseY,
                cellSize, key.mode(), job.colors(), colors, heights);
        CACHE.put(key, new MapTile(colors, heights, System.currentTimeMillis()));
    }
}
```

- [ ] **Step 7: Full build to catch compile errors against the Voxy API**

Run: `.\gradlew build` — Expected: BUILD SUCCESSFUL. If `copyDataTo`/`acquireIfExists`/`release` signatures mismatch, fix against Task 1's javap output.

- [ ] **Step 8: Commit**
```bash
git add src/main/java/com/mia/aperture/map src/test
git commit -m "feat(map): tile cache, worker thread, and Voxy data source"
```

---

### Task 5: Compositor + dynamic textures

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapCompositor.java`

**Design:** one class owns both DynamicTextures and composes on the render thread. View
parameters arrive per call. Pure per-pixel mapping: image pixel → shifted block coords →
tile key + cell index → cached color (or transparent). Requests missing tiles from
MapWorker. No unit tests (GL-bound); logic mirrors tested MapGeometry.

- [ ] **Step 1: Implement**

`src/main/java/com/mia/aperture/map/MapCompositor.java`:
```java
package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class MapCompositor {
    public static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "map");
    public static final Identifier HUD_TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "minimap");

    public static final int MAP_SIZE = 512;
    public static final int HUD_SIZE = 128;
    private static final int HUD_RADIUS_BLOCKS = 64;
    private static final long MAP_INTERVAL_MS = 100;   // 10 Hz
    private static final long HUD_INTERVAL_MS = 500;   // 2 Hz
    private static final long NEAR_TILE_MAX_AGE_MS = 5000;

    private static DynamicTexture mapTexture;
    private static DynamicTexture hudTexture;
    private static long lastMapCompose;
    private static long lastHudCompose;

    private MapCompositor() {}

    private static DynamicTexture ensure(Identifier id, DynamicTexture existing, int size) {
        if (existing != null) return existing;
        DynamicTexture tex = new DynamicTexture(id.toString(), size, size, true);
        Minecraft.getInstance().getTextureManager().register(id, tex);
        return tex;
    }

    // Fullscreen map: centerX/centerZ in WORLD coords, blocksAcross = view span.
    public static void composeMap(double centerWorldX, double centerWorldZ,
                                  int blocksAcross, int bandTopY, int bandBottomY, MapMode mode) {
        long now = System.currentTimeMillis();
        if (now - lastMapCompose < MAP_INTERVAL_MS) return;
        lastMapCompose = now;
        mapTexture = ensure(MAP_TEXTURE, mapTexture, MAP_SIZE);
        compose(mapTexture, MAP_SIZE, centerWorldX, centerWorldZ, blocksAcross,
                bandTopY, bandBottomY, mode);
    }

    // HUD minimap: fixed radius around the player, default band, current mode.
    public static void composeHud(double playerWorldX, double playerWorldZ,
                                  int bandTopY, int bandBottomY, MapMode mode) {
        long now = System.currentTimeMillis();
        if (now - lastHudCompose < HUD_INTERVAL_MS) return;
        lastHudCompose = now;
        hudTexture = ensure(HUD_TEXTURE, hudTexture, HUD_SIZE);
        compose(hudTexture, HUD_SIZE, playerWorldX, playerWorldZ, HUD_RADIUS_BLOCKS * 2,
                bandTopY, bandBottomY, mode);
    }

    private static void compose(DynamicTexture texture, int imageSize,
                                double centerWorldX, double centerWorldZ, int blocksAcross,
                                int bandTopY, int bandBottomY, MapMode mode) {
        VoxyRenderSystem renderSystem = IGetVoxyRenderSystem.getNullable();
        var mc = Minecraft.getInstance();
        NativeImage image = texture.getPixels();
        if (renderSystem == null || mc.level == null || image == null) return;

        var engine = renderSystem.getEngine();
        var colors = new VoxyColorSource(engine.getMapper(), mc.level);

        int sector = AbyssUtil.getSection(centerWorldX);
        int centerShiftedX = MapGeometry.shiftX((int) Math.floor(centerWorldX), sector);
        int centerShiftedZ = (int) Math.floor(centerWorldZ);

        int lvl = MapGeometry.lvlForView(blocksAcross);
        int cellSize = 1 << lvl;
        int bandKey = MapGeometry.bandKey(bandTopY);
        double blocksPerPixel = (double) blocksAcross / imageSize;

        TileKey lastKey = null;
        MapTile lastTile = null;

        for (int py = 0; py < imageSize; py++) {
            int blockZ = centerShiftedZ + (int) Math.floor((py - imageSize / 2.0) * blocksPerPixel);
            for (int px = 0; px < imageSize; px++) {
                int blockX = centerShiftedX + (int) Math.floor((px - imageSize / 2.0) * blocksPerPixel);
                int tx = MapGeometry.blockToTile(blockX, lvl);
                int tz = MapGeometry.blockToTile(blockZ, lvl);
                TileKey key = (lastKey != null && lastKey.sx() == tx && lastKey.sz() == tz)
                        ? lastKey : new TileKey(lvl, tx, tz, bandKey, mode);
                MapTile tile;
                if (key == lastKey) {
                    tile = lastTile;
                } else {
                    tile = MapWorker.request(key, bandTopY, bandBottomY, engine, colors,
                            isNear(blockX, blockZ, centerShiftedX, centerShiftedZ) ? NEAR_TILE_MAX_AGE_MS : 0);
                    lastKey = key;
                    lastTile = tile;
                }
                int argb = 0;
                if (tile != null) {
                    int span = MapGeometry.tileSpanBlocks(lvl);
                    int cx = Math.floorMod(blockX, span) / cellSize;
                    int cz = Math.floorMod(blockZ, span) / cellSize;
                    argb = tile.colors()[cz * 32 + cx];
                }
                image.setPixel(px, py, argb);
            }
        }
        texture.upload();
    }

    private static boolean isNear(int x, int z, int cx, int cz) {
        return Math.abs(x - cx) <= 96 && Math.abs(z - cz) <= 96;
    }

    public static void reset() {
        MapWorker.reset();
    }
}
```
Orientation note: row `py` increases downward and maps to increasing Z (south) — north-up
image, so the GUI blit uses NORMAL V (0→1), unlike the old FBO path.
Color note: if in-game colors show red/blue swapped, change `image.setPixel(px, py, argb)`
to `image.setPixelABGR(px, py, swapRB(argb))`... first try `setPixel`; it takes ARGB in
current mappings.

- [ ] **Step 2: Build**

Run: `.\gradlew build` — Expected: BUILD SUCCESSFUL. (`IGetVoxyRenderSystem`, `getEngine()`, `getMapper()` all exist — used by the old code/verified in Task 1.)

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MapCompositor.java
git commit -m "feat(map): compositor with dynamic map and HUD textures"
```

---

### Task 6: Wire into the map screen and HUD

**Files:**
- Modify: `src/main/java/com/mia/aperture/state/AbyssMapState.java`
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

- [ ] **Step 1: Add map mode + band state to AbyssMapState**

Add fields (keep everything already there):
```java
    public static com.mia.aperture.map.MapMode mapRenderMode = com.mia.aperture.map.MapMode.RELIEF;
    public static boolean mapBandCustom = false;
```
Add helpers at the bottom of the class:
```java
    public static int defaultBandTopY(double playerWorldY, int sector) {
        return com.mia.aperture.map.MapGeometry.shiftY((int) playerWorldY, sector) + 96;
    }

    public static int bandHeight() {
        return 320;
    }
```

- [ ] **Step 2: Rewrite AbyssWorldMapScreen.render to drive the compositor**

Replace the `render` method body and the blit block:
```java
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawGrid(guiGraphics);

        var player = this.minecraft.player;
        if (player != null) {
            int sector = me.cortex.voxy.client.core.util.AbyssUtil.getSection(player.getX());
            int bandTop;
            if (AbyssMapState.mapBandCustom) {
                bandTop = com.mia.aperture.map.MapGeometry.shiftY(
                        (int) (AbyssMapState.scrollTargetCenterY + sector * 480.0
                                - (240 - sector * 30) * 16.0 * 0 // abyss->world handled below
                        ), sector);
                // scrollTargetCenterY is in ABYSS coords (world minus sector*480):
                bandTop = com.mia.aperture.map.MapGeometry.shiftY(
                        (int) (AbyssMapState.scrollTargetCenterY + sector * 480), sector)
                        + (int) (AbyssMapState.apertureThickness / 2);
            } else {
                bandTop = AbyssMapState.defaultBandTopY(player.getY(), sector);
            }
            int bandBottom = bandTop - AbyssMapState.bandHeight();
            int blocksAcross = (int) (256.0f / AbyssMapState.mapZoom);
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            com.mia.aperture.map.MapCompositor.composeMap(centerX, centerZ, blocksAcross,
                    bandTop, bandBottom, AbyssMapState.mapRenderMode);
        }

        guiGraphics.blit(
                com.mia.aperture.map.MapCompositor.MAP_TEXTURE,
                0, 0,
                this.width, this.height,
                0.0f, 1.0f,
                0.0f, 1.0f
        );

        drawMapOverlay(guiGraphics);
    }
```
Remove the `int tex = ...getGlId()...` guard entirely (no longer meaningful).
NOTE on the abyss→shifted conversion for the custom band: `AbyssUtil.toAbyss` computes
`abyssY = worldY - sector*480`, so `worldY = abyssY + sector*480`, then `shiftY(worldY, sector)`.
Simplify the block above to exactly:
```java
            int bandTop;
            if (AbyssMapState.mapBandCustom) {
                int worldY = (int) (AbyssMapState.scrollTargetCenterY + sector * 480);
                bandTop = com.mia.aperture.map.MapGeometry.shiftY(worldY, sector)
                        + (int) (AbyssMapState.apertureThickness / 2);
            } else {
                bandTop = AbyssMapState.defaultBandTopY(player.getY(), sector);
            }
```
(Delete the first, garbled variant — this is the authoritative version.)

- [ ] **Step 3: Update mouseScrolled (slice sets custom band), keyPressed (V toggle), overlay text, zoom limits**

In `mouseScrolled`, inside the `if (sliceModifier)` branch add `AbyssMapState.mapBandCustom = true;`
and guard the world reevaluation:
```java
        if (sliceModifier) {
            AbyssMapState.scrollTargetCenterY += verticalAmount * 16.0;
            AbyssMapState.mapBandCustom = true;
            if (AbyssMapState.scrollActive) {
                InputHandler.triggerReevaluation();
            }
        } else {
```
Change zoom clamps to `0.0125f` minimum (keep `20.0f` max):
```java
            if (AbyssMapState.mapZoom < 0.0125f) AbyssMapState.mapZoom = 0.0125f;
```
In `init()`, add `AbyssMapState.mapBandCustom = false;`.
In `keyPressed`, before the `P` handling add:
```java
        if (event.key() == GLFW.GLFW_KEY_V) {
            AbyssMapState.mapRenderMode = AbyssMapState.mapRenderMode == com.mia.aperture.map.MapMode.RELIEF
                    ? com.mia.aperture.map.MapMode.VANILLA
                    : com.mia.aperture.map.MapMode.RELIEF;
            return true;
        }
```
Remove the `P` perspective toggle handling and the perspective line from `drawMapOverlay`
(side view deferred per spec); replace overlay lines with:
```java
    private void drawMapOverlay(GuiGraphics guiGraphics) {
        guiGraphics.drawString(this.font, "Mode: " + AbyssMapState.mapRenderMode, 10, 10, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Zoom: " + String.format("%.3f", AbyssMapState.mapZoom) + "x", 10, 22, 0xFFFFFFFF);
        guiGraphics.drawString(this.font, "Slice top: " + (AbyssMapState.mapBandCustom ? (int) AbyssMapState.scrollTargetCenterY + "m (custom)" : "player"), 10, 34, 0xFFFF5555);
        guiGraphics.drawString(this.font, "Drag to pan | Scroll to zoom | Ctrl+scroll to slice | V: relief/vanilla", 10, this.height - 20, 0xFFAAAAAA);
    }
```
Also update `mouseDragged` to drop the SIDE_VIEW branch (keep the TOP_DOWN pan only).

- [ ] **Step 4: Rewire the HUD in MiaApertureModClient.drawHud**

Replace the texture section of `drawHud` (keep frame/crosshair/arrow/text/sidebar):
```java
        int sector = me.cortex.voxy.client.core.util.AbyssUtil.getSection(client.player.getX());
        int bandTop = AbyssMapState.defaultBandTopY(client.player.getY(), sector);
        com.mia.aperture.map.MapCompositor.composeHud(client.player.getX(), client.player.getZ(),
                bandTop, bandTop - AbyssMapState.bandHeight(), AbyssMapState.mapRenderMode);

        int x = screenWidth - 110;
        int y = 10;
        int size = 100;
        context.fill(x - 2, y - 2, x + size + 2, y + size + 2, 0xFF111111);
        context.blit(com.mia.aperture.map.MapCompositor.HUD_TEXTURE, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
        context.renderOutline(x - 1, y - 1, size + 2, size + 2, 0xFF888888);
```
Delete: `ensureTextureInitialized()` call, the `tex != 0` guard (blit unconditionally), and
the 5-second `drawHud` debug print.

- [ ] **Step 5: Build**

Run: `.\gradlew build` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/mia/aperture
git commit -m "feat(map): drive map screen and HUD from the tile compositor"
```

---

### Task 7: Retire the FBO/viewport path

**Files:**
- Delete: `src/main/java/com/mia/aperture/client/MinimapFbo.java`
- Delete: `src/main/java/com/mia/aperture/mixin/WorldRendererMixin.java`
- Delete: `src/main/java/com/mia/aperture/mixin/LevelRendererVoxyBypassMixin.java`
- Delete: `src/main/java/com/mia/aperture/mixin/ChunkBoundRendererMixin.java`
- Delete: `src/main/java/com/mia/aperture/mixin/TraversalAccessor.java`
- Delete: `src/main/java/com/mia/aperture/mixin/ViewportSelectorInvoker.java`
- Modify: `src/main/java/com/mia/aperture/mixin/VoxyRenderSystemMixin.java`
- Modify: `src/main/java/com/mia/aperture/duck/VoxyRenderSystemDuck.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`
- Modify: `src/main/java/com/mia/aperture/state/AbyssMapState.java`
- Modify: `src/main/resources/mia_aperture_mod.mixins.json`

- [ ] **Step 1: Delete the six files listed above**
```bash
git rm src/main/java/com/mia/aperture/client/MinimapFbo.java \
  src/main/java/com/mia/aperture/mixin/WorldRendererMixin.java \
  src/main/java/com/mia/aperture/mixin/LevelRendererVoxyBypassMixin.java \
  src/main/java/com/mia/aperture/mixin/ChunkBoundRendererMixin.java \
  src/main/java/com/mia/aperture/mixin/TraversalAccessor.java \
  src/main/java/com/mia/aperture/mixin/ViewportSelectorInvoker.java
```

- [ ] **Step 2: Slim the duck + mixin to only the render-distance tracker**

`src/main/java/com/mia/aperture/duck/VoxyRenderSystemDuck.java` becomes:
```java
package com.mia.aperture.duck;

import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;

public interface VoxyRenderSystemDuck {
    RenderDistanceTracker mia$getRenderDistanceTracker();
}
```
`src/main/java/com/mia/aperture/mixin/VoxyRenderSystemMixin.java` becomes:
```java
package com.mia.aperture.mixin;

import com.mia.aperture.duck.VoxyRenderSystemDuck;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin implements VoxyRenderSystemDuck {
    @Shadow private RenderDistanceTracker renderDistanceTracker;

    @Override
    public RenderDistanceTracker mia$getRenderDistanceTracker() {
        return this.renderDistanceTracker;
    }
}
```

- [ ] **Step 3: Update mixins.json client list to exactly**
```json
  "client": [
    "MouseMixin",
    "KeyboardMixin",
    "VoxyRenderSystemMixin",
    "RenderDistanceTrackerMixin",
    "NodeManagerMixin"
  ],
```

- [ ] **Step 4: Clean MiaApertureModClient and AbyssMapState**

In `MiaApertureModClient`: delete the `MinimapTexture` inner class, `minimapTextureInstance`,
`ensureTextureInitialized()`, `lastHudLogTime`, `lastKnownFog`, `isRenderingMap`, and the
reflection imports (`java.lang.reflect.*`). Keep keybinds, tick handler, HUD callback,
`drawHud` (as rewritten in Task 6), and `drawSidebarLayerBar`.

In `AbyssMapState`: delete `mapPerspective`, the `Perspective` enum, `mapY`, and
`mapFragmentDepthBound` (side view deferred; FBO gone). Keep `mapX`, `mapZ`, `mapZoom`,
`altHeld`, `ctrlHeld`, `scrollActive`, `scrollTargetCenterY`, `apertureThickness`,
`isSectionVisible`, and the Task 6 additions. Fix any references (`AbyssWorldMapScreen`
side-view branches were removed in Task 6).

- [ ] **Step 5: Build and run tests**

Run: `.\gradlew build` — Expected: BUILD SUCCESSFUL, all unit tests pass, no dangling references.

- [ ] **Step 6: Commit**
```bash
git add -A
git commit -m "refactor: retire the FBO/viewport map path and diagnostics"
```

---

### Task 8: Version, docs, install, in-game verification

**Files:**
- Modify: `gradle.properties`
- Modify: `README.md`
- Modify: `project_memory.md`

- [ ] **Step 1: Bump version to 1.2.0**

`gradle.properties`: `mod_version=1.2.0`

- [ ] **Step 2: Update README controls section**

Replace the keybindings section content with:
```markdown
- **`M`**: Open the Abyss World Map Screen (data-driven; shows everything ingested into Voxy's database).
- **`H`**: Toggle vertical aperture culling of the live world (On/Off).
- **`Ctrl + Scroll Wheel`**: Scroll the culling aperture / map slice Y-level (Alt also works where the OS delivers it).
- **`V`** *(Inside Map Screen)*: Toggle map coloring between height-shaded relief and vanilla cartography style.
- **`Left Click + Drag`** *(Inside Map Screen)*: Pan the map.
- **`Scroll Wheel`** *(Inside Map Screen)*: Zoom (out to the entire explored layer).
```
Also update the jar version references to 1.2.0.

- [ ] **Step 3: Build and install**
```powershell
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build
Remove-Item "<mods-dir>\mia-aperture-mod-*.jar"
Copy-Item "build\libs\mia-aperture-mod-1.2.0.jar" "<mods-dir>\"
```

- [ ] **Step 4: Owner in-game verification checklist (report results before proceeding)**
1. HUD minimap shows live terrain around the player within ~1s of joining (finally live).
2. `M` map shows terrain for explored areas; pans and zooms; zooming far out reveals the full explored layer footprint (verifies high-lvl mips exist — spec risk item).
3. `V` toggles relief/vanilla coloring; colors are sane (if red/blue swapped: switch `setPixel` → `setPixelABGR` in MapCompositor, one line).
4. Ctrl+scroll in the map moves the slice (overlay shows custom top); terrain changes accordingly; with `H` off the world is unaffected.
5. In-game Ctrl+scroll aperture culling still works (`H` toggle unaffected by the refactor).
6. No `[MIA Aperture diag]` spam in the log; no crashes on AMD (no readbacks remain).

- [ ] **Step 5: Update project_memory.md**

Replace the "RESUME HERE" block with a short status: data-driven map shipped in v1.2.0 per
spec `docs/plans/specs/2026-07-07-data-driven-map-design.md`; the FBO/viewport path
and its mixins are deleted; keep the "7 root causes" history section; note the deferred
side view and the `voxy_mia_light_zones.json` instance issue.

- [ ] **Step 6: Commit and push**
```bash
git add -A
git commit -m "chore(release): v1.2.0 - data-driven map renderer"
git push origin main
```

---

## Self-Review Notes

- Spec coverage: data source (T4), rasterizer+modes (T3), cache/worker (T4), compositor+
  textures (T5), screen/HUD wiring+controls+zoom limits+band (T6), retirement list (T7),
  JUnit (T1), full-layer zoom (T2 `lvlForView` + T6 zoom clamp), persistence/no-disk
  invariant (nothing writes to disk anywhere), mip-availability risk (T8 step 4.2).
- Reset hook: `MapWorker.reset()` — call site is ClientPlayConnectionEvents? Kept minimal:
  `MapCompositor.reset()` exists; wire it in `MiaApertureModClient.onInitializeClient` via
  `net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT`:
  ```java
  ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> com.mia.aperture.map.MapCompositor.reset());
  ```
  (add in Task 6 Step 4 alongside the HUD rewiring).
- Type consistency checked: `TileKey(lvl,sx,sz,bandKey,mode)`, `MapTile(colors,heights,renderedAtMs)`,
  `renderTile(sections, topSectionTopY, bandTopY, stackBaseY, cellSize, mode, colors, outColor, outHeight)`
  used identically in T3 tests, T3 impl, and T4 worker.
