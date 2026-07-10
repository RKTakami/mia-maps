# Waypoints Phase 1 (Local Waypoints) Implementation Plan


**Goal:** Create, render, and manage colour-coded named waypoints stored per-server — mark-here (B key) and add-from-coords, coloured markers on both maps, and a manager screen (list/edit/delete).

**Architecture:** Pure data + persistence (`Waypoint`, `WaypointColor`, `WaypointStore`, `WaypointConfig`) mirroring the `MapSettings`/`MapConfig` pattern; a generalised `MapGeometry.screenOffsetPixel` reused for the player marker and waypoints; two screens (`WaypointEditScreen`, `WaypointListScreen`); render hooks in `AbyssWorldMapScreen` + `MinimapRenderer`; the B keybind and store load in `MiaApertureModClient`.

**Tech Stack:** Fabric 1.21.11, Java 21, JUnit 5, Gson, GuiGraphics/EditBox/Button.

**Build/test (PowerShell):** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Develop directly on `main` (project convention; no worktrees).

---

### Task 1: WaypointColor + Waypoint

**Files:**
- Create: `src/main/java/com/mia/aperture/map/WaypointColor.java`
- Create: `src/main/java/com/mia/aperture/map/Waypoint.java`
- Test: `src/test/java/com/mia/aperture/map/WaypointColorTest.java`

- [ ] **Step 1: Write the failing test**

Create `WaypointColorTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointColorTest {

    @Test
    void eightPresetsAllOpaqueAndDistinct() {
        WaypointColor[] all = WaypointColor.values();
        assertEquals(8, all.length);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (WaypointColor c : all) {
            assertEquals(0xFF, (c.argb() >>> 24) & 0xFF, c + " must be opaque");
            assertTrue(seen.add(c.argb()), c + " colour must be distinct");
        }
    }

    @Test
    void nextCyclesAndWraps() {
        assertEquals(WaypointColor.values()[1], WaypointColor.values()[0].next());
        WaypointColor last = WaypointColor.values()[WaypointColor.values().length - 1];
        assertEquals(WaypointColor.values()[0], last.next());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew test`
Expected: FAIL — `WaypointColor` does not exist.

- [ ] **Step 3: Create WaypointColor and Waypoint**

`WaypointColor.java`:

```java
package com.mia.aperture.map;

public enum WaypointColor {
    RED(0xFFE04040),
    ORANGE(0xFFE09020),
    YELLOW(0xFFE0D040),
    GREEN(0xFF40C040),
    AQUA(0xFF40C0C0),
    BLUE(0xFF4060E0),
    PURPLE(0xFFA050E0),
    WHITE(0xFFF0F0F0);

    private final int argb;

    WaypointColor(int argb) { this.argb = argb; }

    public int argb() { return argb; }

    public WaypointColor next() {
        WaypointColor[] v = values();
        return v[(ordinal() + 1) % v.length];
    }
}
```

`Waypoint.java` (plain class with a no-arg ctor for Gson, matching `MapSettings`):

```java
package com.mia.aperture.map;

public final class Waypoint {
    public String name;
    public int x;
    public int y;
    public int z;
    public WaypointColor color;

    public Waypoint() {}

    public Waypoint(String name, int x, int y, int z, WaypointColor color) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/WaypointColor.java src/main/java/com/mia/aperture/map/Waypoint.java src/test/java/com/mia/aperture/map/WaypointColorTest.java
git commit -m "feat(waypoints): Waypoint model + 8-colour preset enum"
```

---

### Task 2: WaypointStore (per-server model + sanitize)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/WaypointStore.java`
- Test: `src/test/java/com/mia/aperture/map/WaypointStoreTest.java`

- [ ] **Step 1: Write the failing test**

