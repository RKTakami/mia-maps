# Player Marker + X/Y/Z Readout Implementation Plan


**Goal:** Add a "you are here" dot+facing-chevron to the fullscreen map and a shareable raw X/Y/Z coordinate line to both the HUD and the fullscreen map.

**Architecture:** Purely additive overlays. A pure, unit-tested helper computes the player's screen pixel on the panned/zoomed fullscreen map; the screen draws a rotated chevron (clamped to the edge when panned away); two call sites draw a floored-MC-coord text line. No change to the compose/worker/depth pipeline.

**Tech Stack:** Fabric 1.21.11, Java 21, JUnit 5, GuiGraphics (`blit`, `fill`, `pose()` = Matrix3x2fStack).

**Build/test:** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Develop directly on `main` (project convention; no worktrees).

---

### Task 1: Pure marker-geometry helpers

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/MapGeometryTest.java`

- [ ] **Step 1: Add the failing tests**

Append these methods inside the existing `MapGeometryTest` class:

```java
    @Test
    void playerMarkerCentersWhenUnpanned() {
        assertEquals(400, MapGeometry.playerMarkerX(0.0, 400, 800));
        assertEquals(300, MapGeometry.playerMarkerY(0.0, 300, 600));
    }

    @Test
    void playerMarkerHitsEdgeAtHalfSpanPan() {
        // pan by half the view span puts the player at the screen edge
        assertEquals(0,   MapGeometry.playerMarkerX(200.0, 400, 800));
        assertEquals(800, MapGeometry.playerMarkerX(-200.0, 400, 800));
        assertEquals(0,   MapGeometry.playerMarkerY(150.0, 300, 600));
        assertEquals(600, MapGeometry.playerMarkerY(-150.0, 300, 600));
    }

    @Test
    void playerMarkerPositivePanMovesTowardOrigin() {
        assertTrue(MapGeometry.playerMarkerX(100.0, 400, 800) < 400);
        assertTrue(MapGeometry.playerMarkerY(75.0, 300, 600) < 300);
    }
```

(The test file already uses `import static org.junit.jupiter.api.Assertions.*;`, so `assertTrue` is available.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test`
Expected: FAIL — `cannot find symbol: method playerMarkerX` / `playerMarkerY`.

- [ ] **Step 3: Add the helpers**

In `MapGeometry.java`, add these two static methods (before the closing brace):

```java
    // Player's screen X on the fullscreen map. The map centers on player+pan, so the
    // player sits at screen center when unpanned and shifts by the pan otherwise.
    public static int playerMarkerX(double mapX, int blocksAcrossX, int width) {
        return (int) Math.round(width * (0.5 - mapX / blocksAcrossX));
    }

    public static int playerMarkerY(double mapZ, int blocksAcrossZ, int height) {
        return (int) Math.round(height * (0.5 - mapZ / blocksAcrossZ));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test`
