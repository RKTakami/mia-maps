# Cross-Layer Routing — Phase 2 (Coarse Corridor) Implementation Plan


**Goal:** Route the whole way to a deep or off-layer waypoint by planning a coarse "which shaft to head down" corridor over the entire Abyss column, then aiming the existing block-accurate router at the next point along it.

**Architecture:** A second, coarse tier. `CorridorPlanner` samples Voxy at an auto-selected LOD (≤4) over a padded player↔destination box in the shifted column and runs a 3-D connectivity search (`CorridorFinder`) through non-opaque cells to find the open shaft. `RouteService` caches that corridor and points its existing LOD-0 route (Phase 1) at the next corridor point instead of the raw, out-of-box destination — so footing, safe-drop, and dig hints stay block-accurate near the player while the route reaches the target the whole way.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Voxy is `compileOnly` and NOT on the test classpath, so all pure logic lives in classes that do not import Voxy.

**Spec:** `docs/plans/specs/2026-07-15-cross-layer-routing-design.md`

**Branch policy:** Work directly on `main` in `D:\Users\dev\VSCode-Projects\MIA map mod project`. Do NOT create a branch or worktree (project convention — `AGENTS.md`).

---

## Background an engineer needs before starting

Read `docs/plans/specs/2026-07-15-cross-layer-routing-design.md` and the memory note `project_memory.md` §3b first. The load-bearing facts:

- **The shifted column is the real, continuous Abyss.** Phase 1 already moved all routing into it: `MapGeometry.toShiftedColumn(worldX,worldY,worldZ)` → `{shiftedX, shiftedY, shiftedZ}`, and `toWorld(sx,sy,sz,sector)` back. Sections stack vertically here.
- **Voxy stores nothing coarser than LOD 4** (`WorldEngine.MAX_LOD_LAYER = 4`). So `MAX_CORRIDOR_LVL = 4`.
- **`VoxelCloud.fillOpaque(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl)`** returns a `boolean[]` of size `gX*gY*gZ`, indexed `(gy*gZ + gz)*gX + gx`. Origin and extents are in **cells at `lvl`**, where **one cell = `1 << lvl` blocks**. It already clamps shifted X to `[-8192, 8192)` and reads Voxy's downsampled LOD data, so a coarse grid sampled in the shifted column reads the whole stacked column with no special-casing. A cell is `true` only where Voxy's LOD says that region is opaque; empty/unmapped cells stay `false`.
- **Why LOD 0 alone cannot do this:** the Abyss band is ~7968 blocks tall; a 192×7968×192 LOD-0 grid is ~294M cells. At LOD 4 the same column is ~127k cells.

**Why the corridor search is a NEW search, not `Pathfinder`:** `Pathfinder` is gravity-based (`TraversabilityGrid.standable` = solid below + 2 air), which is meaningless at 16-block cells. The corridor needs 3-D air-connectivity: a cell is passable when it is **not opaque**.

**Design decision — unmapped cells are passable (optimistic).** `fillOpaque` leaves both true-air and unmapped cells `false` (not opaque), so the corridor treats unmapped regions as passable. This is deliberate: the corridor is a *guide* toward the main shaft, and the LOD-0 Tier-2 router is what guarantees real footing. If the corridor ever suggests an unmapped direction, Tier 2 simply finds no footing there and the route stays local. Do not "fix" this to block unmapped cells without re-checking the spec.

**Build:**
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

**Install to the live instance (verification tasks):**
```bash
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```

## File Structure

| File | Responsibility | New? | Testable? |
|---|---|---|---|
| `map/CorridorMath.java` | Pure: LOD pick, cell↔shifted conversion, sub-goal pick | new | yes |
| `map/CorridorFinder.java` | Pure: 26-connected A* through non-opaque cells | new | yes |
| `map/CorridorPlanner.java` | Voxy: sample coarse grid + run finder → shifted corridor | new | no (imports Voxy) |
| `map/RouteService.java` | Cache corridor; aim Tier-2 at sub-goal; expose corridor | modify | no |
| `client/OrbitView.java` | Draw the corridor trail in the 3D view | modify | no |
| `test/.../CorridorMathTest.java` | Tests for CorridorMath | new | — |
| `test/.../CorridorFinderTest.java` | Tests for CorridorFinder | new | — |

