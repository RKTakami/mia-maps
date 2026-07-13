# Vertical Descent — Plan B (Dig/tunnel fallback) Implementation Plan

> the plan. Steps use checkbox (`- [ ]`) syntax.
> **Project rule: work directly on `main`, no worktree.** Requires Plan A merged first
> (needs the `safeDropBlocks` setting and settings-driven `Params` in `RouteService`).

**Goal:** When natural descent (Plan A) stalls above an overhang, recommend one bounded
**L-shaped dig path** — dig straight down through the overhang mass, then a short horizontal
break-out tunnel toward the goal — highlighted in the 3D view, minimap, and fullscreen map. The
mod never breaks or places blocks; it only shows where to.

**Architecture:** New pure `DescentPlanner` (grid coords, unit-tested). `Route` gains an
optional `DigPlan` (world coords). `RouteService.compute` invokes the planner on a stalled
descent and converts grid→world. Three renderers draw the dig cells + a "dig here" entry marker.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5.

**Spec:** `docs/plans/specs/2026-07-12-vertical-descent-routing-design.md`

---

### Task 1: `DescentPlanner` (pure) + tests

**Files:**
- Create: `src/main/java/com/mia/aperture/map/DescentPlanner.java`
- Create: `src/test/java/com/mia/aperture/map/DescentPlannerTest.java`

- [ ] **Step 1: Write the planner**

```java
package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.List;

// Plans one descent "leg" through solid rock when open descent is blocked (an overhang):
// dig straight down, then a short horizontal break-out tunnel toward the goal. Pure, grid
// coordinates. Returns null when no safe dig leg fits the bounds (caller keeps the partial
// route rather than recommending a dig into a void).
public final class DescentPlanner {
    // entry = the frontier stand cell (shaft mouth); cells = blocks to mine, in order.
    public record Plan(int[] entry, List<int[]> cells) {}

    private DescentPlanner() {}

    public static Plan plan(TraversabilityGrid g, int fx, int fy, int fz,
                            int gx, int gy, int gz, int maxDig, int maxTunnel) {
        // Dominant horizontal direction toward the goal.
        int dirx, dirz;
        if (Math.abs(gx - fx) >= Math.abs(gz - fz)) {
            dirx = gx == fx ? 1 : Integer.signum(gx - fx);
            dirz = 0;
        } else {
            dirx = 0;
            dirz = gz == fz ? 1 : Integer.signum(gz - fz);
        }

        List<int[]> cells = new ArrayList<>();
        int x = fx, z = fz, y = fy;   // standing level y; floor at y-1
        boolean airBelow = false;
        int by = fy;                  // break-out level for the tunnel phase

        // Phase 1: dig straight down.
        for (int d = 0; d < maxDig; d++) {
            int floorY = y - 1;
            if (floorY < 1) break;                         // world bottom: solid progress
            if (!g.opaque(x, floorY, z)) { airBelow = true; by = y; break; } // overhang underside
            cells.add(new int[]{x, floorY, z});            // mine the floor block, descend one
            y = floorY;
            if (y <= gy) return new Plan(new int[]{fx, fy, fz}, cells);           // reached goal depth
            if (g.standable(x + dirx, y, z + dirz))                               // natural ledge
                return new Plan(new int[]{fx, fy, fz}, cells);
        }

        if (!airBelow) {
            // Dug through solid to the leg cap / bedrock, bottom still solid -> safe progress.
            return cells.isEmpty() ? null : new Plan(new int[]{fx, fy, fz}, cells);
        }

        // Phase 2: tunnel horizontally toward the goal at level `by`.
        for (int t = 1; t <= maxTunnel; t++) {
            int nx = x + dirx * t, nz = z + dirz * t;
            int floorY = by - 1;
            if (nx < 0 || nz < 0 || nx >= g.gx || nz >= g.gz) break;
            if (!g.opaque(nx, floorY, nz)) break;          // floor gone -> would fall; stop
            if (g.opaque(nx, by, nz)) cells.add(new int[]{nx, by, nz});
            if (by + 1 < g.gy && g.opaque(nx, by + 1, nz)) cells.add(new int[]{nx, by + 1, nz});
            int ex = nx + dirx, ez = nz + dirz;
            boolean inb = ex >= 0 && ez >= 0 && ex < g.gx && ez < g.gz;
            boolean openAhead = inb && !g.opaque(ex, by, ez) && !g.opaque(ex, by + 1, ez);
            boolean ledgeAhead = g.standable(ex, by, ez);
            if (openAhead || ledgeAhead)
                return new Plan(new int[]{fx, fy, fz}, cells);
        }
        // Air below with no safe break-out within bounds -> don't recommend digging into a void.
        return null;
    }
}
```

