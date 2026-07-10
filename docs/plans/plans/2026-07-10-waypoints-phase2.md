# Waypoints Phase 2 (Chat Sharing) Implementation Plan


**Goal:** Share waypoints through game chat — a per-waypoint "Share" posts a parseable `[MIA:WP]` line, and incoming shares are detected and offered with clickable `[✓ Accept]` / `[✗ Reject]` buttons.

**Architecture:** A pure `WaypointCodec` (encode/decode the chat line) is the testable core. `WaypointChat` registers `/miawp accept|reject <id>` client commands and an `ALLOW_CHAT` listener that decodes shares, stows them in a pending map, and re-renders the chat line with clickable Accept/Reject running those commands. Sending is `player.connection.sendChat`.

**Tech Stack:** Fabric 1.21.11 (fabric-api client command + message events), Java 21, JUnit 5.

**Build/test (PowerShell):** `$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build`
Develop directly on `main` (project convention; no worktrees).

---

### Task 1: WaypointCodec (pure encode/decode)

**Files:**
- Create: `src/main/java/com/mia/aperture/map/WaypointCodec.java`
- Test: `src/test/java/com/mia/aperture/map/WaypointCodecTest.java`

- [ ] **Step 1: Write the failing test**

Create `WaypointCodecTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class WaypointCodecTest {

    @Test
    void roundTrip() {
        Waypoint w = new Waypoint("Second Camp", -60, -38, -438, WaypointColor.AQUA);
        Optional<Waypoint> back = WaypointCodec.decode(WaypointCodec.encode(w));
        assertTrue(back.isPresent());
        assertEquals("Second Camp", back.get().name);
        assertEquals(-60, back.get().x);
        assertEquals(-438, back.get().z);
        assertEquals(WaypointColor.AQUA, back.get().color);
    }

    @Test
    void decodesWhenPrefixedBySenderInChat() {
        Optional<Waypoint> w = WaypointCodec.decode("Steve: [MIA:WP] \"Home\" 1 2 3 green");
        assertTrue(w.isPresent());
        assertEquals("Home", w.get().name);
        assertEquals(WaypointColor.GREEN, w.get().color);
    }

    @Test
    void rejectsNonWaypointAndUnknownColour() {
        assertTrue(WaypointCodec.decode("just chatting").isEmpty());
        assertTrue(WaypointCodec.decode("[MIA:WP] \"X\" 1 2 3 chartreuse").isEmpty());
        assertTrue(WaypointCodec.decode(null).isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew test`
Expected: FAIL — `WaypointCodec` does not exist.

- [ ] **Step 3: Create WaypointCodec**

```java
package com.mia.aperture.map;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WaypointCodec {
    public static final String PREFIX = "[MIA:WP]";
    private static final Pattern P = Pattern.compile(
            "\\[MIA:WP\\]\\s+\"(.+?)\"\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([A-Za-z]+)");

    private WaypointCodec() {}

    public static String encode(Waypoint w) {
        return PREFIX + " \"" + w.name.replace("\"", "'") + "\" "
                + w.x + " " + w.y + " " + w.z + " " + w.color.name().toLowerCase();
    }

    public static Optional<Waypoint> decode(String text) {
        if (text == null) return Optional.empty();
        Matcher m = P.matcher(text);
        if (!m.find()) return Optional.empty();
        try {
            WaypointColor color = WaypointColor.valueOf(m.group(5).toUpperCase());
            return Optional.of(new Waypoint(m.group(1),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), color));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/WaypointCodec.java src/test/java/com/mia/aperture/map/WaypointCodecTest.java
git commit -m "feat(waypoints): WaypointCodec chat encode/decode"
```

---

### Task 2: WaypointChat (share + commands + accept/reject listener)

**Files:**
- Create: `src/main/java/com/mia/aperture/client/WaypointChat.java`
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

No unit test (chat/command runtime); verified in-game.

- [ ] **Step 1: Create WaypointChat**