`CorridorMath` and `CorridorFinder` must NOT import any `me.cortex.voxy.*` or `net.minecraft.*` type, or their tests will fail to load the class at runtime (Voxy is `compileOnly`). All Voxy contact is quarantined in `CorridorPlanner`, exactly as `RouteBox` (pure) / `RouteService` (Voxy) already are.

---

### Task 1: CorridorMath — LOD selection

**Files:**
- Create: `src/main/java/com/mia/aperture/map/CorridorMath.java`
- Test: `src/test/java/com/mia/aperture/map/CorridorMathTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/CorridorMathTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorridorMathTest {

    @Test
    void cellsForCeilingDividesByCellSize() {
        assertEquals(1, CorridorMath.cellsFor(1, 0));
        assertEquals(16, CorridorMath.cellsFor(16, 0));
        assertEquals(1, CorridorMath.cellsFor(16, 4));  // 16 blocks / 16-block cell = 1
        assertEquals(2, CorridorMath.cellsFor(17, 4));  // ceil(17/16) = 2
        assertEquals(1, CorridorMath.cellsFor(0, 0));   // never below 1
    }

    @Test
    void cellCountMultipliesTheThreeAxes() {
        assertEquals(256L * 8 * 256, CorridorMath.cellCount(256, 8, 256, 0));
    }

    @Test
    void pickLevelStaysAtZeroWhenItFits() {
        assertEquals(0, CorridorMath.pickLevel(64, 64, 64, 4_000_000, 4));
    }

    @Test
    void pickLevelClimbsToTheFinestThatFits() {
        // The straight-down case from the spec: 256 x 7968 x 256 blocks.
        int lvl = CorridorMath.pickLevel(256, 7968, 256, 4_000_000, 4);
        assertTrue(CorridorMath.cellCount(256, 7968, 256, lvl) <= 4_000_000,
                "level " + lvl + " must fit the budget");
        assertTrue(lvl == 0 || CorridorMath.cellCount(256, 7968, 256, lvl - 1) > 4_000_000,
                "level " + lvl + " must be the FINEST that fits");
    }

    @Test
    void pickLevelNeverExceedsTheCap() {
        // An enormous box that does not fit even at the cap still returns the cap.
        assertEquals(4, CorridorMath.pickLevel(40000, 40000, 40000, 4_000_000, 4));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*CorridorMathTest*'
```

Expected: FAIL — `cannot find symbol: class CorridorMath`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/CorridorMath.java`:

```java
package com.mia.aperture.map;

import java.util.List;

// Pure helpers for the coarse corridor tier: LOD selection, coarse-cell to shifted-column
// conversion, and picking the local router's next sub-goal along the corridor. No Voxy or
// Minecraft types, so it stays unit-testable (Voxy is compileOnly, off the test classpath).
public final class CorridorMath {
    private CorridorMath() {}

    // Cells needed to span `blocks` at LOD lvl, where one cell = (1 << lvl) blocks. Never below 1.
    public static int cellsFor(int blocks, int lvl) {
        if (blocks <= 1) return 1;
        return ((blocks - 1) >> lvl) + 1;
    }

    public static long cellCount(int spanX, int spanY, int spanZ, int lvl) {
        return (long) cellsFor(spanX, lvl) * cellsFor(spanY, lvl) * cellsFor(spanZ, lvl);
    }