- [ ] **Step 2: Write the tests**

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DescentPlannerTest {
    private static int idx(int gx, int gz, int x, int y, int z) { return (y * gz + z) * gx + x; }

    @Test
    void straightDownReachesLedge() {
        int gx = 5, gy = 16, gz = 3, z = 1;
        boolean[] op = new boolean[gx * gy * gz];
        // frontier pillar at x=0: solid floor y=8..11 (stand at y=12)
        for (int y = 8; y <= 11; y++) op[idx(gx, gz, 0, y, z)] = true;
        // a ledge one step toward the goal (x=1) at stand-level y=8: floor at y=7
        op[idx(gx, gz, 1, 7, z)] = true;
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        DescentPlanner.Plan p = DescentPlanner.plan(g, 0, 12, z, 4, 0, z, 24, 8);
        assertNotNull(p);
        for (int[] c : p.cells()) assertEquals(0, c[0]);   // vertical only, no tunnel
        assertFalse(p.cells().isEmpty());
    }

    @Test
    void overhangNeedsTunnel() {
        int gx = 6, gy = 16, gz = 3, z = 1;
        boolean[] op = new boolean[gx * gy * gz];
        // frontier pillar x=0 solid y=10..11 (stand y=12); below y<=9 is void under an overhang
        op[idx(gx, gz, 0, 11, z)] = true;
        op[idx(gx, gz, 0, 10, z)] = true;
        // overhang mass one step toward goal at x=1: solid at body/head (y=10,11) with floor at y=9
        op[idx(gx, gz, 1, 9, z)] = true;
        op[idx(gx, gz, 1, 10, z)] = true;
        op[idx(gx, gz, 1, 11, z)] = true;
        // x>=2 open (air) -> break-out to the open face
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        DescentPlanner.Plan p = DescentPlanner.plan(g, 0, 12, z, 5, 0, z, 24, 8);
        assertNotNull(p);
        boolean hasVertical = p.cells().stream().anyMatch(c -> c[0] == 0);
        boolean hasTunnel = p.cells().stream().anyMatch(c -> c[0] == 1);
        assertTrue(hasVertical && hasTunnel);
    }

    @Test
    void voidWithNoBreakoutReturnsNull() {
        int gx = 5, gy = 16, gz = 3, z = 1;
        boolean[] op = new boolean[gx * gy * gz];
        // frontier floor only at x=0,y=11 (stand y=12); everything below/around is air
        op[idx(gx, gz, 0, 11, z)] = true;
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        DescentPlanner.Plan p = DescentPlanner.plan(g, 0, 12, z, 4, 0, z, 24, 8);
        assertNull(p);
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test --tests "com.mia.aperture.map.DescentPlannerTest"`
Expected: 3 PASS. If a geometry assertion is off, adjust the fixture blocks (not the planner)
until the intended shape (vertical-only / L-shape / null) is produced.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/map/DescentPlanner.java src/test/java/com/mia/aperture/map/DescentPlannerTest.java
git commit -m "feat(descent): DescentPlanner L-shaped dig/tunnel leg + tests"
```

---

### Task 2: `DigPlan` on `Route`

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/Route.java`

- [ ] **Step 1: Add the record + field**

Replace the whole `Route.java` body with:

```java
package com.mia.aperture.map;

import java.util.List;

// A computed route in WORLD coords (shifted X/Y already un-shifted). Bridges empty in Phase 1.
// `dig` is non-null only when Plan B recommended a dig/tunnel leg for descent.
public record Route(List<double[]> points, List<double[][]> bridges,
                    DigPlan dig, Pathfinder.Status status) {
    // entry = shaft mouth (world center); cells = ordered blocks to mine (world centers).
    public record DigPlan(double[] entry, List<double[]> cells) {}

    public static final Route EMPTY =
        new Route(List.of(), List.of(), null, Pathfinder.Status.NO_ROUTE);
}
```

- [ ] **Step 2: Build (expect call-site errors in RouteService — fixed next task)**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: FAIL — `RouteService` still calls the 3-arg `Route(...)`. That's fine; Task 3 fixes it.
(If you prefer a green build here, do Task 3 in the same commit.)

- [ ] **Step 3: Commit (with Task 3) — see Task 3 Step 4.**

---

### Task 3: Trigger + grid→world conversion in `RouteService`

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java`

Context: `compute` currently ends with:

```java
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, params, NODE_CAP);
        List<double[]> pts = new ArrayList<>(res.path().size());
        for (Pathfinder.Cell c : res.path()) {
            pts.add(new double[]{
                    (originX + c.x()) + shiftX + 0.5,
                    (originY + c.y()) - shiftYc + 0.5,
                    (originZ + c.z()) + 0.5});
        }
        return new Route(pts, List.of(), res.status());
```

- [ ] **Step 1: Add planner bounds constants**

Near the other constants at the top of `RouteService` (e.g., below `NODE_CAP`):

```java
    private static final int MAX_DIG = 24;
    private static final int MAX_TUNNEL = 8;
```

- [ ] **Step 2: Replace the return block with the trigger + conversion**

```java
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, params, NODE_CAP);
        List<double[]> pts = new ArrayList<>(res.path().size());
        for (Pathfinder.Cell c : res.path()) {
            pts.add(cellToWorld(c.x(), c.y(), c.z(), originX, originY, originZ, shiftX, shiftYc));
        }

        Route.DigPlan digPlan = null;
        boolean goalWellBelow = goal.y() < start.y() - 2 * safeDrop;
        Pathfinder.Cell frontier = res.path().isEmpty()
                ? start : res.path().get(res.path().size() - 1);
        boolean stalled = frontier.y() > start.y() - safeDrop;
        if (res.status() != Pathfinder.Status.FOUND && goalWellBelow && stalled) {
            DescentPlanner.Plan dp = DescentPlanner.plan(grid,
                    frontier.x(), frontier.y(), frontier.z(),
                    goal.x(), goal.y(), goal.z(), MAX_DIG, MAX_TUNNEL);
            if (dp != null) {
                double[] entryW = cellToWorld(dp.entry()[0], dp.entry()[1], dp.entry()[2],
                        originX, originY, originZ, shiftX, shiftYc);
                List<double[]> cw = new ArrayList<>(dp.cells().size());
                for (int[] c : dp.cells()) {
                    cw.add(cellToWorld(c[0], c[1], c[2], originX, originY, originZ, shiftX, shiftYc));
                }
                digPlan = new Route.DigPlan(entryW, cw);
            }
        }
        return new Route(pts, List.of(), digPlan, res.status());
```

- [ ] **Step 3: Add the shared `cellToWorld` helper**

Add as a private static method in `RouteService` (it centralizes the exact un-shift the loop
used before):

```java
    private static double[] cellToWorld(int cx, int cy, int cz,
            int originX, int originY, int originZ, int shiftX, int shiftYc) {
        return new double[]{
                (originX + cx) + shiftX + 0.5,
                (originY + cy) - shiftYc + 0.5,
                (originZ + cz) + 0.5};
    }
```

Also update `Route.EMPTY` usages: none needed (the constant lives in `Route`). Ensure the other
`Route` constructions in this file (if any beyond `compute`) pass the 4-arg form; `compute`'s
early `return Route.EMPTY;` guards are fine.

- [ ] **Step 4: Build + full test suite + commit (Tasks 2+3 together)**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`, all tests pass.

```bash
git add src/main/java/com/mia/aperture/map/Route.java src/main/java/com/mia/aperture/map/RouteService.java
git commit -m "feat(descent): emit DigPlan when natural descent stalls above an overhang"
```

---

### Task 4: Render the dig plan in the 3D orbit view

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`

Context: `render(...)` already calls `drawRoute(guiGraphics, x0, y0, scale);` then
`drawWaypoints(...)`. `drawRoute` is the template (projects via `OrbitScene.projectHud`,
occludes via `OrbitScene.depthAt`, converts texture→screen with `* scale`).

- [ ] **Step 1: Call `drawDig` right after `drawRoute`**

Find:

```java
            drawRoute(guiGraphics, x0, y0, scale);
            drawWaypoints(guiGraphics, x0, y0, scale);
```

Replace with:

```java
            drawRoute(guiGraphics, x0, y0, scale);
            drawDig(guiGraphics, x0, y0, scale);
            drawWaypoints(guiGraphics, x0, y0, scale);
```

- [ ] **Step 2: Add the `drawDig` method** (next to `drawRoute`):

```java
    // Draw the Plan-B dig/tunnel recommendation: amber blocks-to-mine + a "dig here" beacon.
    private void drawDig(GuiGraphics g, int x0, int y0, double scale) {
        com.mia.aperture.map.Route.DigPlan dp = com.mia.aperture.map.RouteService.route().dig();
        if (dp == null) return;
        var p = this.minecraft.player;
        double fxw = p.getX() + focusOffset[0], fyw = p.getY() + focusOffset[1], fzw = p.getZ() + focusOffset[2];
        int amber = 0xFFFFAA33;
        for (double[] c : dp.cells()) {
            BeaconGeometry.Screen s = OrbitScene.projectHud(c[0] - fxw, c[1] - fyw, c[2] - fzw);
            if (s.depth() <= 0.05) continue;
            if (OrbitScene.depthAt(s.x(), s.y()) < s.depth() - 2.0) continue;
            int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, amber);
        }
        double[] e = dp.entry();
        BeaconGeometry.Screen es = OrbitScene.projectHud(e[0] - fxw, e[1] - fyw, e[2] - fzw);
        int ex, ey;
        if (es.onScreen()) { ex = x0 + (int) Math.round(es.x() * scale); ey = y0 + (int) Math.round(es.y() * scale); }
        else { int[] ec = BeaconGeometry.edgeClamp(es.dirX(), es.dirY(), OrbitScene.size(), OrbitScene.size(), 16);
               ex = x0 + (int) Math.round(ec[0] * scale); ey = y0 + (int) Math.round(ec[1] * scale); }
        g.drawString(this.font, "▼ Dig here", ex + 6, ey - 4, amber);
        g.drawString(this.font, "Descend: dig down, then tunnel to break out", 8, 44, amber);
    }
```

- [ ] **Step 3: Build**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/OrbitView.java
git commit -m "feat(descent): render dig/tunnel plan + beacon in 3D orbit view"
```

---

### Task 5: "Dig here" marker on the minimap HUD and fullscreen map

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MinimapRenderer.java`
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

- [ ] **Step 1: Minimap — add a dig color + downward-triangle helper**

In `MinimapRenderer.java`, after the `ROUTE_COLOR` constant:

```java
    // Amber "dig here" marker for the Plan-B descent recommendation.
    public static final int DIG_COLOR = 0xFFFFAA33;

    private static void drawDownTriangle(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 3, y - 3, x + 4, y - 2, color);
        g.fill(x - 2, y - 2, x + 3, y - 1, color);
        g.fill(x - 1, y - 1, x + 2, y,     color);
        g.fill(x,     y,     x + 1, y + 1, color);
    }
```

- [ ] **Step 2: Minimap — draw the marker after the route dots**

In `draw(...)`, immediately after the `route` for-loop (before the waypoint for-loop):

```java
        com.mia.aperture.map.Route.DigPlan dig = RouteService.route().dig();
        if (dig != null) {
            double dgx = dig.entry()[0] - player.getX();
            double dgz = dig.entry()[2] - player.getZ();
            if (Math.abs(dgx) <= halfBlocks && Math.abs(dgz) <= halfBlocks) {
                float bx = (float) (dgx / halfBlocks) * radius;
                float bz = (float) (dgz / halfBlocks) * radius;
                float rx = (float) (bx * Math.cos(wpRot) - bz * Math.sin(wpRot));
                float rz = (float) (bx * Math.sin(wpRot) + bz * Math.cos(wpRot));
                drawDownTriangle(ctx, cx + Math.round(rx), cy + Math.round(rz), DIG_COLOR);
            }
        }
```

- [ ] **Step 3: Fullscreen — draw the marker after the route dots**

In `AbyssWorldMapScreen.java`, right after the `for (double[] rp : ...RouteService.route().points())`
loop (before the waypoint loop), add:

```java
            com.mia.aperture.map.Route.DigPlan dig = com.mia.aperture.map.RouteService.route().dig();
            if (dig != null) {
                int dgx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        dig.entry()[0] - centerX, this.lastBlocksAcrossX, this.width);
                int dgy = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        dig.entry()[2] - centerZ, this.lastBlocksAcrossZ, this.height);
                int cdx = Math.max(inset, Math.min(this.width - inset, dgx));
                int cdy = Math.max(inset, Math.min(this.height - inset, dgy));
                drawDownTriangle(guiGraphics, cdx, cdy, com.mia.aperture.map.MinimapRenderer.DIG_COLOR);
                guiGraphics.drawString(this.font, "Dig here", cdx + 6, cdy - 4,
                        com.mia.aperture.map.MinimapRenderer.DIG_COLOR);
            }
```

- [ ] **Step 4: Fullscreen — add a local triangle helper**

In `AbyssWorldMapScreen.java`, next to `drawWaypoint(...)`:

```java
    private void drawDownTriangle(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 3, y - 3, x + 4, y - 2, color);
        g.fill(x - 2, y - 2, x + 3, y - 1, color);
        g.fill(x - 1, y - 1, x + 2, y,     color);
        g.fill(x,     y,     x + 1, y + 1, color);
    }
```

- [ ] **Step 5: Build**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MinimapRenderer.java src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(descent): 'dig here' marker on minimap HUD + fullscreen map"
```

---

### Task 6: Build, install, in-game verification

**Files:** none.

- [ ] **Step 1: Full build + test**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Install**

Copy `build/libs/mia-maps-0.1.1-beta.jar` to
`<home>\AppData\Roaming\ModrinthApp\profiles\Mine In Abyss Modpack\mods\`.

- [ ] **Step 3: Owner verification**

Route to a waypoint below an **overhanging** face. Confirm: Plan A trail descends where it can,
and where it stalls above the overhang, an amber dig column + "▼ Dig here" beacon appears in the
3D view and a "dig here" triangle on the minimap + fullscreen map. Dig the marked path; after
you drop below the overhang, the route should recompute and continue (Plan A or the next leg).

- [ ] **Step 4: (After A+B both verified) cut the release**

Not part of this plan's code, but the follow-up: bump the version (immutable GH tags need a new
number), build, install, `gh release` prerelease, push `main`. Handled outside the plan.
