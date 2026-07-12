# 3D Route-Finding — Phase 1 (Pathfinder core + 3D route) Implementation Plan


**Goal:** Route the player to a chosen waypoint over local terrain and draw the path in the 3D Orbit View (walkable-only: walk / step ±1 / safe drop / short jump). No in-world trail (Phase 2) and no bridging (Phase 3) yet.

**Architecture:** A pure, unit-tested `TraversabilityGrid` (standable-cell test over a Voxy opaque grid) and `Pathfinder` (A\* with walk/step/drop/jump). A `RouteService` samples a box around the player toward the active destination (same Voxy source as `VoxelCloud`), runs the pathfinder on a background thread, and caches a world-space `Route`. `OrbitView` draws the route through the orbit camera. Destination is selected from the waypoint list / by clicking a waypoint in 3D.

**Tech Stack:** Fabric 1.21.11, Java 21, JUnit 5.

**Build/test (PowerShell):** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build` — develop on `main`, no worktree.

---

### Task 1: TraversabilityGrid (standable cells)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/TraversabilityGrid.java`
- Test: `src/test/java/com/mia/aperture/map/TraversabilityGridTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraversabilityGridTest {
    // grid layout matches VoxelCloud: index = (y*gz + z)*gx + x
    private static boolean[] grid(int gx, int gy, int gz) { return new boolean[gx * gy * gz]; }
    private static void set(boolean[] o, int gx, int gz, int x, int y, int z) { o[(y * gz + z) * gx + x] = true; }

    @Test
    void standableNeedsGroundAndHeadroom() {
        int gx = 3, gy = 4, gz = 3;
        boolean[] o = grid(gx, gy, gz);
        set(o, gx, gz, 1, 0, 1); // solid floor cell at y=0
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        assertTrue(g.standable(1, 1, 1));   // stand on the floor: feet y=1, head y=2 air, ground y=0
        assertFalse(g.standable(1, 0, 1));  // inside the solid block
        assertFalse(g.standable(1, 2, 1));  // floating: no ground at y=1
    }

    @Test
    void blockedHeadroomIsNotStandable() {
        int gx = 3, gy = 4, gz = 3;
        boolean[] o = grid(gx, gy, gz);
        set(o, gx, gz, 1, 0, 1); // floor
        set(o, gx, gz, 1, 2, 1); // ceiling at head height
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        assertFalse(g.standable(1, 1, 1)); // head blocked
    }
}
```

- [ ] **Step 2: Run tests, verify they fail** — `.\gradlew test --tests "*TraversabilityGridTest"` → FAIL (class missing).

- [ ] **Step 3: Implement**

```java
package com.mia.aperture.map;

// A bounded box of Voxy opaque data (index layout matches VoxelCloud: (y*gz+z)*gx+x).
// A cell is "standable" if it has solid ground below and 2 air cells (feet+head) of headroom.
public final class TraversabilityGrid {
    private final boolean[] opaque;
    public final int gx, gy, gz;

    public TraversabilityGrid(boolean[] opaque, int gx, int gy, int gz) {
        this.opaque = opaque;
        this.gx = gx; this.gy = gy; this.gz = gz;
    }

    // Out-of-bounds is treated as air (open), except below-floor which just means "no ground".
    public boolean opaque(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) return false;
        return opaque[(y * gz + z) * gx + x];
    }

    public boolean standable(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= gx || z >= gz || y < 1 || y >= gy) return false;
        return opaque(x, y - 1, z) && !opaque(x, y, z) && !opaque(x, y + 1, z);
    }
}
```

