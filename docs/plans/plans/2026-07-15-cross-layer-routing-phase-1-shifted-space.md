# Cross-Layer Routing — Phase 1 (Shifted-Space Correctness) Implementation Plan


**Goal:** Make the router work in the Abyss's shifted column so a waypoint on another layer produces a route in the correct direction instead of a meaningless one.

**Architecture:** Every routing coordinate moves into the shifted column, where the Abyss's 15 sections stack into one continuous space. Each endpoint converts through its **own** section (`MapGeometry.toShiftedColumn`) rather than borrowing the player's. `Route` carries shifted coords; renderers convert back at the edge — the 3D view uses them directly, while the in-world overlay and 2D map convert with the player's current section and drop points on other layers.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Voxy is `compileOnly` (not on the test classpath), so all testable logic lives in pure classes under `src/main/java/com/mia/aperture/map/`.

**Spec:** `docs/plans/specs/2026-07-15-cross-layer-routing-design.md`

**Branch policy:** Work directly on `main` in `D:\Users\dev\VSCode-Projects\MIA map mod project`. Do NOT create a branch or worktree (project convention — see `AGENTS.md`).

---

## Background an engineer needs before touching this

The Mine in Abyss world lays out 15 Abyss sections **side by side along world X, 16384 blocks apart**, each one stepping **480 blocks deeper** than the last. Voxy applies a shift at ingest so its database stores them **stacked vertically in one continuous column**. That column is the real, continuous Abyss; world coordinates are the fiction.