Create `WaypointStoreTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointStoreTest {

    @Test
    void addRemoveReplacePerServer() {
        WaypointStore s = new WaypointStore();
        Waypoint a = new Waypoint("A", 1, 2, 3, WaypointColor.RED);
        Waypoint b = new Waypoint("B", 4, 5, 6, WaypointColor.BLUE);
        s.add("srv1", a);
        s.add("srv1", b);
        s.add("srv2", a);
        assertEquals(2, s.list("srv1").size());
        assertEquals(1, s.list("srv2").size());
        s.replace("srv1", 0, new Waypoint("A2", 7, 8, 9, WaypointColor.GREEN));
        assertEquals("A2", s.list("srv1").get(0).name);
        s.remove("srv1", 1);
        assertEquals(1, s.list("srv1").size());
    }

    @Test
    void listNeverNull() {
        assertNotNull(new WaypointStore().list("unknown"));
        assertTrue(new WaypointStore().list("unknown").isEmpty());
    }

    @Test
    void sanitizeKeepsSafeCharsOnly() {
        assertEquals("mc.example.com_25565", WaypointStore.sanitize("mc.example.com:25565"));
        assertEquals("sp_My_World", WaypointStore.sanitize("sp:My World"));
        assertEquals("unknown", WaypointStore.sanitize(""));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `WaypointStore` does not exist.

- [ ] **Step 3: Create WaypointStore**

```java
package com.mia.aperture.map;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WaypointStore {
    private final Map<String, List<Waypoint>> byServer;

    public WaypointStore() { this(new LinkedHashMap<>()); }

    public WaypointStore(Map<String, List<Waypoint>> byServer) {
        this.byServer = byServer != null ? byServer : new LinkedHashMap<>();
    }

    public Map<String, List<Waypoint>> raw() { return byServer; }

    public List<Waypoint> list(String serverKey) {
        return byServer.computeIfAbsent(serverKey, k -> new ArrayList<>());
    }

    public void add(String serverKey, Waypoint w) { list(serverKey).add(w); }

    public void replace(String serverKey, int index, Waypoint w) {
        List<Waypoint> l = list(serverKey);
        if (index >= 0 && index < l.size()) l.set(index, w);
    }

    public void remove(String serverKey, int index) {
        List<Waypoint> l = list(serverKey);
        if (index >= 0 && index < l.size()) l.remove(index);
    }

    // Key the current world: multiplayer server address, or sp:<level>, sanitized.
    public static String currentServerKey(Minecraft mc) {
        if (mc.getCurrentServer() != null) return sanitize(mc.getCurrentServer().ip);
        if (mc.getSingleplayerServer() != null) {
            return sanitize("sp:" + mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        return "unknown";
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "unknown" : s;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/WaypointStore.java src/test/java/com/mia/aperture/map/WaypointStoreTest.java
git commit -m "feat(waypoints): per-server WaypointStore + server-key sanitize"
```

---

### Task 3: WaypointConfig (Gson persistence)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/WaypointConfig.java`
- Test: `src/test/java/com/mia/aperture/map/WaypointConfigTest.java`

- [ ] **Step 1: Write the failing test**

Create `WaypointConfigTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointConfigTest {

    @Test
    void roundTripPreservesPerServerWaypoints() {
        WaypointStore s = new WaypointStore();
        s.add("srv1", new Waypoint("Camp", -60, -38, -438, WaypointColor.AQUA));
        s.add("srv2", new Waypoint("Home", 10, 20, 30, WaypointColor.GREEN));
        WaypointStore back = WaypointConfig.fromJson(WaypointConfig.toJson(s));
        assertEquals(1, back.list("srv1").size());
        Waypoint w = back.list("srv1").get(0);
        assertEquals("Camp", w.name);
        assertEquals(-438, w.z);
        assertEquals(WaypointColor.AQUA, w.color);
        assertEquals(1, back.list("srv2").size());
    }

    @Test
    void nullOrGarbageGivesEmptyStore() {
        assertTrue(WaypointConfig.fromJson(null).raw().isEmpty());
        assertTrue(WaypointConfig.fromJson("not json {{{").raw().isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `WaypointConfig` does not exist.

- [ ] **Step 3: Create WaypointConfig**

```java
package com.mia.aperture.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class WaypointConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, List<Waypoint>>>() {}.getType();

    private WaypointConfig() {}

    public static String toJson(WaypointStore store) {
        return GSON.toJson(store.raw(), TYPE);
    }

    public static WaypointStore fromJson(String json) {
        if (json == null) return new WaypointStore();
        try {
            Map<String, List<Waypoint>> m = GSON.fromJson(json, TYPE);
            return new WaypointStore(m);
        } catch (Throwable t) {
            return new WaypointStore();
        }
    }

    public static WaypointStore load(Path file) {
        try {
            if (Files.exists(file)) return fromJson(Files.readString(file));
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to read waypoints: " + t);
        }
        return new WaypointStore();
    }

    public static void save(Path file, WaypointStore store) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(store));
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to write waypoints: " + t);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/WaypointConfig.java src/test/java/com/mia/aperture/map/WaypointConfigTest.java
git commit -m "feat(waypoints): Gson per-server persistence"
```

---

### Task 4: Generalised screen-offset helper

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapGeometry.java`
- Test: `src/test/java/com/mia/aperture/map/MapGeometryTest.java`

- [ ] **Step 1: Add the failing test**

Append to `MapGeometryTest`:

```java
    @Test
    void screenOffsetPixelCentersAndReachesEdge() {
        assertEquals(400, MapGeometry.screenOffsetPixel(0.0, 400, 800));   // on centre
        assertEquals(0,   MapGeometry.screenOffsetPixel(-200.0, 400, 800)); // half span left
        assertEquals(800, MapGeometry.screenOffsetPixel(200.0, 400, 800));  // half span right
    }

    @Test
    void playerMarkerMatchesScreenOffsetOfNegPan() {
        // the player marker is the point at delta = -pan
        assertEquals(MapGeometry.screenOffsetPixel(-100.0, 400, 800),
                MapGeometry.playerMarkerX(100.0, 400, 800));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `screenOffsetPixel` does not exist.

- [ ] **Step 3: Add the helper and refactor the marker helpers**

In `MapGeometry.java`, add:

```java
    // Screen pixel for a point offset deltaBlocks from the view centre (centre = dim/2,
    // edges at +/- half the span). Used by the player marker and waypoint markers.
    public static int screenOffsetPixel(double deltaBlocks, int blocksAcross, int dim) {
        return (int) Math.round(dim * (0.5 + deltaBlocks / blocksAcross));
    }
```

Replace the existing `playerMarkerX`/`playerMarkerY` bodies to delegate (the player sits at
`delta = -pan`):

```java
    public static int playerMarkerX(double mapX, int blocksAcrossX, int width) {
        return screenOffsetPixel(-mapX, blocksAcrossX, width);
    }

    public static int playerMarkerY(double mapZ, int blocksAcrossZ, int height) {
        return screenOffsetPixel(-mapZ, blocksAcrossZ, height);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS (existing player-marker tests still pass — same formula).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapGeometry.java src/test/java/com/mia/aperture/map/MapGeometryTest.java
git commit -m "refactor(map): generalise marker projection to screenOffsetPixel"
```

---

### Task 5: WaypointEditScreen (create/edit dialog)

**Files:**
- Create: `src/main/java/com/mia/aperture/client/WaypointEditScreen.java`

No unit test (GUI); verified in-game.

- [ ] **Step 1: Create the screen**

```java
package com.mia.aperture.client;

import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointColor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class WaypointEditScreen extends Screen {
    private final Screen parent;
    private final Consumer<Waypoint> onSave;
    private final String prefillName;
    private final int px, py, pz;
    private WaypointColor color;

    private EditBox nameBox, xBox, yBox, zBox;

    public WaypointEditScreen(Screen parent, Component title, String name,
                              int x, int y, int z, WaypointColor color, Consumer<Waypoint> onSave) {
        super(title);
        this.parent = parent;
        this.onSave = onSave;
        this.prefillName = name;
        this.px = x; this.py = y; this.pz = z;
        this.color = color;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4;
        this.nameBox = new EditBox(this.font, cx - 100, y, 200, 20, Component.literal("Name"));
        this.nameBox.setValue(prefillName);
        this.nameBox.setMaxLength(48);
        this.addRenderableWidget(nameBox);

        this.xBox = coordBox(cx - 100, y + 30, String.valueOf(px));
        this.yBox = coordBox(cx - 32, y + 30, String.valueOf(py));
        this.zBox = coordBox(cx + 36, y + 30, String.valueOf(pz));

        this.addRenderableWidget(Button.builder(colorLabel(), b -> {
            color = color.next();
            b.setMessage(colorLabel());
        }).bounds(cx - 100, y + 60, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(cx - 100, y + 90, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"),
                b -> this.minecraft.setScreen(parent)).bounds(cx + 4, y + 90, 96, 20).build());
    }

    private EditBox coordBox(int x, int y, String value) {
        EditBox b = new EditBox(this.font, x, y, 64, 20, Component.literal("coord"));
        b.setValue(value);
        b.setMaxLength(12);
        this.addRenderableWidget(b);
        return b;
    }

    private Component colorLabel() {
        return Component.literal("Colour: " + color);
    }

    private void save() {
        try {
            int x = Integer.parseInt(xBox.getValue().trim());
            int y = Integer.parseInt(yBox.getValue().trim());
            int z = Integer.parseInt(zBox.getValue().trim());
            String name = nameBox.getValue().trim();
            if (name.isEmpty()) name = "Waypoint";
            onSave.accept(new Waypoint(name, x, y, z, color));
            this.minecraft.setScreen(parent);
        } catch (NumberFormatException ignored) {
            // invalid coords: leave the screen open for correction
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 24, 0xFFFFFFFF);
        int cx = this.width / 2;
        int y = this.height / 4;
        g.drawString(this.font, "Name", cx - 100, y - 10, 0xFFAAAAAA);
        g.drawString(this.font, "X / Y / Z", cx - 100, y + 22, 0xFFAAAAAA);
        g.fill(cx + 84, y + 60, cx + 100, y + 76, color.argb()); // colour swatch on the button
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/WaypointEditScreen.java
git commit -m "feat(waypoints): create/edit dialog screen"
```

---

### Task 6: WaypointListScreen (manager) + fullscreen button

**Files:**
- Create: `src/main/java/com/mia/aperture/client/WaypointListScreen.java`
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

No unit test (GUI); verified in-game.

- [ ] **Step 1: Create the list screen**

```java
package com.mia.aperture.client;

import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointColor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class WaypointListScreen extends Screen {
    private final Screen parent;

    public WaypointListScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
    }

    private static String key() {
        return com.mia.aperture.map.WaypointStore.currentServerKey(net.minecraft.client.Minecraft.getInstance());
    }

    private static List<Waypoint> list() {
        return MiaApertureModClient.waypoints.list(key());
    }

    private static void persist() {
        com.mia.aperture.map.WaypointConfig.save(MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 40;
        List<Waypoint> wps = list();
        for (int i = 0; i < wps.size() && i < 8; i++) {
            final int index = i;
            int rowY = top + i * 24;
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(index))
                    .bounds(cx + 40, rowY, 60, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                list().remove(index);
                persist();
                this.rebuildWidgets();
            }).bounds(cx + 104, rowY, 60, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Add"), b ->
                this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("New Waypoint"),
                        "Waypoint", 0, 0, 0, WaypointColor.RED, w -> { list().add(w); persist(); })))
                .bounds(cx - 100, this.height - 52, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent)).bounds(cx + 4, this.height - 52, 96, 20).build());
    }

    private void edit(int index) {
        List<Waypoint> wps = list();
        if (index < 0 || index >= wps.size()) return;
        Waypoint w = wps.get(index);
        this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("Edit Waypoint"),
                w.name, w.x, w.y, w.z, w.color, nw -> { list().set(index, nw); persist(); }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        int cx = this.width / 2;
        int top = 40;
        List<Waypoint> wps = list();
        if (wps.isEmpty()) {
            g.drawCenteredString(this.font, "No waypoints yet — Add one, or press B in-world.",
                    cx, top + 4, 0xFFAAAAAA);
        }
        for (int i = 0; i < wps.size() && i < 8; i++) {
            Waypoint w = wps.get(i);
            int rowY = top + i * 24;
            g.fill(cx - 100, rowY + 4, cx - 88, rowY + 16, w.color.argb());
            g.drawString(this.font, w.name + "  (" + w.x + " " + w.y + " " + w.z + ")",
                    cx - 82, rowY + 6, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
```

- [ ] **Step 2: Add a "Waypoints" button to the fullscreen map**

In `AbyssWorldMapScreen.init`, after the "Reset" button block, add:

```java
        this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Waypoints"),
                b -> this.minecraft.setScreen(new WaypointListScreen(this)))
            .bounds(this.width - 270, this.height - 30, 80, 20)
            .build());
```

- [ ] **Step 3: Build to verify it compiles**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL. (Depends on `MiaApertureModClient.waypoints` and
`waypointConfigPath()` added in Task 8 — if compiling this task alone, do Task 8 Step 1
first, or compile them together.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/WaypointListScreen.java src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(waypoints): manager list screen + fullscreen map button"
```

---

### Task 7: Render waypoints on both maps

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`
- Modify: `src/main/java/com/mia/aperture/map/MinimapRenderer.java`

No unit test (render path); verified in-game.

- [ ] **Step 1: Fullscreen — draw waypoints after the player marker**

In `AbyssWorldMapScreen.render`, inside the `if (player != null)` block that draws the
player marker (right after the `drawPlayerMarker(...)` call), add:

```java
            String wpKey = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
            double centerX = player.getX() + AbyssMapState.mapX;
            double centerZ = player.getZ() + AbyssMapState.mapZ;
            for (com.mia.aperture.map.Waypoint w : MiaApertureModClient.waypoints.list(wpKey)) {
                int wx = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.x - centerX, this.lastBlocksAcrossX, this.width);
                int wy = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        w.z - centerZ, this.lastBlocksAcrossZ, this.height);
                int cwx = Math.max(inset, Math.min(this.width - inset, wx));
                int cwy = Math.max(inset, Math.min(this.height - inset, wy));
                drawWaypoint(guiGraphics, cwx, cwy, w.color.argb(), w.name);
            }
```

(`inset` is the local already declared for the player-marker clamp; keep it in scope by
placing this block right after the marker clamp code.)

- [ ] **Step 2: Add the waypoint-drawing helper to AbyssWorldMapScreen**

```java
    private void drawWaypoint(GuiGraphics g, int cx, int cy, int color, String name) {
        // small diamond
        g.fill(cx - 1, cy - 4, cx + 2, cy + 5, color);
        g.fill(cx - 4, cy - 1, cx + 5, cy + 2, color);
        g.fill(cx - 2, cy - 3, cx + 3, cy + 4, color);
        g.fill(cx - 3, cy - 2, cx + 4, cy + 3, color);
        g.drawString(this.font, name, cx + 6, cy - 4, 0xFFFFFFFF);
    }
```

- [ ] **Step 3: Minimap — draw waypoint dots within radius**

In `MinimapRenderer.draw`, after the crosshair/arrow drawing and before
`MinimapFrame.drawCardinals(...)`, add:

```java
        String wpKey = AbyssUtil.getSection(player.getX()) >= 0
                ? com.mia.aperture.map.WaypointStore.currentServerKey(net.minecraft.client.Minecraft.getInstance())
                : "unknown";
        double halfBlocks = MapCompositor.HUD_RADIUS_BLOCKS; // 96
        for (com.mia.aperture.map.Waypoint w : com.mia.aperture.client.MiaApertureModClient.waypoints.list(wpKey)) {
            double dx = w.x - player.getX();
            double dz = w.z - player.getZ();
            if (Math.abs(dx) > halfBlocks || Math.abs(dz) > halfBlocks) continue;
            float px = (float) (dx / halfBlocks) * radius;
            float pz = (float) (dz / halfBlocks) * radius;
            float rot = MinimapMarkers.headingRotationRad(s.orientation, yaw);
            float rx = (float) (px * Math.cos(rot) - pz * Math.sin(rot));
            float rz = (float) (px * Math.sin(rot) + pz * Math.cos(rot));
            int dotX = cx + Math.round(rx);
            int dotY = cy + Math.round(rz);
            ctx.fill(dotX - 1, dotY - 1, dotX + 2, dotY + 2, w.color.argb());
        }
```

(The `cx`, `cy`, `radius`, `yaw`, `s`, and `AbyssUtil` are already in scope in `draw`.)

- [ ] **Step 4: Build to verify it compiles**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java src/main/java/com/mia/aperture/map/MinimapRenderer.java
git commit -m "feat(waypoints): render markers on the fullscreen map and minimap"
```

---

### Task 8: Store wiring + B keybind (mark here)

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`
- Modify: `src/main/resources/assets/mia_aperture_mod/lang/en_us.json`

No unit test (keybind/IO); verified in-game.

- [ ] **Step 1: Add the store, config path, and keybind field**

In `MiaApertureModClient`, next to `mapSettings`, add:

```java
    public static com.mia.aperture.map.WaypointStore waypoints = new com.mia.aperture.map.WaypointStore();
    public static KeyMapping markWaypointKeyBind;

    public static java.nio.file.Path waypointConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("mia_maps_waypoints.json");
    }
```

- [ ] **Step 2: Load the store on init and register the keybind**

In `onInitializeClient`, after `mapSettings = ... MapConfig.load(...)`, add:

```java
        waypoints = com.mia.aperture.map.WaypointConfig.load(waypointConfigPath());
```

After the `caveKeyBind` registration block, add:

```java
        markWaypointKeyBind = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mia_aperture_mod.mark_waypoint",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                KeyMapping.Category.MISC
        ));
