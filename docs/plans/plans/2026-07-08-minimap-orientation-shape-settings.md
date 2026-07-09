# Minimap Orientation, Shape, Size & Settings Panel — Implementation Plan

> **Project convention:** work directly on `main` in `<project-root>` — NO worktrees, NO branches.

**Goal:** Add HUD-minimap orientation modes (north-up / heading-up), square/round frame, resize, N/E/S/W cardinal markers, and a settings panel with persisted config, per `docs/plans/specs/2026-07-08-minimap-orientation-shape-settings-design.md`.

**Architecture:** Pure helpers (`MinimapMarkers` for rotation/marker angles, `MapConfig` JSON (de)serialization, `MapSettings` clamp) are unit-tested; the HUD draw, frame/mask rendering, settings screen, and config file I/O are in-game verified. The minimap texture is composed oversized so it can rotate/clip without empty corners; the fullscreen map stays north-up.

**Tech Stack:** Java 21, Fabric/loom, Mojang mappings (MC 1.21.11), Gson (bundled with MC), JUnit 5.

**Build/test (always the vendored JDK):**
```powershell
cd "<project-root>"
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew test    # unit tests
.\gradlew build   # full build incl. mod jar
```
Baseline test count entering this plan: **24**.

**Confirmed 1.21.11 APIs:**
- `GuiGraphics.enableScissor(int x0,int y0,int x1,int y1)` / `disableScissor()`; `blit(Identifier, int x1,int y1,int x2,int y2, float u0,float u1,float v0,float v1)`; `pose()` is a `Matrix3x2fStack` with `pushMatrix()/translate(float,float)/rotate(float rad)/popMatrix()`.
- `Button.builder(Component, Button.OnPress).bounds(x,y,w,h).build()`; `Screen.addRenderableWidget(widget)`; `AbstractSliderButton(int x,int y,int w,int h, Component msg, double value)` with abstract `updateMessage()` + `applyValue()` (read `this.value`, range 0..1; field is `protected double value`).
- `net.minecraft.network.chat.Component.literal(String)`.
- `net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()` → `java.nio.file.Path`.
- `com.google.gson.Gson` / `GsonBuilder` (bundled).
- `DynamicTexture(String label,int w,int h,boolean clear)`, `getPixels()`→`NativeImage`, `setPixel(x,y,argb)`, `upload()`; register via `Minecraft.getInstance().getTextureManager().register(Identifier, tex)`.

**Orientation math contract (locked here so signs aren't guessed):**
North's on-screen angle, measured **clockwise from up** in degrees:
`northAngleDeg = (orientation==NORTH_UP) ? 0 : -(yaw + 180)`.
A cardinal `c` (0=N,1=E,2=S,3=W) sits at `northAngleDeg + c*90`. Screen position on a circle of radius `r` about center `(cx,cy)`: `x = cx + r*sin(a)`, `y = cy - r*cos(a)` (a in radians). The minimap texture rotation passed to `pose().rotate` is `headingRotationRad = (NORTH_UP)?0 : toRadians(-(yaw+180))` — consistent with the already-verified arrow rotation `+(yaw+180)`.

---

### Task 1: `MapSettings` + enums

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Write the failing test**
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSettingsTest {
    @Test
    void defaults() {
        MapSettings s = new MapSettings();
        assertEquals(MapSettings.Orientation.NORTH_UP, s.orientation);
        assertEquals(MapSettings.FrameShape.SQUARE, s.shape);
        assertEquals(100, s.minimapSize);
    }

    @Test
    void sizeClampsToRange() {
        MapSettings s = new MapSettings();
        s.setMinimapSize(20);
        assertEquals(80, s.minimapSize);
        s.setMinimapSize(9999);
        assertEquals(256, s.minimapSize);
        s.setMinimapSize(150);
        assertEquals(150, s.minimapSize);
    }
}
```

- [ ] **Step 2: Run `.\gradlew test` → FAIL** (MapSettings missing).

- [ ] **Step 3: Implement**
```java
package com.mia.aperture.map;

public final class MapSettings {
    public enum Orientation { NORTH_UP, HEADING_UP }
    public enum FrameShape { SQUARE, ROUND }

    public static final int MIN_SIZE = 80;
    public static final int MAX_SIZE = 256;

    public Orientation orientation = Orientation.NORTH_UP;
    public FrameShape shape = FrameShape.SQUARE;
    public int minimapSize = 100;

    public void setMinimapSize(int px) {
        this.minimapSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, px));
    }
}
```

- [ ] **Step 4: Run `.\gradlew test` → PASS** (26 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/test
git commit -m "feat(map): MapSettings holder with orientation/shape/size + clamp"
```

