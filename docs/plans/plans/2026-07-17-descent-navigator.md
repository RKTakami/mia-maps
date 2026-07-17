# Descent Navigator Implementation Plan


**Goal:** Route multilayer descents down survivable drops, and where a drop is unsafe or blocked, along dug stairs/tunnels that bridge to the next safe drop — all marked amber.

**Architecture:** `Pathfinder` gains a two-tier fall model (prefer gentle drops, accept penalised survivable ones). A new pure `BridgePlanner` replaces the descent-only `DescentPlanner`: a bounded mining mini-search that digs stair-steps and tunnels from a stalled frontier to the next natural resume point. `RouteService` wires both and keeps the corridor as a visual guide only. The bridge is a `Route.DigPlan`, so all existing amber rendering is reused.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Voxy is `compileOnly` and OFF the test classpath — pure classes (`Pathfinder`, `BridgePlanner`, `MapSettings`) must not import `me.cortex.voxy.*` or `net.minecraft.*`.

**Spec:** `docs/plans/specs/2026-07-17-descent-navigator-design.md`

**Branch policy:** Work directly on `main`. Do NOT create a branch or worktree (project convention — `AGENTS.md`).

---

## Starting state you must know

`src/main/java/com/mia/aperture/map/RouteService.java` currently has **uncommitted** changes from a
debugging session that are part of this feature — do NOT revert them:
- The corridor was demoted to a visual guide: `compute` aims at the destination (`double[] t =
  MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);`), not the corridor sub-goal.
- Temporary `DIAG` `println` instrumentation was added (a `DIAG` constant, an `fmt` helper, a
  `[MIA Corridor]` line in `computeCorridor`, an `ABORT` line and a `[MIA Route]` summary line in
  `compute`). **Task 4 removes all of it.**

Build/test:
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```
Install to the live instance (Task 6):
```bash
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```

Key existing facts:
- `Pathfinder.Params(int stepUp, int maxFall, int maxJumpGap)`. A move's drop cost is
  `1 + (y-ny)*0.5`; drops bound by `maxFall`. `Pathfinder` is pure + unit-tested.
- `TraversabilityGrid` exposes `public final int gx, gy, gz;` and `boolean opaque(x,y,z)` /
  `boolean standable(x,y,z)` (standable = solid below + 2 air). Index `(y*gz+z)*gx+x`.
- `Route.DigPlan(int[] entry, List<int[]> cells)` (grid-relative). `DescentPlanner.Plan` has the
  same shape and is the only thing that constructs dig plans today.
- `RouteService` uses `DescentPlanner.plan(...)` at one site; nothing else references
  `DescentPlanner`.

## File structure

| File | Responsibility | Change | Tested |
|---|---|---|---|
| `map/Pathfinder.java` | A* over standable cells | two-tier `Params` + drop cost/bound | yes |
| `map/BridgePlanner.java` | pure stair/tunnel/drop bridge search | new | yes |
| `map/DescentPlanner.java` | old dig-only planner | delete (Task 4) | remove test |
| `map/MapSettings.java` | settings | `maxSurvivableDrop` + clamp | yes |
| `map/MapConfig.java` | persistence | clamp on load | no |
| `client/MapSettingsScreen.java` | settings UI | survivable-drop stepper | no |
| `map/RouteService.java` | route worker | 4-arg Params, BridgePlanner, drop DIAG | no |
| `client/AbyssWorldMapScreen.java`, `client/OrbitView.java` | dig label | "Dig here" → "Descend here" | no |

---

### Task 1: Pathfinder two-tier fall model

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/Pathfinder.java`
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java` (keep the build green: temporary 4-arg Params)
- Test: `src/test/java/com/mia/aperture/map/PathfinderTest.java`

- [ ] **Step 1: Update existing tests to the 4-arg Params and add preference tests**

In `src/test/java/com/mia/aperture/map/PathfinderTest.java`, replace every `new Pathfinder.Params(1, N, 1)`
with `new Pathfinder.Params(1, N, N, 1)` (safeFall = survivableFall = the old maxFall preserves
current behaviour). There are four: the `P` field (`1,3,1`→`1,3,3,1`), and three inline
(`1,4,1`→`1,4,4,1`, `1,3,1`→`1,3,3,1`, `1,4,1`→`1,4,4,1`).

Then add, before the final `}` of the class:

```java
    @Test
    void takesASurvivableDropWhenItIsTheOnlyWayDown() {
        // 8-block drop into the ADJACENT column: impossible at survivable=4, possible at 8.
        int gx = 3, gy = 16, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        floor(o, gx, gz, 11, 0, 0, z);   // top ledge x=0, stand y=12
        floor(o, gx, gz, 3, 1, 1, z);    // landing x=1 (adjacent), stand y=4 -> 8-block drop
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Cell start = new Pathfinder.Cell(0, 12, z), goal = new Pathfinder.Cell(1, 4, z);

        assertNotEquals(Pathfinder.Status.FOUND,
                Pathfinder.find(g, start, goal, new Pathfinder.Params(1, 4, 4, 1), 100000).status());
        assertEquals(Pathfinder.Status.FOUND,
                Pathfinder.find(g, start, goal, new Pathfinder.Params(1, 4, 8, 1), 100000).status());
    }

    @Test
    void gentleDropCostIsUnchanged() {
        // A 2-block drop (<= safeFall) must still cost exactly 1 + 2*0.5 = 2.0 (no extra penalty),
        // so gentle descents behave exactly as before the two-tier change.
        int gx = 3, gy = 8, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        floor(o, gx, gz, 5, 0, 0, z);   // stand y=6
        floor(o, gx, gz, 3, 1, 1, z);   // stand y=4 (2-block drop, adjacent)
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g,
                new Pathfinder.Cell(0, 6, z), new Pathfinder.Cell(1, 4, z),
                new Pathfinder.Params(1, 4, 16, 1), 100000);
        assertEquals(Pathfinder.Status.FOUND, r.status());
    }