    // The FINEST (smallest) LOD whose cell count fits `budget`, capped at `maxLvl`.
    public static int pickLevel(int spanX, int spanY, int spanZ, long budget, int maxLvl) {
        int lvl = 0;
        while (lvl < maxLvl && cellCount(spanX, spanY, spanZ, lvl) > budget) lvl++;
        return lvl;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*CorridorMathTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/CorridorMath.java src/test/java/com/mia/aperture/map/CorridorMathTest.java
git commit -m "feat(corridor): pure LOD selection for the coarse corridor

```

---

### Task 2: CorridorMath — cell↔shifted conversion and sub-goal

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/CorridorMath.java`
- Test: `src/test/java/com/mia/aperture/map/CorridorMathTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CorridorMathTest`, before the closing `}`:

```java
    @Test
    void cellCentreIsTheMiddleOfTheCoarseCell() {
        // origin cell (10,20,30) in cells, cell 0,0,0, at lvl 4 (16-block cells).
        double[] c = CorridorMath.cellCentreShifted(0, 0, 0, 10, 20, 30, 4);
        assertEquals(10 * 16 + 8, c[0], 1e-9);
        assertEquals(20 * 16 + 8, c[1], 1e-9);
        assertEquals(30 * 16 + 8, c[2], 1e-9);
    }

    @Test
    void subGoalOfAnEmptyCorridorIsNull() {
        assertNull(CorridorMath.subGoal(java.util.List.of(), 0, 0, 0, 72));
    }

    @Test
    void subGoalPicksTheFarthestPointWithinReach() {
        // Corridor straight down from the player. Reach 72 -> the point at y=-72 is the farthest
        // within reach; y=-100 is beyond it.
        java.util.List<double[]> corridor = java.util.List.of(
                new double[]{0, 0, 0},
                new double[]{0, -40, 0},
                new double[]{0, -72, 0},
                new double[]{0, -100, 0});
        double[] g = CorridorMath.subGoal(corridor, 0, 0, 0, 72);
        assertArrayEquals(new double[]{0, -72, 0}, g, 1e-9);
    }

    @Test
    void subGoalFallsBackToTheNearestPointWhenNoneAreWithinReach() {
        java.util.List<double[]> corridor = java.util.List.of(
                new double[]{0, -200, 0},
                new double[]{0, -240, 0});
        double[] g = CorridorMath.subGoal(corridor, 0, 0, 0, 72);
        assertArrayEquals(new double[]{0, -200, 0}, g, 1e-9);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*CorridorMathTest*'
```

Expected: FAIL — `cannot find symbol: method cellCentreShifted`, `subGoal`.

- [ ] **Step 3: Implement**

In `CorridorMath.java`, add before the closing `}`:

```java
    // Shifted-column centre of coarse cell (cx,cy,cz), given the grid's origin in CELLS and the LOD
    // (cell = 1 << lvl blocks).
    public static double[] cellCentreShifted(int cx, int cy, int cz,
            int originCellX, int originCellY, int originCellZ, int lvl) {
        int size = 1 << lvl;
        return new double[]{
                (double) (originCellX + cx) * size + size / 2.0,
                (double) (originCellY + cy) * size + size / 2.0,
                (double) (originCellZ + cz) * size + size / 2.0};
    }

    // The corridor point (ordered player->destination, shifted coords) to aim the local router at:
    // the farthest point still within `reach` blocks of the player, scanning forward from the point
    // nearest the player. Falls back to that nearest point, or null when the corridor is empty.
    public static double[] subGoal(List<double[]> corridor, double px, double py, double pz, double reach) {
        if (corridor.isEmpty()) return null;
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < corridor.size(); i++) {
            double d = dist2(corridor.get(i), px, py, pz);
            if (d < best) { best = d; nearest = i; }
        }
        double reach2 = reach * reach;
        double[] pick = corridor.get(nearest);
        for (int i = nearest; i < corridor.size(); i++) {
            if (dist2(corridor.get(i), px, py, pz) <= reach2) pick = corridor.get(i);
            else break;
        }
        return pick;
    }

    private static double dist2(double[] c, double x, double y, double z) {
        double dx = c[0] - x, dy = c[1] - y, dz = c[2] - z;
        return dx * dx + dy * dy + dz * dz;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*CorridorMathTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/CorridorMath.java src/test/java/com/mia/aperture/map/CorridorMathTest.java
git commit -m "feat(corridor): coarse-cell to shifted conversion + sub-goal pick

```

---

### Task 3: CorridorFinder — 26-connected A* through non-opaque cells

**Files:**
- Create: `src/main/java/com/mia/aperture/map/CorridorFinder.java`
- Test: `src/test/java/com/mia/aperture/map/CorridorFinderTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/CorridorFinderTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorridorFinderTest {

    // Helper: an all-air grid with an optional solid slab. index = (y*gz+z)*gx+x.
    private static boolean[] air(int gx, int gy, int gz) {
        return new boolean[gx * gy * gz];
    }
    private static int idx(int x, int y, int z, int gx, int gz) {
        return (y * gz + z) * gx + x;
    }

    @Test
    void straightOpenShaftIsFound() {
        int g = 5;
        boolean[] o = air(g, g, g);
        CorridorFinder.Result r = CorridorFinder.find(o, g, g, g,
                new CorridorFinder.Cell(2, 4, 2), new CorridorFinder.Cell(2, 0, 2), 10_000);
        assertEquals(CorridorFinder.Status.FOUND, r.status());
        assertEquals(new CorridorFinder.Cell(2, 4, 2), r.path().get(0));
        assertEquals(new CorridorFinder.Cell(2, 0, 2), r.path().get(r.path().size() - 1));
    }

    @Test
    void aFullSolidSlabBlocksTheDescentAsPartial() {
        int gx = 5, gy = 5, gz = 5;
        boolean[] o = air(gx, gy, gz);
        for (int x = 0; x < gx; x++)          // solid floor across the whole y=2 layer
            for (int z = 0; z < gz; z++) o[idx(x, 2, z, gx, gz)] = true;
        CorridorFinder.Result r = CorridorFinder.find(o, gx, gy, gz,
                new CorridorFinder.Cell(2, 4, 2), new CorridorFinder.Cell(2, 0, 2), 10_000);
        assertEquals(CorridorFinder.Status.PARTIAL, r.status());
        assertTrue(r.path().get(r.path().size() - 1).y() >= 3, "stops above the slab");
    }

    @Test
    void aDiagonalGapIsThreadedByThe26Connectivity() {
        // Slab at y=2 with a single open cell offset diagonally from the start column.
        int gx = 5, gy = 5, gz = 5;
        boolean[] o = air(gx, gy, gz);
        for (int x = 0; x < gx; x++)
            for (int z = 0; z < gz; z++) o[idx(x, 2, z, gx, gz)] = true;
        o[idx(3, 2, 3, gx, gz)] = false;   // one hole, diagonal from (2,*,2)
        CorridorFinder.Result r = CorridorFinder.find(o, gx, gy, gz,
                new CorridorFinder.Cell(2, 4, 2), new CorridorFinder.Cell(3, 0, 3), 10_000);
        assertEquals(CorridorFinder.Status.FOUND, r.status());
    }

    @Test
    void aStartInsideRockYieldsNoRoute() {
        int g = 3;
        boolean[] o = air(g, g, g);
        o[idx(1, 1, 1, g, g)] = true;         // start cell solid, and everything around it too
        for (int x = 0; x < g; x++) for (int y = 0; y < g; y++) for (int z = 0; z < g; z++)
            o[idx(x, y, z, g, g)] = true;
        CorridorFinder.Result r = CorridorFinder.find(o, g, g, g,
                new CorridorFinder.Cell(1, 1, 1), new CorridorFinder.Cell(0, 0, 0), 10_000);
        assertEquals(CorridorFinder.Status.NO_ROUTE, r.status());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*CorridorFinderTest*'
```

Expected: FAIL — `cannot find symbol: class CorridorFinder`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/CorridorFinder.java`:

```java
package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// A* through a coarse grid, treating a cell as passable when it is NOT opaque — i.e. air
// connectivity, no gravity. 26-connected so the search can thread a shaft that steps diagonally.
// Returns the path (start..goal), the nearest-to-goal path (PARTIAL) when the goal is unreachable,
// or NO_ROUTE when the start itself is boxed in. Pure: no Voxy or Minecraft types.
public final class CorridorFinder {
    public record Cell(int x, int y, int z) {}
    public enum Status { FOUND, PARTIAL, NO_ROUTE }
    public record Result(List<Cell> path, Status status) {}

    private CorridorFinder() {}

    private static final int[][] DIRS = buildDirs();

    public static Result find(boolean[] opaque, int gx, int gy, int gz,
            Cell start, Cell goal, int nodeCap) {
        Cell s = nearestPassable(opaque, gx, gy, gz, start);
        if (s == null) return new Result(List.of(), Status.NO_ROUTE);
        Cell g = clamp(goal, gx, gy, gz);

        Map<Integer, Integer> cameFrom = new HashMap<>();
        Map<Integer, Double> gScore = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        int startKey = key(s.x(), s.y(), s.z(), gx, gz);
        gScore.put(startKey, 0.0);
        open.add(new double[]{startKey, heuristic(s, g)});

        int best = startKey;
        double bestH = heuristic(s, g);
        int expanded = 0;

        while (!open.isEmpty() && expanded < nodeCap) {
            double[] cur = open.poll();
            int ck = (int) cur[0];
            int cx = ck % gx, cy = ck / (gx * gz), cz = (ck / gx) % gz;
            if (cx == g.x() && cy == g.y() && cz == g.z()) {
                return new Result(reconstruct(cameFrom, ck, gx, gz), Status.FOUND);
            }
            expanded++;
            double cg = gScore.getOrDefault(ck, Double.MAX_VALUE);
            for (int[] d : DIRS) {
                int nx = cx + d[0], ny = cy + d[1], nz = cz + d[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= gx || ny >= gy || nz >= gz) continue;
                if (opaque[(ny * gz + nz) * gx + nx]) continue;
                double step = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                int nk = key(nx, ny, nz, gx, gz);
                double ng = cg + step;
                if (ng < gScore.getOrDefault(nk, Double.MAX_VALUE)) {
                    gScore.put(nk, ng);
                    cameFrom.put(nk, ck);
                    double h = heuristic(new Cell(nx, ny, nz), g);
                    if (h < bestH) { bestH = h; best = nk; }
                    open.add(new double[]{nk, ng + h});
                }
            }
        }
        return new Result(reconstruct(cameFrom, best, gx, gz), Status.PARTIAL);
    }

    private static Cell nearestPassable(boolean[] o, int gx, int gy, int gz, Cell c) {
        Cell k = clamp(c, gx, gy, gz);
        for (int r = 0; r <= 2; r++) {
            for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                int x = k.x() + dx, y = k.y() + dy, z = k.z() + dz;
                if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) continue;
                if (!o[(y * gz + z) * gx + x]) return new Cell(x, y, z);
            }
        }
        return null;
    }