---

### Task 2: `MinimapMarkers` pure helper

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MinimapMarkers.java`
- Test: `src/test/java/com/mia/aperture/map/MinimapMarkersTest.java`

- [ ] **Step 1: Write the failing tests**
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mia.aperture.map.MapSettings.Orientation;

class MinimapMarkersTest {
    private static void assertPos(int[] p, int x, int y) {
        assertEquals(x, p[0], "x"); assertEquals(y, p[1], "y");
    }

    @Test
    void northUpCardinalsAreAxisAligned() {
        // center (100,100), radius 50, any yaw ignored in NORTH_UP
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 0), 100, 50);  // N up
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 1), 150, 100); // E right
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 2), 100, 150); // S down
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 3), 50, 100);  // W left
    }

    @Test
    void headingUpFacingEastPutsNorthLeft() {
        // yaw -90 = facing east; east should be up, north left
        float yaw = -90f;
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 0), 50, 100);  // N left
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 1), 100, 50);  // E up
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 2), 150, 100); // S right
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 3), 100, 150); // W down
    }

    @Test
    void headingUpFacingSouthPutsNorthBottom() {
        // yaw 0 = facing south; south up, north bottom, east left
        float yaw = 0f;
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 0), 100, 150); // N bottom
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 1), 50, 100);  // E left
    }

    @Test
    void headingRotationZeroForNorthUp() {
        assertEquals(0f, MinimapMarkers.headingRotationRad(Orientation.NORTH_UP, 123f), 1e-6);
    }

    @Test
    void headingRotationHeadingUp() {
        assertEquals((float) Math.toRadians(-180.0), MinimapMarkers.headingRotationRad(Orientation.HEADING_UP, 0f), 1e-4);
        assertEquals((float) Math.toRadians(-90.0), MinimapMarkers.headingRotationRad(Orientation.HEADING_UP, -90f), 1e-4);
    }
}
```

- [ ] **Step 2: Run `.\gradlew test` → FAIL** (MinimapMarkers missing).

- [ ] **Step 3: Implement**
```java
package com.mia.aperture.map;

import com.mia.aperture.map.MapSettings.Orientation;

public final class MinimapMarkers {
    private MinimapMarkers() {}

    // North's on-screen angle, clockwise from up, in degrees.
    private static double northAngleDeg(Orientation o, float yaw) {
        return o == Orientation.NORTH_UP ? 0.0 : -(yaw + 180.0);
    }

    // Screen position of a cardinal (0=N,1=E,2=S,3=W) on a circle radius r about (cx,cy).
    public static int[] cardinalPos(int cx, int cy, int r, Orientation o, float yaw, int cardinal) {
        double a = Math.toRadians(northAngleDeg(o, yaw) + cardinal * 90.0);
        int x = cx + (int) Math.round(r * Math.sin(a));
        int y = cy - (int) Math.round(r * Math.cos(a));
        return new int[]{x, y};
    }

    // Rotation (radians) to apply to the minimap texture via pose().rotate.
    public static float headingRotationRad(Orientation o, float yaw) {
        return o == Orientation.NORTH_UP ? 0f : (float) Math.toRadians(-(yaw + 180.0));
    }
}
```

