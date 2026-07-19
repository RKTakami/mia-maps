# 2D Map Waypoint Interaction Implementation Plan


**Goal:** On the fullscreen 2D map, add Shift+right-click to create a waypoint and left-click a waypoint to navigate to it — mirroring the 3D orbit view.

**Architecture:** A pure inverse coordinate helper in `MapGeometry` (unit-tested) converts a clicked pixel to world X/Z; `AbyssWorldMapScreen` gains a `mouseClicked` handler that reuses the existing `WaypointEditScreen` (create) and `RouteService.setDestination` (navigate), plus per-frame waypoint hit-boxes recorded during render.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5.

**Spec:** `docs/plans/specs/2026-07-19-2d-map-waypoint-interaction-design.md`

**Branch:** Work on `main`. Do NOT create branches/worktrees. Build:
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

## Background an engineer needs

- The 2D map is world-X/Z centered on the player: `centerX = player.getX() + AbyssMapState.mapX`,
  `centerZ = player.getZ() + AbyssMapState.mapZ`. Markers draw via
  `MapGeometry.screenOffsetPixel(deltaBlocks, blocksAcross, dim) = round(dim*(0.5 + deltaBlocks/blocksAcross))`.
  `AbyssWorldMapScreen` stores `lastBlocksAcrossX/Z` (fields) each frame.
- The 3D reference (`OrbitView.mouseClicked`): left-click within 8px of a recorded hit `{sx,sy,wx,wy,wz}`
  → `RouteService.setDestination(wx,wy,wz)`; Shift+right-click → `new WaypointEditScreen(parent,
  Component.literal("New Waypoint"), "Waypoint", wx, wy, wz, WaypointColor.RED, onSave)` where onSave
  does `MiaApertureModClient.waypoints.add(key, w)` + `WaypointConfig.save(waypointConfigPath(), waypoints)`.
- Confirmed signatures: `WaypointEditScreen(Screen parent, Component title, String name, int x, int y,
  int z, WaypointColor color, Consumer<Waypoint> onSave)`; `RouteService.setDestination(double,double,double)`.
- `mouseClicked` signature in this MC/Fabric: `public boolean mouseClicked(MouseButtonEvent event, boolean doubled)`;
  use `event.button()` (0=left,1=right), `event.x()`, `event.y()`, `event.modifiers()`.
  `org.lwjgl.glfw.GLFW` is already imported in `AbyssWorldMapScreen`.
- New waypoints default `visible = true` (Waypoint field init), so a created one shows immediately.

---

### Task 1: MapGeometry.worldDeltaFromPixel (pure inverse)

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/MapGeometryTest.java` (create if absent)

- [ ] **Step 1: Write the failing test**

Add to `MapGeometryTest` (create the file with this content if it doesn't exist):

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapGeometryTest {
    @Test
    void worldDeltaFromPixelIsInverseOfScreenOffsetPixel() {
        int[] dims = {256, 400, 1080};
        int[] spans = {64, 512, 4096};
        for (int dim : dims) {
            for (int span : spans) {
                for (double delta : new double[]{-span / 2.0, -37.5, 0, 12.0, span / 2.0 - 1}) {
                    int px = MapGeometry.screenOffsetPixel(delta, span, dim);
                    double back = MapGeometry.worldDeltaFromPixel(px, span, dim);
                    // round-trip within one block-per-pixel of rounding error
                    assertEquals(delta, back, (double) span / dim + 1e-6,
                            "dim=" + dim + " span=" + span + " delta=" + delta);
                }
            }
        }
    }

    @Test
    void centerPixelMapsToZeroDelta() {
        assertEquals(0.0, MapGeometry.worldDeltaFromPixel(200, 800, 400), 1e-9); // pixel dim/2 -> 0
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*MapGeometryTest*'
```

Expected: FAIL — `cannot find symbol: method worldDeltaFromPixel`.

- [ ] **Step 3: Implement**