    private static Cell clamp(Cell c, int gx, int gy, int gz) {
        return new Cell(Math.max(0, Math.min(gx - 1, c.x())),
                        Math.max(0, Math.min(gy - 1, c.y())),
                        Math.max(0, Math.min(gz - 1, c.z())));
    }

    private static int key(int x, int y, int z, int gx, int gz) {
        return (y * gz + z) * gx + x;
    }

    private static double heuristic(Cell a, Cell b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Cell> reconstruct(Map<Integer, Integer> cameFrom, int endKey, int gx, int gz) {
        List<Cell> path = new ArrayList<>();
        Integer k = endKey;
        while (k != null) {
            int x = k % gx, y = k / (gx * gz), z = (k / gx) % gz;
            path.add(new Cell(x, y, z));
            k = cameFrom.get(k);
        }
        Collections.reverse(path);
        return path;
    }

    private static int[][] buildDirs() {
        int[][] d = new int[26][];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0) d[i++] = new int[]{dx, dy, dz};
        return d;
    }
}
```

Note on the cell-key decode: `key = (y*gz + z)*gx + x`, so `x = k % gx`, `y = k / (gx*gz)`, `z = (k / gx) % gz`. These appear together in `find` and `reconstruct`; keep them consistent.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*CorridorFinderTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/CorridorFinder.java src/test/java/com/mia/aperture/map/CorridorFinderTest.java
git commit -m "feat(corridor): 26-connected A* through non-opaque cells

```