- [ ] **Step 4: Run `.\gradlew test` → PASS** (31 tests). If a rounding boundary makes a coordinate off by one, DO NOT change the test — recheck the formula; the four provided cases are exact for these inputs (sin/cos of multiples of 90° are 0/±1).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MinimapMarkers.java src/test
git commit -m "feat(map): pure minimap marker angle + heading rotation helper"
```

---

### Task 3: `MapConfig` (Gson) + load on init

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MapConfig.java`
- Test: `src/test/java/com/mia/aperture/map/MapConfigTest.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

- [ ] **Step 1: Write the failing test** (pure JSON round-trip, no disk)
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapConfigTest {
    @Test
    void roundTripPreservesValues() {
        MapSettings s = new MapSettings();
        s.orientation = MapSettings.Orientation.HEADING_UP;
        s.shape = MapSettings.FrameShape.ROUND;
        s.setMinimapSize(180);
        MapSettings back = MapConfig.fromJson(MapConfig.toJson(s));
        assertEquals(MapSettings.Orientation.HEADING_UP, back.orientation);
        assertEquals(MapSettings.FrameShape.ROUND, back.shape);
        assertEquals(180, back.minimapSize);
    }

    @Test
    void fromNullOrGarbageGivesDefaults() {
        MapSettings a = MapConfig.fromJson(null);
        assertEquals(MapSettings.Orientation.NORTH_UP, a.orientation);
        MapSettings b = MapConfig.fromJson("not json {{{");
        assertEquals(100, b.minimapSize);
        assertEquals(MapSettings.FrameShape.SQUARE, b.shape);
    }

    @Test
    void fromJsonClampsSize() {
        MapSettings s = MapConfig.fromJson("{\"minimapSize\": 5000}");
        assertEquals(256, s.minimapSize);
    }
}
```

- [ ] **Step 2: Run `.\gradlew test` → FAIL** (MapConfig missing).

- [ ] **Step 3: Implement**
```java
package com.mia.aperture.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MapConfig() {}

    public static String toJson(MapSettings s) {
        return GSON.toJson(s);
    }

    public static MapSettings fromJson(String json) {
        if (json == null) return new MapSettings();
        try {
            MapSettings s = GSON.fromJson(json, MapSettings.class);
            if (s == null) return new MapSettings();
            if (s.orientation == null) s.orientation = MapSettings.Orientation.NORTH_UP;
            if (s.shape == null) s.shape = MapSettings.FrameShape.SQUARE;
            s.setMinimapSize(s.minimapSize); // re-clamp deserialized value
            return s;
        } catch (Throwable t) {
            return new MapSettings();
        }
    }

    public static MapSettings load(Path file) {
        try {
            if (Files.exists(file)) {
                return fromJson(Files.readString(file));
            }
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to read map config: " + t);
        }
        MapSettings s = new MapSettings();
        save(file, s);
        return s;
    }

    public static void save(Path file, MapSettings s) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(s));
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to write map config: " + t);
        }
    }
}
```
Note: a freshly `new MapSettings()` deserialized with default `minimapSize=100` passes the clamp unchanged; a garbage/absent field leaves the default. `s.setMinimapSize(s.minimapSize)` re-clamps out-of-range persisted values.

- [ ] **Step 4: Run `.\gradlew test` → PASS** (34 tests).

- [ ] **Step 5: Load config on client init + expose the live settings.** In `MiaApertureModClient`, add a public static field and load it in `onInitializeClient` (top of the method, before keybinds):
```java
    public static com.mia.aperture.map.MapSettings mapSettings = new com.mia.aperture.map.MapSettings();
    public static java.nio.file.Path mapConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mia_aperture_map.json");
    }
```
and at the start of `onInitializeClient()`:
```java
        mapSettings = com.mia.aperture.map.MapConfig.load(mapConfigPath());
```

- [ ] **Step 6: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 34 tests. (Confirms Gson + FabricLoader.getConfigDir resolve on the classpath.)

- [ ] **Step 7: Commit**
```bash
git add src/main/java/com/mia/aperture src/test
git commit -m "feat(map): JSON config load/save for map settings; load on init"
```

---