- `shiftedX = worldX - sector * 16384`
- `shiftedY = worldY + 3840 - sector * 480`
- `sector = (int)(worldX / 16384 + 0.5)` (Voxy's `AbyssUtil.getSection`; truncation toward zero is deliberate)

Sections are 512 blocks tall in world Y (`-256..256`) but step 480, so **adjacent sections overlap by 32 blocks** — that overlap is where you walk down from one layer to the next.

**The bug:** `RouteService.compute` builds its grid in shifted space but converts the goal with the *player's* sector. An off-layer goal therefore lands ~16384 cells outside the grid, and `clampCell` clamps rather than rejects, so A* paths toward an arbitrary box edge. The box bias is broken the same way: `dx = dst[0] - x` is a world delta, so an off-layer target gives `horiz ≈ 16384` and biases the box due east regardless of the real direction.

**Build:**
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

**Already shipped** (commit `23897c7`, the 3D marker fix) and available for reuse — do NOT re-add:
`MapGeometry.sectorForX`, `MapGeometry.toShiftedColumn`, `MapGeometry.abyssDepth`,
`MapGeometry.SECTOR_SPAN_X`, `MapGeometry.SECTOR_DEPTH`, `MapGeometry.RIM_SHIFTED_Y`,
`OrbitScene.projectShifted`, `OrbitScene.hudFocusShiftedY`, `ColorMath.withAlpha`.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/java/com/mia/aperture/map/MapGeometry.java` | Pure Abyss coordinate model | Add the shifted→world inverse (`sectorContainsShiftedY`, `sectorForShiftedY`, `toWorld`) |
| `src/main/java/com/mia/aperture/map/RouteBox.java` | **NEW** — pure placement of the search box in the shifted column | Create |
| `src/main/java/com/mia/aperture/map/Route.java` | Route data | Doc only: points/dig are now shifted |
| `src/main/java/com/mia/aperture/map/RouteService.java` | Route computation + accessors | Compute in shifted space; add shifted/world accessors |
| `src/main/java/com/mia/aperture/client/OrbitView.java` | 3D view | Consume shifted points directly |
| `src/main/java/com/mia/aperture/client/RoutePathRenderer.java` | In-world overlay | Consume world accessors |
| `src/main/java/com/mia/aperture/map/MinimapRenderer.java` | Minimap | Consume world accessors |
| `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java` | Fullscreen 2D map | Consume world accessors |
| `src/test/java/com/mia/aperture/map/MapGeometryTest.java` | Coordinate tests | Add inverse tests |
| `src/test/java/com/mia/aperture/map/RouteBoxTest.java` | **NEW** — box placement tests | Create |

`RouteBox` is a new file rather than private methods on `RouteService` for one reason: `RouteService` needs Voxy and Minecraft to run, so nothing in it can be unit tested. That is exactly why this bug survived. Pulling the placement rules into a pure class makes the regression testable.

---

### Task 1: MapGeometry — the shifted→world inverse

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/MapGeometryTest.java`

- [ ] **Step 1: Write the failing tests**

Add these to `src/test/java/com/mia/aperture/map/MapGeometryTest.java`, immediately before the closing `}` of the class:

```java
    @Test
    void overlapIsThirtyTwoBlocks() {
        // Sections are 512 tall but step 480 -- the 32-block overlap is the band you walk down
        // through from one layer to the next. If these constants ever disagree, sectorForShiftedY's
        // tie-breaking is meaningless.
        assertEquals(32, MapGeometry.SECTION_WORLD_Y_HEIGHT - MapGeometry.SECTOR_DEPTH);
    }

    @Test
    void toWorldInvertsToShiftedColumnForEverySection() {
        for (int sector = 0; sector < MapGeometry.SECTION_COUNT; sector++) {
            double worldX = (double) sector * MapGeometry.SECTOR_SPAN_X + 100;
            double[] s = MapGeometry.toShiftedColumn(worldX, 12, -7);
            double[] w = MapGeometry.toWorld(s[0], s[1], s[2], sector);
            assertEquals(worldX, w[0], 1e-9, "section " + sector);
            assertEquals(12, w[1], 1e-9, "section " + sector);
            assertEquals(-7, w[2], 1e-9, "section " + sector);
        }
    }

    @Test
    void sectorForShiftedYFindsTheOwningSection() {
        // worldY 0 sits mid-band, so exactly one section owns it -- the preference cannot matter.
        for (int sector = 0; sector < MapGeometry.SECTION_COUNT; sector++) {
            double shiftedY = MapGeometry.shiftY(0, sector);
            assertEquals(sector, MapGeometry.sectorForShiftedY(shiftedY, 0), "section " + sector);
            assertEquals(sector, MapGeometry.sectorForShiftedY(shiftedY, MapGeometry.SECTION_COUNT - 1),
                    "section " + sector);
        }
    }

    @Test
    void overlapBandPrefersTheRequestedSection() {
        // worldY -240 of section 3 IS worldY 240 of section 4 -- the same place, reached by walking
        // down. Both answers are correct, so the caller's preference decides; that keeps a
        // descending route on one layer instead of flickering between two equally valid answers.
        double shiftedY = MapGeometry.shiftY(-240, 3);
        assertTrue(MapGeometry.sectorContainsShiftedY(3, shiftedY));
        assertTrue(MapGeometry.sectorContainsShiftedY(4, shiftedY));
        assertEquals(3, MapGeometry.sectorForShiftedY(shiftedY, 3));
        assertEquals(4, MapGeometry.sectorForShiftedY(shiftedY, 4));
    }

    @Test
    void sectorForShiftedYIgnoresAnImpossiblePreference() {
        double shiftedY = MapGeometry.shiftY(0, 7);
        assertEquals(7, MapGeometry.sectorForShiftedY(shiftedY, 0));
        assertEquals(7, MapGeometry.sectorForShiftedY(shiftedY, 14));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*MapGeometryTest*'
```

Expected: FAIL — compilation errors, `cannot find symbol: SECTION_WORLD_Y_HEIGHT`, `SECTION_COUNT`, `toWorld`, `sectorForShiftedY`, `sectorContainsShiftedY`.

- [ ] **Step 3: Implement**

In `src/main/java/com/mia/aperture/map/MapGeometry.java`, add immediately after the existing `abyssDepth` method:

```java
    // A section's world Y band. 512 tall but stepping SECTOR_DEPTH (480) means adjacent sections
    // overlap by 32 blocks -- the band you walk down through from one layer to the next. Mirrors
    // Voxy's AbyssUtil abyss_wy/abyss_wh/abyss_sections.
    public static final int SECTION_COUNT = 15;
    public static final int SECTION_WORLD_Y_MIN = -256;
    public static final int SECTION_WORLD_Y_HEIGHT = 512;

    public static boolean sectorContainsShiftedY(int sector, double shiftedY) {
        double worldY = shiftedY - RIM_SHIFTED_Y + (double) sector * SECTOR_DEPTH;
        return worldY >= SECTION_WORLD_Y_MIN && worldY < SECTION_WORLD_Y_MIN + SECTION_WORLD_Y_HEIGHT;
    }

    // The section owning a shifted Y. Inside the 32-block overlap TWO sections legitimately contain
    // it, so `preferred` breaks the tie -- callers pass the layer they are already on, which keeps a
    // descending route from flickering between two equally correct answers.
    public static int sectorForShiftedY(double shiftedY, int preferred) {
        if (preferred >= 0 && preferred < SECTION_COUNT && sectorContainsShiftedY(preferred, shiftedY)) {
            return preferred;
        }
        int approx = (int) Math.round((RIM_SHIFTED_Y - shiftedY) / (double) SECTOR_DEPTH);
        for (int d = 0; d <= 1; d++) {
            for (int s : new int[]{approx - d, approx + d}) {
                if (s >= 0 && s < SECTION_COUNT && sectorContainsShiftedY(s, shiftedY)) return s;
            }
        }
        return Math.max(0, Math.min(SECTION_COUNT - 1, approx));
    }

    // Inverse of toShiftedColumn for a known section.
    public static double[] toWorld(double sx, double sy, double sz, int sector) {
        return new double[]{
                sx + (double) sector * SECTOR_SPAN_X,
                sy - RIM_SHIFTED_Y + (double) sector * SECTOR_DEPTH,
                sz};
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*MapGeometryTest*'
```

Expected: PASS (BUILD SUCCESSFUL).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapGeometry.java src/test/java/com/mia/aperture/map/MapGeometryTest.java
git commit -m "feat(geometry): shifted-column to world inverse

Adds the section-resolving inverse of toShiftedColumn. Inside the 32-block
overlap two sections both legitimately own a shifted Y, so callers pass the
layer they are on to break the tie.

```

---

### Task 2: RouteBox — pure search-box placement

**Files:**
- Create: `src/main/java/com/mia/aperture/map/RouteBox.java`
- Test: `src/test/java/com/mia/aperture/map/RouteBoxTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/RouteBoxTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteBoxTest {
    private static final int BOX = 192, VBOX = 96, MARGIN = 24;

    @Test
    void aGoalOnTheLayerBelowBiasesTheBoxDownNotSideways() {
        // THE regression this class exists to prevent. A waypoint one section down sits 480 blocks
        // BELOW you in the shifted column, directly overhead in x/z. The old code took the delta in
        // WORLD space, where that same waypoint is 16384 blocks EAST, and biased the whole box east
        // -- squarely away from the target.
        RouteBox.Box b = RouteBox.place(0, 0, 0, 0, -MapGeometry.SECTOR_DEPTH, 0, BOX, VBOX, MARGIN);
        assertEquals(-96, b.originX(), "no sideways bias: the target is straight down");
        assertEquals(-96, b.originZ(), "no sideways bias: the target is straight down");
        assertTrue(b.originY() + b.gy() / 2 < 0, "box centre must sit BELOW the player");
        assertEquals(-168, b.originY()); // centre -72 (bias capped at VBOX-MARGIN), minus gy/2
    }

    @Test
    void theBoxAlwaysContainsThePlayer() {
        // A partial route is useless if it cannot start under your feet.
        RouteBox.Box b = RouteBox.place(0, 0, 0, 5000, -5000, 5000, BOX, VBOX, MARGIN);
        assertTrue(0 >= b.originX() && 0 < b.originX() + b.gx());
        assertTrue(0 >= b.originY() && 0 < b.originY() + b.gy());
        assertTrue(0 >= b.originZ() && 0 < b.originZ() + b.gz());
    }

    @Test
    void aDistantHorizontalGoalBiasesTowardItButCapsAtTheMargin() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 1000, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(72 - 96, b.originX()); // bias capped at BOX/2 - MARGIN = 72
        assertEquals(-96, b.originZ());
    }

    @Test
    void aNearbyGoalIsBiasedByHalfTheDistance() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 20, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(10 - 96, b.originX());
    }

    @Test
    void aGoalAtThePlayerCentresTheBox() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 0, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(-96, b.originX());
        assertEquals(-96, b.originY());
        assertEquals(-96, b.originZ());
    }

    @Test
    void dimensionsFollowTheRequestedExtents() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 0, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(BOX, b.gx());
        assertEquals(2 * VBOX, b.gy());
        assertEquals(BOX, b.gz());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*RouteBoxTest*'
