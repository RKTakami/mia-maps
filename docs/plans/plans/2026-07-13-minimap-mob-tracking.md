# Minimap Mob Tracking — Implementation Plan

> Steps use checkbox (`- [ ]`) syntax. **Project rule: work directly on `main`, no worktree.**

**Goal:** Show nearby entities on the minimap + fullscreen map — colored dots by category,
per-category filters, a ±32-block vertical band with ▲/▼ cues, and optional labels.

**Architecture:** New pure-ish client helper `MobTracker.collect(...)` enumerates the client's
loaded entities, classifies + filters them, returns blips sorted nearest-first. `MinimapRenderer`
and `AbyssWorldMapScreen` render the blips. Four new `MapSettings` booleans + settings toggles.

**Tech Stack:** Java 21, Fabric 1.21.11. Verified APIs: `ClientLevel.entitiesForRendering()`,
`net.minecraft.world.entity.monster.Enemy`, `Entity.getName()`, `Entity.getX/getY/getZ()`.

**Spec:** `docs/plans/specs/2026-07-13-minimap-mob-tracking-design.md`

---

### Task 1: Settings fields + toggles

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`

- [ ] **Step 1: Add the fields to `MapSettings`** (after `safeDropBlocks` / its helpers):

```java
    public boolean trackHostiles = true;
    public boolean trackPlayers = true;
    public boolean trackPassive = false;
    public boolean mobLabels = false;
```

(No `MapConfig` guard needed — Gson runs the field initializers via the no-arg constructor, so
a config missing these keys keeps these defaults.)

- [ ] **Step 2: Add a 2×2 toggle grid + move Done in `MapSettingsScreen.init()`**

Replace the existing "Done" button block:

```java
        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 144, 200, 20).build());
```

with:

```java
        this.addRenderableWidget(Button.builder(mobLabel("Hostiles", settings().trackHostiles), b -> {
            settings().trackHostiles = !settings().trackHostiles;
            b.setMessage(mobLabel("Hostiles", settings().trackHostiles));
            persist();
        }).bounds(cx - 100, cy2 + 144, 98, 20).build());
        this.addRenderableWidget(Button.builder(mobLabel("Players", settings().trackPlayers), b -> {
            settings().trackPlayers = !settings().trackPlayers;
            b.setMessage(mobLabel("Players", settings().trackPlayers));
            persist();
        }).bounds(cx + 2, cy2 + 144, 98, 20).build());
        this.addRenderableWidget(Button.builder(mobLabel("Passive", settings().trackPassive), b -> {
            settings().trackPassive = !settings().trackPassive;
            b.setMessage(mobLabel("Passive", settings().trackPassive));
            persist();
        }).bounds(cx - 100, cy2 + 168, 98, 20).build());
        this.addRenderableWidget(Button.builder(mobLabel("Labels", settings().mobLabels), b -> {
            settings().mobLabels = !settings().mobLabels;
            b.setMessage(mobLabel("Labels", settings().mobLabels));
            persist();
        }).bounds(cx + 2, cy2 + 168, 98, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"),
                b -> this.minecraft.setScreen(parent))
                .bounds(cx - 100, cy2 + 192, 200, 20).build());
```

- [ ] **Step 3: Add the label helper** (next to the other `*Label()` helpers):

```java
    private static Component mobLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "On" : "Off"));
    }
```

- [ ] **Step 4: Build**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/client/MapSettingsScreen.java
git commit -m "feat(mobs): mob-tracking settings (hostiles/players/passive/labels)"
```

---

### Task 2: `MobTracker` helper

**Files:**
- Create: `src/main/java/com/mia/aperture/client/MobTracker.java`

- [ ] **Step 1: Write the helper**

```java
package com.mia.aperture.client;

import com.mia.aperture.map.MapSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

// Collects nearby living entities the client knows about, classified + filtered by the settings,
// sorted nearest-first (horizontal). Client-only: the server only sends entities within tracking
// range, which suits a local minimap radius.
public final class MobTracker {
    public enum Cat {
        HOSTILE(0xFFFF3344), PLAYER(0xFFFFFFFF), PASSIVE(0xFF33CC44);
        public final int color;
        Cat(int c) { this.color = c; }
    }
    public record Blip(double x, double y, double z, Cat cat, String name, double horizSq) {}

    private MobTracker() {}

    // horizRadius = blocks; band = +/- vertical blocks (<=0 disables the band).
    public static List<Blip> collect(Minecraft mc, double horizRadius, double band, MapSettings s) {
        List<Blip> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        double r2 = horizRadius * horizRadius;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity) || e == mc.player) continue;
            double ex = e.getX(), ey = e.getY(), ez = e.getZ();
            double dx = ex - px, dz = ez - pz;
            double h2 = dx * dx + dz * dz;
            if (h2 > r2) continue;
            if (band > 0 && Math.abs(ey - py) > band) continue;
            Cat cat = e instanceof Player ? Cat.PLAYER
                    : e instanceof Enemy ? Cat.HOSTILE : Cat.PASSIVE;
            if (cat == Cat.HOSTILE && !s.trackHostiles) continue;
            if (cat == Cat.PLAYER && !s.trackPlayers) continue;
            if (cat == Cat.PASSIVE && !s.trackPassive) continue;
            out.add(new Blip(ex, ey, ez, cat, e.getName().getString(), h2));
        }
        out.sort((a, b) -> Double.compare(a.horizSq(), b.horizSq()));
        return out;
    }
}
```