Expected: PASS (all tests, including the 3 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapGeometry.java src/test/java/com/mia/aperture/map/MapGeometryTest.java
git commit -m "feat(map): pure player-marker screen-position helpers"
```

---

### Task 2: Draw the player marker on the fullscreen map

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

No unit test (GuiGraphics render path); verified in-game.

- [ ] **Step 1: Add fields to reuse the computed view span**

At the top of the class, next to `private int lastBandTop;`, add:

```java
    private int lastBlocksAcrossX = 1;
    private int lastBlocksAcrossZ = 1;
```

- [ ] **Step 2: Record the view span during the compose block**

In `render`, inside the `if (player != null) {` block, immediately after the two lines
that compute `blocksAcrossX` and `blocksAcrossZ`, add:

```java
            this.lastBlocksAcrossX = blocksAcrossX;
            this.lastBlocksAcrossZ = blocksAcrossZ;
```

- [ ] **Step 3: Draw the marker after the map blit**

In `render`, immediately AFTER the `drawMapOverlay(guiGraphics);` call and BEFORE the
N/S/E/W label block, add:

```java
        if (player != null) {
            int mx = com.mia.aperture.map.MapGeometry.playerMarkerX(
                    AbyssMapState.mapX, this.lastBlocksAcrossX, this.width);
            int my = com.mia.aperture.map.MapGeometry.playerMarkerY(
                    AbyssMapState.mapZ, this.lastBlocksAcrossZ, this.height);
            int inset = 6;
            int cmx = Math.max(inset, Math.min(this.width - inset, mx));
            int cmy = Math.max(inset, Math.min(this.height - inset, my));
            drawPlayerMarker(guiGraphics, cmx, cmy, player.getYRot());
        }
```

- [ ] **Step 4: Add the marker-drawing helper**

Add this private method to the class (e.g. after `drawMapOverlay`):

```java
    private void drawPlayerMarker(GuiGraphics g, int cx, int cy, float yaw) {
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFF0000);
        g.pose().pushMatrix();
        g.pose().translate(cx + 0.5f, cy + 0.5f);
        g.pose().rotate((float) Math.toRadians(yaw + 180.0f));
        g.fill(0, -6, 1, -5, 0xFFFFFF00);
        g.fill(-1, -5, 2, -4, 0xFFFFFF00);
        g.fill(-2, -4, 3, -3, 0xFFFFFF00);
        g.fill(-3, -3, 4, -2, 0xFFFFFF00);
        g.fill(-4, -2, 5, -1, 0xFFFFFF00);
        g.fill(-4, -1, -1, 0, 0xFFFFFF00);
        g.fill(2, -1, 5, 0, 0xFFFFFF00);
        g.pose().popMatrix();
    }
```

- [ ] **Step 5: Build to verify it compiles**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(map): draw player position + facing marker on the fullscreen map"
```

---

### Task 3: X/Y/Z readout on the HUD and fullscreen map

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

No unit test (render text); verified in-game against F3.

- [ ] **Step 1: Add the HUD coordinate line and reflow the text block**

In `MiaApertureModClient.drawHud`, the current text block reads:

```java
        int textX = x;
        int textBlockH = 34;
        int textY = (y + size + 6 + textBlockH <= screenHeight) ? (y + size + 6) : (y - textBlockH);
        context.drawString(client.font, "Depth: " + physicalDepth + "m", textX, textY, 0xFFFFFFFF);
        context.drawString(client.font, "Layer: " + layerName, textX, textY + 10, 0xFF55FF55);

        if (AbyssMapState.scrollActive) {
            context.drawString(client.font, "View: " + (int) AbyssMapState.scrollTargetCenterY + "m", textX, textY + 20, 0xFFFF5555);
        }
```

Replace it with (bump `textBlockH` to 44, insert X/Y/Z at `+20`, move `View:` to `+30`):

```java
        int textX = x;
        int textBlockH = 44;
        int textY = (y + size + 6 + textBlockH <= screenHeight) ? (y + size + 6) : (y - textBlockH);
        context.drawString(client.font, "Depth: " + physicalDepth + "m", textX, textY, 0xFFFFFFFF);
        context.drawString(client.font, "Layer: " + layerName, textX, textY + 10, 0xFF55FF55);
        context.drawString(client.font,
                "X " + (int) Math.floor(client.player.getX())
                        + "  Y " + (int) Math.floor(client.player.getY())
                        + "  Z " + (int) Math.floor(client.player.getZ()),
                textX, textY + 20, 0xFFFFFFFF);

        if (AbyssMapState.scrollActive) {
            context.drawString(client.font, "View: " + (int) AbyssMapState.scrollTargetCenterY + "m", textX, textY + 30, 0xFFFF5555);
        }
```

- [ ] **Step 2: Add the fullscreen coordinate line**

In `AbyssWorldMapScreen.drawMapOverlay`, the last line before the method's closing brace
is the "Drag to pan…" help line. Immediately BEFORE that help line, add:

```java
        var marker = this.minecraft.player;
        if (marker != null) {
            guiGraphics.drawString(this.font,
                    "X " + (int) Math.floor(marker.getX())
                            + "  Y " + (int) Math.floor(marker.getY())
                            + "  Z " + (int) Math.floor(marker.getZ()),
                    10, 46, 0xFFFFFFFF);
        }
```

(The existing overlay lines are at y = 10, 22, 34; 46 is the next row. The help line
stays anchored at `this.height - 20`.)

- [ ] **Step 3: Build to verify it compiles**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MiaApertureModClient.java src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(map): raw X/Y/Z coordinate readout on HUD and fullscreen map"
```

---

### Task 4: Version bump, build, install

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change `mod_version=1.6.0` to `mod_version=1.7.0`.

- [ ] **Step 2: Build and run all tests**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass (48 existing + 3 new = 51).

- [ ] **Step 3: Install to the Modrinth instance**

```powershell
$dest="<mods-dir>"
Remove-Item "$dest\mia-aperture-mod-*.jar"
Copy-Item "build\libs\mia-aperture-mod-1.7.0.jar" $dest
```
(If the jar is locked, the game is running — close it first.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "chore: bump version to 1.7.0"
```

---

## In-game verification (after install)

1. Open the fullscreen map (`M`): a red dot + yellow chevron sits on your position; the chevron points where you face (turn to confirm).
2. Pan the map: the marker tracks your real location; when you pan far, it clamps to the screen edge instead of disappearing.
3. Zoom in/out: the marker stays on your position.
4. HUD: an `X … Y … Z …` line appears under Depth/Layer and matches F3; toggling the aperture cull (`H`) still shows the `View:` line without overlap.
5. Fullscreen overlay: the same `X/Y/Z` line appears with Mode/Zoom/Slice and matches F3.