- [ ] **Step 4: Run tests, verify pass.**

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/TraversabilityGrid.java src/test/java/com/mia/aperture/map/TraversabilityGridTest.java
git commit -m "feat(route): TraversabilityGrid standable-cell test"
```

---

### Task 2: Pathfinder (A\* — walk / step / drop / jump)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/Pathfinder.java`
- Test: `src/test/java/com/mia/aperture/map/PathfinderTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PathfinderTest {
    private static void floor(boolean[] o, int gx, int gz, int y, int x0, int x1, int z) {
        for (int x = x0; x <= x1; x++) o[(y * gz + z) * gx + x] = true;
    }
    private static final Pathfinder.Params P = new Pathfinder.Params(1, 3, 1);

    @Test
    void findsFlatWalk() {
        int gx = 8, gy = 4, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        floor(o, gx, gz, 0, 0, 7, 1); // solid floor row at y=0, z=1
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g, new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(7, 1, 1), P, 10000);
        assertEquals(Pathfinder.Status.FOUND, r.status());
        assertEquals(new Pathfinder.Cell(0, 1, 1), r.path().get(0));
        assertEquals(new Pathfinder.Cell(7, 1, 1), r.path().get(r.path().size() - 1));
    }

    @Test
    void jumpsAOneWideGapButNotTwo() {
        // floor with a gap at x=3 (one column missing) -> jump of 1; gap x=3..4 -> too wide for maxJumpGap=1
        int gx = 8, gy = 4, gz = 3;
        boolean[] o1 = new boolean[gx * gy * gz];
        floor(o1, gx, gz, 0, 0, 7, 1);
        o1[(0 * gz + 1) * gx + 3] = false; // remove x=3 -> 1-wide gap
        Pathfinder.Result r1 = Pathfinder.find(new TraversabilityGrid(o1, gx, gy, gz),
                new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(7, 1, 1), P, 10000);
        assertEquals(Pathfinder.Status.FOUND, r1.status());

        boolean[] o2 = new boolean[gx * gy * gz];
        floor(o2, gx, gz, 0, 0, 7, 1);
        o2[(0 * gz + 1) * gx + 3] = false;
        o2[(0 * gz + 1) * gx + 4] = false; // 2-wide gap -> unreachable at maxJumpGap=1
        Pathfinder.Result r2 = Pathfinder.find(new TraversabilityGrid(o2, gx, gy, gz),
                new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(7, 1, 1), P, 10000);
        assertNotEquals(Pathfinder.Status.FOUND, r2.status()); // PARTIAL (nearest) — not reached
    }

    @Test
    void dropWithinMaxFall() {
        int gx = 4, gy = 8, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        o[(5 * gz + 1) * gx + 0] = true;  // high floor under x=0 (stand at y=6)
        o[(5 * gz + 1) * gx + 1] = true;
        o[(3 * gz + 1) * gx + 2] = true;  // lower floor under x=2 (stand at y=4) -> drop of 2
        o[(3 * gz + 1) * gx + 3] = true;
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g, new Pathfinder.Cell(0, 6, 1), new Pathfinder.Cell(3, 4, 1), P, 10000);
        assertEquals(Pathfinder.Status.FOUND, r.status());
    }

    @Test
    void noRouteWhenIsolated() {
        int gx = 5, gy = 4, gz = 3;
        boolean[] o = new boolean[gx * gy * gz];
        o[(0 * gz + 1) * gx + 0] = true;  // lone start floor
        o[(0 * gz + 1) * gx + 4] = true;  // lone goal floor, far, unreachable
        TraversabilityGrid g = new TraversabilityGrid(o, gx, gy, gz);
        Pathfinder.Result r = Pathfinder.find(g, new Pathfinder.Cell(0, 1, 1), new Pathfinder.Cell(4, 1, 1), P, 10000);
        assertNotEquals(Pathfinder.Status.FOUND, r.status());
    }
}
```

- [ ] **Step 2: Run tests, verify they fail.**

- [ ] **Step 3: Implement**