---

### Task 4: CorridorPlanner — build a shifted corridor from Voxy

**Files:**
- Create: `src/main/java/com/mia/aperture/map/CorridorPlanner.java`

No unit test: this class imports Voxy (`WorldEngine`), which is off the test classpath. Its pure inputs are already covered by `CorridorMathTest` / `CorridorFinderTest`; correctness on real data is verified in-game in Task 7. Verification here is that the project compiles.

- [ ] **Step 1: Implement**

Create `src/main/java/com/mia/aperture/map/CorridorPlanner.java`:

```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.ArrayList;
import java.util.List;

// Tier 1 of cross-layer routing: a coarse "which shaft to head down" corridor over the whole Abyss
// column. Samples Voxy at the finest LOD (<= 4) whose cell count fits CELL_BUDGET across a padded
// player->destination box in the shifted column, then runs CorridorFinder through the non-opaque
// cells. Returns the corridor as an ordered list of shifted-column points (player -> destination),
// or empty when nothing could be sampled. Voxy contact is quarantined here.
public final class CorridorPlanner {
    private static final long CELL_BUDGET = 4_000_000L;
    private static final int PAD = 128;             // horizontal margin (blocks) around the span
    private static final int HORIZ_CAP = 1024;      // max horizontal span (blocks); clips far goals
    private static final int MAX_CORRIDOR_LVL = 4;  // Voxy stores nothing coarser (project_memory §3b)
    private static final int NODE_CAP = 400_000;

    private CorridorPlanner() {}

    // p and t are the player and destination in the shifted column.
    public static List<double[]> plan(WorldEngine engine, MapColorSource colors,
            double[] p, double[] t) {
        // Axis-aligned span covering both endpoints, padded horizontally. The vertical extent is
        // kept whole (the Abyss column is the point); the horizontal extent is capped and clipped
        // toward the goal so a far-horizontal waypoint yields a PARTIAL corridor that progresses as
        // you travel, rather than a multi-hundred-MB grid at LOD 4.
        int[] xr = clipToward((int) Math.floor(Math.min(p[0], t[0])) - PAD,
                (int) Math.ceil(Math.max(p[0], t[0])) + PAD,
                (int) Math.floor(p[0]), (int) Math.floor(t[0]), HORIZ_CAP);
        int[] zr = clipToward((int) Math.floor(Math.min(p[2], t[2])) - PAD,
                (int) Math.ceil(Math.max(p[2], t[2])) + PAD,
                (int) Math.floor(p[2]), (int) Math.floor(t[2]), HORIZ_CAP);
        int minX = xr[0], maxX = xr[1];
        int minY = (int) Math.floor(Math.min(p[1], t[1]));
        int maxY = (int) Math.ceil(Math.max(p[1], t[1]));
        int minZ = zr[0], maxZ = zr[1];
        int spanX = maxX - minX, spanY = maxY - minY, spanZ = maxZ - minZ;

        int lvl = CorridorMath.pickLevel(spanX, spanY, spanZ, CELL_BUDGET, MAX_CORRIDOR_LVL);

        int originCellX = Math.floorDiv(minX, 1 << lvl);
        int originCellY = Math.floorDiv(minY, 1 << lvl);
        int originCellZ = Math.floorDiv(minZ, 1 << lvl);
        int gx = CorridorMath.cellsFor(spanX, lvl) + 1;
        int gy = CorridorMath.cellsFor(spanY, lvl) + 1;
        int gz = CorridorMath.cellsFor(spanZ, lvl) + 1;

        boolean[] opaque = VoxelCloud.fillOpaque(engine, colors,
                originCellX, originCellY, originCellZ, gx, gy, gz, lvl);

        CorridorFinder.Cell start = cell(p, originCellX, originCellY, originCellZ, lvl, gx, gy, gz);
        CorridorFinder.Cell goal = cell(t, originCellX, originCellY, originCellZ, lvl, gx, gy, gz);
        CorridorFinder.Result res = CorridorFinder.find(opaque, gx, gy, gz, start, goal, NODE_CAP);

        List<double[]> corridor = new ArrayList<>(res.path().size());
        for (CorridorFinder.Cell c : res.path()) {
            corridor.add(CorridorMath.cellCentreShifted(c.x(), c.y(), c.z(),
                    originCellX, originCellY, originCellZ, lvl));
        }
        return corridor;
    }

    // Narrow [lo,hi] to at most `cap` wide, always containing `keep` (the player) and extending
    // toward `toward` (the goal). Returns [lo,hi] unchanged when already within cap.
    private static int[] clipToward(int lo, int hi, int keep, int toward, int cap) {
        if (hi - lo <= cap) return new int[]{lo, hi};
        if (toward >= keep) return new int[]{keep, keep + cap};
        return new int[]{keep - cap, keep};
    }

    private static CorridorFinder.Cell cell(double[] shifted,
            int originCellX, int originCellY, int originCellZ, int lvl, int gx, int gy, int gz) {
        int cx = Math.floorDiv((int) Math.floor(shifted[0]), 1 << lvl) - originCellX;
        int cy = Math.floorDiv((int) Math.floor(shifted[1]), 1 << lvl) - originCellY;
        int cz = Math.floorDiv((int) Math.floor(shifted[2]), 1 << lvl) - originCellZ;
        return new CorridorFinder.Cell(
                Math.max(0, Math.min(gx - 1, cx)),
                Math.max(0, Math.min(gy - 1, cy)),
                Math.max(0, Math.min(gz - 1, cz)));
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL (all existing tests still pass).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/map/CorridorPlanner.java
git commit -m "feat(corridor): sample a coarse shifted-column corridor from Voxy

```