```

Expected: FAIL — compilation error, `package com.mia.aperture.map.RouteBox does not exist` / `cannot find symbol: RouteBox`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/RouteBox.java`:

```java
package com.mia.aperture.map;

// Places RouteService's search box in the Abyss's shifted column: centred on the player, biased
// toward the goal, but always keeping the player `margin` blocks clear of the edge so a partial
// route still starts under their feet.
//
// Pure on purpose. These rules used to live inside RouteService.compute, which needs Voxy and
// Minecraft to run and therefore could not be tested at all -- which is how it shipped taking its
// bias from a WORLD-space delta while feeding a SHIFTED-space grid.
public final class RouteBox {
    // Grid origin (shifted column) and extents, in cells at LOD 0 (1 cell = 1 block).
    public record Box(int originX, int originY, int originZ, int gx, int gy, int gz) {}

    private RouteBox() {}

    // px/py/pz: player, tx/ty/tz: target -- both in the shifted column.
    public static Box place(double px, double py, double pz,
                            double tx, double ty, double tz,
                            int box, int vbox, int margin) {
        double dx = tx - px, dy = ty - py, dz = tz - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double bias = Math.min(horiz * 0.5, box / 2.0 - margin);
        double ux = horiz > 1e-6 ? dx / horiz : 0, uz = horiz > 1e-6 ? dz / horiz : 0;
        double bcx = px + ux * bias, bcz = pz + uz * bias;
        double bcy = py + Math.max(-(vbox - margin), Math.min(vbox - margin, dy * 0.5));
        int gx = box, gy = 2 * vbox, gz = box;
        return new Box((int) Math.floor(bcx) - gx / 2,
                       (int) Math.floor(bcy) - gy / 2,
                       (int) Math.floor(bcz) - gz / 2,
                       gx, gy, gz);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*RouteBoxTest*'
```

