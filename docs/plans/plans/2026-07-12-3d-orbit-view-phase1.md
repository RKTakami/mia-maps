# 3D Orbit View — Phase 1 (Orbit Cloud, Solid) Implementation Plan


**Goal:** A mouse-orbit 3D point-cloud view of the Abyss around the player (Solid mode) — sample Voxy surface voxels, project through an orbit camera, rasterize with a depth buffer into a texture, and show it in a screen with drag-to-orbit and scroll-to-zoom.

**Architecture:** A pure, unit-tested `OrbitCamera` (reusing `BeaconGeometry.project`, which gains a depth field). A `VoxelCloud` sampler reads a Voxy region into a unified grid and extracts surface voxels (surface test is pure/tested). An `OrbitScene` rasterizes the cloud into a `DynamicTexture` with a per-pixel depth buffer, recomputing only when the camera/focus changes (render-thread, bounded voxel count — a worker can come later if needed). An `OrbitView` screen owns input + blit.

**Tech Stack:** Fabric 1.21.11, Java 21, JUnit 5, GuiGraphics/NativeImage/DynamicTexture.

**Build/test (PowerShell):** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Develop directly on `main` (project convention; no worktrees).

---

### Task 1: OrbitCamera (+ depth on BeaconGeometry.Screen)

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/BeaconGeometry.java`
- Create: `src/main/java/com/mia/aperture/map/OrbitCamera.java`
- Test: `src/test/java/com/mia/aperture/map/OrbitCameraTest.java`

- [ ] **Step 1: Add a depth field to BeaconGeometry.Screen**

The orbit renderer needs per-voxel depth for the z-buffer. Change the record and both
constructor calls in `BeaconGeometry`:

```java
    public record Screen(boolean onScreen, int x, int y, double dirX, double dirY, double depth) {}
```

In `project`, the on-front return becomes:
```java
            return new Screen(on, sx, sy, xc, -yc, zc);
```
and the behind-camera return becomes:
```java
        return new Screen(false, 0, 0, -xc, yc, zc);
```
(Existing `BeaconRenderer`/`BeaconGeometryTest` only read `onScreen/x/y/dirX/dirY`, so
they're unaffected.)

- [ ] **Step 2: Write the failing OrbitCamera test**

Create `OrbitCameraTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitCameraTest {

    @Test
    void cameraSitsBackFromFocusAlongForward() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        double[] cam = c.cameraPos();
        assertEquals(0, cam[0], 1e-9);
        assertEquals(0, cam[1], 1e-9);
        assertEquals(-10, cam[2], 1e-9); // yaw0/pitch0 forward=(0,0,1) -> camera at -Z
    }

    @Test
    void focusProjectsToScreenCentre() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        BeaconGeometry.Screen s = c.project(0, 0, 0, 500, 800, 600);
        assertTrue(s.onScreen());
        assertEquals(400, s.x());
        assertEquals(300, s.y());
    }

    @Test
    void pointAboveFocusProjectsAboveCentre() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        BeaconGeometry.Screen s = c.project(0, 3, 0, 500, 800, 600);
        assertEquals(400, s.x());
        assertTrue(s.y() < 300);
    }

    @Test
    void nearerPointHasSmallerDepth() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        double near = c.project(0, 0, 0, 500, 800, 600).depth();   // at focus, depth 10
        double far = c.project(0, 0, 20, 500, 800, 600).depth();   // beyond focus, depth 30
        assertTrue(near < far);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `OrbitCamera` does not exist.

- [ ] **Step 4: Create OrbitCamera**

