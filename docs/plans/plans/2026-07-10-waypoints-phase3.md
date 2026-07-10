# Waypoints Phase 3 (In-World Beacons) Implementation Plan


**Goal:** Render each waypoint as an in-world HUD beacon — a coloured icon + name + distance projected at its real position, an edge arrow when off-screen, toggled on/off.

**Architecture:** A pure `BeaconGeometry` does the camera-relative projection and edge clamping (unit-tested with synthetic camera bases). `BeaconRenderer` feeds it the live camera basis + FOV each HUD frame and draws icons/labels/edge-markers. A persisted `MapSettings.showBeacons` gates it, toggled by an **N** keybind and a settings button.

**Tech Stack:** Fabric 1.21.11, Java 21, JUnit 5, GuiGraphics, JOML camera vectors.

**Build/test (PowerShell):** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Develop directly on `main` (project convention; no worktrees).

---

### Task 1: showBeacons setting + toggle keybind

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`
- Modify: `src/main/resources/assets/mia_aperture_mod/lang/en_us.json`

- [ ] **Step 1: Add the setting**

In `MapSettings.java`, next to the other fields, add (defaults on; a primitive boolean
initialised to `true` stays `true` when absent from JSON, so no config guard needed):

```java
    public boolean showBeacons = true;
```

- [ ] **Step 2: Add the keybind field + registration**

In `MiaApertureModClient`, next to `markWaypointKeyBind`, add:

```java
    public static KeyMapping toggleBeaconsKeyBind;
```

After the `markWaypointKeyBind` registration block, add:

```java
        toggleBeaconsKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.toggle_beacons",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                KeyMapping.Category.MISC
        ));
```

- [ ] **Step 3: Handle the toggle in the client tick**

In the `ClientTickEvents.END_CLIENT_TICK` lambda, after the `markWaypointKeyBind`
while-loop, add:

```java
            while (toggleBeaconsKeyBind.consumeClick()) {
                mapSettings.showBeacons = !mapSettings.showBeacons;
                com.mia.aperture.map.MapConfig.save(mapConfigPath(), mapSettings);
                if (client.player != null) {
                    client.player.displayClientMessage(Component.literal(
                            "Waypoint beacons: " + (mapSettings.showBeacons ? "ON" : "OFF")), true);
                }
            }
```

- [ ] **Step 4: Name the keybind in the lang file**

In `src/main/resources/assets/mia_aperture_mod/lang/en_us.json`, add (comma after the
previous last entry):

```json
  "key.mia_aperture_mod.toggle_beacons": "Toggle Waypoint Beacons"
```

- [ ] **Step 5: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/client/MiaApertureModClient.java src/main/resources/assets/mia_aperture_mod/lang/en_us.json
git commit -m "feat(waypoints): showBeacons setting + N toggle keybind"
```

---

### Task 2: BeaconGeometry (pure projection + edge clamp)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/BeaconGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/BeaconGeometryTest.java`

- [ ] **Step 1: Write the failing test**

Create `BeaconGeometryTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeaconGeometryTest {
    // Camera at origin looking +Z, up +Y, left +X, focal 500, 800x600 screen.
    private static BeaconGeometry.Screen proj(double x, double y, double z) {
        return BeaconGeometry.project(x, y, z, 0, 0, 1, 0, 1, 0, 1, 0, 0, 500, 800, 600);
    }

    @Test
    void pointAheadProjectsToCentre() {
        BeaconGeometry.Screen s = proj(0, 0, 10);
        assertTrue(s.onScreen());
        assertEquals(400, s.x());
        assertEquals(300, s.y());
    }

    @Test
    void pointToRightIsRightOfCentre() {
        // rel offset -5 on left axis => rightward on screen
        BeaconGeometry.Screen s = proj(-5, 0, 10);
        assertEquals(650, s.x());
        assertTrue(s.onScreen());
    }

    @Test
    void behindCameraIsOffScreen() {
        assertFalse(proj(0, 0, -10).onScreen());
    }

    @Test
    void edgeClampRightwardHitsRightEdge() {
        int[] p = BeaconGeometry.edgeClamp(1.0, 0.0, 800, 600, 10);
        assertEquals(790, p[0]);
        assertEquals(300, p[1]);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `BeaconGeometry` does not exist.

- [ ] **Step 3: Create BeaconGeometry**

```java
package com.mia.aperture.map;