Expected: PASS (BUILD SUCCESSFUL).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/RouteBox.java src/test/java/com/mia/aperture/map/RouteBoxTest.java
git commit -m "feat(route): extract search-box placement into a pure RouteBox

Same placement rules as RouteService.compute, but callable without Voxy or
Minecraft, so the cross-layer bias regression is now unit-testable.

```

---

### Task 3: RouteService computes in the shifted column

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/Route.java:5-6`
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java`

This task is the actual bug fix. No new unit test — `RouteService` cannot be unit tested (it needs Voxy + Minecraft), which is precisely why Tasks 1 and 2 pulled the testable logic out into `MapGeometry` and `RouteBox`. Verification is the full build plus the in-game check in Task 6.

- [ ] **Step 1: Update the Route doc comment**

In `src/main/java/com/mia/aperture/map/Route.java`, replace this comment:

```java
// A computed route in WORLD coords (shifted X/Y already un-shifted). Bridges empty in Phase 1.
// `dig` is non-null only when Plan B recommended a dig/tunnel leg for descent.
```

with:

```java
// A computed route in the Abyss's SHIFTED column (see MapGeometry.toShiftedColumn) -- the space
// where the 15 sections stack continuously, so a route may cross a layer boundary. World coords
// cannot express that: every layer reuses the same world Y. Renderers convert at the edge via
// RouteService.aheadPointsWorld()/digWorld(). Bridges empty in Phase 1.
// `dig` is non-null only when Plan B recommended a dig/tunnel leg for descent.
```

- [ ] **Step 2: Remove the now-unused AbyssUtil import**

In `src/main/java/com/mia/aperture/map/RouteService.java`, delete this line:

```java
import me.cortex.voxy.client.core.util.AbyssUtil;
```

- [ ] **Step 3: Add the MARGIN constant**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace:

```java
    private static final int VBOX = 96;        // vertical grid extent (blocks) each way
