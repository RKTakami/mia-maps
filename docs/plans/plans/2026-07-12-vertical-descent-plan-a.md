# Vertical Descent — Plan A (Natural descent) Implementation Plan

> the plan to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax. **Project rule: work directly on `main`, no worktree.**

**Goal:** Let the router descend natural cliff faces by chaining safe (≤ safe-drop) drops,
driven by a new configurable "safe fall distance" setting (default 4).

**Architecture:** Add `safeDropBlocks` to `MapSettings` (persisted, UI stepper). `RouteService`
builds the pathfinder `Params` from it each compute (was a hardcoded `maxFall = 3`). The
existing downward box-bias and re-route-as-you-descend loop already handle deep descents; the
route trail already renders. No new rendering.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5 (pure map classes), Gson (config).

**Spec:** `docs/plans/specs/2026-07-12-vertical-descent-routing-design.md`

---

### Task 1: `safeDropBlocks` setting + persistence

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/map/MapConfig.java`

- [ ] **Step 1: Add the field + clamp helper to `MapSettings`**

In `MapSettings.java`, after the `orbitQuality` field (line 43):

```java
    public int safeDropBlocks = 4;

    public static final int MIN_SAFE_DROP = 2;
    public static final int MAX_SAFE_DROP = 8;

    public void setSafeDropBlocks(int n) {
        this.safeDropBlocks = Math.max(MIN_SAFE_DROP, Math.min(MAX_SAFE_DROP, n));
    }
```

- [ ] **Step 2: Guard old/missing config values in `MapConfig.fromJson`**

In `MapConfig.java`, inside `fromJson`, just before `return s;` (after the
`s.setMinimapPos(...)` line):

```java
            s.setSafeDropBlocks(s.safeDropBlocks == 0 ? 4 : s.safeDropBlocks);
```

(Gson runs field initializers via the no-arg constructor, so a missing key stays 4; the `== 0`
guard covers a config written by a future/older build that stored 0.)

- [ ] **Step 3: Build to confirm it compiles**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/map/MapConfig.java
git commit -m "feat(descent): safeDropBlocks setting (default 4) + config guard"
```

---

### Task 2: Settings-screen stepper for safe fall distance

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Add a cycling button below the 3D Quality button**

In `init()`, the last control before "Done" is the orbit-quality button at `cy2 + 96`. Insert a
new button at `cy2 + 120` and move "Done" to `cy2 + 144`.

Replace the "Done" button block:

```java
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 120, 200, 20).build());
```

with:

```java
        this.addRenderableWidget(Button.builder(safeDropLabel(), b -> {
            MapSettings s = settings();
            int next = s.safeDropBlocks + 1;
            if (next > MapSettings.MAX_SAFE_DROP) next = MapSettings.MIN_SAFE_DROP;
            s.setSafeDropBlocks(next);
            b.setMessage(safeDropLabel());
            persist();
        }).bounds(cx - 100, cy2 + 120, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 144, 200, 20).build());
```

- [ ] **Step 2: Add the label helper**

After `orbitQualityLabel()` (near line 129):

```java
    private static Component safeDropLabel() {
        return Component.literal("Safe fall distance: " + settings().safeDropBlocks + " blocks");
    }
```

- [ ] **Step 3: Build + manual check**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`. (In-game: the Map Settings screen shows the new cycling button,
2→…→8→2.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(descent): safe-fall-distance stepper in map settings"
```

---

### Task 3: Drive the pathfinder's `maxFall` from the setting

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/RouteService.java`

- [ ] **Step 1: Build `Params` from the setting in `compute`**

In `RouteService.java`, the `PARAMS` constant is currently
`new Pathfinder.Params(1, 3, 1)` and is used in `compute` as:

```java
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, PARAMS, NODE_CAP);
```

Replace that line with a locally built params that reads the live setting:

```java
        int safeDrop = com.mia.aperture.client.MiaApertureModClient.mapSettings.safeDropBlocks;
        Pathfinder.Params params = new Pathfinder.Params(1, safeDrop, 1);
        Pathfinder.Result res = Pathfinder.find(grid, start, goal, params, NODE_CAP);
```

- [ ] **Step 2: Repoint the `PARAMS` constant to a default (keep it as a documented default)**

Change the constant declaration:

```java
    private static final Pathfinder.Params PARAMS = new Pathfinder.Params(1, 3, 1);