public final class BeaconGeometry {
    private BeaconGeometry() {}

    public record Screen(boolean onScreen, int x, int y, double dirX, double dirY) {}

    // Project a camera-relative world offset (relX/Y/Z) with the camera basis (forward,
    // up, left) and focal length onto a w x h screen. dirX/dirY give the screen-space
    // direction (y down) toward the point, for an edge arrow when it is off-screen.
    public static Screen project(double relX, double relY, double relZ,
            double fx, double fy, double fz, double ux, double uy, double uz,
            double lx, double ly, double lz, double focal, int w, int h) {
        double zc = relX * fx + relY * fy + relZ * fz;
        double xc = -(relX * lx + relY * ly + relZ * lz);
        double yc = relX * ux + relY * uy + relZ * uz;
        if (zc > 0.05) {
            int sx = (int) Math.round(w / 2.0 + (xc / zc) * focal);
            int sy = (int) Math.round(h / 2.0 - (yc / zc) * focal);
            boolean on = sx >= 0 && sx < w && sy >= 0 && sy < h;
            return new Screen(on, sx, sy, xc, -yc);
        }
        // behind the camera: never on-screen; flip x so the edge arrow points correctly
        return new Screen(false, 0, 0, -xc, yc);
    }

    // Clamp a screen-space direction to the screen edge (inset by margin).
    public static int[] edgeClamp(double dirX, double dirY, int w, int h, int margin) {
        if (dirX == 0 && dirY == 0) return new int[]{w / 2, h / 2};
        double ang = Math.atan2(dirY, dirX);
        int x = (int) Math.round(w / 2.0 + Math.cos(ang) * (w / 2.0 - margin));
        int y = (int) Math.round(h / 2.0 + Math.sin(ang) * (h / 2.0 - margin));
        x = Math.max(margin, Math.min(w - margin, x));
        y = Math.max(margin, Math.min(h - margin, y));
        return new int[]{x, y};
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/BeaconGeometry.java src/test/java/com/mia/aperture/map/BeaconGeometryTest.java
git commit -m "feat(waypoints): pure beacon projection + edge-clamp geometry"
```

---

### Task 3: BeaconRenderer + HUD wiring

**Files:**
- Create: `src/main/java/com/mia/aperture/client/BeaconRenderer.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

No unit test (render/camera path); verified in-game.

- [ ] **Step 1: Create BeaconRenderer**

```java
package com.mia.aperture.client;

import com.mia.aperture.map.BeaconGeometry;
import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointStore;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class BeaconRenderer {
    private BeaconRenderer() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!MiaApertureModClient.mapSettings.showBeacons) return;
        if (mc.player == null || mc.level == null) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 c = cam.getPosition();
        Vector3f f = cam.getLookVector(), u = cam.getUpVector(), l = cam.getLeftVector();
        int w = g.guiWidth(), h = g.guiHeight();
        double focal = (h / 2.0) / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        String key = WaypointStore.currentServerKey(mc);
        for (Waypoint wp : MiaApertureModClient.waypoints.list(key)) {
            double rx = (wp.x + 0.5) - c.x, ry = (wp.y + 0.5) - c.y, rz = (wp.z + 0.5) - c.z;
            double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
            BeaconGeometry.Screen s = BeaconGeometry.project(rx, ry, rz,
                    f.x, f.y, f.z, u.x, u.y, u.z, l.x, l.y, l.z, focal, w, h);
            int color = wp.color.argb();
            if (s.onScreen()) {
                drawIcon(g, s.x(), s.y(), color);
                String label = wp.name + "  " + (int) dist + "m";
                int tw = mc.font.width(label);
                g.drawString(mc.font, label, s.x() - tw / 2, s.y() - 16, 0xFFFFFFFF);
            } else {
                int[] e = BeaconGeometry.edgeClamp(s.dirX(), s.dirY(), w, h, 16);
                drawIcon(g, e[0], e[1], color);
            }
        }
    }

    private static void drawIcon(GuiGraphics g, int cx, int cy, int color) {
        // diamond with a dark halo for contrast on any background
        g.fill(cx - 4, cy - 1, cx + 5, cy + 2, 0xC0000000);
        g.fill(cx - 1, cy - 4, cx + 2, cy + 5, 0xC0000000);
        g.fill(cx - 3, cy - 1, cx + 4, cy + 2, color);
        g.fill(cx - 1, cy - 3, cx + 2, cy + 4, color);
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, color);
    }
}
```

*API note:* `Camera.getLookVector/getUpVector/getLeftVector` return JOML `Vector3f`;
`getPosition()` returns `Vec3`; `mc.options.fov().get()` is the base vertical FOV in
degrees (beacon placement drifts slightly during FOV-changing effects — acceptable). If
`getLeftVector` is absent, derive right as `fwd × up`.

- [ ] **Step 2: Draw beacons from the HUD pass**

In `MiaApertureModClient.drawHud`, at the very end (after `drawSidebarLayerBar(...)`), add:

```java
        BeaconRenderer.render(context);
```

(`drawHud` already returns early when a screen is open or the GUI is hidden, so beacons
only render during normal play.)

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/BeaconRenderer.java src/main/java/com/mia/aperture/client/MiaApertureModClient.java
git commit -m "feat(waypoints): in-world beacon rendering (icon + name + distance, edge arrows)"
```

---

### Task 4: Beacons toggle in the settings panel

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Add a Beacons button before Done**

In `MapSettingsScreen.init`, replace the final Cave Mode + Done blocks. The current tail is:

```java
        this.addRenderableWidget(Button.builder(caveLabel(), b -> {
            MapSettings s = settings();
            s.caveMode = switch (s.caveMode) {
                case AUTO -> MapSettings.CaveMode.ON;
                case ON -> MapSettings.CaveMode.OFF;
                case OFF -> MapSettings.CaveMode.AUTO;
            };
            b.setMessage(caveLabel());
            persist();
        }).bounds(cx - 100, cy2 + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 72, 200, 20).build());
```

Replace with (insert a Beacons toggle at `cy2 + 72`, move Done to `cy2 + 96`):

```java
        this.addRenderableWidget(Button.builder(caveLabel(), b -> {
            MapSettings s = settings();
            s.caveMode = switch (s.caveMode) {
                case AUTO -> MapSettings.CaveMode.ON;
                case ON -> MapSettings.CaveMode.OFF;
                case OFF -> MapSettings.CaveMode.AUTO;
            };
            b.setMessage(caveLabel());
            persist();
        }).bounds(cx - 100, cy2 + 48, 200, 20).build());

        this.addRenderableWidget(Button.builder(beaconLabel(), b -> {
            settings().showBeacons = !settings().showBeacons;
            b.setMessage(beaconLabel());
            persist();
        }).bounds(cx - 100, cy2 + 72, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 96, 200, 20).build());
```

Add the label helper next to `caveLabel()`:

```java
    private static Component beaconLabel() {
        return Component.literal("Waypoint beacons: " + (settings().showBeacons ? "On" : "Off"));
    }
```

- [ ] **Step 2: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(waypoints): beacons toggle in the settings panel"
```

---

### Task 5: Version bump, build, install

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change `mod_version=1.12.0` to `mod_version=1.13.0`.

- [ ] **Step 2: Build + all tests**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass (78 + 4 new BeaconGeometry = 82).

- [ ] **Step 3: Install to the Modrinth instance**

```powershell
$dest="<mods-dir>"
Remove-Item "$dest\mia-maps-*.jar" -ErrorAction SilentlyContinue
Copy-Item "build\libs\mia-maps-1.13.0.jar" $dest
```
(If the jar is locked, close Minecraft first.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "chore: bump version to 1.13.0"
```

---

## In-game verification (after install)

1. With a waypoint set, look around — a coloured diamond + `name  <dist>m` floats at its
   real world position and tracks as you move/turn.
2. Turn away — the beacon becomes an edge marker on the side it's toward; behind you it
   sits on the correct edge.
3. Press **N** (or Settings → "Waypoint beacons") to toggle all beacons off/on; the state
   persists across a restart. The keybind shows as "Toggle Waypoint Beacons" in Controls.
4. Distance counts down as you approach; the beacon lands on/near the block when close.