```

with:

```java
    private static final int VBOX = 96;        // vertical grid extent (blocks) each way
    private static final int MARGIN = 24;      // keep the player this far from the box edge
```

- [ ] **Step 4: Replace compute() and cellToWorld()**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace the whole of `compute` and `cellToWorld` (from `private static Route compute(double[] dst) {` through the closing brace of `cellToWorld`) with:

```java
    private static Route compute(double[] dst) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.player == null || dst == null) return Route.EMPTY;
        WorldEngine engine = rs.getEngine();
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return Route.EMPTY;

        // Everything below is in the shifted column, where the sections stack into one continuous
        // space and a path may legitimately cross a layer boundary. Each endpoint converts through
        // its OWN section: the destination is frequently on a different layer than the player, and
        // borrowing the player's section there is what made off-layer routing nonsense.
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        double[] t = MapGeometry.toShiftedColumn(dst[0], dst[1], dst[2]);
        RouteBox.Box b = RouteBox.place(p[0], p[1], p[2], t[0], t[1], t[2], BOX, VBOX, MARGIN);

        boolean[] opaque = VoxelCloud.fillOpaque(engine, colors,
                b.originX(), b.originY(), b.originZ(), b.gx(), b.gy(), b.gz(), LVL);
        TraversabilityGrid grid = new TraversabilityGrid(opaque, b.gx(), b.gy(), b.gz());

        Pathfinder.Cell start = nearestStandable(grid,
                (int) Math.floor(p[0]) - b.originX(),
                (int) Math.floor(p[1]) - b.originY(),
                (int) Math.floor(p[2]) - b.originZ());
        if (start == null) return Route.EMPTY;
        Pathfinder.Cell goal = clampCell(grid,
                (int) Math.floor(t[0]) - b.originX(),
                (int) Math.floor(t[1]) - b.originY(),
                (int) Math.floor(t[2]) - b.originZ());

        int safeDrop = com.mia.aperture.client.MiaApertureModClient.mapSettings.safeDropBlocks;
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, 1);
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, params, NODE_CAP);
        List<double[]> pts = new ArrayList<>(res.path().size());
        for (Pathfinder.Cell c : res.path()) {
            pts.add(cellToShifted(c.x(), c.y(), c.z(), b));
        }

        Route.DigPlan digPlan = null;
        Pathfinder.Cell frontier = res.path().isEmpty()
                ? start : res.path().get(res.path().size() - 1);
        // Recommend digging when the route can't reach the goal and the closest we got (the
        // frontier, where the player will end up stuck) is still well above the goal — a real
        // descent the pathfinder couldn't finish, i.e. an overhang. Dig FROM the frontier.
        boolean descentRemains = frontier.y() > goal.y() + safeDrop;
        if (res.status() != Pathfinder.Status.FOUND && descentRemains) {
            DescentPlanner.Plan dp = DescentPlanner.plan(grid,
                    frontier.x(), frontier.y(), frontier.z(),
                    goal.x(), goal.y(), goal.z(), MAX_DIG, MAX_TUNNEL);
            if (dp != null) {
                double[] entryS = cellToShifted(dp.entry()[0], dp.entry()[1], dp.entry()[2], b);
                List<double[]> cells = new ArrayList<>(dp.cells().size());
                for (int[] c : dp.cells()) {
                    cells.add(cellToShifted(c[0], c[1], c[2], b));
                }
                digPlan = new Route.DigPlan(entryS, cells);
            }
        }
        return new Route(pts, List.of(), digPlan, res.status());
    }

    private static double[] cellToShifted(int cx, int cy, int cz, RouteBox.Box b) {
        return new double[]{
                (b.originX() + cx) + 0.5,
                (b.originY() + cy) + 0.5,
                (b.originZ() + cz) + 0.5};
    }
```

- [ ] **Step 5: Verify it compiles**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: FAIL — `cannot find symbol: method aheadPoints()` from `OrbitView`, `MinimapRenderer`, `AbyssWorldMapScreen`, `RoutePathRenderer`. That is expected and is fixed in Tasks 4 and 5. Do not commit yet; go straight to Task 4.

If instead you see an error inside `RouteService.java` itself, stop and fix that before continuing.

---

### Task 4: RouteService accessors — shifted for 3D, world for everything else

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java`