### Task 4: Oversample the HUD minimap texture

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapCompositor.java`

The HUD texture must cover more ground than the frame shows so rotation/clipping never reveals empty corners. Raise the HUD texture to 256² and its radius to 96 blocks (1.5× the 64-block visible radius). No unit test (compose is GL-bound); verified in-game.

- [ ] **Step 1: Change the HUD constants.** In `MapCompositor`, set:
```java
    public static final int HUD_SIZE = 256;
    private static final int HUD_RADIUS_BLOCKS = 96;
```
(These already exist with values 128 and 64 — change them. `composeHud` already computes `blocksAcross = HUD_RADIUS_BLOCKS * 2`, so it now covers 192 blocks into the 256² texture; the drawer shows the centre 128-block region — see Task 6.)

- [ ] **Step 2: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 34 tests. (HUD still draws via the existing drawHud until Task 6; it will look zoomed-out for this one commit — acceptable, fixed in Task 6.)

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MapCompositor.java
git commit -m "feat(map): oversample the HUD minimap texture (256, r=96) for rotation headroom"
```

---

### Task 5: `MinimapFrame` — round mask + frame/cardinal drawing

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MinimapFrame.java`

Drawing helpers for the HUD minimap frame. Round is achieved with a generated radial mask texture (opaque dark outside a circle, transparent inside, grey ring at the edge) overlaid on the map — no stencil/shader. No unit test (GL/draw-bound); verified in-game. The mask uses only grey/transparent values (channel-order-agnostic).

- [ ] **Step 1: Implement**
```java
package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class MinimapFrame {
    public static final Identifier ROUND_MASK = Identifier.fromNamespaceAndPath("mia_aperture_mod", "round_mask");
    private static final int MASK_RES = 256;
    private static final int BG = 0xFF111111;
    private static final int BORDER = 0xFF888888;
    private static DynamicTexture maskTexture;

    private MinimapFrame() {}

    // Radial mask: transparent inside the circle, opaque dark outside, grey ring at the rim.
    private static void ensureMask() {
        if (maskTexture != null) return;
        DynamicTexture tex = new DynamicTexture(ROUND_MASK.toString(), MASK_RES, MASK_RES, true);
        NativeImage img = tex.getPixels();
        float c = (MASK_RES - 1) / 2.0f;
        float rInner = c;          // circle radius = half the mask
        float rBorder = c - 2.0f;  // 2px ring
        for (int y = 0; y < MASK_RES; y++) {
            for (int x = 0; x < MASK_RES; x++) {
                float dx = x - c, dy = y - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                int argb;
                if (d <= rBorder) argb = 0x00000000;      // inside: transparent (map shows)
                else if (d <= rInner) argb = BORDER;        // rim: grey border
                else argb = BG;                             // outside: opaque dark (hides corners)
                img.setPixel(x, y, argb);
            }
        }
        tex.upload();
        Minecraft.getInstance().getTextureManager().register(ROUND_MASK, tex);
        maskTexture = tex;
    }

    // Draw the square frame background + border for a size x size frame at (x,y).
    public static void drawSquareFrame(GuiGraphics g, int x, int y, int size) {
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, BG);
        g.renderOutline(x - 1, y - 1, size + 2, size + 2, BORDER);
    }

    // Overlay the round mask (hides square corners, draws the circular border).
    public static void drawRoundMask(GuiGraphics g, int x, int y, int size) {
        ensureMask();
        g.blit(ROUND_MASK, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
    }

    // Draw the four cardinal letters around the frame.
    public static void drawCardinals(GuiGraphics g, int cx, int cy, int radius,
                                     MapSettings.Orientation orientation, float yaw) {
        Font font = Minecraft.getInstance().font;
        String[] letters = {"N", "E", "S", "W"};
        int[] colors = {0xFFFF5555, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        for (int i = 0; i < 4; i++) {
            int[] p = MinimapMarkers.cardinalPos(cx, cy, radius, orientation, yaw, i);
            int tw = font.width(letters[i]);
            g.drawString(font, letters[i], p[0] - tw / 2, p[1] - 4, colors[i]);
        }
    }

    public static void reset() {
        maskTexture = null;
    }
}
```
(`renderOutline(x,y,w,h,argb)` is the existing method used in `drawHud` today.)

- [ ] **Step 2: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 34 tests.

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MinimapFrame.java
git commit -m "feat(map): minimap frame helper - round mask, square frame, cardinal letters"
```

---

### Task 6: Rewire `drawHud` for orientation / shape / size / cardinals

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

Replace the fixed minimap block in `drawHud` (the part from `int x = screenWidth - 110;` through the arrow `popMatrix()`) with settings-driven rendering. Keep the depth/layer text and sidebar below unchanged.

- [ ] **Step 1: Replace the minimap draw block.** In `drawHud`, replace everything from `int x = screenWidth - 110;` down to and including the arrow's `context.pose().popMatrix();` with:
```java
        var s = mapSettings;
        int size = s.minimapSize;
        int margin = 10;
        int x = screenWidth - size - margin;
        int y = margin;
        int cx = x + size / 2;
        int cy = y + size / 2;
        int radius = size / 2;
        float yaw = client.player.getYRot();

        // Background (square frame drawn for both; round overlays a mask after).
        com.mia.aperture.map.MinimapFrame.drawSquareFrame(context, x, y, size);

        // Clip to the frame and draw the (optionally rotated, oversampled) map centred.
        context.enableScissor(x, y, x + size, y + size);
        context.pose().pushMatrix();
        context.pose().translate(cx + 0.5f, cy + 0.5f);
        float rot = com.mia.aperture.map.MinimapMarkers.headingRotationRad(s.orientation, yaw);
        if (rot != 0f) context.pose().rotate(rot);
        // Oversampled texture (192 blocks) drawn at 1.5x frame so the centre 128 blocks fill
        // the frame and rotation always has data in the corners.
        int drawSize = (int) (size * 1.5f);
        int half = drawSize / 2;
        context.blit(com.mia.aperture.map.MapCompositor.HUD_TEXTURE,
                -half, -half, half, half, 0.0f, 1.0f, 0.0f, 1.0f);
        context.pose().popMatrix();
        context.disableScissor();

        // Round frame: mask the corners.
        if (s.shape == com.mia.aperture.map.MapSettings.FrameShape.ROUND) {
            com.mia.aperture.map.MinimapFrame.drawRoundMask(context, x, y, size);
        }

        // Centre crosshair.
        context.fill(cx - 3, cy, cx + 4, cy + 1, 0x88FF0000);
        context.fill(cx, cy - 3, cx + 1, cy + 4, 0x88FF0000);

        // Player arrow: rotates with facing when north-up; fixed pointing up when heading-up.
        context.pose().pushMatrix();
        context.pose().translate(cx + 0.5f, cy + 0.5f);
        if (s.orientation == com.mia.aperture.map.MapSettings.Orientation.NORTH_UP) {
            context.pose().rotate((float) Math.toRadians(yaw + 180.0f));
        }
        context.fill(0, -4, 1, -3, 0xFFFFFF00);
        context.fill(-1, -3, 2, -2, 0xFFFFFF00);
        context.fill(-2, -2, 3, -1, 0xFFFFFF00);
        context.fill(-3, -1, 4, 0, 0xFFFFFF00);
        context.fill(-3, 0, -1, 1, 0xFFFFFF00);
        context.fill(2, 0, 4, 1, 0xFFFFFF00);
        context.pose().popMatrix();

        // Cardinal letters around the frame (just inside the edge).
        com.mia.aperture.map.MinimapFrame.drawCardinals(context, cx, cy, radius - 6, s.orientation, yaw);
```

- [ ] **Step 2: Update the depth/layer text position to sit below the resizable minimap.** The text currently uses `int textX = screenWidth - 110; int textY = 120;`. Replace with:
```java
        int textX = screenWidth - mapSettings.minimapSize - 10;
        int textY = 10 + mapSettings.minimapSize + 6;
```
(so it follows the minimap's bottom edge as size changes).

- [ ] **Step 3: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 34 tests.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/mia/aperture/client/MiaApertureModClient.java
git commit -m "feat(map): HUD minimap honours orientation, shape, size, cardinal markers"
```

---

### Task 7: Fullscreen map — Settings button + edge cardinals

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

- [ ] **Step 1: Add edge cardinal letters** in `render`, after `drawMapOverlay(guiGraphics);` (map is always north-up):
```java
        var font = this.font;
        int midX = this.width / 2;
        int midY = this.height / 2;
        guiGraphics.drawString(font, "N", midX - font.width("N") / 2, 2, 0xFFFF5555);
        guiGraphics.drawString(font, "S", midX - font.width("S") / 2, this.height - 12, 0xFFFFFFFF);
        guiGraphics.drawString(font, "E", this.width - 10, midY - 4, 0xFFFFFFFF);
        guiGraphics.drawString(font, "W", 2, midY - 4, 0xFFFFFFFF);
```

- [ ] **Step 2: Add a Settings button** in `init()`, after the existing offset resets:
```java
        this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                net.minecraft.network.chat.Component.literal("Settings"),
                b -> this.minecraft.setScreen(new MapSettingsScreen(this)))
            .bounds(this.width - 90, this.height - 30, 80, 20)
            .build());