In `MapGeometry.java`, immediately after the `screenOffsetPixel` method, add:

```java
    // Inverse of screenOffsetPixel: a screen pixel -> world-block delta from the map centre.
    public static double worldDeltaFromPixel(double pixel, int blocksAcross, int dim) {
        return (pixel / (double) dim - 0.5) * blocksAcross;
    }
```

- [ ] **Step 4: Run to verify it passes**

```bash
./gradlew test --tests '*MapGeometryTest*'
```

Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapGeometry.java src/test/java/com/mia/aperture/map/MapGeometryTest.java
git commit -m "feat(map): worldDeltaFromPixel inverse for screen->world clicks

```

---

### Task 2: AbyssWorldMapScreen — record hits + mouseClicked (create + navigate)

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

No unit test (LWJGL/Screen-bound). Build-verified + Task 4 in-game.

- [ ] **Step 1: Add the per-frame hit-box field**

Near the other fields (after `lastBlocksAcrossZ`), add:

```java
    // Screen hit-boxes for visible waypoints drawn this frame: {screenX, screenY, wx, wy, wz}.
    // Left-click one to navigate. Rebuilt every render.
    private final java.util.List<double[]> waypointHits = new java.util.ArrayList<>();
```

- [ ] **Step 2: Record hits in the waypoint render loop**

In `render`, the waypoint loop currently is:

```java
            for (com.mia.aperture.map.Waypoint w : MiaApertureModClient.mapSettings.showNavMarkers
                    ? MiaApertureModClient.waypoints.list(wpKey)
                    : java.util.List.<com.mia.aperture.map.Waypoint>of()) {
                if (!w.visible) continue;
                int wx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.x - centerX, this.lastBlocksAcrossX, this.width);
                int wy = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.z - centerZ, this.lastBlocksAcrossZ, this.height);
                int cwx = Math.max(inset, Math.min(this.width - inset, wx));
                int cwy = Math.max(inset, Math.min(this.height - inset, wy));
                drawWaypoint(guiGraphics, cwx, cwy, w.color.argb(), w.name,
                        w.x + ", " + w.y + ", " + w.z);
            }
```

Immediately BEFORE the `for` loop, clear the hit list:

```java
            this.waypointHits.clear();
```

And inside the loop, immediately after computing `cwx`/`cwy` and before `drawWaypoint(...)`, add:

```java
                this.waypointHits.add(new double[]{cwx, cwy, w.x, w.y, w.z});
```

- [ ] **Step 3: Add `mouseClicked`**

Add this method to `AbyssWorldMapScreen` (near `mouseDragged`):

```java
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Let buttons (Waypoints/Settings/etc.) consume the click first.
        if (super.mouseClicked(event, doubled)) return true;
        var player = this.minecraft.player;
        if (player == null) return false;

        // Left-click on a visible waypoint marker: navigate to it.
        if (event.button() == 0) {
            for (double[] h : this.waypointHits) {
                if (Math.abs(event.x() - h[0]) <= 8 && Math.abs(event.y() - h[1]) <= 8) {
                    com.mia.aperture.map.RouteService.setDestination(h[2], h[3], h[4]);
                    return true;
                }
            }
        }

        // Shift+right-click: create a waypoint at the clicked world X/Z (Y = player's, editable).
        if (event.button() == 1 && (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            int wx = (int) Math.floor(centerX + com.mia.aperture.map.MapGeometry.worldDeltaFromPixel(
                    event.x(), this.lastBlocksAcrossX, this.width));
            int wz = (int) Math.floor(centerZ + com.mia.aperture.map.MapGeometry.worldDeltaFromPixel(
                    event.y(), this.lastBlocksAcrossZ, this.height));
            int wy = (int) Math.floor(player.getY());
            String key = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
            this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("New Waypoint"),
                    "Waypoint", wx, wy, wz, com.mia.aperture.map.WaypointColor.RED, w -> {
                        MiaApertureModClient.waypoints.add(key, w);
                        com.mia.aperture.map.WaypointConfig.save(
                                MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
                    }));
            return true;
        }
        return false;
    }