```java
package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// A* over standable cells. Moves: walk / step up 1 / drop (<= maxFall) / jump a small air gap
// (<= maxJumpGap). Grid units. Returns the path (start..goal), or the nearest-to-goal path
// (PARTIAL) when the goal isn't reachable, or NO_ROUTE if the start itself isn't standable.
public final class Pathfinder {
    public record Params(int stepUp, int maxFall, int maxJumpGap) {}
    public record Cell(int x, int y, int z) {}
    public enum Status { FOUND, PARTIAL, NO_ROUTE }
    public record Result(List<Cell> path, Status status) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private Pathfinder() {}

    public static Result find(TraversabilityGrid g, Cell start, Cell goal, Params p, int nodeCap) {
        if (!g.standable(start.x(), start.y(), start.z())) return new Result(List.of(), Status.NO_ROUTE);

        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Double.compare(
                Double.longBitsToDouble(a[1]), Double.longBitsToDouble(b[1])));
        long startKey = key(g, start.x(), start.y(), start.z());
        gScore.put(startKey, 0.0);
        open.add(new long[]{startKey, Double.doubleToLongBits(heur(start, goal))});

        long bestKey = startKey;
        double bestH = heur(start, goal);
        int expanded = 0;

        while (!open.isEmpty() && expanded++ < nodeCap) {
            long ck = open.poll()[0];
            int[] c = unkey(g, ck);
            if (c[0] == goal.x() && c[1] == goal.y() && c[2] == goal.z())
                return new Result(reconstruct(g, cameFrom, ck), Status.FOUND);
            double h = heur(new Cell(c[0], c[1], c[2]), goal);
            if (h < bestH) { bestH = h; bestKey = ck; }

            for (double[] nb : neighbors(g, c[0], c[1], c[2], p)) {
                int nx = (int) nb[0], ny = (int) nb[1], nz = (int) nb[2];
                long nk = key(g, nx, ny, nz);
                double tentative = gScore.get(ck) + nb[3];
                if (tentative < gScore.getOrDefault(nk, Double.MAX_VALUE)) {
                    cameFrom.put(nk, ck);
                    gScore.put(nk, tentative);
                    double f = tentative + heur(new Cell(nx, ny, nz), goal);
                    open.add(new long[]{nk, Double.doubleToLongBits(f)});
                }
            }
        }
        // goal not reached: return the path to the closest-to-goal node we saw
        List<Cell> partial = reconstruct(g, cameFrom, bestKey);
        return new Result(partial, partial.size() > 1 ? Status.PARTIAL : Status.NO_ROUTE);
    }

    // neighbours as {x, y, z, cost}
    private static List<double[]> neighbors(TraversabilityGrid g, int x, int y, int z, Params p) {
        List<double[]> out = new ArrayList<>();
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            boolean stepped = false;
            // walk / step up 1 / drop: highest standable in the adjacent column within [y+stepUp, y-maxFall]
            for (int ny = y + p.stepUp(); ny >= y - p.maxFall(); ny--) {
                if (g.standable(nx, ny, nz)) {
                    double cost = 1.0 + (ny < y ? (y - ny) * 0.5 : 0) + (ny > y ? 0.5 : 0);
                    out.add(new double[]{nx, ny, nz, cost});
                    stepped = true;
                    break;
                }
            }
            // jump a small air gap (only if the immediate step failed -> a gap)
            if (!stepped) {
                for (int gap = 1; gap <= p.maxJumpGap(); gap++) {
                    int jx = x + d[0] * (gap + 1), jz = z + d[1] * (gap + 1);
                    for (int jy = y; jy >= y - 2; jy--) {
                        if (g.standable(jx, jy, jz)) {
                            out.add(new double[]{jx, jy, jz, 2.0 + gap});
                            break;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static double heur(Cell a, Cell b) {
        int dx = Math.abs(a.x() - b.x()), dy = Math.abs(a.y() - b.y()), dz = Math.abs(a.z() - b.z());
        return dx + dy + dz;
    }

    private static long key(TraversabilityGrid g, int x, int y, int z) {
        return ((long) y * g.gz + z) * g.gx + x;
    }
    private static int[] unkey(TraversabilityGrid g, long k) {
        int x = (int) (k % g.gx); long r = k / g.gx;
        int z = (int) (r % g.gz); int y = (int) (r / g.gz);
        return new int[]{x, y, z};
    }
    private static List<Cell> reconstruct(TraversabilityGrid g, Map<Long, Long> cameFrom, long end) {
        List<Cell> path = new ArrayList<>();
        Long k = end;
        while (k != null) {
            int[] c = unkey(g, k);
            path.add(new Cell(c[0], c[1], c[2]));
            k = cameFrom.get(k);
        }
        java.util.Collections.reverse(path);
        return path;
    }
}
```