- [ ] **Step 1: Replace aheadPoints() with the shifted/world pair**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace:

```java
    // Route breadcrumbs still ahead of the player (passed ones erased), for all trail renderers.
    public static java.util.List<double[]> aheadPoints() {
        return route.ahead(px, py, pz);
    }
```

with:

```java
    // Route breadcrumbs still ahead of the player (passed ones erased), in the shifted column --
    // every layer. The 3D view wants these: it projects the shifted column directly.
    public static java.util.List<double[]> aheadPointsShifted() {
        double[] p = MapGeometry.toShiftedColumn(px, py, pz);
        return route.ahead(p[0], p[1], p[2]);
    }

    // Breadcrumbs still ahead that are on the player's CURRENT layer, in world coords. The in-world
    // overlay and the 2D map can only draw this layer, so points on other layers are dropped rather
    // than un-shifted into a place that does not exist in this section.
    public static java.util.List<double[]> aheadPointsWorld() {
        int sector = MapGeometry.sectorForX(px);
        java.util.List<double[]> out = new java.util.ArrayList<>();
        for (double[] s : aheadPointsShifted()) {
            if (MapGeometry.sectorForShiftedY(s[1], sector) != sector) continue;
            out.add(MapGeometry.toWorld(s[0], s[1], s[2], sector));
        }
        return out;
    }

    // The dig plan in world coords, or null when there is none or it is not on the player's layer.
    public static Route.DigPlan digWorld() {
        Route.DigPlan d = route.dig();
        if (d == null) return null;
        int sector = MapGeometry.sectorForX(px);
        if (MapGeometry.sectorForShiftedY(d.entry()[1], sector) != sector) return null;
        double[] entry = MapGeometry.toWorld(d.entry()[0], d.entry()[1], d.entry()[2], sector);
        java.util.List<double[]> cells = new java.util.ArrayList<>(d.cells().size());
        for (double[] c : d.cells()) {
            cells.add(MapGeometry.toWorld(c[0], c[1], c[2], sector));
        }
        return new Route.DigPlan(entry, cells);
    }
```

- [ ] **Step 2: Fix offRoute to compare in the shifted column**

In `src/main/java/com/mia/aperture/map/RouteService.java`, replace:

```java
    private static boolean offRoute(double x, double y, double z) {
        java.util.List<double[]> pts = route.points();
        if (pts.isEmpty()) return false;
        double best = Double.MAX_VALUE;
        for (double[] p : pts) {
            double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best > OFF_ROUTE_DIST * OFF_ROUTE_DIST;
    }
```

with:

```java
    private static boolean offRoute(double x, double y, double z) {
        java.util.List<double[]> pts = route.points();
        if (pts.isEmpty()) return false;
        // Route points are shifted; the caller hands us world coords. Comparing the two spaces
        // directly would read as "16384 blocks off route" the moment a route crossed a layer.
        double[] p = MapGeometry.toShiftedColumn(x, y, z);
        double best = Double.MAX_VALUE;
        for (double[] q : pts) {
            double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best > OFF_ROUTE_DIST * OFF_ROUTE_DIST;
    }
```

- [ ] **Step 3: Verify RouteService itself compiles**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: still FAIL, but now ONLY with `cannot find symbol: method aheadPoints()` in the four consumer files (`OrbitView`, `RoutePathRenderer`, `MinimapRenderer`, `AbyssWorldMapScreen`). Task 5 fixes those. Any error inside `RouteService.java` must be fixed now.

---

### Task 5: Point the consumers at the right space

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java` (route + dig → shifted)
- Modify: `src/main/java/com/mia/aperture/client/RoutePathRenderer.java:48,55-56` (→ world)
- Modify: `src/main/java/com/mia/aperture/map/MinimapRenderer.java:83,102` (→ world)
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java:128,145` (→ world)

- [ ] **Step 1: OrbitView.drawRoute — project the shifted column directly**

In `src/main/java/com/mia/aperture/client/OrbitView.java`, replace the body of `drawRoute` from `java.util.List<double[]> pts = ...` down to the end of the `if (!pts.isEmpty()) { ... }` block with the code below.