```

Note: if `WaypointEditScreen`/`WaypointColor`/`WaypointStore`/`RouteService` aren't already imported,
either import them or use fully-qualified names as shown (the snippet uses FQNs except
`WaypointEditScreen`, `Component`, `MiaApertureModClient`, `AbyssMapState`, `GLFW` which are already
imported — verify and adjust).

- [ ] **Step 4: Update the on-screen hint line**

In `drawMapOverlay`, replace the hint string:

```java
        guiGraphics.drawString(this.font, "Drag to pan | Scroll to zoom | Ctrl+scroll to slice | Reset returns to you | V: relief/vanilla", 10, this.height - 20, 0xFFAAAAAA);
```

with:

```java
        guiGraphics.drawString(this.font, "Drag: pan | Scroll: zoom | Ctrl+scroll: slice | Shift+right-click: add waypoint | click waypoint: navigate | V: mode", 10, this.height - 20, 0xFFAAAAAA);
```

- [ ] **Step 5: Build**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(map): 2D map shift+right-click add waypoint + click-to-navigate

```

---

### Task 3: HelpContent — document the gestures

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/HelpContent.java`
- Test: `src/test/java/com/mia/aperture/map/HelpContentTest.java`

- [ ] **Step 1: Write the failing test**

Add to `HelpContentTest`:

```java
    @Test
    void mapTabDocumentsWaypointGestures() {
        boolean found = HelpContent.lines(HelpContent.Tab.MAP, keys).stream()
                .anyMatch(ln -> ln.text() != null && ln.text().toLowerCase().contains("waypoint"));
        assertTrue(found, "Map tab should document the waypoint click gestures");
    }
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*HelpContentTest*'
```

Expected: FAIL.

- [ ] **Step 3: Implement**

In `HelpContent.java`, in the `case MAP ->` block, after the existing
`o.add(item("Ctrl/Alt+Scroll", "Move the depth slice up or down"));` line, add:

```java
                o.add(item("Shift+Right-click", "Add a waypoint at that spot on the map"));
                o.add(item("Click a waypoint", "Route to that waypoint"));
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests '*HelpContentTest*'
```

Expected: PASS.

- [ ] **Step 5: Build + commit**

```bash
./gradlew build
git add src/main/java/com/mia/aperture/map/HelpContent.java src/test/java/com/mia/aperture/map/HelpContentTest.java
git commit -m "docs(help): document 2D map waypoint gestures

```

---

### Task 4: Build, install, in-game verify

**Files:** none.

- [ ] **Step 1: Build + install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```

- [ ] **Step 2: Verify in game**

Open the fullscreen map (M). Check:
1. **Shift+right-click** an empty spot → the edit screen opens prefilled with the clicked X/Z (Y =
   your Y); save → the new waypoint appears on the map (and minimap/3D), visible by default.
2. **Left-click** a visible waypoint marker → a route is planned to it (trail draws on the map).
3. **Buttons still work** (Waypoints, Settings) and **left-drag still pans**; scroll still zooms.
4. The hint line shows the new gestures; Help → Map tab lists them.

- [ ] **Step 3: Report findings**

Report each check. If the created waypoint lands at the wrong X/Z, capture the click position and the
resulting coords (points to the inverse mapping or center calc).

---

## Notes for the implementer

- Do NOT create a branch or worktree. Work on `main`. Commit only; push when the owner asks.
- No inline narrating comments; comments explain constraints/why.
- Reuse the existing `WaypointEditScreen` + save flow and `RouteService.setDestination` — do not
  reimplement creation or routing.
- The Y-from-a-top-down-click limitation is intentional (prefill player Y, user edits). Do not try
  to infer depth from the 2D map.