- [ ] **Step 2: Build**

Run: `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Expected: `BUILD SUCCESSFUL`. (If `e.getX()` no-arg doesn't resolve, use `e.position().x/.y/.z`.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MobTracker.java
git commit -m "feat(mobs): MobTracker — collect/classify/sort nearby entities"
```

---

### Task 3: Minimap rendering

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MinimapRenderer.java`

- [ ] **Step 1: Add a `MOB_BAND` constant + small ▲/▼ helpers** (near `DIG_COLOR` / `drawDownTriangle`):

```java
    public static final double MOB_BAND = 32.0;

    private static void mobUp(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y - 2, x + 1, y - 1, color);
        g.fill(x - 1, y - 1, x + 2, y, color);
    }
    private static void mobDown(GuiGraphics g, int x, int y, int color) {
        g.fill(x - 1, y + 1, x + 2, y + 2, color);
        g.fill(x, y + 2, x + 1, y + 3, color);
    }
```

- [ ] **Step 2: Draw mob blips** — in `draw(...)`, immediately after the waypoint loop and
before `MinimapFrame.drawCardinals(...)`:

```java
        java.util.List<com.mia.aperture.client.MobTracker.Blip> blips =
                com.mia.aperture.client.MobTracker.collect(
                        net.minecraft.client.Minecraft.getInstance(), halfBlocks, MOB_BAND, s);
        var mcFont = net.minecraft.client.Minecraft.getInstance().font;
        int labeled = 0;
        for (com.mia.aperture.client.MobTracker.Blip bl : blips) {
            double dx = bl.x() - player.getX();
            double dz = bl.z() - player.getZ();
            if (Math.abs(dx) > halfBlocks || Math.abs(dz) > halfBlocks) continue;
            float bx = (float) (dx / halfBlocks) * radius;
            float bz = (float) (dz / halfBlocks) * radius;
            float rx = (float) (bx * Math.cos(wpRot) - bz * Math.sin(wpRot));
            float rz = (float) (bx * Math.sin(wpRot) + bz * Math.cos(wpRot));
            int mx = cx + Math.round(rx), my = cy + Math.round(rz);
            double dy = bl.y() - player.getY();
            int fade = (int) Math.min(150, Math.abs(dy) / MOB_BAND * 150);
            int color = (bl.cat().color & 0xFFFFFF) | ((0xFF - fade) << 24);
            ctx.fill(mx - 1, my - 1, mx + 2, my + 2, color);
            if (dy > 2) mobUp(ctx, mx, my, color);
            else if (dy < -2) mobDown(ctx, mx, my, color);
            if (s.mobLabels && labeled < 3) {
                ctx.drawString(mcFont, bl.name(), mx + 5, my - 4, 0xFFFFFFFF);
                labeled++;
            }
        }
```

- [ ] **Step 3: Build**

Run: `.\gradlew build` (with `JAVA_HOME` set). Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MinimapRenderer.java
git commit -m "feat(mobs): draw mob blips on the minimap (band, up/down cues, nearest-3 labels)"
```

---

### Task 4: Fullscreen-map rendering

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

- [ ] **Step 1: Draw mob blips** — in the `if (player != null)` block, after the waypoint loop
(and the dig marker), add this. Uses half the horizontal span as the radius, no vertical band
(the fullscreen map spans layers). `MobTracker` is in the `client` package (same as this screen):

```java
            double mobRadius = Math.max(this.lastBlocksAcrossX, this.lastBlocksAcrossZ) / 2.0 + 8;
            for (com.mia.aperture.client.MobTracker.Blip bl :
                    com.mia.aperture.client.MobTracker.collect(this.minecraft, mobRadius, 0,
                            MiaApertureModClient.mapSettings)) {
                int bxp = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        bl.x() - centerX, this.lastBlocksAcrossX, this.width);
                int byp = com.mia.aperture.map.MapGeometry.screenOffsetPixel(
                        bl.z() - centerZ, this.lastBlocksAcrossZ, this.height);
                if (bxp < 0 || bxp >= this.width || byp < 0 || byp >= this.height) continue;
                int color = bl.cat().color;
                guiGraphics.fill(bxp - 1, byp - 1, bxp + 2, byp + 2, color);
                if (MiaApertureModClient.mapSettings.mobLabels) {
                    guiGraphics.drawString(this.font, bl.name(), bxp + 5, byp - 4, 0xFFFFFFFF);
                }
            }
```

- [ ] **Step 2: Build**

Run: `.\gradlew build` (with `JAVA_HOME` set). Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(mobs): draw mob blips + labels on the fullscreen map"
```

---

### Task 5: Build, install, verify

**Files:** none.

- [ ] **Step 1: Full build + test**

Run: `.\gradlew build` (with `JAVA_HOME` set). Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Install**

Copy `build/libs/mia-maps-0.1.2-beta.jar` to
`<home>\AppData\Roaming\ModrinthApp\profiles\Mine In Abyss Modpack\mods\`.

- [ ] **Step 3: Owner verification**

Confirm nearby mobs show as colored dots on the minimap + fullscreen map; toggles filter each
category; the ±32 band + ▲/▼ behave; labels toggle shows nearest-3 (minimap) / all (fullscreen).
**Report what the real Abyss mobs classify as** (hostile/passive) so the classification can be
tuned (e.g., treat unknown living mobs as hostile, or match by entity-type id).