```java
package com.mia.aperture.map;

public final class OrbitCamera {
    public double focusX, focusY, focusZ;
    public double yawDeg, pitchDeg, distance;

    public OrbitCamera(double focusX, double focusY, double focusZ,
                       double yawDeg, double pitchDeg, double distance) {
        this.focusX = focusX; this.focusY = focusY; this.focusZ = focusZ;
        this.yawDeg = yawDeg; this.pitchDeg = pitchDeg; this.distance = distance;
    }

    // Direction the camera looks (camera -> focus). Minecraft-style yaw (0 -> +Z).
    public double[] forward() {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        return new double[]{ -Math.sin(yaw) * cp, -sp, Math.cos(yaw) * cp };
    }

    public double[] cameraPos() {
        double[] f = forward();
        return new double[]{ focusX - f[0] * distance, focusY - f[1] * distance, focusZ - f[2] * distance };
    }

    // [fx,fy,fz, ux,uy,uz, lx,ly,lz] — forward, up, left (left = -right).
    public double[] basis() {
        double[] f = forward();
        double rrx = -f[2], rrz = f[0];              // cross(forward, worldUp=(0,1,0))
        double rl = Math.sqrt(rrx * rrx + rrz * rrz);
        if (rl < 1e-6) rl = 1;
        double rx = rrx / rl, rz = rrz / rl;         // right (y=0)
        double lx = -rx, lz = -rz;                   // left
        double ux = -rz * f[1], uy = rz * f[0] - rx * f[2], uz = rx * f[1]; // up = cross(right, forward)
        return new double[]{ f[0], f[1], f[2], ux, uy, uz, lx, 0, lz };
    }

    public BeaconGeometry.Screen project(double wx, double wy, double wz, double focal, int w, int h) {
        double[] cam = cameraPos();
        double[] b = basis();
        return BeaconGeometry.project(wx - cam[0], wy - cam[1], wz - cam[2],
                b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, w, h);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/BeaconGeometry.java src/main/java/com/mia/aperture/map/OrbitCamera.java src/test/java/com/mia/aperture/map/OrbitCameraTest.java
git commit -m "feat(3d): OrbitCamera projection + depth on BeaconGeometry.Screen"
```

---

### Task 2: VoxelCloud sampler (surface voxels from Voxy)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/VoxelCloud.java`
- Test: `src/test/java/com/mia/aperture/map/VoxelCloudTest.java`

- [ ] **Step 1: Write the failing test (pure surface detection)**

Create `VoxelCloudTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelCloudTest {

    @Test
    void enclosedCellIsNotSurface() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true; // solid 3x3x3
        // centre (1,1,1) has all 6 neighbours solid -> interior
        assertFalse(VoxelCloud.isSurface(g, 3, 3, 3, 1, 1, 1));
    }

    @Test
    void cellWithAnAirNeighbourIsSurface() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true;
        g[idx(3, 3, 1, 1, 0)] = false; // open the cell below-ish (y=0) neighbour of (1,1,1)
        assertTrue(VoxelCloud.isSurface(g, 3, 3, 3, 1, 1, 1));
    }

    @Test
    void edgeCellIsSurface() {
        boolean[] g = new boolean[3 * 3 * 3];
        for (int i = 0; i < g.length; i++) g[i] = true;
        // a cell on the grid boundary has an out-of-bounds neighbour -> treated as exposed
        assertTrue(VoxelCloud.isSurface(g, 3, 3, 3, 0, 1, 1));
    }

    private static int idx(int w, int h, int x, int y, int z) { return (y * 3 + z) * w + x; }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `VoxelCloud` does not exist.

- [ ] **Step 3: Create VoxelCloud**

```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayList;
import java.util.List;

public final class VoxelCloud {
    private VoxelCloud() {}

    // A cloud point in world coords, ARGB colour, and cell size (blocks) for point sizing.
    public record Point(double x, double y, double z, int argb, int cellSize) {}

    // Grid index for a gx*gy*gz opaque grid (y-major, then z, then x). gy inferred by caller.
    private static int gi(int gx, int gz, int x, int y, int z) { return (y * gz + z) * gx + x; }