```
(Note: `MapSettingsScreen` is created in Task 8; this file will not compile until then. Commit Tasks 7 and 8 together — do Task 8 before running the build in Task 8 Step 4.)

- [ ] **Step 3: Commit (code only; build happens after Task 8)**
```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(map): fullscreen map edge cardinals + Settings button"
```

---

### Task 8: `MapSettingsScreen` panel

**Files:**
- Create: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Implement**
```java
package com.mia.aperture.client;

import com.mia.aperture.map.MapConfig;
import com.mia.aperture.map.MapSettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MapSettingsScreen extends Screen {
    private final Screen parent;

    public MapSettingsScreen(Screen parent) {
        super(Component.literal("Map Settings"));
        this.parent = parent;
    }

    private static MapSettings settings() {
        return MiaApertureModClient.mapSettings;
    }

    private static void persist() {
        MapConfig.save(MiaApertureModClient.mapConfigPath(), settings());
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4;

        this.addRenderableWidget(Button.builder(orientationLabel(), b -> {
            MapSettings s = settings();
            s.orientation = s.orientation == MapSettings.Orientation.NORTH_UP
                    ? MapSettings.Orientation.HEADING_UP : MapSettings.Orientation.NORTH_UP;
            b.setMessage(orientationLabel());
            persist();
        }).bounds(cx - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(shapeLabel(), b -> {
            MapSettings s = settings();
            s.shape = s.shape == MapSettings.FrameShape.SQUARE
                    ? MapSettings.FrameShape.ROUND : MapSettings.FrameShape.SQUARE;
            b.setMessage(shapeLabel());
            persist();
        }).bounds(cx - 100, y + 24, 200, 20).build());

        this.addRenderableWidget(new AbstractSliderButton(cx - 100, y + 48, 200, 20,
                sizeLabel(), sizeToValue(settings().minimapSize)) {
            @Override protected void updateMessage() { setMessage(sizeLabel()); }
            @Override protected void applyValue() {
                int px = MapSettings.MIN_SIZE
                        + (int) Math.round(this.value * (MapSettings.MAX_SIZE - MapSettings.MIN_SIZE));
                settings().setMinimapSize(px);
                persist();
            }
        });

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, y + 80, 200, 20).build());
    }

    private static double sizeToValue(int px) {
        return (px - MapSettings.MIN_SIZE) / (double) (MapSettings.MAX_SIZE - MapSettings.MIN_SIZE);
    }

    private static Component orientationLabel() {
        return Component.literal("Orientation: " + (settings().orientation == MapSettings.Orientation.NORTH_UP
                ? "North-locked" : "Rotate with facing"));
    }
    private static Component shapeLabel() {
        return Component.literal("Frame: " + (settings().shape == MapSettings.FrameShape.SQUARE
                ? "Square" : "Round"));
    }
    private static Component sizeLabel() {
        return Component.literal("Minimap size: " + settings().minimapSize + "px");
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 20, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
```

- [ ] **Step 2: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 34 tests (this compiles Task 7's reference to `MapSettingsScreen`). If `drawCenteredString` or `AbstractSliderButton.value` field access differs, javap-check and adjust the member name only (spike confirmed `protected double value`, `updateMessage`, `applyValue`).

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(map): map settings panel (orientation, shape, size slider)"
```

---

### Task 9: Release v1.4.0

**Files:**
- Modify: `gradle.properties`, `README.md`, `project_memory.md`

- [ ] **Step 1: Bump version.** `gradle.properties`: `mod_version=1.4.0`.

- [ ] **Step 2: README.** In Features/controls, note: minimap cardinal markers (N/E/S/W), north-locked vs rotate-with-facing, square/round frame, resizable, and a Settings button on the map screen. Update jar reference to `1.4.0`.

- [ ] **Step 3: Build + install.**
```powershell
cd "<project-root>"
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew build
Remove-Item "<mods-dir>\mia-aperture-mod-*.jar"
Copy-Item "build\libs\mia-aperture-mod-1.4.0.jar" "<mods-dir>\"
```
Verify exactly one `mia-aperture-mod-1.4.0.jar` present.

- [ ] **Step 4: Owner in-game verification (report before releasing — hold the GitHub release until confirmed):**
  1. Minimap shows N/E/S/W; north-locked: N at top, arrow rotates with facing.
  2. Settings → Rotate with facing: map spins so facing is up, arrow points up, cardinals orbit (turn E/W to confirm N moves correctly).
  3. Square vs Round frame both look right (round hides corners, has a border, no clipped data at 45° headings).
  4. Size slider resizes the minimap; depth/layer text follows; setting persists after a full game restart (config file written).
  5. Fullscreen map shows edge cardinals and a working Settings button.
  6. No `[MIA Aperture]` errors; no magenta (air fix holds).

- [ ] **Step 5: Update `project_memory.md`** with a v1.4.0 entry: minimap orientation/shape/size + cardinal markers + settings panel + `config/mia_aperture_map.json`; note the pure helpers (`MinimapMarkers`, `MapConfig`, `MapSettings`) are unit-tested; note the oversample (256²/r96) enabling rotation.

- [ ] **Step 6: Commit + push.**
```bash
git add -A
git commit -m "chore(release): v1.4.0 - minimap orientation, shape, size, cardinals, settings"
git push origin main
```

---

## Self-Review Notes

- **Spec coverage:** cardinal markers N/E/S/W (T2 helper + T5 draw + T7 fullscreen), orientation modes (T2 rotation + T6 draw), square/round (T5 mask + T6), resize (T1 clamp + T6 size + T8 slider), settings panel (T8) + button (T7), persistence (T3 + T8 persist calls + T3 load-on-init), oversample (T4), arrow per mode (T6). Fullscreen stays north-up (T7 only adds static letters + button).
- **Placeholder scan:** none — every step has concrete code/commands.
- **Type consistency:** `MapSettings.Orientation`/`FrameShape`, `MinimapMarkers.cardinalPos(cx,cy,r,orientation,yaw,cardinal)` and `headingRotationRad(orientation,yaw)`, `MapConfig.toJson/fromJson/load/save`, `MiaApertureModClient.mapSettings`/`mapConfigPath()`, `MinimapFrame.drawSquareFrame/drawRoundMask/drawCardinals`, `MapSettingsScreen(parent)` — used identically across tasks.
- **Cross-task build note:** Task 7 references `MapSettingsScreen` (Task 8); their builds are sequenced so the first green build is at Task 8 Step 2. Both commit before that build — acceptable (main-only, no CI gating per commit).
- **Test count:** 24 → 26 (T1) → 31 (T2) → 34 (T3); UI tasks add none.