```

- [ ] **Step 3: Handle the mark-here keybind in the client tick**

In the `ClientTickEvents.END_CLIENT_TICK` lambda, after the `caveKeyBind` while-loop, add:

```java
            while (markWaypointKeyBind.consumeClick()) {
                if (client.player != null) {
                    int x = (int) Math.floor(client.player.getX());
                    int y = (int) Math.floor(client.player.getY());
                    int z = (int) Math.floor(client.player.getZ());
                    String skey = com.mia.aperture.map.WaypointStore.currentServerKey(client);
                    client.setScreen(new WaypointEditScreen(null, Component.literal("New Waypoint"),
                            "Waypoint", x, y, z, com.mia.aperture.map.WaypointColor.RED, w -> {
                        waypoints.add(skey, w);
                        com.mia.aperture.map.WaypointConfig.save(waypointConfigPath(), waypoints);
                    }));
                }
            }
```

- [ ] **Step 4: Name the keybind in the lang file**

In `src/main/resources/assets/mia_aperture_mod/lang/en_us.json`, add the entry (keep valid
JSON — add a comma after the previous last entry):

```json
  "key.mia_aperture_mod.mark_waypoint": "Mark Waypoint Here"
```

- [ ] **Step 5: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MiaApertureModClient.java src/main/resources/assets/mia_aperture_mod/lang/en_us.json
git commit -m "feat(waypoints): store load + B keybind to mark a waypoint here"
```

---

### Task 9: Version bump, build, install

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change `mod_version=1.10.0` to `mod_version=1.11.0`.

- [ ] **Step 2: Build + all tests**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass (66 existing + new WaypointColor/Store/Config +
MapGeometry = ~72).

- [ ] **Step 3: Install to the Modrinth instance**

```powershell
$dest="<mods-dir>"
Remove-Item "$dest\mia-maps-*.jar" -ErrorAction SilentlyContinue
Copy-Item "build\libs\mia-maps-1.11.0.jar" $dest
```
(If the jar is locked, close Minecraft first.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "chore: bump version to 1.11.0"
```

---

## In-game verification (after install)

1. Press **B** in-world → the edit dialog opens prefilled with your coords; name it, pick a colour, Save. It appears on both maps.
2. Fullscreen map → **Waypoints** button → the list shows it; **Add** creates one from typed coords; **Edit** changes it; **Delete** removes it. All persist across a restart.
3. Markers: coloured diamond + name on the fullscreen map (edge-clamped when panned away); coloured dot on the minimap when within range (rotates in heading-up mode).
4. Waypoints are per-server (a different server shows a different/empty list) and named "Mark Waypoint Here" in Controls.