- [ ] **Step 4: Run tests, verify pass.** Adjust cost constants only if a test reveals a genuine logic gap (not to force a pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/Pathfinder.java src/test/java/com/mia/aperture/map/PathfinderTest.java
git commit -m "feat(route): A* pathfinder (walk/step/drop/jump) with partial-path fallback"
```

---

### Task 3: RouteService (sample Voxy toward the destination, run pathfinder, world-space Route)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/Route.java`
- Create: `src/main/java/com/mia/aperture/map/RouteService.java`

No unit test (needs the Voxy engine); verified in-game.

- [ ] **Step 1: Route model**

```java
package com.mia.aperture.map;

import java.util.List;

// A computed route in WORLD coords (shifted X/Y already un-shifted back). Bridges empty in Phase 1.
public record Route(List<double[]> points, List<double[][]> bridges, Pathfinder.Status status) {
    public static final Route EMPTY = new Route(List.of(), List.of(), Pathfinder.Status.NO_ROUTE);
}
```

- [ ] **Step 2: RouteService**

`RouteService` mirrors `VoxelCloud`/`OrbitScene` sampling. It samples an opaque grid for a box
centred on the player (biased toward the destination), builds a `TraversabilityGrid`, converts
the player + destination world positions into grid cells, runs `Pathfinder`, and converts the
resulting cells back to world points. Runs on a daemon thread (pattern: `MapWorker`),
recomputing when the player moves > `REROUTE_DIST` blocks or the destination changes; result
cached in a `volatile Route`.

```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.AbyssUtil;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class RouteService {
    private static final int BOX = 192;          // horizontal grid edge (blocks) at lvl 0
    private static final int VBOX = 128;         // vertical grid extent (blocks) each way
    private static final int LVL = 0;            // finest LOD for accurate footing
    private static final int NODE_CAP = 200_000;
    private static final double REROUTE_DIST = 4.0;
    private static final Pathfinder.Params PARAMS = new Pathfinder.Params(1, 3, 1);

    private static volatile Route route = Route.EMPTY;
    private static volatile double[] destination; // world x,y,z or null
    private static volatile boolean dirty;
    private static double lastX, lastY, lastZ;
    private static Thread thread;

    private RouteService() {}

    public static Route route() { return route; }
    public static double[] destination() { return destination; }

    public static void setDestination(double wx, double wy, double wz) {
        destination = new double[]{wx, wy, wz};
        dirty = true;
        ensureThread();
    }
    public static void clear() { destination = null; route = Route.EMPTY; }

    // Called each client tick (or render) with the player position to trigger re-routes.
    public static void tick(double px, double py, double pz) {
        if (destination == null) return;
        if (dirty || Math.abs(px - lastX) + Math.abs(py - lastY) + Math.abs(pz - lastZ) > REROUTE_DIST) {
            dirty = false;
            lastX = px; lastY = py; lastZ = pz;
            ensureThread();
        }
    }

    private static void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(RouteService::loop, "MIA-Route-Worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        thread.start();
    }

    private static void loop() {
        double[] dst = destination;
        if (dst == null) return;
        try {
            route = compute(dst);
        } catch (Throwable t) {
            System.err.println("[MIA Maps] route compute failed: " + t);
        }
    }

    private static Route compute(double[] dst) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.player == null) return Route.EMPTY;
        WorldEngine engine = rs.getEngine();
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return Route.EMPTY;

        double px = lastX, py = lastY, pz = lastZ;
        int sector = AbyssUtil.getSection(px);
        int shiftX = sector << 14;
        int shiftYc = (240 - sector * 30) * 16;

        // box centred between player and destination (clamped to BOX), in shifted coords
        int cx = (int) Math.floor((px + dst[0]) / 2) - shiftX;
        int cz = (int) Math.floor((pz + dst[2]) / 2);
        int cy = (int) Math.floor((py + dst[1]) / 2) + shiftYc;

        int gx = BOX, gy = 2 * VBOX, gz = BOX;
        int originX = cx - gx / 2, originY = cy - gy / 2, originZ = cz - gz / 2;
        boolean[] opaque = sampleOpaque(engine, colors, originX, originY, originZ, gx, gy, gz);
        TraversabilityGrid grid = new TraversabilityGrid(opaque, gx, gy, gz);

        Pathfinder.Cell start = nearestStandable(grid, (int) Math.floor(px) - shiftX - originX,
                (int) Math.floor(py) + shiftYc - originY, (int) Math.floor(pz) - originZ);
        Pathfinder.Cell goal = clampCell(grid, (int) Math.floor(dst[0]) - shiftX - originX,
                (int) Math.floor(dst[1]) + shiftYc - originY, (int) Math.floor(dst[2]) - originZ);
        if (start == null) return Route.EMPTY;

        Pathfinder.Result res = Pathfinder.find(grid, start, goal, PARAMS, NODE_CAP);
        List<double[]> pts = new ArrayList<>();
        for (Pathfinder.Cell c : res.path()) {
            pts.add(new double[]{ (originX + c.x()) + shiftX + 0.5,
                                  (originY + c.y()) - shiftYc + 0.5,
                                  (originZ + c.z()) + 0.5 });
        }
        return new Route(pts, List.of(), res.status());
    }

    // Fill an opaque grid from Voxy (reuse VoxelCloud's per-section finest-LOD read).
    private static boolean[] sampleOpaque(WorldEngine engine, MapColorSource colors,
            int originX, int originY, int originZ, int gx, int gy, int gz) {
        // Implementation note: extract VoxelCloud's section-fill loop into a shared helper
        // `VoxelCloud.fillOpaque(engine, colors, originX, originY, originZ, gx, gy, gz, lvl)`
        // returning boolean[], and call it here + from VoxelCloud.sample (DRY). Same acquireFinest
        // + (y<<10)|(z<<5)|x indexing.
        return VoxelCloud.fillOpaque(engine, colors, originX, originY, originZ, gx, gy, gz, LVL);
    }

    private static Pathfinder.Cell nearestStandable(TraversabilityGrid g, int x, int y, int z) {
        for (int r = 0; r <= 4; r++)
            for (int dy = -r; dy <= r; dy++)
                if (g.standable(x, y + dy, z)) return new Pathfinder.Cell(x, y + dy, z);
        return null;
    }
    private static Pathfinder.Cell clampCell(TraversabilityGrid g, int x, int y, int z) {
        x = Math.max(0, Math.min(g.gx - 1, x));
        y = Math.max(1, Math.min(g.gy - 1, y));
        z = Math.max(0, Math.min(g.gz - 1, z));
        return new Pathfinder.Cell(x, y, z);
    }
}
```

- [ ] **Step 3: Extract `VoxelCloud.fillOpaque(...)`** from the existing `sample()` section-fill
loop (the `acquireFinest` + cell-copy that populates `opaque[]`), returning `boolean[]`, and have
`VoxelCloud.sample` call it too — so the sampler is shared (DRY) and `RouteService` reuses it.
Keep `argb[]` handling in `sample` (routing doesn't need colours).

- [ ] **Step 4: Build** — `.\gradlew build` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/Route.java src/main/java/com/mia/aperture/map/RouteService.java src/main/java/com/mia/aperture/map/VoxelCloud.java
git commit -m "feat(route): RouteService samples Voxy toward destination + runs pathfinder (worker)"
```

---

### Task 4: Destination selection (Navigate / Stop)

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/WaypointListScreen.java`
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

- [ ] **Step 1: Tick the RouteService** — in the `END_CLIENT_TICK` handler in
`MiaApertureModClient`, call `RouteService.tick(player.getX(), getY(), getZ())` when a player
exists (drives re-routes as you move).

- [ ] **Step 2: "Navigate"/"Stop" in the waypoint list** — in `WaypointListScreen`, add a
per-row **"Go"** button that calls `RouteService.setDestination(w.x + 0.5, w.y + 0.5, w.z + 0.5)`
and a **"Stop"** button (shown when a destination is active) that calls `RouteService.clear()`.

- [ ] **Step 3: Click a waypoint in 3D to navigate** — in `OrbitView`, extend the waypoint
draw loop to record each marker's screen rect; on left-click (not drag) over a waypoint marker,
call `RouteService.setDestination(...)`. (Left-click elsewhere still starts an orbit-drag — only
treat it as a waypoint click when the press lands on a marker.)

- [ ] **Step 4: Build** — `.\gradlew build` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(route): select active destination (Navigate/Stop + click waypoint in 3D)"
```

---

### Task 5: Draw the route in the 3D view

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`

- [ ] **Step 1: Draw the route path** — in `OrbitView.render`, after the waypoints, if
`RouteService.route()` has points, project each point as an offset from the focus
(`OrbitScene.projectHud(px - fxw, py - fyw, pz - fzw)`) and draw connected segments with
`drawLine` in a bright route colour (e.g. `0xFF33DDFF`). Depth-test each sample against
`OrbitScene.depthAt` (like `drawArm`) so the route occludes into terrain. Highlight the
destination waypoint (larger ring). Show the status if `PARTIAL`/`NO_ROUTE` as a small HUD note.

```java
// sketch inside render(), fxw/fyw/fzw = focus world coords (player + focusOffset):
Route rt = RouteService.route();
List<double[]> pts = rt.points();
int prevX = 0, prevY = 0; boolean prev = false;
for (double[] wp : pts) {
    BeaconGeometry.Screen s = OrbitScene.projectHud(wp[0] - fxw, wp[1] - fyw, wp[2] - fzw);
    boolean vis = s.depth() > 0.05
            && OrbitScene.depthAt(s.x(), s.y()) >= s.depth() - 2.0;
    if (vis) {
        int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
        if (prev) drawLine(guiGraphics, prevX, prevY, sx, sy, 0xFF33DDFF);
        prevX = sx; prevY = sy;
    }
    prev = vis;
}
```

- [ ] **Step 2: HUD hint** — add "Go: click a waypoint  ·  Stop: <key>" to the control line;
show route status text when not `FOUND`.

- [ ] **Step 3: Build + install**

```powershell
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build
Copy-Item "build\libs\mia-maps-0.1.1-beta.jar" "<mods-dir>"
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(route): draw the computed route in the 3D orbit view"
```

---

### Task 6: In-game verification

- [ ] Open the fullscreen map → 3D View. Set a nearby waypoint; click **Go** (list) or click its
marker in 3D. A cyan route line appears from you to it, following walkable terrain (steps,
drops, a 1-block hop), occluding into rock.
- [ ] Walk toward it — the route recomputes (re-centres) as you move.
- [ ] Put the waypoint across a wide gap with no walkable way → route shows `PARTIAL`/`NO_ROUTE`
(bridging comes in Phase 3).
- [ ] No FPS hitch (pathfinding is on the worker thread).
- [ ] Tune live if needed: `BOX`, `VBOX`, `NODE_CAP`, `REROUTE_DIST`, `PARAMS` (step/fall/jump).

---

## Notes for Phase 2 / 3
- **Phase 2:** in-world trail — render `RouteService.route()` points via the player-eye camera in the HUD pass (reuse `BeaconRenderer`), + "next step" marker.
- **Phase 3:** bridging — add bridge edges to `Pathfinder.neighbors` (cross up to `maxBridge` air with a high cost, record spans), a bridging toggle (MapSettings), and "build here" markers in both the 3D view and in-world.
