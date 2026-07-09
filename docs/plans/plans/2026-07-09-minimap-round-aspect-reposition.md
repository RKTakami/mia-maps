# Minimap Polish: Round, Aspect, Reposition — Implementation Plan

> **Project convention:** work directly on `main` in `<project-root>` — NO worktrees, NO branches.

**Goal:** Make the round minimap truly round (transparent corners), fix fullscreen-map block elongation (aspect), and make the HUD minimap repositionable by drag + corner presets, per `docs/plans/specs/2026-07-09-minimap-round-aspect-reposition-design.md`. Ship v1.5.0.

**Architecture:** Pure helpers (`MinimapLayout`, `MapSettings` clamp, `MapConfig`) are unit-tested. Round = a circular alpha-mask applied to the HUD `DynamicTexture` in `composeHud` (rotation-invariant) + a ring-only border overlay. Aspect = per-axis block span in the compositor so the fullscreen stretch cancels. Reposition = normalized position stored in `MapSettings`, a reusable `MinimapRenderer.draw`, and a `MinimapRepositionScreen` drag editor.

**Tech Stack:** Java 21, Fabric/loom, Mojang mappings (MC 1.21.11), Gson, JUnit 5.

**Build/test (always the vendored JDK):**
```powershell
cd "<project-root>"
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew test
.\gradlew build
```
Baseline test count entering this plan: **34**.

**Position model (locked here):** the minimap position is stored as two normalized fractions
`minimapX, minimapY ∈ [0,1]` giving the top-left position along the *valid on-screen range*:
`originX = margin + fx*(screenW - size - 2*margin)`, `originY = margin + fy*(screenH - size - 2*margin)`.
So `(1,0)`=top-right (the v1.4.0 default), `(0,0)`=top-left, `(0,1)`=bottom-left, `(1,1)`=bottom-right — size-independent. Margin = 10.

**Confirmed current signatures (from source):** `MapCompositor.composeMap(double,double,int blocksAcross,int,int,MapMode)`, `composeHud(double,double,int,int,MapMode)`, private `compose(DynamicTexture,int imageSize,double,double,int blocksAcross,int,int,MapMode)` with `blocksPerPixel = blocksAcross/imageSize`. `MinimapFrame.drawSquareFrame/drawRoundMask/drawCardinals`. `MapSettings` has `Orientation`, `FrameShape`, `minimapSize`, `setMinimapSize`. `MiaApertureModClient.drawHud` draws the minimap inline then depth/layer text then `drawSidebarLayerBar`.

---

### Task 1: `MapSettings` — position fields + corner enum

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Add failing tests** — append to `MapSettingsTest`:
```java
    @Test
    void positionDefaultsTopRight() {
        MapSettings s = new MapSettings();
        assertEquals(1.0, s.minimapX, 1e-9);
        assertEquals(0.0, s.minimapY, 1e-9);
    }

    @Test
    void positionClampsToUnitRange() {
        MapSettings s = new MapSettings();
        s.setMinimapPos(-0.5, 2.0);
        assertEquals(0.0, s.minimapX, 1e-9);
        assertEquals(1.0, s.minimapY, 1e-9);
        s.setMinimapPos(0.3, 0.7);
        assertEquals(0.3, s.minimapX, 1e-9);
        assertEquals(0.7, s.minimapY, 1e-9);
    }
```

- [ ] **Step 2: Run `.\gradlew test` → FAIL** (fields/method missing).

- [ ] **Step 3: Implement** — add to `MapSettings` (alongside the existing fields/enums):
```java
    public enum MinimapCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public double minimapX = 1.0; // normalized [0,1] along valid range; (1,0) = top-right
    public double minimapY = 0.0;

    public void setMinimapPos(double fx, double fy) {
        this.minimapX = Math.max(0.0, Math.min(1.0, fx));
        this.minimapY = Math.max(0.0, Math.min(1.0, fy));
    }
```