---

### Task 5: RouteService — cache the corridor and aim Tier 2 at its sub-goal

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java`

No unit test (RouteService needs Voxy + Minecraft). Verified by build + Task 7 in-game.

- [ ] **Step 1: Add corridor state and constants**

In `RouteService.java`, after the line `private static final int MAX_TUNNEL = 8;`, add:

```java
    private static final double CORRIDOR_REFRESH_DIST = 64.0;  // recompute corridor after this much drift
    private static final double SUBGOAL_REACH = BOX / 2.0 - MARGIN;
```

After the line `private static volatile Route route = Route.EMPTY;`, add:

```java
    private static volatile java.util.List<double[]> corridor = java.util.List.of(); // shifted points
    private static volatile boolean needsCorridor;
    private static double lastCorridorX, lastCorridorY, lastCorridorZ;
```

- [ ] **Step 2: Add the corridor accessor**

In `RouteService.java`, right after the `aheadPointsShifted()` method, add:

```java
    // The full coarse corridor (shifted column, player -> destination) for the 3D view to draw.
    // Empty when there is no destination or nothing could be sampled.
    public static java.util.List<double[]> corridorShifted() {
        return corridor;
    }
```

- [ ] **Step 2b: Trigger a corridor build on a new destination**

In `setDestination`, add `needsCorridor = true;`:

```java
    public static void setDestination(double wx, double wy, double wz) {
        destination = new double[]{wx, wy, wz};
        needsRecompute = true;
        needsCorridor = true;
        ensureThread();
    }