```

to:

```java
    private static final int DEFAULT_SAFE_DROP = 4;
```

and delete the now-unused `PARAMS` reference (only `compute` used it; Step 1 removed that use).
If any other reference to `PARAMS` remains, replace it with a locally built
`new Pathfinder.Params(1, DEFAULT_SAFE_DROP, 1)`.

- [ ] **Step 3: Build**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL` (no "PARAMS not found" / unused errors).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/map/RouteService.java
git commit -m "feat(descent): pathfinder maxFall follows safeDropBlocks setting"
```

---

### Task 4: Pathfinder descent test (maxFall = 4)

**Files:**
- Modify: `src/test/java/com/mia/aperture/map/PathfinderTest.java`

- [ ] **Step 1: Write a failing test for a 4-block staged descent**

Add to `PathfinderTest.java`. This builds a two-ledge grid: a top ledge at y=8 and a lower
ledge 4 blocks down at y=4, reachable only by a 4-block drop. With `maxFall = 4` the path must
reach the lower ledge; with `maxFall = 3` it must not.

```java
    @Test
    void descendsFourBlockDrop() {
        int gx = 6, gy = 12, gz = 3;
        boolean[] op = new boolean[gx * gy * gz];
        // helper: index
        java.util.function.IntUnaryOperator noop = i -> i;
        // floor slab at y=7 under x in [0,1] (top ledge; stand at y=8)
        // lower slab at y=3 under x in [4,5] (lower ledge; stand at y=4)
        int z = 1;
        for (int x = 0; x <= 1; x++) op[(7 * gz + z) * gx + x] = true;
        for (int x = 2; x <= 5; x++) op[(3 * gz + z) * gx + x] = true;
        TraversabilityGrid g = new TraversabilityGrid(op, gx, gy, gz);

        Pathfinder.Cell start = new Pathfinder.Cell(0, 8, z);
        Pathfinder.Cell goal = new Pathfinder.Cell(5, 4, z);

        Pathfinder.Result okFall = Pathfinder.find(g, start, goal, new Pathfinder.Params(1, 4, 1), 100000);
        assertEquals(Pathfinder.Status.FOUND, okFall.status());

        Pathfinder.Result noFall = Pathfinder.find(g, start, goal, new Pathfinder.Params(1, 3, 1), 100000);
        assertNotEquals(Pathfinder.Status.FOUND, noFall.status());
    }
```

Add imports if missing at the top of the file:

```java
    import static org.junit.jupiter.api.Assertions.assertNotEquals;
```

- [ ] **Step 2: Run the test**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test --tests "com.mia.aperture.map.PathfinderTest"`
Expected: PASS (the walk step at x=1→2 drops from y=8 to the lower slab's stand level y=4, a
4-block drop; the highest-standable-in-column scan in `Pathfinder.neighbors` finds it).

If it fails because the drop geometry doesn't line up with the neighbor scan (the scan checks
`standable` in `[y+stepUp, y-maxFall]`), adjust the lower slab Y so the stand level is exactly
4 below the top stand level (top stand y=8 → lower stand y=4), keeping the slab blocks one below
the stand level. Re-run until the maxFall=4 case is FOUND and maxFall=3 is not.

- [ ] **Step 3: Full test suite**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test`
Expected: all pass (existing 4 Pathfinder tests + this one, plus the rest of the suite).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/mia/aperture/map/PathfinderTest.java
git commit -m "test(descent): pathfinder chains a 4-block drop under maxFall=4"
```

---

### Task 5: Build, install, hand off for in-game check

**Files:** none (build/install only).

- [ ] **Step 1: Build**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install into the Mine In Abyss instance**

Copy `build/libs/mia-maps-0.1.1-beta.jar` to
`<home>\AppData\Roaming\ModrinthApp\profiles\Mine In Abyss Modpack\mods\`.

- [ ] **Step 3: Owner in-game verification**

Route to a waypoint below a natural (non-overhanging) cliff. Confirm the trail now descends the
face in ≤4-block hops and reaches (or progressively approaches) the waypoint. Try lowering the
"Safe fall distance" setting and confirm the descent becomes more conservative.

Note: sheer/overhang faces are Plan B — expect a PARTIAL trail there until Plan B ships.