Keep the `com.mia.aperture.map.Route rt = com.mia.aperture.map.RouteService.route();` line above it — the status-line block at the end of the method still uses `rt`.

```java
        java.util.List<double[]> pts = com.mia.aperture.map.RouteService.aheadPointsShifted();
        if (!pts.isEmpty()) {
            int prevX = 0, prevY = 0;
            boolean havePrev = false, prevVis = false;
            for (double[] wp : pts) {
                BeaconGeometry.Screen s = OrbitScene.projectShifted(wp[0], wp[1], wp[2]);
                if (s.depth() <= 0.05) { havePrev = false; continue; }
                boolean vis = OrbitScene.depthAt(s.x(), s.y()) >= s.depth() - 2.0;
                int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
                if (havePrev) {
                    // hybrid: bright where the segment is visible, dim/ghosted behind terrain
                    int color = (vis && prevVis) ? 0xFF33DDFF : 0x6633DDFF;
                    drawLine(g, prevX, prevY, sx, sy, color);
                    drawLine(g, prevX, prevY + 1, sx, sy + 1, color);
                }
                prevX = sx;
                prevY = sy;
                havePrev = true;
                prevVis = vis;
            }
            double[] next = pts.get(0);
            BeaconGeometry.Screen ns = OrbitScene.projectShifted(next[0], next[1], next[2]);
            if (ns.depth() > 0.05) {
                int nx = x0 + (int) Math.round(ns.x() * scale), ny = y0 + (int) Math.round(ns.y() * scale);
                g.fill(nx - 3, ny - 3, nx + 4, ny + 4, 0xFFEAFFFF);
            }
        }
```

Note the `var p = this.minecraft.player;` and `double fxw = ...` lines are deleted — `projectShifted` subtracts the focus itself.

- [ ] **Step 2: OrbitView.drawDig — same treatment**

In `src/main/java/com/mia/aperture/client/OrbitView.java`, replace exactly this block at the top of `drawDig`:

```java
        com.mia.aperture.map.Route.DigPlan dp = com.mia.aperture.map.RouteService.route().dig();
        if (dp == null) return;
        var p = this.minecraft.player;
        double fxw = p.getX() + focusOffset[0], fyw = p.getY() + focusOffset[1], fzw = p.getZ() + focusOffset[2];
        int amber = 0xFFFFAA33;
```

with:

```java
        com.mia.aperture.map.Route.DigPlan dp = com.mia.aperture.map.RouteService.route().dig();
        if (dp == null) return;
        int amber = 0xFFFFAA33;
```

`RouteService.route().dig()` is correct here and must NOT become `digWorld()` — the 3D view wants the shifted column.

Then within `drawDig`, replace both projection calls:

```java
            BeaconGeometry.Screen s = OrbitScene.projectHud(c[0] - fxw, c[1] - fyw, c[2] - fzw);
```
becomes
```java
            BeaconGeometry.Screen s = OrbitScene.projectShifted(c[0], c[1], c[2]);
```

and

```java
        BeaconGeometry.Screen es = OrbitScene.projectHud(e[0] - fxw, e[1] - fyw, e[2] - fzw);
```
becomes
```java
        BeaconGeometry.Screen es = OrbitScene.projectShifted(e[0], e[1], e[2]);
```

- [ ] **Step 3: RoutePathRenderer — world coords**

In `src/main/java/com/mia/aperture/client/RoutePathRenderer.java`, replace:

```java
        Route rt = RouteService.route();
        Route.DigPlan dig = rt.dig();
        if (rt.points().isEmpty() && dig == null) return;
```

with:

```java
        Route.DigPlan dig = RouteService.digWorld();
        if (RouteService.route().points().isEmpty() && dig == null) return;
```

and replace:

```java
        java.util.List<double[]> pts = RouteService.aheadPoints();
```

with:

```java
        java.util.List<double[]> pts = RouteService.aheadPointsWorld();
```

- [ ] **Step 4: MinimapRenderer — world coords**

In `src/main/java/com/mia/aperture/map/MinimapRenderer.java`, replace:

```java
        java.util.List<double[]> route = RouteService.aheadPoints();
```