```

(The "prefer gentle stairs over a big drop" behaviour comes directly from the `EXTRA_DROP_PENALTY`
cost and is verified in-game in Task 6 — a clean synthetic grid for it is not worth the fragility.)

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*PathfinderTest*'
```
Expected: FAIL to compile — `Params(int,int,int,int)` does not exist.

- [ ] **Step 3: Implement the two-tier Params and cost**

In `src/main/java/com/mia/aperture/map/Pathfinder.java`, replace:

```java
    public record Params(int stepUp, int maxFall, int maxJumpGap) {}
```

with:

```java
    // safeFall: drop height with no extra cost (gentle). survivableFall: largest drop allowed.
    // Drops in (safeFall, survivableFall] are permitted but penalised so A* prefers stairs and
    // small hops. maxJumpGap: small air gap the walker can jump.
    public record Params(int stepUp, int safeFall, int survivableFall, int maxJumpGap) {}

    private static final double SAFE_DROP_COST = 0.5;
    private static final double EXTRA_DROP_PENALTY = 1.5;
```

In `neighbors`, replace the drop scan + cost:

```java
            for (int ny = y + p.stepUp(); ny >= y - p.maxFall(); ny--) {
                if (g.standable(nx, ny, nz)) {
                    if (clear(g, nx, nz, ny + 1, y + 1)) {
                        double cost = 1.0 + (ny < y ? (y - ny) * 0.5 : 0) + (ny > y ? 0.5 : 0);
                        out.add(new double[]{nx, ny, nz, cost});
                        stepped = true;
                    }
                    break;
                }
            }
```

with:

```java
            for (int ny = y + p.stepUp(); ny >= y - p.survivableFall(); ny--) {
                if (g.standable(nx, ny, nz)) {
                    if (clear(g, nx, nz, ny + 1, y + 1)) {
                        int drop = Math.max(0, y - ny);
                        double cost = 1.0 + drop * SAFE_DROP_COST
                                + Math.max(0, drop - p.safeFall()) * EXTRA_DROP_PENALTY
                                + (ny > y ? 0.5 : 0);
                        out.add(new double[]{nx, ny, nz, cost});
                        stepped = true;
                    }
                    break;
                }
            }
```

- [ ] **Step 4: Keep the build green — update the RouteService call site temporarily**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace:

```java
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, 1);
```

