# Help / Tutorial Screen Implementation Plan


**Goal:** A tabbed Help/tutorial screen (Overview + Map/3D/Waypoints/Settings/Keys) opened from a new "Help" button on the fullscreen map, documenting every control, button, and keybind.

**Architecture:** A pure `HelpContent` model (content in one place, keyed by tab, resolving live keybind names through a `KeyResolver`) drives a `HelpScreen extends Screen` (tab bar + scrollable text). No new game state, no persistence.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Build: `$env:JAVA_HOME="D:\Users\dev\VSCode-Projects\MIA map mod project\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`.

**Spec:** `docs/plans/specs/2026-07-15-help-tutorial-screen-design.md`

---

## File Structure

- Create: `src/main/java/com/mia/aperture/map/HelpContent.java` — pure content model (`Tab`, `KeyResolver`, `Line`, `lines(Tab, KeyResolver)`).
- Create: `src/main/java/com/mia/aperture/client/HelpScreen.java` — tabbed UI + scroll + live key resolver.
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java` — widen `mapKeyBind` + `toggleCullKeyBind` to `public`.
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java` — add the "Help" button.
- Test: `src/test/java/com/mia/aperture/map/HelpContentTest.java`.

---

## Task 1: `HelpContent` pure model + tests

**Files:**
- Create: `src/main/java/com/mia/aperture/map/HelpContent.java`
- Test: `src/test/java/com/mia/aperture/map/HelpContentTest.java`

- [ ] **Step 1: Write the failing test**

`HelpContentTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HelpContentTest {
    // stub resolver: echoes the action so tests can spot resolved keys
    private final HelpContent.KeyResolver keys = action -> "<" + action + ">";

    @Test
    void everyTabHasContent() {
        for (HelpContent.Tab t : HelpContent.Tab.values()) {
            List<HelpContent.Line> lines = HelpContent.lines(t, keys);
            assertFalse(lines.isEmpty(), "tab " + t + " should have content");
        }
    }

    @Test
    void noLineHasEmptyText() {
        for (HelpContent.Tab t : HelpContent.Tab.values())
            for (HelpContent.Line ln : HelpContent.lines(t, keys))
                assertFalse(ln.text() == null || ln.text().isBlank(), "empty line in " + t);
    }

    @Test
    void keysTabUsesResolvedOpenMapKey() {
        boolean found = HelpContent.lines(HelpContent.Tab.KEYS, keys).stream()
                .anyMatch(ln -> "<open_map>".equals(ln.key()));
        assertTrue(found, "KEYS tab should list the resolved open-map key");
    }

    @Test
    void everyTabHasAHeading() {
        for (HelpContent.Tab t : HelpContent.Tab.values())
            assertTrue(HelpContent.lines(t, keys).stream().anyMatch(HelpContent.Line::heading),
                    "tab " + t + " should start with a heading");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `.\gradlew test --tests com.mia.aperture.map.HelpContentTest`
Expected: FAIL to compile (`HelpContent` does not exist).

- [ ] **Step 3: Implement `HelpContent`**

`HelpContent.java`:

```java
package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.List;

// Pure help/tutorial content, keyed by tab. Keybind names come through a KeyResolver so the live
// (possibly rebound) keys show; fixed in-screen keys (V, X, Esc, mouse) are literal.
public final class HelpContent {
    public enum Tab {
        OVERVIEW("Overview"), MAP("Map"), THREED("3D View"),
        WAYPOINTS("Waypoints & Routing"), SETTINGS("Settings"), KEYS("Keys");
        public final String title;
        Tab(String t) { this.title = t; }
    }

    // Resolves a logical action (e.g. "open_map") to a display key (e.g. "M").
    public interface KeyResolver { String key(String action); }

    // heading -> section header (key null). key != null -> a control + its description.
    // key == null && !heading -> a plain body line.
    public record Line(boolean heading, String key, String text) {}

    private HelpContent() {}

    private static Line h(String t) { return new Line(true, null, t); }
    private static Line item(String k, String t) { return new Line(false, k, t); }
    private static Line text(String t) { return new Line(false, null, t); }