    // Pure: is cell (x,y,z) a surface cell in a gx*gy*gz opaque grid? True if opaque and
    // any 6-neighbour is air OR out of bounds (grid edges count as exposed).
    public static boolean isSurface(boolean[] opaque, int gx, int gy, int gz, int x, int y, int z) {
        if (!opaque[gi(gx, gz, x, y, z)]) return false;
        int[][] n = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] d : n) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= gx || ny >= gy || nz >= gz) return true;
            if (!opaque[gi(gx, gz, nx, ny, nz)]) return true;
        }
        return false;
    }

    // Sample a cube of `extent` blocks (per axis) around focus at the given Voxy level,
    // returning surface voxels as world-space points. Bounded by maxPoints via stride.
    public static List<Point> sample(WorldEngine engine, MapColorSource colors,
                                     int focusX, int focusY, int focusZ, int extent, int lvl, int maxPoints) {
        int cell = 1 << lvl;
        int g = Math.max(1, extent / cell);                 // cells per axis
        int originX = focusX - (g / 2) * cell;
        int originY = focusY - (g / 2) * cell;
        int originZ = focusZ - (g / 2) * cell;
        boolean[] opaque = new boolean[g * g * g];
        int[] argb = new int[g * g * g];

        long[] scratch = new long[32 * 32 * 32];
        // Fill the grid section by section (each Voxy section is 32 cells at this lvl).
        for (int by = 0; by < g; by += 32) {
            for (int bz = 0; bz < g; bz += 32) {
                for (int bx = 0; bx < g; bx += 32) {
                    int secX = Math.floorDiv(originX + bx * cell, 32 * cell);
                    int secY = Math.floorDiv(originY + by * cell, 32 * cell);
                    int secZ = Math.floorDiv(originZ + bz * cell, 32 * cell);
                    WorldSection sec = engine.acquireIfExists(lvl, secX, secY, secZ);
                    if (sec == null) continue;
                    try {
                        sec.copyDataTo(scratch);
                        int baseCellX = secX * 32, baseCellY = secY * 32, baseCellZ = secZ * 32;
                        for (int ly = 0; ly < 32; ly++) for (int lz = 0; lz < 32; lz++) for (int lx = 0; lx < 32; lx++) {
                            long id = scratch[(ly << 10) | (lz << 5) | lx];
                            if (id == 0 || !colors.isOpaque(id)) continue;
                            int worldCellX = baseCellX + lx, worldCellY = baseCellY + ly, worldCellZ = baseCellZ + lz;
                            int gxi = worldCellX - originX / cell, gyi = worldCellY - originY / cell, gzi = worldCellZ - originZ / cell;
                            if (gxi < 0 || gyi < 0 || gzi < 0 || gxi >= g || gyi >= g || gzi >= g) continue;
                            int gi = (gyi * g + gzi) * g + gxi;
                            opaque[gi] = true;
                            argb[gi] = colors.baseColor(id, Face.TOP);
                        }
                    } finally {
                        sec.release();
                    }
                }
            }
        }

        List<Point> pts = new ArrayList<>();
        for (int y = 0; y < g; y++) for (int z = 0; z < g; z++) for (int x = 0; x < g; x++) {
            if (!isSurface(opaque, g, g, g, x, y, z)) continue;
            int gi = (y * g + z) * g + x;
            pts.add(new Point(
                    originX + (x + 0.5) * cell, originY + (y + 0.5) * cell, originZ + (z + 0.5) * cell,
                    argb[gi], cell));
        }
        if (pts.size() > maxPoints) {
            int stride = (pts.size() + maxPoints - 1) / maxPoints;
            List<Point> trimmed = new ArrayList<>(maxPoints);
            for (int i = 0; i < pts.size(); i += stride) trimmed.add(pts.get(i));
            return trimmed;
        }
        return pts;
    }
}
```

*Note:* the section-fill indexing is fiddly; the pure `isSurface` is what the tests pin.
Verify the grid mapping in-game (points should sit on real terrain); adjust the
`worldCell → grid` arithmetic if the cloud looks offset.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/VoxelCloud.java src/test/java/com/mia/aperture/map/VoxelCloudTest.java
git commit -m "feat(3d): VoxelCloud surface-voxel sampler"
```

---

### Task 3: OrbitScene (rasterize cloud to a texture, Solid + depth buffer)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/OrbitScene.java`

No unit test (render-thread + Voxy + textures); verified in-game.

- [ ] **Step 1: Create OrbitScene**