with:

```java
        java.util.List<double[]> route = RouteService.aheadPointsWorld();
```

and replace:

```java
        Route.DigPlan dig = RouteService.route().dig();
```

with:

```java
        Route.DigPlan dig = RouteService.digWorld();
```

- [ ] **Step 5: AbyssWorldMapScreen — world coords**

In `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`, replace:

```java
            java.util.List<double[]> routePts = com.mia.aperture.map.RouteService.aheadPoints();
```

with:

```java
            java.util.List<double[]> routePts = com.mia.aperture.map.RouteService.aheadPointsWorld();
```

and replace:

```java
            com.mia.aperture.map.Route.DigPlan dig = com.mia.aperture.map.RouteService.route().dig();
```

with:

```java
            com.mia.aperture.map.Route.DigPlan dig = com.mia.aperture.map.RouteService.digWorld();
```

- [ ] **Step 6: Build and run the full suite**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL, all tests pass. If `javac` reports an unused-variable or unused-import problem in `OrbitView`, delete the now-dead `fxw`/`fyw`/`fzw` locals it names.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(route): route in the Abyss's shifted column, not world coords

RouteService built its grid in shifted space but converted the GOAL with the
player's sector, so an off-layer destination landed ~16384 cells outside the
grid; clampCell clamps rather than rejects, so A* pathed toward an arbitrary box
edge. The box bias was wrong the same way -- a world-space delta made every
off-layer target read as 16384 blocks east. Sections are only 480 blocks of
depth apart, so this hit nearly every waypoint.

Route now carries shifted coords, and each endpoint converts through its own
section. Renderers convert at the edge: the 3D view projects the column
directly, while the in-world overlay and 2D map use the player's current
section and drop points on other layers, which they cannot draw anyway.

```

---

### Task 6: Install and verify in game

**Files:** none (verification only)

- [ ] **Step 1: Build and install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
MODS="C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods"
cp build/libs/mia-maps-0.1.8-beta.jar "$MODS/"
ls -la "$MODS/" | grep mia-maps
```

Expected: exactly one `mia-maps-*.jar` in the mods folder.

- [ ] **Step 2: Same-layer regression check**

Launch Mine in Abyss. Set a waypoint a short walk away **on your current layer** and click it in the 3D view.

Expected: the route behaves exactly as it did before this change — breadcrumbs in the world, on the minimap, on the fullscreen map, and a cyan line in the 3D view. This is the regression check that the shifted refactor did not break normal routing.

- [ ] **Step 3: Cross-layer check (the point of the whole plan)**

Set a waypoint on the layer **below** you (more than ~480 blocks of depth away) and click it in the 3D view.

Expected: the route heads **down the shaft**, not east. Before this change it pointed at an arbitrary box edge. The route will be PARTIAL — the status line reads "Route: partial (walk closer / no full path yet)" — because the box only reaches ~168 blocks down. That is correct for Phase 1; Phase 2's corridor is what extends it the whole way.

- [ ] **Step 4: Boundary crossing check**

With that cross-layer route active, walk/fall down across the section boundary.

Expected: the route survives the crossing and keeps pointing at the target; the in-world breadcrumbs reappear as the target's layer becomes your layer. Nothing flickers or jumps by a section.

- [ ] **Step 5: Report findings**

Report to the owner what happened at each step. If Step 3 does not head down, capture the `X/Y/Z` HUD readout, the waypoint's coords, and the "3D Stats" overlay (Settings → 3D Stats — it shows the sector and shifted focus) — that combination pins whether the fault is in the conversion or the search.

---

## Notes for the implementer

- **Do not** create a branch or worktree. Work on `main` (project convention, `AGENTS.md`).
- **No inline comments** explaining what a line does — the codebase's convention is comments that explain constraints and why, not narration.
- `VoxelCloud.fill` clamps shifted X to `[-8192, 8192)` but never Y. That is why a box spanning a section boundary reads both layers stitched together, and it is load-bearing for this whole design. Do not "fix" it.
- If the box is placed near shifted X ±8192 (a section band's far edge, thousands of blocks outside the playable Abyss) those cells read as air. Pre-existing, out of scope.