    public static List<Line> lines(Tab tab, KeyResolver k) {
        List<Line> o = new ArrayList<>();
        switch (tab) {
            case OVERVIEW -> {
                o.add(h("MIA Maps"));
                o.add(text("A live Abyss map with a 3D view, waypoints, routing, and cave x-ray."));
                o.add(h("Start here"));
                o.add(item(k.key("open_map"), "Open the fullscreen map"));
                o.add(item(k.key("mark_waypoint"), "Drop a waypoint where you stand"));
                o.add(item("V", "On the map: cycle Relief / Vanilla / X-ray"));
                o.add(h("What's in each tab"));
                o.add(text("Map - pan, zoom, depth slice, render modes, buttons"));
                o.add(text("3D View - orbit the terrain and x-ray into caves"));
                o.add(text("Waypoints & Routing - mark places and follow routes"));
                o.add(text("Settings - every option explained"));
                o.add(text("Keys - the full keybind list"));
            }
            case MAP -> {
                o.add(h("Fullscreen map"));
                o.add(item("Drag", "Pan the map"));
                o.add(item("Scroll", "Zoom in and out"));
                o.add(item("Ctrl/Alt+Scroll", "Move the depth slice up or down"));
                o.add(item("V", "Render mode: Relief (shaded), Vanilla (flat), X-ray (caves)"));
                o.add(item(k.key("reset_view"), "Reset the depth slice back to your level"));
                o.add(h("Buttons"));
                o.add(item("3D View", "Open the orbiting 3D voxel view"));
                o.add(item("Waypoints", "Manage your saved waypoints"));
                o.add(item("Reset", "Recenter the view on you"));
                o.add(item("Settings", "Open map settings"));
                o.add(h("Reading it"));
                o.add(text("Top-left shows your depth and current Abyss layer; switch blocks vs metres in Settings."));
                o.add(text("X-ray dims the terrain and lights up caves beneath you in cyan."));
            }
            case THREED -> {
                o.add(h("3D view"));
                o.add(item("Drag", "Orbit the camera"));
                o.add(item("Scroll", "Zoom in and out"));
                o.add(item("Right-click", "Move the focus point"));
                o.add(item("Shift+Right-click", "Drop a waypoint at a spot"));
                o.add(item("Click a waypoint", "Route to that waypoint"));
                o.add(item("R", "Recenter on you"));
                o.add(item("X", "X-ray: Off / Ghost shell / Caves only"));
                o.add(item("Esc", "Close the 3D view"));
                o.add(text("Detail is controlled by Orbit Quality in Settings."));
            }
            case WAYPOINTS -> {
                o.add(h("Waypoints"));
                o.add(item(k.key("mark_waypoint"), "Mark a waypoint at your position"));
                o.add(item(k.key("toggle_beacons"), "Show or hide in-world waypoint beacons"));
                o.add(text("In the Waypoints list: Add, Edit, Delete, Share to chat, or Go (route to it)."));
                o.add(text("Toggle any single waypoint on or off, or all markers at once."));
                o.add(h("Routing"));
                o.add(text("Pick a destination (Go, or click a waypoint in 3D) to draw a walking route."));
                o.add(text("Breadcrumbs erase as you pass them; the brightest marker is your next step."));
                o.add(text("Amber markers suggest where to dig down a cliff you cannot walk around."));
            }
            case SETTINGS -> {
                o.add(h("Map settings"));
                o.add(item("Orientation", "North-locked, or rotate with your facing"));
                o.add(item("Shape", "Square or round minimap"));
                o.add(item("Size / Corner / Reposition", "Minimap size and where it sits"));
                o.add(item("Map mode", "Relief / Vanilla / X-ray (same as V)"));
                o.add(item("Cave mode", "Auto / On / Off in-cave minimap view"));
                o.add(item("Beacons", "In-world waypoint beams"));
                o.add(item("Orbit quality", "3D view detail vs performance"));
                o.add(item("Safe fall", "Drop height the router treats as safe"));
                o.add(item("Depth units", "Show depth in blocks or metres"));
                o.add(h("Mob tracking"));
                o.add(item("Hostiles / Players / Passive", "Which blips to show"));
                o.add(item("Labels", "Name labels on mob blips"));
                o.add(item("Nearby List", "A text list of nearby mobs on the HUD"));
                o.add(item("Nav markers", "Show waypoint markers on the maps"));
            }
            case KEYS -> {
                o.add(h("Keybinds"));
                o.add(item(k.key("open_map"), "Open the fullscreen map"));
                o.add(item(k.key("mark_waypoint"), "Mark a waypoint"));
                o.add(item(k.key("toggle_beacons"), "Toggle waypoint beacons"));
                o.add(item(k.key("cave_mode"), "Cycle cave mode (Auto / On / Off)"));
                o.add(item(k.key("toggle_cull"), "Toggle Aperture cull"));
                o.add(item(k.key("reset_view"), "Reset map depth to you"));
                o.add(h("On the map"));
                o.add(item("V", "Render mode: Relief / Vanilla / X-ray"));
                o.add(item("Drag / Scroll", "Pan / Zoom"));
                o.add(item("Ctrl/Alt+Scroll", "Depth slice"));
                o.add(h("In the 3D view"));
                o.add(item("X", "X-ray: Off / Ghost / Caves only"));
                o.add(item("R", "Recenter"));
                o.add(item("Right-click / Shift+Right-click", "Move focus / Drop waypoint"));
                o.add(item("Esc", "Close"));
            }
        }
        return o;
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `.\gradlew test --tests com.mia.aperture.map.HelpContentTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/HelpContent.java src/test/java/com/mia/aperture/map/HelpContentTest.java
git commit -m "feat(help): pure HelpContent model (tabs + live-key resolver) + tests"
```

---

## Task 2: `HelpScreen` UI (tabs + scroll + live keys)

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`
- Create: `src/main/java/com/mia/aperture/client/HelpScreen.java`

- [ ] **Step 1: Widen the two private keybinds so the resolver can read them**

In `MiaApertureModClient.java`, change:

```java
    private static KeyMapping mapKeyBind;
    private static KeyMapping toggleCullKeyBind;
```

to:

```java
    public static KeyMapping mapKeyBind;
    public static KeyMapping toggleCullKeyBind;
```

(`resetKeyBind`, `caveKeyBind`, `markWaypointKeyBind`, `toggleBeaconsKeyBind` are already `public static`.)

- [ ] **Step 2: Create `HelpScreen`**

`HelpScreen.java`:

```java
package com.mia.aperture.client;

import com.mia.aperture.map.HelpContent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public class HelpScreen extends Screen {
    private final Screen parent;
    // Rendered manually (like MapSettingsScreen) so we never call super.render()/renderBackground(),
    // which would trip the modpack blur mod's "Can only blur once per frame".
    private final List<Button> widgets = new ArrayList<>();
    private HelpContent.Tab active = HelpContent.Tab.OVERVIEW;
    private double scrollOffset;
    private int maxScroll;
    private int contentTop, contentBottom;

    private static final int LINE_H = 11;
    private static final int HEADING_GAP = 8;
    private static final int LEFT_PAD = 16;
    private static final int KEY_COL_W = 132;
    private static final int KEY_COLOR = 0xFF66CCFF;
    private static final int HEAD_COLOR = 0xFFFFFFFF;
    private static final int BODY_COLOR = 0xFFC8C8C8;

    // Live keys for the rebindable global binds; unknown actions fall back to their own name.
    private static final HelpContent.KeyResolver KEYS = action -> {
        net.minecraft.client.KeyMapping km = switch (action) {
            case "open_map" -> MiaApertureModClient.mapKeyBind;
            case "mark_waypoint" -> MiaApertureModClient.markWaypointKeyBind;
            case "toggle_beacons" -> MiaApertureModClient.toggleBeaconsKeyBind;
            case "cave_mode" -> MiaApertureModClient.caveKeyBind;
            case "toggle_cull" -> MiaApertureModClient.toggleCullKeyBind;
            case "reset_view" -> MiaApertureModClient.resetKeyBind;
            default -> null;
        };
        return km == null ? action : km.getTranslatedKeyMessage().getString();
    };

    public HelpScreen(Screen parent) {
        super(Component.literal("MIA Maps - Help"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        widgets.clear();
        contentTop = 58;
        contentBottom = this.height - 40;

        HelpContent.Tab[] tabs = HelpContent.Tab.values();
        int gap = 6;
        int[] w = new int[tabs.length];
        int totalW = 0;
        for (int i = 0; i < tabs.length; i++) {
            w[i] = this.font.width(tabs[i].title) + 12;
            totalW += w[i] + (i > 0 ? gap : 0);
        }
        int x = Math.max(6, (this.width - totalW) / 2);
        for (int i = 0; i < tabs.length; i++) {
            final HelpContent.Tab tab = tabs[i];
            Button b = Button.builder(Component.literal(tab.title), btn -> selectTab(tab))
                    .bounds(x, 32, w[i], 18).build();
            b.active = (tab != active); // active tab shown disabled as the selection cue
            widgets.add(this.addRenderableWidget(b)); // addRenderableWidget -> clickable; rendered manually below
            x += w[i] + gap;
        }

        widgets.add(this.addRenderableWidget(Button.builder(Component.literal("Done"),
                btn -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20).build()));
    }

    private void selectTab(HelpContent.Tab t) {
        this.active = t;
        this.scrollOffset = 0;
        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Plain dark fill instead of renderBackground()'s blur (the modpack already blurs once).
        g.fill(0, 0, this.width, this.height, 0xE0101018);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        for (Button b : widgets) b.render(g, mouseX, mouseY, partial); // manual: no super.render()

        List<HelpContent.Line> lines = HelpContent.lines(active, KEYS);
        int total = layout(g, lines, false);
        maxScroll = Math.max(0, total - (contentBottom - contentTop));
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        g.enableScissor(0, contentTop, this.width, contentBottom);
        layout(g, lines, true);
        g.disableScissor();
    }

    // Measures (draw=false) or draws (draw=true) the active tab's content; returns its total height.
    private int layout(GuiGraphics g, List<HelpContent.Line> lines, boolean draw) {
        int contentRight = this.width - 12;
        int y = contentTop - (int) scrollOffset;
        int startY = y;
        for (HelpContent.Line ln : lines) {
            if (ln.heading()) {
                y += HEADING_GAP;
                if (draw) g.drawString(this.font, ln.text(), LEFT_PAD - 6, y, HEAD_COLOR);
                y += LINE_H + 2;
            } else if (ln.key() != null) {
                if (draw) g.drawString(this.font, ln.key(), LEFT_PAD, y, KEY_COLOR);
                int tx = LEFT_PAD + KEY_COL_W;
                List<FormattedCharSequence> wrapped = this.font.split(Component.literal(ln.text()), contentRight - tx);
                for (FormattedCharSequence seq : wrapped) {
                    if (draw) g.drawString(this.font, seq, tx, y, BODY_COLOR);
                    y += LINE_H;
                }
                if (wrapped.isEmpty()) y += LINE_H;
            } else {
                List<FormattedCharSequence> wrapped = this.font.split(Component.literal(ln.text()), contentRight - LEFT_PAD);
                for (FormattedCharSequence seq : wrapped) {
                    if (draw) g.drawString(this.font, seq, LEFT_PAD, y, BODY_COLOR);
                    y += LINE_H;
                }
                if (wrapped.isEmpty()) y += LINE_H;
            }
        }
        return y - startY;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - dy * 16));
        return true;
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
```

- [ ] **Step 3: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL. (If `getTranslatedKeyMessage` or `mouseScrolled(double,double,double,double)` mismatch, fix from the compiler message — both were API-verified against the shipped jar.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/HelpScreen.java src/main/java/com/mia/aperture/client/MiaApertureModClient.java
git commit -m "feat(help): tabbed scrollable HelpScreen with live keybind names"
```

---

## Task 3: "Help" button on the fullscreen map

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java`

- [ ] **Step 1: Add the button**

In `AbyssWorldMapScreen.init`, after the existing "3D View" button block (the buttons sit at `this.width - 90 / -180 / -270 / -360`), add a fifth at `-450`:

```java
        this.mapButtons.add(this.addRenderableWidget(
            net.minecraft.client.gui.components.Button.builder(
                Component.literal("Help"),
                b -> this.minecraft.setScreen(new HelpScreen(this)))
            .bounds(this.width - 450, this.height - 30, 80, 20)
            .build()));
```

(The existing `mapButtons` re-render loop already redraws these on top of the map blit, so no other change is needed.)

- [ ] **Step 2: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/AbyssWorldMapScreen.java
git commit -m "feat(help): add Help button to the fullscreen map"
```

---

## Task 4: Full build, install, verify

**Files:** none (build + manual verify).

- [ ] **Step 1: Full test + build**

Run: `$env:JAVA_HOME="D:\Users\dev\VSCode-Projects\MIA map mod project\libs\jdk21\jdk-21.0.11+10"; .\gradlew clean test build`
Expected: BUILD SUCCESSFUL, all tests pass (incl. `HelpContentTest`).

- [ ] **Step 2: Install to the instance**

```bash
cp -v "build/libs/mia-maps-0.1.5-beta.jar" "/c/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
```

- [ ] **Step 3: Owner verifies in-game**

- Open the fullscreen map (`M`) - a **Help** button sits in the bottom button row.
- Click it: the Help screen opens on the **Overview** tab.
- Tab buttons across the top switch sections (Map / 3D View / Waypoints & Routing / Settings / Keys); the active tab reads as selected (disabled).
- Long tabs **scroll** with the mouse wheel; text wraps and stays clipped to the content area.
- The **Keys** and **Overview** tabs show the *real* open-map / mark-waypoint keys (rebind one in Controls to confirm it updates).
- **Done** (or Esc) returns to the map.

- [ ] **Step 4: Report.** If good, cut **v0.1.6-beta** (bump `gradle.properties`, clean build, install, `gh release` prerelease, push) - done by the controller after verification. This release also carries the corrected deep-layer boundaries already on main. If issues, capture the symptom and return to the relevant task.

---

## Notes

- `HelpContent` is the single source of truth for the text - update it as features change; the KEYS/OVERVIEW/WAYPOINTS tabs already pull live keys via the resolver.
- Button label ("Help"), tab titles/order, and colours are all trivial single-point changes if the owner wants tweaks after seeing it.