```java
package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.List;

public final class OrbitScene {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("mia_aperture_mod", "orbit");
    public static final int SIZE = 768;
    private static final double FOV = Math.toRadians(70.0);
    private static final int EXTENT = 192;      // sampled cube edge (blocks) at zoom 1
    private static final int MAX_POINTS = 40000;

    private static DynamicTexture texture;
    private static long lastSig = Long.MIN_VALUE;
    private static List<VoxelCloud.Point> cloud;
    private static long cloudSig = Long.MIN_VALUE;

    private OrbitScene() {}

    public static void reset() { cloud = null; cloudSig = lastSig = Long.MIN_VALUE; }

    // Render-thread. Recomputes the cloud when the focus/zoom changes and re-rasterizes
    // when the camera changes; returns the texture id to blit.
    public static Identifier render(OrbitCamera cam, double zoom) {
        VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
        Minecraft mc = Minecraft.getInstance();
        if (rs == null || mc.level == null) return TEXTURE;
        var engine = rs.getEngine();
        var mapper = engine.getMapper();

        int extent = (int) Math.round(EXTENT * zoom);
        int lvl = MapGeometry.lvlForView(extent);
        long cs = java.util.Objects.hash((int) cam.focusX, (int) cam.focusY, (int) cam.focusZ, extent, lvl);
        if (cloud == null || cs != cloudSig) {
            var colors = new VoxyColorSource(new BlockColorBake().snapshot(), null); // see note
            cloud = VoxelCloud.sample(engine, MapCompositorColors.get(), (int) cam.focusX, (int) cam.focusY, (int) cam.focusZ,
                    extent, lvl, MAX_POINTS);
            cloudSig = cs;
        }

        long sig = java.util.Objects.hash(cs, (int) cam.yawDeg, (int) cam.pitchDeg, (int) cam.distance);
        if (texture == null) {
            texture = new DynamicTexture(TEXTURE.toString(), SIZE, SIZE, true);
            mc.getTextureManager().register(TEXTURE, texture);
        }
        if (sig != lastSig) {
            rasterize(cam);
            texture.upload();
            lastSig = sig;
        }
        return TEXTURE;
    }

    private static void rasterize(OrbitCamera cam) {
        NativeImage img = texture.getPixels();
        if (img == null || cloud == null) return;
        double focal = (SIZE / 2.0) / Math.tan(FOV / 2.0);
        float[] depth = new float[SIZE * SIZE];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        for (int i = 0; i < SIZE * SIZE; i++) img.setPixel(i % SIZE, i / SIZE, 0x00000000);
        double[] cel = cam.cameraPos();
        double[] b = cam.basis();
        for (VoxelCloud.Point p : cloud) {
            BeaconGeometry.Screen s = BeaconGeometry.project(p.x() - cel[0], p.y() - cel[1], p.z() - cel[2],
                    b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], focal, SIZE, SIZE);
            if (!s.onScreen()) continue;
            int r = Math.max(1, (int) Math.round(focal * p.cellSize() / s.depth() / 2.0));
            plot(img, depth, s.x(), s.y(), r, (float) s.depth(), 0xFF000000 | (p.argb() & 0xFFFFFF));
        }
    }

    private static void plot(NativeImage img, float[] depth, int cx, int cy, int r, float z, int color) {
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            int px = cx + dx, py = cy + dy;
            if (px < 0 || py < 0 || px >= SIZE || py >= SIZE) continue;
            int di = py * SIZE + px;
            if (z >= depth[di]) continue;
            depth[di] = z;
            img.setPixel(px, py, color);
        }
    }
}
```

*Notes for implementation (resolve inline):* colours must come from the same baked
`VoxyColorSource` the map uses — reuse the existing bake rather than a fresh one. The map's
colour source lives in `MapCompositor`; expose a package accessor (e.g. a static
`MapCompositor.colorSource()` that returns the current `VoxyColorSource`, baking via the
existing `BAKE`) and call that here instead of the placeholder `MapCompositorColors.get()`.
Wire that accessor as the first sub-step. NativeImage pixel channel order matches the map
compositor's `setPixel` usage.

- [ ] **Step 2: Add the colour-source accessor to MapCompositor**

In `MapCompositor`, add (baking on the render thread as `compose` already does):

```java
    public static VoxyColorSource colorSource() {
        var rs = IGetVoxyRenderSystem.getNullable();
        var mc = Minecraft.getInstance();
        if (rs == null || mc.level == null) return null;
        var mapper = rs.getEngine().getMapper();
        BAKE.update(mapper);
        if (tintResolver == null) tintResolver = new BiomeTintResolver(mapper, mc.level);
        return new VoxyColorSource(BAKE.snapshot(), tintResolver);
    }
```

Then in `OrbitScene.render`, replace the colour placeholder with:
```java
        MapColorSource colors = MapCompositor.colorSource();
        if (colors == null) return TEXTURE;
```
and pass `colors` to `VoxelCloud.sample`.

- [ ] **Step 3: Build to verify it compiles**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/map/OrbitScene.java src/main/java/com/mia/aperture/map/MapCompositor.java
git commit -m "feat(3d): OrbitScene rasterizes the voxel cloud to a texture (Solid + z-buffer)"
```

---

### Task 4: OrbitView screen (orbit + zoom + blit)

**Files:**
- Create: `src/main/java/com/mia/aperture/client/OrbitView.java`

No unit test (screen); verified in-game.

- [ ] **Step 1: Create OrbitView**

```java
package com.mia.aperture.client;