```

And in `clear`, reset it:

```java
    public static void clear() {
        destination = null;
        route = Route.EMPTY;
        corridor = java.util.List.of();
    }
```

- [ ] **Step 3: Refresh the corridor when the player drifts far from where it was built**

In `tick`, replace the reroute block:

```java
        if ((moved || knockedOff) && now - lastRerouteMs > REROUTE_COOLDOWN_MS) {
            lastX = x; lastY = y; lastZ = z;
            lastRerouteMs = now;
            needsRecompute = true;
        }
        ensureThread();
```

with:

```java
        if ((moved || knockedOff) && now - lastRerouteMs > REROUTE_COOLDOWN_MS) {
            lastX = x; lastY = y; lastZ = z;
            lastRerouteMs = now;
            needsRecompute = true;
        }
        if (Math.abs(x - lastCorridorX) + Math.abs(y - lastCorridorY) + Math.abs(z - lastCorridorZ)
                > CORRIDOR_REFRESH_DIST) {
            needsCorridor = true;
        }
        ensureThread();
```

- [ ] **Step 4: Build the corridor in the worker loop**

In `RouteService.java`, replace the whole `loop()` method:

```java
    private static void loop() {
        while (true) {
            try {
                if (needsRecompute && destination != null) {
                    needsRecompute = false;
                    route = compute(destination);
                } else {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] route compute failed: " + t);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }
    }
```

with:

```java
    private static void loop() {
        while (true) {
            try {
                boolean did = false;
                if (needsCorridor && destination != null) {
                    needsCorridor = false;
                    lastCorridorX = px; lastCorridorY = py; lastCorridorZ = pz;
                    corridor = computeCorridor(destination);
                    did = true;
                }
                if (needsRecompute && destination != null) {
                    needsRecompute = false;
                    route = compute(destination);
                    did = true;
                }
                if (!did) Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] route compute failed: " + t);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static java.util.List<double[]> computeCorridor(double[] dst) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.player == null || dst == null) return java.util.List.of();
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return java.util.List.of();
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        double[] t = MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);
        return CorridorPlanner.plan(rs.getEngine(), colors, p, t);
    }
```

- [ ] **Step 5: Aim the Tier-2 route at the corridor sub-goal**

In `compute(double[] dst)`, replace:

```java
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        double[] t = MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);
        RouteBox.Box b = RouteBox.place(p[0], p[1], p[2], t[0], t[1], t[2], BOX, VBOX, MARGIN);
```

with:

```java
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        // Aim at the next point along the coarse corridor, not the raw destination. The corridor
        // threads the open shaft the whole way down; the block-accurate search below only has to
        // reach the next corridor point, which is within a box of the player. Falls back to the
        // destination itself when there is no corridor (e.g. it is still being built).
        double[] sub = CorridorMath.subGoal(corridor, p[0], p[1], p[2], SUBGOAL_REACH);
        double[] t = sub != null ? sub : MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);
        RouteBox.Box b = RouteBox.place(p[0], p[1], p[2], t[0], t[1], t[2], BOX, VBOX, MARGIN);