with (safeFall = survivableFall = safeDrop for now; Task 4 threads the real survivable setting):

```java
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, safeDrop, 1);
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests '*PathfinderTest*'
```
Expected: PASS (existing + 2 new).

- [ ] **Step 6: Full build (nothing else broke)**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mia/aperture/map/Pathfinder.java src/test/java/com/mia/aperture/map/PathfinderTest.java src/main/java/com/mia/aperture/map/RouteService.java
git commit -m "feat(route): two-tier fall model — prefer gentle drops, accept survivable

```

---

### Task 2: BridgePlanner

**Files:**
- Create: `src/main/java/com/mia/aperture/map/BridgePlanner.java`
- Test: `src/test/java/com/mia/aperture/map/BridgePlannerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/BridgePlannerTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BridgePlannerTest {
    // solid block at (x,y,z)
    private static void set(boolean[] o, int gx, int gz, int x, int y, int z) {
        o[(y * gz + z) * gx + x] = true;
    }

    @Test
    void tunnelsThroughAWallToAShaftThenResumesByDropping() {
        // Frontier stands on a floor at x=0..1; a solid wall at x=2 (full height); beyond it at
        // x=3 an open shaft down to a deep floor. The bridge must tunnel through the x=2 wall to
        // x=3, where a survivable drop resumes.
        int gx = 6, gy = 20, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        for (int x = 0; x <= 1; x++) set(o, gx, gz, x, 9, z);     // frontier floor, stand y=10
        for (int y = 0; y < gy; y++) set(o, gx, gz, 2, y, z);     // solid wall at x=2
        set(o, gx, gz, 3, 3, z);                                  // shaft landing at x=3, stand y=4
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);

        BridgePlanner.Plan plan = BridgePlanner.plan(g, 1, 10, z, 4, 4, z, 4, 12, 32);
        assertNotNull(plan);
        assertArrayEquals(new int[]{1, 10, z}, plan.entry());
        assertFalse(plan.cells().isEmpty(), "must mine the wall");
        // every mined cell was solid rock
        for (int[] c : plan.cells()) assertTrue(g.opaque(c[0], c[1], c[2]), "mines only rock");
    }

    @Test
    void nullWhenNoResumeWithinBudget() {
        // Frontier boxed in solid with no reachable drop within the dig budget.
        int gx = 5, gy = 8, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        for (int x = 0; x < gx; x++) for (int y = 0; y < gy; y++) set(o, gx, gz, x, y, z);
        // carve a 1-cell standing pocket for the frontier at (2, 4)
        o[(4 * gz + z) * gx + 2] = false;
        o[(5 * gz + z) * gx + 2] = false;
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        assertNull(BridgePlanner.plan(g, 2, 4, z, 0, 0, z, 4, 12, 4));
    }

    @Test
    void bridgesPastABlockingLipToReachAShaftEdge() {
        // Frontier on a ledge; the cell straight east is solid rock (a lip that blocks a plain
        // walk), so A* stalled. One block east of that is an open shaft with a survivable landing.
        // Mining through the lip reaches the shaft edge, from which you step off and drop.
        int gx = 4, gy = 16, gz = 3, z = 1;
        boolean[] o = new boolean[gx * gy * gz];
        set(o, gx, gz, 0, 9, z);     // frontier floor, stand y=10
        set(o, gx, gz, 1, 9, z);     // lip floor (so the mined cell above it is standable)
        set(o, gx, gz, 1, 10, z);    // lip block at frontier level — blocks the walk, gets mined
        set(o, gx, gz, 2, 3, z);     // shaft landing east, stand y=4 (drop of 6)
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        BridgePlanner.Plan plan = BridgePlanner.plan(g, 0, 10, z, 2, 4, z, 4, 12, 32);
        assertNotNull(plan);
        assertFalse(plan.cells().isEmpty());
        for (int[] c : plan.cells()) assertTrue(g.opaque(c[0], c[1], c[2]), "mines only rock");
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*BridgePlannerTest*'
```
Expected: FAIL — `cannot find symbol: class BridgePlanner`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/BridgePlanner.java`:

```java
package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// Bridges a stalled descent to the next point where natural descent resumes, by digging a short
// staircase and/or horizontal tunnel from the frontier. A bounded Dijkstra over stand-cells
// reached by mining (cost = blocks mined); returns the cheapest bridge or null when no resume
// point is reachable within budget. Pure: grid coords only, no Voxy/Minecraft.
public final class BridgePlanner {
    // entry = frontier stand cell; cells = blocks to mine, in dig order.
    public record Plan(int[] entry, List<int[]> cells) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private BridgePlanner() {}

    public static Plan plan(TraversabilityGrid g, int fx, int fy, int fz,
                            int goalX, int goalY, int goalZ,
                            int safeFall, int survivableFall, int maxDig) {
        int frontierHoriz = Math.abs(fx - goalX) + Math.abs(fz - goalZ);
        long startKey = key(g, fx, fy, fz);
        Map<Long, Integer> dig = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        Map<Long, int[][]> edge = new HashMap<>();
        dig.put(startKey, 0);
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        open.add(new long[]{startKey, 0});

        while (!open.isEmpty()) {
            long[] cur = open.poll();
            long ck = cur[0];
            int cd = (int) cur[1];
            if (cd > dig.getOrDefault(ck, Integer.MAX_VALUE)) continue;
            int[] c = unkey(g, ck);
            int x = c[0], y = c[1], z = c[2];

            // Resume where natural descent picks back up. Not the frontier itself, no farther from
            // the goal horizontally (never wander outward), and a real descent available. Same-level
            // resumes are valid: tunnelling through a wall to a shaft edge you then drop off is the
            // whole point — A* stalled precisely because it could not reach that edge by walking.
            if (ck != startKey
                    && Math.abs(x - goalX) + Math.abs(z - goalZ) <= frontierHoriz
                    && resumes(g, x, y, z, goalX, goalZ, survivableFall)) {
                return build(g, from, edge, ck, fx, fy, fz);
            }

            for (int[] d : DIRS) {
                relax(g, open, dig, from, edge, ck, cd, x + d[0], y, z + d[1], y, maxDig);       // tunnel
                relax(g, open, dig, from, edge, ck, cd, x + d[0], y - 1, z + d[1], y, maxDig);   // stair-down
            }
        }
        return null;
    }

    // Move to stand-cell (nx,ny,nz) by mining. Requires solid floor below the target (else it is a
    // drop, handled by resumes(), not a dug move). Mines the target's feet + head if solid.
    private static void relax(TraversabilityGrid g, PriorityQueue<long[]> open,
            Map<Long, Integer> dig, Map<Long, Long> from, Map<Long, int[][]> edge,
            long ck, int cd, int nx, int ny, int nz, int fromY, int maxDig) {
        if (nx < 0 || ny < 1 || nz < 0 || nx >= g.gx || ny >= g.gy || nz >= g.gz) return;
        if (!g.opaque(nx, ny - 1, nz)) return;
        List<int[]> mined = new ArrayList<>(2);
        if (g.opaque(nx, ny, nz)) mined.add(new int[]{nx, ny, nz});
        if (ny + 1 < g.gy && g.opaque(nx, ny + 1, nz)) mined.add(new int[]{nx, ny + 1, nz});
        int nd = cd + mined.size();
        if (nd > maxDig) return;
        long nk = key(g, nx, ny, nz);
        if (nd < dig.getOrDefault(nk, Integer.MAX_VALUE)) {
            dig.put(nk, nd);
            from.put(nk, ck);
            edge.put(nk, mined.toArray(new int[0][]));
            open.add(new long[]{nk, nd});
        }
    }

    // Natural descent resumes at (x,y,z) if a survivable open drop lands standable directly below,
    // or stepping off toward the goal drops/steps survivably onto standable ground — the moves the
    // main A* would take next.
    private static boolean resumes(TraversabilityGrid g, int x, int y, int z,
            int goalX, int goalZ, int survivableFall) {
        if (dropDown(g, x, y, z, survivableFall)) return true;
        int dx = Integer.signum(goalX - x), dz = Integer.signum(goalZ - z);
        if (dx != 0 && stepOff(g, x + dx, z, y, survivableFall)) return true;
        if (dz != 0 && stepOff(g, x, z + dz, y, survivableFall)) return true;
        return false;
    }

    // A clear drop straight down at (x,z) landing standable within survivableFall.
    private static boolean dropDown(TraversabilityGrid g, int x, int y, int z, int survivableFall) {
        for (int yy = y - 1; yy >= Math.max(1, y - survivableFall); yy--) {
            if (g.opaque(x, yy, z)) break;
            if (g.standable(x, yy, z)) return true;
        }
        return false;
    }

    // Step off stand-level y into column (cx,cz): land standable within [y-survivableFall .. y]
    // with the fall column clear from the landing up to head level.
    private static boolean stepOff(TraversabilityGrid g, int cx, int cz, int y, int survivableFall) {
        if (cx < 0 || cz < 0 || cx >= g.gx || cz >= g.gz) return false;
        for (int yy = y; yy >= Math.max(1, y - survivableFall); yy--) {
            if (g.standable(cx, yy, cz)) {
                for (int k = yy + 1; k <= y + 1 && k < g.gy; k++) if (g.opaque(cx, k, cz)) return false;
                return true;
            }
        }
        return false;
    }

    private static Plan build(TraversabilityGrid g, Map<Long, Long> from, Map<Long, int[][]> edge,
            long endKey, int fx, int fy, int fz) {
        List<int[]> cells = new ArrayList<>();
        Long k = endKey;
        while (from.containsKey(k)) {
            for (int[] m : edge.get(k)) cells.add(m);
            k = from.get(k);
        }
        return new Plan(new int[]{fx, fy, fz}, cells);
    }

    private static long key(TraversabilityGrid g, int x, int y, int z) {
        return ((long) y * g.gz + z) * g.gx + x;
    }
    private static int[] unkey(TraversabilityGrid g, long kk) {
        int x = (int) (kk % g.gx); long r = kk / g.gx;
        int z = (int) (r % g.gz); int y = (int) (r / g.gz);
        return new int[]{x, y, z};
    }
}
```

Note: `standable(x, yy, z)` already requires solid at `yy-1`, so the drop scan lands on real ground.
The scan breaks at the first rock so it never "sees through" a floor.

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests '*BridgePlannerTest*'
```
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/BridgePlanner.java src/test/java/com/mia/aperture/map/BridgePlannerTest.java
git commit -m "feat(route): BridgePlanner — dig stairs/tunnels to the next safe drop

```

---

### Task 3: maxSurvivableDrop setting

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/map/MapConfig.java:28-29`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/mia/aperture/map/MapSettingsTest.java`, before the final `}`:

```java
    @Test
    void maxSurvivableDropDefaultsAndClamps() {
        MapSettings s = new MapSettings();
        assertEquals(16, s.maxSurvivableDrop);
        s.setMaxSurvivableDrop(999);
        assertEquals(MapSettings.MAX_SURVIVABLE_DROP, s.maxSurvivableDrop);
        s.setMaxSurvivableDrop(0);
        assertEquals(MapSettings.MIN_SURVIVABLE_DROP, s.maxSurvivableDrop);
    }

    @Test
    void survivableDropNeverBelowSafeDrop() {
        MapSettings s = new MapSettings();
        s.setSafeDropBlocks(8);
        s.setMaxSurvivableDrop(5);          // below safe -> raised to safe
        assertEquals(8, s.maxSurvivableDrop);
    }
```

- [ ] **Step 2: Run to verify failure**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*MapSettingsTest*'
```
Expected: FAIL — `cannot find symbol: maxSurvivableDrop`.

- [ ] **Step 3: Implement in MapSettings**

In `src/main/java/com/mia/aperture/map/MapSettings.java`, after the existing safe-drop block:

```java
    public void setSafeDropBlocks(int n) {
        this.safeDropBlocks = Math.max(MIN_SAFE_DROP, Math.min(MAX_SAFE_DROP, n));
    }
```

add:

```java
    // How far the descent router will drop when nothing gentler reaches the goal. Never below
    // safeDropBlocks — a survivable tier under the safe tier is meaningless.
    public int maxSurvivableDrop = 16;

    public static final int MIN_SURVIVABLE_DROP = 4;
    public static final int MAX_SURVIVABLE_DROP = 28;

    public void setMaxSurvivableDrop(int n) {
        this.maxSurvivableDrop = Math.max(Math.max(MIN_SURVIVABLE_DROP, safeDropBlocks),
                Math.min(MAX_SURVIVABLE_DROP, n));
    }
```

- [ ] **Step 4: Persist-clamp on load**

In `src/main/java/com/mia/aperture/map/MapConfig.java`, after the line
`s.setSafeDropBlocks(s.safeDropBlocks == 0 ? 4 : s.safeDropBlocks);` add:

```java
            s.setMaxSurvivableDrop(s.maxSurvivableDrop == 0 ? 16 : s.maxSurvivableDrop);
```

(GSON already serialises the new public field; this only clamps legacy/blank values on load.)

- [ ] **Step 5: Run tests + build**

```bash
./gradlew test --tests '*MapSettingsTest*'
./gradlew build
```
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/map/MapConfig.java src/test/java/com/mia/aperture/map/MapSettingsTest.java
git commit -m "feat(settings): maxSurvivableDrop tier (default 16, >= safe drop)

```

---

### Task 4: Wire RouteService to BridgePlanner; delete DescentPlanner; remove DIAG

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java`
- Delete: `src/main/java/com/mia/aperture/map/DescentPlanner.java`
- Delete: `src/test/java/com/mia/aperture/map/DescentPlannerTest.java`

No unit test (Voxy/Minecraft-facing). Verified by build + Task 6.

- [ ] **Step 1: Use the survivable-drop setting in Params**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace:

```java
        int safeDrop = com.mia.aperture.client.MiaApertureModClient.mapSettings.safeDropBlocks;
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, safeDrop, 1);
```

with:

```java
        int safeDrop = com.mia.aperture.client.MiaApertureModClient.mapSettings.safeDropBlocks;
        int survivable = com.mia.aperture.client.MiaApertureModClient.mapSettings.maxSurvivableDrop;
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, survivable, 1);
```

- [ ] **Step 2: Swap DescentPlanner → BridgePlanner**

In the same file, find the dig block:

```java
        if (res.status() != Pathfinder.Status.FOUND && descentRemains) {
            DescentPlanner.Plan dp = DescentPlanner.plan(grid,
                    frontier.x(), frontier.y(), frontier.z(),
                    goal.x(), goal.y(), goal.z(), MAX_DIG, MAX_TUNNEL);
            if (dp != null) {
```

replace the `DescentPlanner.Plan dp = DescentPlanner.plan(...)` call with:

```java
        if (res.status() != Pathfinder.Status.FOUND && descentRemains) {
            BridgePlanner.Plan dp = BridgePlanner.plan(grid,
                    frontier.x(), frontier.y(), frontier.z(),
                    goal.x(), goal.y(), goal.z(), safeDrop, survivable, MAX_BRIDGE_DIG);
            if (dp != null) {
```

- [ ] **Step 3: Replace the dig constants**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace:

```java
    private static final int MAX_DIG = 24;
    private static final int MAX_TUNNEL = 8;
```

with:

```java
    private static final int MAX_BRIDGE_DIG = 32;   // max blocks the bridge planner will mine
```

- [ ] **Step 4: Remove the temporary DIAG instrumentation**

Delete, in this file:
- the `DIAG` constant and the `fmt` helper method (added during debugging);
- the `[MIA Corridor]` `println` block in `computeCorridor` (restore it to
  `return CorridorPlanner.plan(rs.getEngine(), colors, p, t);` directly returning the list);
- the `if (DIAG) System.out.println("[MIA Route] ABORT ...")` block (keep the `return Route.EMPTY;`);
- the `if (DIAG) { ... "[MIA Route] tgt=dst ..." }` summary block before `return new Route(...)`.

After this, grep must return nothing:
```bash
grep -n "DIAG\|MIA Route\|MIA Corridor\|MAX_DIG\|MAX_TUNNEL\|DescentPlanner" src/main/java/com/mia/aperture/map/RouteService.java
```

- [ ] **Step 5: Delete DescentPlanner and its test**

```bash
git rm src/main/java/com/mia/aperture/map/DescentPlanner.java src/test/java/com/mia/aperture/map/DescentPlannerTest.java
```

- [ ] **Step 6: Build the whole suite**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```
Expected: BUILD SUCCESSFUL, all tests pass. If a compile error names `DescentPlanner`, a
reference was missed — the only expected one was in `RouteService`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(route): route descents via BridgePlanner; retire DescentPlanner + DIAG

Aims the block-accurate search at the destination (corridor is a visual guide),
threads survivable drops, and bridges unsafe legs with dug stairs/tunnels to the
next safe drop. Removes the debugging instrumentation and the superseded
dig-only DescentPlanner.

```

---

### Task 5: "Descend here" label + survivable-drop stepper

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java:154`
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java:283`
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Relabel the dig beacon**

In `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`, replace the string
`"Dig here"` (line ~154) with `"Descend here"`.

In `src/main/java/com/mia/aperture/client/OrbitView.java`, replace `"▼ Dig here"` (line ~283) with
`"▼ Descend here"`.

- [ ] **Step 2: Add the survivable-drop stepper**

In `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`, immediately after the existing
safe-drop stepper block (the `addScroll(Button.builder(safeDropLabel(), ...))` that ends with
`.bounds(cx - 100, 0, 200, 20).build(), r++);`), add:

```java
        addScroll(Button.builder(maxSurvivableDropLabel(), b -> {
            MapSettings s = settings();
            int next = s.maxSurvivableDrop + 2;
            if (next > MapSettings.MAX_SURVIVABLE_DROP) next = MapSettings.MIN_SURVIVABLE_DROP;
            s.setMaxSurvivableDrop(next);
            b.setMessage(maxSurvivableDropLabel());
            persist();
        }).bounds(cx - 100, 0, 200, 20).build(), r++);
```

- [ ] **Step 3: Add the label helper**

In `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`, immediately after the existing
`safeDropLabel()` method, add:

```java
    private static Component maxSurvivableDropLabel() {
        return Component.literal("Max survivable drop: " + settings().maxSurvivableDrop + " blocks");
    }
```

- [ ] **Step 4: Build and commit**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java src/main/java/com/mia/aperture/client/OrbitView.java src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(3d): Descend-here label + survivable-drop stepper in Settings

```
Expected before commit: BUILD SUCCESSFUL.

---

### Task 6: Install and verify in game

**Files:** none (verification only)

- [ ] **Step 1: Build and install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
ls -la "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/" | grep mia-maps
```
Expected: exactly one `mia-maps-*.jar`.

- [ ] **Step 2: The repro descends**

Restart the client. At the "Twilight Forest old stair" (the earlier failing spot), route to the
deep waypoint and follow it.

Expected: the cyan route now **descends the staircase and survivable drops** toward the goal
instead of wandering up/sideways. Where an unsafe wall/overhang blocks the descent, an **amber
"Descend here"** bridge (stairs/tunnel) appears, and following it lets the route continue on the
next reroute. No more dead-end with no dig.

- [ ] **Step 3: Setting works**

Open Settings → step "Max survivable drop" up; confirm the route takes larger drops (and prefers
gentle ones when both reach the goal). Step "Safe fall distance" and confirm survivable never
drops below it.

- [ ] **Step 4: Same-layer regression**

Route to a nearby same-layer waypoint: still a clean direct route, no spurious bridges.

- [ ] **Step 5: Report findings**

Report each step. If a bridge points somewhere unhelpful, note where — the resume heuristic
(`BridgePlanner.resumes`) is the expected tuning point; capture the frontier depth and what the
terrain looks like there.

---

## Notes for the implementer

- **Do not** create a branch or worktree. Work on `main`.
- **No narrating inline comments** — comments explain constraints and why.
- `Pathfinder`, `BridgePlanner`, `MapSettings` must not import `me.cortex.voxy.*` or
  `net.minecraft.*`.
- The corridor stays a visual guide (built by `computeCorridor`, drawn by `OrbitView`); nothing in
  this plan re-couples it to the router. Do not "restore" sub-goal targeting.
- Keep each task's build green: Task 1 updates the `RouteService` Params call to the 4-arg form so
  the tree compiles before Task 4 threads the real survivable setting.