```java
package com.mia.aperture.client;

import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointCodec;
import com.mia.aperture.map.WaypointConfig;
import com.mia.aperture.map.WaypointStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class WaypointChat {
    private static final Map<Integer, Waypoint> PENDING = new HashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private WaypointChat() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("miawp")
                .then(literal("accept").then(argument("id", IntegerArgumentType.integer())
                    .executes(ctx -> { accept(IntegerArgumentType.getInteger(ctx, "id")); return 1; })))
                .then(literal("reject").then(argument("id", IntegerArgumentType.integer())
                    .executes(ctx -> { reject(IntegerArgumentType.getInteger(ctx, "id")); return 1; })))));

        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            Optional<Waypoint> wp = WaypointCodec.decode(message.getString());
            if (wp.isEmpty()) return true; // ordinary chat -> show as-is
            Minecraft mc = Minecraft.getInstance();
            boolean self = sender != null && mc.player != null
                    && sender.getId().equals(mc.player.getGameProfile().getId());
            int id = NEXT_ID.incrementAndGet();
            PENDING.put(id, wp.get());
            String who = self ? "You" : (sender != null ? sender.getName() : "Someone");
            mc.gui.getChat().addMessage(prompt(who, wp.get(), id, self));
            return false; // replace the raw line with our augmented one
        });
    }

    public static void share(Waypoint w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendChat(WaypointCodec.encode(w));
    }

    private static Component prompt(String who, Waypoint w, int id, boolean self) {
        MutableComponent line = Component.literal("[MIA Maps] " + who + " shared \"" + w.name + "\" ("
                + w.x + " " + w.y + " " + w.z + ")  ").withStyle(Style.EMPTY.withColor(0xAAAAAA));
        MutableComponent accept = Component.literal("[✓ Add]").withStyle(Style.EMPTY
                .withColor(0x55FF55)
                .withClickEvent(new ClickEvent.RunCommand("/miawp accept " + id)));
        MutableComponent reject = Component.literal("  [✗ Reject]").withStyle(Style.EMPTY
                .withColor(0xFF5555)
                .withClickEvent(new ClickEvent.RunCommand("/miawp reject " + id)));
        return line.append(accept).append(reject);
    }

    private static void accept(int id) {
        Minecraft mc = Minecraft.getInstance();
        Waypoint w = PENDING.remove(id);
        if (w == null) { info("That waypoint was already handled."); return; }
        String key = WaypointStore.currentServerKey(mc);
        MiaApertureModClient.waypoints.add(key, w);
        WaypointConfig.save(MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
        info("Added waypoint \"" + w.name + "\".");
    }

    private static void reject(int id) {
        PENDING.remove(id);
        info("Dismissed shared waypoint.");
    }

    private static void info(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[MIA Maps] " + msg), false);
    }
}
```

*API note:* if `new ClickEvent.RunCommand(...)` does not resolve in this mappings set, use
the pre-1.21.5 form `new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/miawp accept " + id)`.
If `sender.getName()` is unavailable, fall back to `params.name().getString()`.

- [ ] **Step 2: Register it on client init**

In `MiaApertureModClient.onInitializeClient`, after the waypoint keybind registration (and
before/after the HUD callback is fine), add:

```java
        WaypointChat.register();
```

- [ ] **Step 3: Build to verify it compiles**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL (resolve the ClickEvent/sender API note if the compiler flags it).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mia/aperture/client/WaypointChat.java src/main/java/com/mia/aperture/client/MiaApertureModClient.java
git commit -m "feat(waypoints): chat share + accept/reject import"
```

---

### Task 3: Share button in the manager

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/WaypointListScreen.java`

- [ ] **Step 1: Add a Share button per row and retighten the row layout**

In `WaypointListScreen.init`, replace the per-row Edit/Delete button block:

```java
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(index))
                    .bounds(cx + 40, rowY, 60, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                list().remove(index);
                persist();
                this.rebuildWidgets();
            }).bounds(cx + 104, rowY, 60, 20).build());
```

with (Share / Edit / Delete, narrower):

```java
            this.addRenderableWidget(Button.builder(Component.literal("Share"),
                    b -> WaypointChat.share(list().get(index)))
                    .bounds(cx + 26, rowY, 46, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(index))
                    .bounds(cx + 74, rowY, 46, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
                list().remove(index);
                persist();
                this.rebuildWidgets();
            }).bounds(cx + 122, rowY, 46, 20).build());
```

- [ ] **Step 2: Build**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/client/WaypointListScreen.java
git commit -m "feat(waypoints): Share button in the manager list"
```

---

### Task 4: Version bump, build, install

**Files:**
- Modify: `gradle.properties`

- [ ] **Step 1: Bump the version**

In `gradle.properties`, change `mod_version=1.11.0` to `mod_version=1.12.0`.

- [ ] **Step 2: Build + all tests**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass (75 + 3 new WaypointCodec = 78).

- [ ] **Step 3: Install to the Modrinth instance**

```powershell
$dest="<mods-dir>"
Remove-Item "$dest\mia-maps-*.jar" -ErrorAction SilentlyContinue
Copy-Item "build\libs\mia-maps-1.12.0.jar" $dest
```
(If the jar is locked, close Minecraft first.)

- [ ] **Step 4: Commit**

```bash
git add gradle.properties
git commit -m "chore: bump version to 1.12.0"
```

---

## In-game verification (after install)

1. Manager → a waypoint's **Share** button posts a `[MIA:WP] "…" x y z colour` line to chat.
2. When that line appears in chat (from you or a teammate), it renders as
   `[MIA Maps] <who> shared "name" (x y z)  [✓ Add] [✗ Reject]`.
3. **Add** imports it to the current server's list (confirmation message; appears on both
   maps); **Reject** dismisses it; nothing is added without clicking Add.
4. A malformed or non-waypoint chat line is untouched; a second click on an already-handled
   prompt says so instead of duplicating.