```

Note: `t` now drives both the box bias AND the goal (the goal is computed from `t` further down in the same method — that code already reads `t`, so no other change is needed there).

- [ ] **Step 6: Build and run the full suite**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mia/aperture/map/RouteService.java
git commit -m "feat(corridor): cache the corridor and route the local tier along it

```

---

### Task 6: OrbitView — draw the corridor trail

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`

- [ ] **Step 1: Add the corridor draw call**

In `OrbitView.render`, find the block that draws the route and dig:

```java
            drawRoute(guiGraphics, x0, y0, scale);
            drawDig(guiGraphics, x0, y0, scale);
```

Replace it with (corridor first, so the bright block-accurate route draws on top):

```java
            drawCorridor(guiGraphics, x0, y0, scale);
            drawRoute(guiGraphics, x0, y0, scale);
            drawDig(guiGraphics, x0, y0, scale);
```

- [ ] **Step 2: Implement drawCorridor**

In `OrbitView.java`, immediately before the `drawRoute` method, add:

```java
    // Draw the coarse corridor (the whole-column "which shaft to head down" guide) as a faint amber
    // dotted trail through the cloud. The bright block-accurate route is drawn on top of it.
    private void drawCorridor(GuiGraphics g, int x0, int y0, double scale) {
        java.util.List<double[]> corridor = com.mia.aperture.map.RouteService.corridorShifted();
        if (corridor.size() < 2) return;
        int faint = 0x66FFCC33;
        for (double[] c : corridor) {
            BeaconGeometry.Screen s = OrbitScene.projectShifted(c[0], c[1], c[2]);
            if (s.depth() <= 0.05) continue;
            int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
            com.mia.aperture.map.MarkerShapes.disc(g, sx, sy, 1, faint);
        }
    }
```

- [ ] **Step 3: Build**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/OrbitView.java
git commit -m "feat(corridor): draw the coarse corridor trail in the 3D view

```

---

### Task 7: Install and verify in game

**Files:** none (verification only)

- [ ] **Step 1: Build and install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
ls -la "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/" | grep mia-maps
```

Expected: exactly one `mia-maps-*.jar`.

- [ ] **Step 2: Corridor appears and spans the column**

Restart the client. Set navigation to the deep test waypoint (the one that was `▼438` / far below), open the 3D view.

Expected: a faint amber dotted trail (the corridor) runs from you down the shaft toward the waypoint across the whole column — not just the ~168-block local reach. The bright sphere route sits on the near part of it.

- [ ] **Step 3: The local route follows the corridor**

Walk/descend along the corridor.

Expected: the bright block-accurate route now heads along the corridor (down the shaft), and its `next` sphere sits on the corridor rather than pointing at a box edge away from it. As you descend, the route keeps extending down the corridor.

- [ ] **Step 4: Same-layer regression**

Set a nearby waypoint on your current layer and navigate.

Expected: unchanged from Phase 1 — a normal block-accurate route straight to it (the corridor for a nearby in-box goal is short/degenerate and the sub-goal is essentially the destination).

- [ ] **Step 5: Report findings**

Report what happened at each step. If the corridor does NOT span the column (stops partway), note where — that is either the coarse shaft pinching below 16 blocks (an accepted limitation) or a gap in Voxy's LOD data; capture the 3D Stats overlay (sector/LOD) at the stopping point.

---

## Notes for the implementer

- **Do not** create a branch or worktree. Work on `main`.
- **No inline comments** that narrate code; comments explain constraints and *why*, matching the codebase.
- `CorridorMath` and `CorridorFinder` must not import `me.cortex.voxy.*` or `net.minecraft.*`. If you need a Voxy value in them, pass it in as a plain `int`/`boolean[]`.
- The corridor is a **guide**, not a guarantee. It may thread through unmapped cells or miss a passage narrower than one coarse cell. The LOD-0 Tier-2 route is the source of truth for footing. Do not add terrain-safety logic to the corridor tier.
- Keep the corridor's own recompute (`needsCorridor`) distinct from the fine reroute (`needsRecompute`): the corridor is expensive-ish and only needs rebuilding on a new destination or after `CORRIDOR_REFRESH_DIST` of drift, while the fine route reruns every few blocks.