- [ ] **Step 4: Run `.\gradlew test` → PASS** (36 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/test
git commit -m "feat(map): minimap position fields (normalized) + corner enum"
```

---

### Task 2: `MinimapLayout` pure helper

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MinimapLayout.java`
- Test: `src/test/java/com/mia/aperture/map/MinimapLayoutTest.java`

- [ ] **Step 1: Write failing tests**
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mia.aperture.map.MapSettings.MinimapCorner;

class MinimapLayoutTest {
    // screenW=400, screenH=300, size=100, margin=10 -> valid X range [10, 290], Y range [10, 190]
    @Test
    void originXEndpointsAndMiddle() {
        assertEquals(10,  MinimapLayout.originX(0.0, 400, 100, 10));
        assertEquals(290, MinimapLayout.originX(1.0, 400, 100, 10));
        assertEquals(150, MinimapLayout.originX(0.5, 400, 100, 10));
    }

    @Test
    void originYEndpointsAndMiddle() {
        assertEquals(10,  MinimapLayout.originY(0.0, 300, 100, 10));
        assertEquals(190, MinimapLayout.originY(1.0, 300, 100, 10));
        assertEquals(100, MinimapLayout.originY(0.5, 300, 100, 10));
    }

    @Test
    void originClampsOutOfRangeFraction() {
        assertEquals(10,  MinimapLayout.originX(-1.0, 400, 100, 10));
        assertEquals(290, MinimapLayout.originX(2.0, 400, 100, 10));
    }

    @Test
    void cornerFractions() {
        assertArrayEquals(new double[]{0.0, 0.0}, MinimapLayout.cornerFraction(MinimapCorner.TOP_LEFT), 1e-9);
        assertArrayEquals(new double[]{1.0, 0.0}, MinimapLayout.cornerFraction(MinimapCorner.TOP_RIGHT), 1e-9);
        assertArrayEquals(new double[]{0.0, 1.0}, MinimapLayout.cornerFraction(MinimapCorner.BOTTOM_LEFT), 1e-9);
        assertArrayEquals(new double[]{1.0, 1.0}, MinimapLayout.cornerFraction(MinimapCorner.BOTTOM_RIGHT), 1e-9);
    }

    @Test
    void fractionFromPixelIsInverseOfOrigin() {
        // pixel 150 in X -> fraction 0.5 -> back to 150
        double fx = MinimapLayout.fractionFromPixelX(150, 400, 100, 10);
        assertEquals(0.5, fx, 1e-9);
        assertEquals(150, MinimapLayout.originX(fx, 400, 100, 10));
        // clamps
        assertEquals(0.0, MinimapLayout.fractionFromPixelX(-50, 400, 100, 10), 1e-9);
        assertEquals(1.0, MinimapLayout.fractionFromPixelX(9999, 400, 100, 10), 1e-9);
    }
}
```

- [ ] **Step 2: Run `.\gradlew test` → FAIL** (MinimapLayout missing).

- [ ] **Step 3: Implement**
```java
package com.mia.aperture.map;

import com.mia.aperture.map.MapSettings.MinimapCorner;

public final class MinimapLayout {
    private MinimapLayout() {}

    private static double clamp01(double v) { return Math.max(0.0, Math.min(1.0, v)); }
    private static int rangeX(int screenW, int size, int margin) { return Math.max(0, screenW - size - 2 * margin); }
    private static int rangeY(int screenH, int size, int margin) { return Math.max(0, screenH - size - 2 * margin); }

    public static int originX(double fx, int screenW, int size, int margin) {
        return margin + (int) Math.round(clamp01(fx) * rangeX(screenW, size, margin));
    }

    public static int originY(double fy, int screenH, int size, int margin) {
        return margin + (int) Math.round(clamp01(fy) * rangeY(screenH, size, margin));
    }

    public static double fractionFromPixelX(int px, int screenW, int size, int margin) {
        int r = rangeX(screenW, size, margin);
        if (r <= 0) return 0.0;
        return clamp01((px - margin) / (double) r);
    }

    public static double fractionFromPixelY(int py, int screenH, int size, int margin) {
        int r = rangeY(screenH, size, margin);
        if (r <= 0) return 0.0;
        return clamp01((py - margin) / (double) r);
    }

    public static double[] cornerFraction(MinimapCorner corner) {
        return switch (corner) {
            case TOP_LEFT -> new double[]{0.0, 0.0};
            case TOP_RIGHT -> new double[]{1.0, 0.0};
            case BOTTOM_LEFT -> new double[]{0.0, 1.0};
            case BOTTOM_RIGHT -> new double[]{1.0, 1.0};
        };
    }
}
```

- [ ] **Step 4: Run `.\gradlew test` → PASS** (41 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MinimapLayout.java src/test
git commit -m "feat(map): pure minimap layout helper (origin clamp, corners, pixel->fraction)"
```

---

### Task 3: `MapConfig` — guard new position fields

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapConfig.java`
- Test: `src/test/java/com/mia/aperture/map/MapConfigTest.java`

- [ ] **Step 1: Add failing tests** — append to `MapConfigTest`:
```java
    @Test
    void positionRoundTrips() {
        MapSettings s = new MapSettings();
        s.setMinimapPos(0.25, 0.75);
        MapSettings back = MapConfig.fromJson(MapConfig.toJson(s));
        assertEquals(0.25, back.minimapX, 1e-9);
        assertEquals(0.75, back.minimapY, 1e-9);
    }

    @Test
    void positionDefaultsWhenAbsent() {
        MapSettings s = MapConfig.fromJson("{\"minimapSize\": 120}");
        assertEquals(1.0, s.minimapX, 1e-9);
        assertEquals(0.0, s.minimapY, 1e-9);
    }

    @Test
    void positionClampedWhenOutOfRange() {
        MapSettings s = MapConfig.fromJson("{\"minimapX\": 5.0, \"minimapY\": -3.0}");
        assertEquals(1.0, s.minimapX, 1e-9);
        assertEquals(0.0, s.minimapY, 1e-9);
    }
```

- [ ] **Step 2: Run `.\gradlew test` → FAIL** (`positionClampedWhenOutOfRange` fails — deserialized values not re-clamped).

- [ ] **Step 3: Implement** — in `MapConfig.fromJson`, after the existing `s.setMinimapSize(s.minimapSize);` re-clamp line, add:
```java
            s.setMinimapPos(s.minimapX, s.minimapY);
```
(Absent JSON fields keep the `new MapSettings()` defaults 1.0/0.0; present values get clamped.)

- [ ] **Step 4: Run `.\gradlew test` → PASS** (44 tests).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture/map/MapConfig.java src/test
git commit -m "feat(map): persist + clamp minimap position in config"
```

---

### Task 4: Fullscreen aspect — per-axis block span

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapCompositor.java`
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

No unit test (GL-bound); verified in-game. Goal: the fullscreen map composes a region whose X:Z block ratio equals the screen's W:H, so the stretch-to-screen blit yields square blocks.

- [ ] **Step 1: Change `compose` to per-axis span.** In `MapCompositor.compose`, change the signature and the two mapping lines:
  - Signature: replace `int blocksAcross` with `int blocksAcrossX, int blocksAcrossZ`.
  - Replace `int lvl = MapGeometry.lvlForView(blocksAcross);` with `int lvl = MapGeometry.lvlForView(Math.max(blocksAcrossX, blocksAcrossZ));`
  - Replace `double blocksPerPixel = (double) blocksAcross / imageSize;` with:
    ```java
        double blocksPerPixelX = (double) blocksAcrossX / imageSize;
        double blocksPerPixelZ = (double) blocksAcrossZ / imageSize;
    ```
  - In the `py` loop: `int blockZ = centerShiftedZ + (int) Math.floor((py - imageSize / 2.0) * blocksPerPixelZ);`
  - In the `px` loop: `int blockX = centerShiftedX + (int) Math.floor((px - imageSize / 2.0) * blocksPerPixelX);`

- [ ] **Step 2: Update `composeMap` signature + sig hash.** Replace the `composeMap` method's `int blocksAcross` param with `int blocksAcrossX, int blocksAcrossZ`; include both in the hash and pass both to `compose`:
```java
    public static void composeMap(double centerWorldX, double centerWorldZ,
                                  int blocksAcrossX, int blocksAcrossZ, int bandTopY, int bandBottomY, MapMode mode) {
        long sig = java.util.Objects.hash((int) Math.floor(centerWorldX), (int) Math.floor(centerWorldZ),
                blocksAcrossX, blocksAcrossZ, bandTopY, bandBottomY, mode);
        int completed = MapWorker.COMPLETED.get();
        boolean viewChanged = sig != lastMapSig;
        boolean tilesChanged = completed != lastCompletedSeen;
        if (!viewChanged && !tilesChanged) return;
        long now = System.currentTimeMillis();
        if (!viewChanged && now - lastMapCompose < MAP_MIN_INTERVAL_MS) return;
        lastMapSig = sig;
        lastCompletedSeen = completed;
        lastMapCompose = now;
        mapTexture = ensure(MAP_TEXTURE, mapTexture, MAP_SIZE);
        compose(mapTexture, MAP_SIZE, centerWorldX, centerWorldZ, blocksAcrossX, blocksAcrossZ,
                bandTopY, bandBottomY, mode);
    }
```

- [ ] **Step 3: Update `composeHud` to pass equal spans.** Change its `compose(...)` call to pass the radius for both axes:
```java
        compose(hudTexture, HUD_SIZE, playerWorldX, playerWorldZ, HUD_RADIUS_BLOCKS * 2, HUD_RADIUS_BLOCKS * 2,
                bandTopY, bandBottomY, mode);
```

- [ ] **Step 4: Update the fullscreen caller** in `AbyssWorldMapScreen.render`. Replace:
```java
            int blocksAcross = (int) (256.0f / AbyssMapState.mapZoom);
```
```java
            com.mia.aperture.map.MapCompositor.composeMap(centerX, centerZ, blocksAcross,
                    bandTop, bandBottom, AbyssMapState.mapRenderMode);
```
with:
```java
            int base = (int) (256.0f / AbyssMapState.mapZoom);
            double aspect = (double) this.width / this.height;
            int blocksAcrossX = Math.max(1, (int) Math.round(base * aspect));
            int blocksAcrossZ = base;
            com.mia.aperture.map.MapCompositor.composeMap(centerX, centerZ, blocksAcrossX, blocksAcrossZ,
                    bandTop, bandBottom, AbyssMapState.mapRenderMode);
```

- [ ] **Step 5: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 44 tests.

- [ ] **Step 6: Commit**
```bash
git add src/main/java/com/mia/aperture
git commit -m "fix(map): fullscreen map aspect-correct via per-axis block span"
```

---

### Task 5: Truly round — circular alpha-mask + ring border

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapCompositor.java`
- Modify: `src/main/java/com/mia/aperture/map/MinimapFrame.java`

No unit test (GL-bound). Round now = HUD texture alpha-masked outside the inscribed circle + a ring-only border overlay; the opaque corner mask is removed.

- [ ] **Step 1: Add an `OVERSAMPLE` constant** to `MapCompositor` (single source for the 1.5× draw + mask radius). After the `HUD_RADIUS_BLOCKS` line add:
```java
    public static final float OVERSAMPLE = 1.5f;
```

- [ ] **Step 2: Add a round flag through composeHud → compose.** Change `composeHud` signature to add `boolean round`, and pass a mask radius to `compose`:
```java
    public static void composeHud(double playerWorldX, double playerWorldZ,
                                  int bandTopY, int bandBottomY, MapMode mode, boolean round) {
        long now = System.currentTimeMillis();
        if (now - lastHudCompose < HUD_INTERVAL_MS) return;
        lastHudCompose = now;
        hudTexture = ensure(HUD_TEXTURE, hudTexture, HUD_SIZE);
        double maskRadius = round ? HUD_SIZE / (2.0 * OVERSAMPLE) : 0.0;
        compose(hudTexture, HUD_SIZE, playerWorldX, playerWorldZ, HUD_RADIUS_BLOCKS * 2, HUD_RADIUS_BLOCKS * 2,
                bandTopY, bandBottomY, mode, maskRadius);
    }
```
Change `composeMap`'s `compose(...)` call to pass `0.0` as the trailing mask radius, and add the parameter to `compose`'s signature: `..., MapMode mode, double roundMaskRadius)`.

- [ ] **Step 3: Apply the alpha mask at the end of `compose`.** Just before `texture.upload();`, insert:
```java
        if (roundMaskRadius > 0.0) {
            double c = (imageSize - 1) / 2.0;
            double r2 = roundMaskRadius * roundMaskRadius;
            for (int py2 = 0; py2 < imageSize; py2++) {
                for (int px2 = 0; px2 < imageSize; px2++) {
                    double dx = px2 - c, dy = py2 - c;
                    if (dx * dx + dy * dy > r2) {
                        image.setPixel(px2, py2, 0x00000000);
                    }
                }
            }
        }
```

- [ ] **Step 4: Replace the opaque round mask with a ring-only border** in `MinimapFrame`. Change `ensureMask` so the mask is transparent everywhere except a grey ring at the rim, and rename `drawRoundMask` → `drawRoundBorder`:
```java
    private static void ensureMask() {
        if (maskTexture != null) return;
        DynamicTexture tex = new DynamicTexture(ROUND_MASK.toString(), MASK_RES, MASK_RES, true);
        NativeImage img = tex.getPixels();
        float c = (MASK_RES - 1) / 2.0f;
        float rOuter = c;
        float rInner = c - 2.0f;
        for (int y = 0; y < MASK_RES; y++) {
            for (int x = 0; x < MASK_RES; x++) {
                float dx = x - c, dy = y - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                img.setPixel(x, y, (d <= rOuter && d >= rInner) ? BORDER : 0x00000000);
            }
        }
        tex.upload();
        Minecraft.getInstance().getTextureManager().register(ROUND_MASK, tex);
        maskTexture = tex;
    }

    public static void drawRoundBorder(GuiGraphics g, int x, int y, int size) {
        ensureMask();
        g.blit(ROUND_MASK, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
    }
```
(The `BG` constant is still used by `drawSquareFrame`; keep it.)

- [ ] **Step 5: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 44 tests. (`drawHud` still calls the old `composeHud`/`drawRoundMask` names until Task 6 — this task WILL leave `MiaApertureModClient` uncompilable because signatures changed. So DO Task 6's compile together: build green is achieved at Task 6 Step 4. Commit code-only here.)

- [ ] **Step 6: Commit (code only; green build at Task 6)**
```bash
git add src/main/java/com/mia/aperture/map
git commit -m "feat(map): round minimap via circular alpha-mask + ring border"
```

---

### Task 6: Extract `MinimapRenderer` + reposition-aware `drawHud`

**Files:**
- Create: `src/main/java/com/mia/aperture/map/MinimapRenderer.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

Extract the minimap drawing (frame + map + markers) into a reusable renderer used by both the HUD and the drag editor, and position it via `MinimapLayout`.

- [ ] **Step 1: Create `MinimapRenderer`.** It composes the HUD texture and draws the minimap at a given `(x,y,size)`. (Pulls the block currently inline in `drawHud`.)
```java
package com.mia.aperture.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import me.cortex.voxy.client.core.util.AbyssUtil;
import com.mia.aperture.state.AbyssMapState;

public final class MinimapRenderer {
    private MinimapRenderer() {}

    // Draws the minimap frame + map + crosshair + arrow + cardinals at (x,y) size px.
    public static void draw(GuiGraphics ctx, LocalPlayer player, int x, int y, int size, MapSettings s) {
        int sector = AbyssUtil.getSection(player.getX());
        int bandTop = AbyssMapState.defaultBandTopY(player.getY(), sector);
        boolean round = s.shape == MapSettings.FrameShape.ROUND;
        MapCompositor.composeHud(player.getX(), player.getZ(), bandTop, bandTop - AbyssMapState.bandHeight(),
                s.mapRenderMode == null ? MapMode.RELIEF : s.mapRenderMode, round);

        int cx = x + size / 2;
        int cy = y + size / 2;
        int radius = size / 2;
        float yaw = player.getYRot();

        if (!round) {
            MinimapFrame.drawSquareFrame(ctx, x, y, size);
        }

        ctx.enableScissor(x, y, x + size, y + size);
        ctx.pose().pushMatrix();
        ctx.pose().translate(cx + 0.5f, cy + 0.5f);
        float rot = MinimapMarkers.headingRotationRad(s.orientation, yaw);
        if (rot != 0f) ctx.pose().rotate(rot);
        int drawSize = (int) (size * MapCompositor.OVERSAMPLE);
        int half = drawSize / 2;
        ctx.blit(MapCompositor.HUD_TEXTURE, -half, -half, half, half, 0.0f, 1.0f, 0.0f, 1.0f);
        ctx.pose().popMatrix();
        ctx.disableScissor();

        if (round) {
            MinimapFrame.drawRoundBorder(ctx, x, y, size);
        }

        ctx.fill(cx - 3, cy, cx + 4, cy + 1, 0x88FF0000);
        ctx.fill(cx, cy - 3, cx + 1, cy + 4, 0x88FF0000);

        ctx.pose().pushMatrix();
        ctx.pose().translate(cx + 0.5f, cy + 0.5f);
        if (s.orientation == MapSettings.Orientation.NORTH_UP) {
            ctx.pose().rotate((float) Math.toRadians(yaw + 180.0f));
        }
        ctx.fill(0, -4, 1, -3, 0xFFFFFF00);
        ctx.fill(-1, -3, 2, -2, 0xFFFFFF00);
        ctx.fill(-2, -2, 3, -1, 0xFFFFFF00);
        ctx.fill(-3, -1, 4, 0, 0xFFFFFF00);
        ctx.fill(-3, 0, -1, 1, 0xFFFFFF00);
        ctx.fill(2, 0, 4, 1, 0xFFFFFF00);
        ctx.pose().popMatrix();

        MinimapFrame.drawCardinals(ctx, cx, cy, radius - 6, s.orientation, yaw);
    }
}
```
NOTE: this uses `s.mapRenderMode`. In the current code the render mode is `AbyssMapState.mapRenderMode` (a field on AbyssMapState), NOT on MapSettings. Use `AbyssMapState.mapRenderMode` directly instead of `s.mapRenderMode` — i.e. replace the `composeHud(...)` mode argument with `AbyssMapState.mapRenderMode` and delete the `s.mapRenderMode == null ? ... ` expression. (Confirm by reading `AbyssMapState`.)

- [ ] **Step 2: Rewrite the minimap block in `drawHud`.** Replace the whole inline minimap block (from `var s = mapSettings;` through the cardinals `drawCardinals(...)` line — i.e. everything Task M6 added) with:
```java
        var s = mapSettings;
        int size = s.minimapSize;
        int margin = 10;
        int x = com.mia.aperture.map.MinimapLayout.originX(s.minimapX, screenWidth, size, margin);
        int y = com.mia.aperture.map.MinimapLayout.originY(s.minimapY, screenHeight, size, margin);
        com.mia.aperture.map.MinimapRenderer.draw(context, client.player, x, y, size, s);
```

- [ ] **Step 3: Reposition the depth/layer text to follow the minimap.** Replace the current `int textX = ...; int textY = ...;` (the M6 values `screenWidth - minimapSize - 10` / `10 + minimapSize + 6`) with placement relative to the minimap origin, flipping above when near the bottom:
```java
        int textX = x;
        int textBlockH = 34;
        int textY = (y + size + 6 + textBlockH <= screenHeight) ? (y + size + 6) : (y - textBlockH);
```
(Keep the existing `Depth:`/`Layer:`/`View:` drawString calls that follow, using `textX`/`textY`.)

- [ ] **Step 4: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 44 tests. This is the first green build since Task 5 (composeHud/drawRoundBorder signatures now satisfied). Fix any leftover references to the old inline locals (e.g. `cx`,`cy`,`radius`,`yaw` are now inside `MinimapRenderer`; remove any dangling uses in `drawHud`).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/mia/aperture
git commit -m "feat(map): reusable MinimapRenderer; HUD minimap positioned via MinimapLayout"
```

---

### Task 7: `MinimapRepositionScreen` drag editor

**Files:**
- Create: `src/main/java/com/mia/aperture/client/MinimapRepositionScreen.java`

- [ ] **Step 1: Implement**
```java
package com.mia.aperture.client;

import com.mia.aperture.map.MapConfig;
import com.mia.aperture.map.MapSettings;
import com.mia.aperture.map.MinimapLayout;
import com.mia.aperture.map.MinimapRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class MinimapRepositionScreen extends Screen {
    private static final int MARGIN = 10;
    private final Screen parent;

    public MinimapRepositionScreen(Screen parent) {
        super(Component.literal("Reposition Minimap"));
        this.parent = parent;
    }

    private static MapSettings settings() {
        return MiaApertureModClient.mapSettings;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, "Drag the minimap to reposition it", this.width / 2, 20, 0xFFFFFFFF);
        if (this.minecraft.player != null) {
            var s = settings();
            int size = s.minimapSize;
            int x = MinimapLayout.originX(s.minimapX, this.width, size, MARGIN);
            int y = MinimapLayout.originY(s.minimapY, this.height, size, MARGIN);
            MinimapRenderer.draw(g, this.minecraft.player, x, y, size, s);
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        var s = settings();
        int size = s.minimapSize;
        // place the minimap's top-left so its centre follows the cursor
        double topLeftX = event.x() - size / 2.0;
        double topLeftY = event.y() - size / 2.0;
        double fx = MinimapLayout.fractionFromPixelX((int) Math.round(topLeftX), this.width, size, MARGIN);
        double fy = MinimapLayout.fractionFromPixelY((int) Math.round(topLeftY), this.height, size, MARGIN);
        s.setMinimapPos(fx, fy);
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        MapConfig.save(MiaApertureModClient.mapConfigPath(), settings());
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
```
NOTE: `MouseButtonEvent.x()/y()` are the current cursor coords (confirmed by `AbyssWorldMapScreen.mouseScrolled`/`mouseDragged` usage in this project). If `event.x()`/`event.y()` don't resolve, use the `mouseX`/`mouseY` from a `mouseDragged(double mouseX, double mouseY, ...)` overload — javap `net.minecraft.client.gui.screens.Screen` to confirm the exact `mouseDragged` signature (the project's `AbyssWorldMapScreen` overrides `mouseDragged(MouseButtonEvent event, double dragX, double dragY)`, so mirror that).

- [ ] **Step 2: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 44 tests. Adjust the mouse-event accessor only if needed (see NOTE), keeping the drag→fraction→setMinimapPos flow.

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/mia/aperture/client/MinimapRepositionScreen.java
git commit -m "feat(map): drag-to-reposition minimap editor screen"
```

---

### Task 8: Settings panel — corner buttons + reposition button

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Add controls in `init()`.** After the existing size slider (before or after the Done button), add a row of four corner buttons and a Reposition button. Insert:
```java
        int cy2 = y + 108;
        this.addRenderableWidget(Button.builder(Component.literal("↖"), b -> setCorner(MapSettings.MinimapCorner.TOP_LEFT))
                .bounds(cx - 100, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("↗"), b -> setCorner(MapSettings.MinimapCorner.TOP_RIGHT))
                .bounds(cx - 50, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("↙"), b -> setCorner(MapSettings.MinimapCorner.BOTTOM_LEFT))
                .bounds(cx + 4, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("↘"), b -> setCorner(MapSettings.MinimapCorner.BOTTOM_RIGHT))
                .bounds(cx + 54, cy2, 46, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reposition (drag)"),
                b -> this.minecraft.setScreen(new MinimapRepositionScreen(this)))
                .bounds(cx - 100, cy2 + 24, 200, 20).build());
```
And move the existing Done button down so it doesn't overlap (change its `bounds(cx - 100, y + 80, 200, 20)` to `bounds(cx - 100, cy2 + 48, 200, 20)`).

- [ ] **Step 2: Add the `setCorner` helper** to `MapSettingsScreen`:
```java
    private static void setCorner(MapSettings.MinimapCorner corner) {
        double[] f = com.mia.aperture.map.MinimapLayout.cornerFraction(corner);
        settings().setMinimapPos(f[0], f[1]);
        persist();
    }
```
(`settings()` and `persist()` already exist in this class.)

- [ ] **Step 3: Build.** `.\gradlew build` → BUILD SUCCESSFUL, 44 tests. If the arrow glyphs (`↖` etc.) don't render in the font, replace with `"TL"/"TR"/"BL"/"BR"` labels.

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(map): settings panel corner presets + reposition button"
```

---

### Task 9: Release v1.5.0 + retire broken v1.3.0

**Files:**
- Modify: `gradle.properties`, `README.md`, `project_memory.md`

- [ ] **Step 1: Bump version.** `gradle.properties`: `mod_version=1.4.0` → `mod_version=1.5.0`.

- [ ] **Step 2: README.** Note: truly-round minimap, aspect-correct fullscreen map, repositionable minimap (drag + corner presets in Settings). Update jar reference to `1.5.0`.

- [ ] **Step 3: Build + install.**
```powershell
cd "<project-root>"
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew build
Remove-Item "<mods-dir>\mia-aperture-mod-*.jar"
Copy-Item "build\libs\mia-aperture-mod-1.5.0.jar" "<mods-dir>\"
```
Verify exactly one `mia-aperture-mod-1.5.0.jar`.

- [ ] **Step 4: Owner in-game verification (report before releasing to GitHub):**
  1. Round minimap has transparent corners (world visible) with a round border — at 0° and 45° facing (heading-up too).
  2. Square minimap unchanged.
  3. Fullscreen map (M) blocks are square, not stretched, and fill the screen.
  4. Settings → corner buttons snap the minimap to each corner; "Reposition (drag)" lets you drag it anywhere; it stays on-screen; position persists after restart.
  5. Depth/layer text stays on-screen in every position (top and bottom).
  6. No `[MIA Aperture]` errors.

- [ ] **Step 5: Update `project_memory.md`** — add a v1.5.0 entry (round via circular alpha-mask, fullscreen per-axis aspect, reposition via normalized fraction + `MinimapLayout` + `MinimapRepositionScreen`); note 44 tests; clear the "NEXT SESSION 3 items" block (now done); keep the BACKLOG block.

- [ ] **Step 6: Commit + push.**
```bash
git add -A
git commit -m "chore(release): v1.5.0 - round minimap, aspect fix, repositioning"
git push origin main
```

- [ ] **Step 7: After owner in-game sign-off, publish the GitHub release and retire the broken v1.3.0** (controller/owner does this, NOT the implementer subagent):
```bash
gh release create v1.5.0 "build/libs/mia-aperture-mod-1.5.0.jar" --repo crkt/MIA-Voxy-map-mod --target main --title "v1.5.0 - Round Minimap, Aspect Fix, Repositioning" --notes "..."
gh release delete v1.3.0 --repo crkt/MIA-Voxy-map-mod --yes   # broken blank-map build
```

---

## Self-Review Notes

- **Spec coverage:** round (T5 alpha-mask + ring, T6 draw path), fullscreen aspect (T4), reposition storage (T1) + layout helper (T2) + persistence (T3) + HUD wiring (T6) + drag editor (T7) + panel controls (T8); v1.5.0 + retire v1.3.0 (T9).
- **Placeholder scan:** none — every step has concrete code. Two flagged reconciliation NOTES (T6 `mapRenderMode` lives on AbyssMapState not MapSettings; T7 mouse-event accessor) are real "read the neighbouring file / javap" checks, not invented APIs.
- **Type consistency:** `MapSettings.minimapX/minimapY` + `setMinimapPos` + `MinimapCorner` (T1) used in T2/T3/T6/T7/T8; `MinimapLayout.originX/originY/fractionFromPixelX/Y/cornerFraction` (T2) used in T6/T7/T8; `MapCompositor.composeMap(...,blocksAcrossX,blocksAcrossZ,...)`, `composeHud(...,round)`, `compose(...,roundMaskRadius)`, `OVERSAMPLE`, `HUD_TEXTURE` (T4/T5) used in T6; `MinimapFrame.drawRoundBorder` (T5) used in T6; `MinimapRenderer.draw` (T6) used in T7.
- **Build sequencing:** Tasks 5 and 6 are committed code-only; first green build is Task 6 Step 4 (signatures line up then). All other tasks build green individually.
- **Test count:** 34 → 36 (T1) → 41 (T2) → 44 (T3); UI/GL tasks add none.