import com.mia.aperture.map.OrbitCamera;
import com.mia.aperture.map.OrbitScene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class OrbitView extends Screen {
    private double yaw = 45, pitch = 30, zoom = 1.0;

    public OrbitView() { super(Component.literal("Abyss 3D")); }

    private OrbitCamera camera() {
        var p = this.minecraft.player;
        double fx = p != null ? p.getX() : 0, fy = p != null ? p.getY() : 0, fz = p != null ? p.getZ() : 0;
        double dist = 160 * zoom;
        return new OrbitCamera(fx, fy, fz, yaw, pitch, dist);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, this.width, this.height, 0xFF0B0B10); // dark backdrop
        if (this.minecraft.player != null) {
            OrbitScene.render(camera(), zoom);
            int s = Math.min(this.width, this.height);
            int x0 = (this.width - s) / 2, y0 = (this.height - s) / 2;
            g.blit(OrbitScene.TEXTURE, x0, y0, s, s, 0.0f, 1.0f, 0.0f, 1.0f);
        }
        g.drawString(this.font, "Abyss 3D  —  drag: orbit   scroll: zoom   Esc: close", 8, 8, 0xFFFFFFFF);
        g.drawString(this.font, String.format("yaw %.0f  pitch %.0f  zoom %.2fx", yaw, pitch, zoom), 8, 20, 0xFFAAAAAA);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        yaw += dragX * 0.4;
        pitch = Math.max(-89, Math.min(89, pitch + dragY * 0.4));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        zoom *= vertical > 0 ? 0.85 : 1.18;
        zoom = Math.max(0.15, Math.min(6.0, zoom));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
```

*Note:* the exact `blit` overload and `MouseButtonEvent`/`KeyEvent` accessors match the
existing `AbyssWorldMapScreen` — mirror that file if a signature differs.

- [ ] **Step 2: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/OrbitView.java
git commit -m "feat(3d): OrbitView screen (drag-orbit, scroll-zoom, blit)"
```

---

### Task 5: Open keybind + reset wiring

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`
- Modify: `src/main/resources/assets/mia_aperture_mod/lang/en_us.json`

- [ ] **Step 1: Add the keybind field + registration (default J)**

Next to `toggleBeaconsKeyBind`, add:
```java
    public static KeyMapping orbitViewKeyBind;
```
After the beacons keybind registration:
```java
        orbitViewKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.orbit_view",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                KeyMapping.Category.MISC
        ));
```

- [ ] **Step 2: Open the screen from the client tick**

In the `END_CLIENT_TICK` lambda, after the beacons toggle while-loop:
```java
            while (orbitViewKeyBind.consumeClick()) {
                client.setScreen(new OrbitView());
            }
```

- [ ] **Step 3: Free the orbit texture on disconnect**

In the `ClientPlayConnectionEvents.DISCONNECT` handler, alongside `MapCompositor.reset()`, add:
```java
                com.mia.aperture.map.OrbitScene.reset();
```

- [ ] **Step 4: Name the keybind**

In `en_us.json` add (comma after the previous last entry):
```json
  "key.mia_aperture_mod.orbit_view": "Open Abyss 3D View"
```

- [ ] **Step 5: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MiaApertureModClient.java src/main/resources/assets/mia_aperture_mod/lang/en_us.json
git commit -m "feat(3d): J keybind opens the Abyss 3D orbit view"
```

---

### Task 6: Version bump, build, install

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

`mod_version=1.13.0` → `mod_version=1.14.0`.

- [ ] **Step 2: Build + all tests**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass (82 + OrbitCamera(4) + VoxelCloud(3) = 89).

- [ ] **Step 3: Install to the Modrinth instance**

```powershell
$dest="<mods-dir>"
Remove-Item "$dest\mia-maps-*.jar" -ErrorAction SilentlyContinue
Copy-Item "build\libs\mia-maps-1.14.0.jar" $dest
```
(Close Minecraft first if the jar is locked.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "chore: bump version to 1.14.0"
```

---

## In-game verification (after install)

1. Press **J** — a dark screen opens showing a 3D point-cloud of the Abyss around you.
2. **Left-drag** orbits the view (yaw + pitch); **scroll** zooms between a local detailed box and a wider coarser view; **Esc** closes.
3. The cloud sits on real terrain (roughly where blocks are), layers read as colour bands, and it stays responsive while dragging.
4. Reopening / moving re-centres on you; no lag spikes in normal play (the view only computes while open).

*Expect to tune after seeing it:* `EXTENT`, `MAX_POINTS`, `FOV`, point radius, and drag/zoom sensitivity — all constants. If dragging stutters, that's the cue to move rasterization to a worker thread (Phase 1.5).
