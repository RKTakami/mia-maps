# Whole-Abyss Cache Persistence Implementation Plan


**Goal:** Persist the whole-Abyss span cache to a local file and share it across the servers one client connects to, with survive-server data prioritized (gap-filled by build), so the whole-Abyss overview is instant-on and cross-session.

**Architecture:** Two coarse column layers — `primaryWorking` (survive) and `fallbackWorking` (build/others) — held by `AbyssModelBuilder`. Each LOD-4 probe writes into the layer matching the connected server; the published snapshot is a pure survive-over-build gap-fill composite. Both layers serialize to one gzip binary via a pure codec, loaded on init and saved on a timer + disconnect + shutdown.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Voxy is `compileOnly` and OFF the test classpath — pure classes must not import `me.cortex.voxy.*` or `net.minecraft.*`.

**Spec:** `docs/plans/specs/2026-07-18-abyss-cache-persistence-design.md`

**Branch policy:** Work directly on `main` in `D:\Users\dev\VSCode-Projects\MIA map mod project`. Do NOT create a branch or worktree (project convention — `AGENTS.md`).

---

## Background an engineer needs

- Read the spec first. This builds on the whole-Abyss 3D view (already code-complete): `AbyssSpanStore` (pure store — `Column(int[] spans, int[] colors)`, `packKey/keyX/keyZ`, `buildSnapshot`, `publish`, `forEachSurface`), `SpanMath` (pure span ops — `packSpan/spanTop/spanBottom`, `insertRun(col, top, bottom, color)`, `clearRange(col, clearTop, clearBottom)`, `solidAt`, `mipInto`), and `AbyssModelBuilder` (the Voxy-facing background probe).
- **Anti-clone invariant (security):** only native LOD-4 (≥16-block) data is ever serialized. The builder never puts finer data into these maps, so the file cannot reconstruct a block-level build. Every new class carries a comment stating this.
- **Coordinate model:** columns are keyed by `(cellX, cellZ)` in the Abyss shifted column; `AbyssSpanStore.packKey(cellX, cellZ)` (12-bit biased). Y within a column is a `cellY` (16-block cells at base).
- **Voxy read reaches disk:** `WorldEngine.acquireIfExists` → `ActiveSectionTracker` → `storage.loadSection` on a cache miss, so probing the connected server imports its whole on-disk map, not just what is in memory.
- **Pure/impure split:** the codec and composite are pure (unit-tested, on the test classpath); the builder and client wiring are Voxy/Minecraft-facing (build-verified + in-game).
- **Gson config quirk (relied on elsewhere in this file):** `MapSettings` has an implicit no-arg constructor, so field initializers run during deserialization; a JSON key that is absent keeps its initializer value. That is why `boolean persistAbyssModel = true` defaults to true for old configs without the key.
- **Build:**
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

## File Structure

| File | Responsibility | New? | Tested? |
|---|---|---|---|
| `map/AbyssModelCodec.java` | Pure encode/decode of the two-layer model ↔ gzip bytes; version + corruption handling | new | yes |
| `map/AbyssComposite.java` | Pure gap-fill merge(primary, fallback) → composited base map | new | yes |
| `map/MapSettings.java` | `primaryServer`, `persistAbyssModel`, `hostMatches` helper | modify | yes |
| `map/MapConfig.java` | Default the new `primaryServer` on load | modify | yes |
| `map/AbyssModelBuilder.java` | Two layers, tier bucketing, composite publish, load/seed, save | modify | no (Voxy-facing) |
| `client/MiaApertureModClient.java` | `abyssModelPath()`, load on init, JOIN tier+sweep, DISCONNECT + shutdown save | modify | no |

---

### Task 1: AbyssModelCodec — pure two-layer (de)serialization

**Files:**
- Create: `src/main/java/com/mia/aperture/map/AbyssModelCodec.java`
- Test: `src/test/java/com/mia/aperture/map/AbyssModelCodecTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/AbyssModelCodecTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AbyssModelCodecTest {

    @Test
    void roundTripsTwoLayersWithColorsAndNegatives() {
        Map<Integer, AbyssSpanStore.Column> p = new HashMap<>();
        p.put(AbyssSpanStore.packKey(3, -4), SpanMath.insertRun(null, 10, 5, 0xFF112233));
        Map<Integer, AbyssSpanStore.Column> f = new HashMap<>();
        f.put(AbyssSpanStore.packKey(-7, 8),
                SpanMath.insertRun(SpanMath.insertRun(null, 2, 0, 1), 20, 15, 2));

        byte[] bytes = AbyssModelCodec.encode(
                new AbyssModelCodec.LayeredModel(p, f, "survive.mineinabyss.com"));
        AbyssModelCodec.LayeredModel m = AbyssModelCodec.decode(bytes);

        assertEquals("survive.mineinabyss.com", m.primaryServer());
        assertEquals(1, m.primary().size());
        assertEquals(1, m.fallback().size());
        AbyssSpanStore.Column pc = m.primary().get(AbyssSpanStore.packKey(3, -4));
        assertEquals(10, SpanMath.spanTop(pc.spans()[0]));
        assertEquals(5, SpanMath.spanBottom(pc.spans()[0]));
        assertEquals(0xFF112233, pc.colors()[0]);
        assertEquals(2, m.fallback().get(AbyssSpanStore.packKey(-7, 8)).spans().length);
    }

    @Test
    void emptyModelRoundTrips() {
        byte[] b = AbyssModelCodec.encode(
                new AbyssModelCodec.LayeredModel(new HashMap<>(), new HashMap<>(), ""));
        AbyssModelCodec.LayeredModel m = AbyssModelCodec.decode(b);
        assertTrue(m.primary().isEmpty());
        assertTrue(m.fallback().isEmpty());
    }

    @Test
    void nullAndEmptyInputAreSafe() {
        assertTrue(AbyssModelCodec.decode(null).primary().isEmpty());
        assertTrue(AbyssModelCodec.decode(new byte[0]).fallback().isEmpty());
    }

    @Test
    void garbageDecodesToEmpty() {
        AbyssModelCodec.LayeredModel m = AbyssModelCodec.decode(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertTrue(m.primary().isEmpty());
        assertTrue(m.fallback().isEmpty());
    }

    @Test
    void wrongVersionDecodesToEmpty() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
            out.writeInt(0x4D41424D);   // MAGIC
            out.writeInt(999);          // unsupported version
        }
        AbyssModelCodec.LayeredModel m = AbyssModelCodec.decode(bos.toByteArray());
        assertTrue(m.primary().isEmpty());
        assertTrue(m.fallback().isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*AbyssModelCodecTest*'
```

Expected: FAIL — `cannot find symbol: class AbyssModelCodec`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/AbyssModelCodec.java`:

```java
package com.mia.aperture.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// Pure serialization for the persistent whole-Abyss model: two coarse column layers (primary +
// fallback) plus the primary-server label, gzip-compressed. No Voxy or Minecraft imports.
//
// SECURITY INVARIANT: only native LOD-4 (>= 16-block) column data is ever written here. The
// builder never puts finer data into these maps, so the file is structurally incapable of
// reconstructing a block-level build. Do not point this codec at fine-LOD data.
public final class AbyssModelCodec {
    private static final int MAGIC = 0x4D41424D;   // "MABM"
    private static final int VERSION = 1;
    private static final int MAX_COLUMNS = 2_000_000;
    private static final int MAX_SPANS = 8192;

    public record LayeredModel(Map<Integer, AbyssSpanStore.Column> primary,
                               Map<Integer, AbyssSpanStore.Column> fallback,
                               String primaryServer) {}

    private AbyssModelCodec() {}

    public static byte[] encode(LayeredModel m) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(m.primaryServer() == null ? "" : m.primaryServer());
            writeLayer(out, m.primary());
            writeLayer(out, m.fallback());
        } catch (Throwable t) {
            System.err.println("[MIA Maps] abyss model encode failed: " + t);
            return new byte[0];
        }
        return bos.toByteArray();
    }

    private static void writeLayer(DataOutputStream out, Map<Integer, AbyssSpanStore.Column> map)
            throws Exception {
        out.writeInt(map.size());
        for (Map.Entry<Integer, AbyssSpanStore.Column> e : map.entrySet()) {
            out.writeInt(AbyssSpanStore.keyX(e.getKey()));
            out.writeInt(AbyssSpanStore.keyZ(e.getKey()));
            int[] spans = e.getValue().spans();
            int[] colors = e.getValue().colors();
            out.writeInt(spans.length);
            for (int i = 0; i < spans.length; i++) {
                out.writeInt(spans[i]);
                out.writeInt(colors[i]);
            }
        }
    }

    // Decode; on any corruption/version mismatch returns an empty model (never throws).
    public static LayeredModel decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new LayeredModel(new HashMap<>(), new HashMap<>(), "");
        }
        Map<Integer, AbyssSpanStore.Column> primary = new HashMap<>();
        Map<Integer, AbyssSpanStore.Column> fallback = new HashMap<>();
        String primaryServer;
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return new LayeredModel(new HashMap<>(), new HashMap<>(), "");
            }
            primaryServer = in.readUTF();
            readLayer(in, primary);
            readLayer(in, fallback);
        } catch (Throwable t) {
            return new LayeredModel(new HashMap<>(), new HashMap<>(), "");
        }
        return new LayeredModel(primary, fallback, primaryServer);
    }

    private static void readLayer(DataInputStream in, Map<Integer, AbyssSpanStore.Column> map)
            throws Exception {
        int n = in.readInt();
        if (n < 0 || n > MAX_COLUMNS) throw new IllegalStateException("bad column count " + n);
        for (int i = 0; i < n; i++) {
            int cx = in.readInt();
            int cz = in.readInt();
            int sc = in.readInt();
            if (sc < 0 || sc > MAX_SPANS) throw new IllegalStateException("bad span count " + sc);
            int[] spans = new int[sc];
            int[] colors = new int[sc];
            for (int j = 0; j < sc; j++) {
                spans[j] = in.readInt();
                colors[j] = in.readInt();
            }
            map.put(AbyssSpanStore.packKey(cx, cz), new AbyssSpanStore.Column(spans, colors));
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*AbyssModelCodecTest*'
```

Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/AbyssModelCodec.java src/test/java/com/mia/aperture/map/AbyssModelCodecTest.java
git commit -m "feat(abyss-model): pure gzip codec for the persistent two-layer cache

```

---

### Task 2: AbyssComposite — pure survive-over-build gap-fill

**Files:**
- Create: `src/main/java/com/mia/aperture/map/AbyssComposite.java`
- Test: `src/test/java/com/mia/aperture/map/AbyssCompositeTest.java`

Note on behavior (accepted, coarse-scale): primary spans win at every depth they occupy; fallback fills only depths primary leaves empty. When a fallback fragment is *contiguous* with a primary span, `SpanMath.insertRun` merges them into one span carrying the higher top's (primary's) color — so a contiguous build continuation below survive renders in survive's color. Fallback keeps its own color only where an air gap separates it from primary. Geometry is always correct; this is a color-fidelity simplification only.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/AbyssCompositeTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbyssCompositeTest {

    private static Map<Integer, AbyssSpanStore.Column> one(int cx, int cz, AbyssSpanStore.Column c) {
        Map<Integer, AbyssSpanStore.Column> m = new HashMap<>();
        m.put(AbyssSpanStore.packKey(cx, cz), c);
        return m;
    }

    private static AbyssSpanStore.Column colAt(Map<Integer, AbyssSpanStore.Column> m, int cx, int cz) {
        return m.get(AbyssSpanStore.packKey(cx, cz));
    }

    @Test
    void primaryOnlyPassesThrough() {
        Map<Integer, AbyssSpanStore.Column> r =
                AbyssComposite.merge(one(0, 0, SpanMath.insertRun(null, 10, 5, 1)), new HashMap<>());
        assertEquals(1, r.size());
        assertTrue(SpanMath.solidAt(colAt(r, 0, 0), 7));
    }

    @Test
    void fallbackOnlyPassesThrough() {
        Map<Integer, AbyssSpanStore.Column> r =
                AbyssComposite.merge(new HashMap<>(), one(0, 0, SpanMath.insertRun(null, 10, 5, 2)));
        assertEquals(2, colAt(r, 0, 0).colors()[0]);
    }

    @Test
    void fallbackFillsAirGapBelowPrimaryKeepingItsColor() {
        // primary solid 20..15 (color 1); fallback solid 10..0 (color 2); air gap 14..11.
        Map<Integer, AbyssSpanStore.Column> r = AbyssComposite.merge(
                one(0, 0, SpanMath.insertRun(null, 20, 15, 1)),
                one(0, 0, SpanMath.insertRun(null, 10, 0, 2)));
        AbyssSpanStore.Column c = colAt(r, 0, 0);
        assertTrue(SpanMath.solidAt(c, 17));   // primary region
        assertFalse(SpanMath.solidAt(c, 12));  // air gap preserved
        assertTrue(SpanMath.solidAt(c, 5));    // fallback fill
        assertEquals(2, c.spans().length);
        // lower span keeps fallback color
        int lower = c.spans()[0];              // sorted ascending by bottom
        assertEquals(0, SpanMath.spanBottom(lower));
        assertEquals(2, c.colors()[0]);
    }

    @Test
    void fallbackInsidePrimaryAddsNothing() {
        Map<Integer, AbyssSpanStore.Column> r = AbyssComposite.merge(
                one(0, 0, SpanMath.insertRun(null, 20, 0, 1)),
                one(0, 0, SpanMath.insertRun(null, 10, 5, 2)));
        AbyssSpanStore.Column c = colAt(r, 0, 0);
        assertEquals(1, c.spans().length);
        assertEquals(20, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(1, c.colors()[0]);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*AbyssCompositeTest*'
```

Expected: FAIL — `cannot find symbol: class AbyssComposite`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/AbyssComposite.java`:

```java
package com.mia.aperture.map;

import java.util.HashMap;
import java.util.Map;

// Pure gap-fill merge of the survive-priority (primary) layer over the build (fallback) layer.
// Primary spans win at every depth they occupy; fallback fills only the depths primary leaves
// empty in the same column. Output is the base column map OrbitScene already renders. No Voxy or
// Minecraft imports.
//
// SECURITY INVARIANT: operates only on coarse LOD-4 column data (see AbyssModelCodec).
public final class AbyssComposite {
    private AbyssComposite() {}

    public static Map<Integer, AbyssSpanStore.Column> merge(
            Map<Integer, AbyssSpanStore.Column> primary,
            Map<Integer, AbyssSpanStore.Column> fallback) {
        Map<Integer, AbyssSpanStore.Column> out = new HashMap<>();
        for (Map.Entry<Integer, AbyssSpanStore.Column> e : fallback.entrySet()) {
            if (!primary.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<Integer, AbyssSpanStore.Column> e : primary.entrySet()) {
            AbyssSpanStore.Column fc = fallback.get(e.getKey());
            out.put(e.getKey(), fc == null ? e.getValue() : mergeColumn(e.getValue(), fc));
        }
        return out;
    }

    private static AbyssSpanStore.Column mergeColumn(
            AbyssSpanStore.Column primary, AbyssSpanStore.Column fallback) {
        AbyssSpanStore.Column result = null;
        for (int i = 0; i < primary.spans().length; i++) {
            result = SpanMath.insertRun(result,
                    SpanMath.spanTop(primary.spans()[i]), SpanMath.spanBottom(primary.spans()[i]),
                    primary.colors()[i]);
        }
        for (int i = 0; i < fallback.spans().length; i++) {
            AbyssSpanStore.Column frag = SpanMath.insertRun(null,
                    SpanMath.spanTop(fallback.spans()[i]), SpanMath.spanBottom(fallback.spans()[i]),
                    fallback.colors()[i]);
            for (int j = 0; j < primary.spans().length && frag != null; j++) {
                frag = SpanMath.clearRange(frag, SpanMath.spanTop(primary.spans()[j]),
                        SpanMath.spanBottom(primary.spans()[j]));
            }
            if (frag != null) {
                for (int j = 0; j < frag.spans().length; j++) {
                    result = SpanMath.insertRun(result,
                            SpanMath.spanTop(frag.spans()[j]), SpanMath.spanBottom(frag.spans()[j]),
                            frag.colors()[j]);
                }
            }
        }
        return result;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*AbyssCompositeTest*'
```

Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/AbyssComposite.java src/test/java/com/mia/aperture/map/AbyssCompositeTest.java
git commit -m "feat(abyss-model): pure survive-over-build gap-fill composite

```

---

### Task 3: MapSettings + MapConfig — primaryServer, persist toggle, host match

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/map/MapConfig.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/mia/aperture/map/MapSettingsTest.java`, add these tests before the final `}`:

```java
    @Test
    void persistenceDefaultsPresent() {
        MapSettings s = new MapSettings();
        assertEquals("survive.mineinabyss.com", s.primaryServer);
        assertTrue(s.persistAbyssModel);
    }

    @Test
    void hostMatchesIgnoresPortAndCase() {
        assertTrue(MapSettings.hostMatches("survive.mineinabyss.com:25565", "survive.mineinabyss.com"));
        assertTrue(MapSettings.hostMatches("SURVIVE.MineInAbyss.com", "survive.mineinabyss.com"));
        assertFalse(MapSettings.hostMatches("build.mineinabyss.com", "survive.mineinabyss.com"));
        assertFalse(MapSettings.hostMatches(null, "survive.mineinabyss.com"));
    }

    @Test
    void configFillsMissingPrimaryServer() {
        MapSettings s = MapConfig.fromJson("{}");
        assertEquals("survive.mineinabyss.com", s.primaryServer);
        assertTrue(s.persistAbyssModel);
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*MapSettingsTest*'
```

Expected: FAIL — `cannot find symbol: primaryServer` / `hostMatches`.

- [ ] **Step 3: Add the fields + helper to MapSettings**

In `src/main/java/com/mia/aperture/map/MapSettings.java`, immediately after the line `public int safeDropBlocks = 4;` (line 49), add:

```java
    // Persistent whole-Abyss cache: which server's data has priority (survive over build), and
    // whether the coarse model is written to disk at all. See the 2026-07-18 persistence spec.
    public String primaryServer = "survive.mineinabyss.com";
    public boolean persistAbyssModel = true;

    // True when currentIp's host equals primary's host, ignoring port and case. Used to route a
    // probe into the primary (survive) layer vs the fallback (build/other) layer.
    public static boolean hostMatches(String currentIp, String primary) {
        if (currentIp == null || primary == null) return false;
        return hostPart(currentIp).equalsIgnoreCase(hostPart(primary));
    }

    private static String hostPart(String s) {
        String h = s.trim();
        int c = h.indexOf(':');
        return c >= 0 ? h.substring(0, c) : h;
    }
```

- [ ] **Step 4: Default primaryServer on load in MapConfig**

In `src/main/java/com/mia/aperture/map/MapConfig.java`, in `fromJson`, after the line
`if (s.caveMode == null) s.caveMode = MapSettings.CaveMode.AUTO;` (line 25), add:

```java
            if (s.primaryServer == null || s.primaryServer.isBlank())
                s.primaryServer = "survive.mineinabyss.com";
```

- [ ] **Step 5: Run the tests**

```bash
./gradlew test --tests '*MapSettingsTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/map/MapConfig.java src/test/java/com/mia/aperture/map/MapSettingsTest.java
git commit -m "feat(abyss-model): primaryServer + persist settings and host-match helper

```

---

### Task 4: AbyssModelBuilder — two layers, composite publish, load/seed, save

**Files:**
- Modify (full replacement): `src/main/java/com/mia/aperture/map/AbyssModelBuilder.java`

No unit test (Voxy-facing). Verified by build + Task 6. This replaces the single `working` map with two tier layers, publishes the composite, and adds persistence.

- [ ] **Step 1: Replace the file**

Replace the entire contents of `src/main/java/com/mia/aperture/map/AbyssModelBuilder.java` with:

```java
package com.mia.aperture.map;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// Background builder for AbyssSpanStore. Probes native LOD-4 sections in the shifted column,
// converts solid cells to column spans, and publishes composited snapshots. Two source layers:
// `primaryWorking` (the primary server, survive) and `fallbackWorking` (everything else). Each
// probe writes into the layer matching the connected server; the published snapshot is the
// survive-over-build gap-fill composite. Both layers persist to disk (AbyssModelCodec) so build
// data seeds survive across sessions. All Voxy contact for the whole-Abyss feature is here.
//
// SECURITY INVARIANT: only native LOD-4 (LVL below) data ever enters the layers, so the persisted
// file is a coarse silhouette that cannot reconstruct a block-level build.
public final class AbyssModelBuilder {
    private static final int LVL = 4;                    // native 16-block LOD — never synthesize
    private static final int SEC_CELLS = 32;             // cells per section edge at LVL
    private static final int SEC_BLOCKS = SEC_CELLS << LVL;
    private static final int SEC_XZ_MIN = -16, SEC_XZ_MAX = 15;   // [-8192, 8192) blocks
    private static final int SEC_Y_MIN = -8, SEC_Y_MAX = 8;       // covers the Abyss band
    private static final int BATCH = 64;                 // probes per loop iteration
    private static final long SLEEP_MS = 10;
    private static final int PUBLISH_EVERY = 512;        // sections between progressive publishes
    private static final long DIRTY_PERIOD_MS = 10_000;
    private static final int DIRTY_RADIUS_BLOCKS = 768;
    private static final int MAX_COLUMNS = 2_000_000;    // defends against a coordinate bug
    private static final long SAVE_PERIOD_MS = 180_000;  // periodic autosave when dirty

    private static final Object LOCK = new Object();     // guards the two working maps
    private static final Map<Integer, AbyssSpanStore.Column> primaryWorking = new HashMap<>();
    private static final Map<Integer, AbyssSpanStore.Column> fallbackWorking = new HashMap<>();
    private static final Queue<long[]> queue = new ConcurrentLinkedQueue<>();

    private static volatile boolean currentTierIsPrimary;
    private static volatile String primaryServer = "survive.mineinabyss.com";
    private static volatile Path saveFile;
    private static volatile boolean dirty;
    private static int probed, total;
    private static long lastDirtyMs, lastSaveMs;
    private static boolean overflowLogged;
    private static Thread thread;

    private AbyssModelBuilder() {}

    public static void setPrimaryServer(String s) { if (s != null && !s.isBlank()) primaryServer = s; }

    public static void setPrimaryTier(boolean primary) { currentTierIsPrimary = primary; }

    // Load persisted layers (also sets the save file) and publish an initial composite so the
    // whole-Abyss view is instant-on from cache before any probing. Enables persistence.
    public static void load(Path p) {
        saveFile = p;
        try {
            if (p != null && Files.exists(p)) {
                AbyssModelCodec.LayeredModel m = AbyssModelCodec.decode(Files.readAllBytes(p));
                synchronized (LOCK) {
                    primaryWorking.clear(); primaryWorking.putAll(m.primary());
                    fallbackWorking.clear(); fallbackWorking.putAll(m.fallback());
                }
                setPrimaryServer(m.primaryServer());
            }
            publish();
        } catch (Throwable t) {
            System.err.println("[MIA Maps] abyss model load failed: " + t);
        }
    }

    // Serialize both layers to disk (temp file + atomic rename). No-op when persistence is off
    // (saveFile null). Safe to call from any thread — copies the maps under LOCK first.
    public static void save() {
        Path p = saveFile;
        if (p == null) return;
        Map<Integer, AbyssSpanStore.Column> pc, fc;
        String host;
        synchronized (LOCK) {
            pc = new HashMap<>(primaryWorking);
            fc = new HashMap<>(fallbackWorking);
            host = primaryServer;
            dirty = false;
        }
        try {
            byte[] bytes = AbyssModelCodec.encode(new AbyssModelCodec.LayeredModel(pc, fc, host));
            if (bytes.length == 0) return;
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            System.err.println("[MIA Maps] abyss model save failed: " + t);
        }
    }

    public static synchronized void ensureStarted() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(AbyssModelBuilder::loop, "MIA-Abyss-Model");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    // Queue a full sweep of the shifted column — called on server join so the newly connected
    // server's on-disk Voxy data imports into the current tier.
    public static void enqueueFullSweep() {
        queue.clear();
        int n = 0;
        for (int sy = SEC_Y_MIN; sy <= SEC_Y_MAX; sy++) {
            for (int sz = SEC_XZ_MIN; sz <= SEC_XZ_MAX; sz++) {
                for (int sx = SEC_XZ_MIN; sx <= SEC_XZ_MAX; sx++) {
                    queue.add(new long[]{sx, sy, sz});
                    n++;
                }
            }
        }
        total = n;
        probed = 0;
    }

    private static void publish() {
        Map<Integer, AbyssSpanStore.Column> base;
        synchronized (LOCK) {
            base = AbyssComposite.merge(primaryWorking, fallbackWorking);
        }
        AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(base, probed, total));
    }

    private static void loop() {
        boolean[] opaque = new boolean[SEC_CELLS * SEC_CELLS * SEC_CELLS];
        int[] argb = new int[SEC_CELLS * SEC_CELLS * SEC_CELLS];
        long[] scratch = new long[SEC_CELLS * SEC_CELLS * SEC_CELLS];
        long[][] synth = new long[1][];
        while (true) {
            try {
                VoxyRenderSystem rs = IGetVoxyRenderSystem.getNullable();
                MapColorSource colors = MapCompositor.colorSource();
                if (rs == null || colors == null) { Thread.sleep(500); continue; }
                WorldEngine engine = rs.getEngine();

                int did = 0;
                long[] sec;
                while (did < BATCH && (sec = queue.poll()) != null) {
                    probeSection(engine, colors, (int) sec[0], (int) sec[1], (int) sec[2],
                            opaque, argb, scratch, synth);
                    probed++;
                    did++;
                    if (probed % PUBLISH_EVERY == 0) publish();
                }
                if (did > 0 && queue.isEmpty()) publish();
                if (queue.isEmpty()) enqueueDirtyNearPlayer();
                maybeAutosave();
                Thread.sleep(queue.isEmpty() ? 250 : SLEEP_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] abyss model build failed: " + t);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static void maybeAutosave() {
        long now = System.currentTimeMillis();
        if (dirty && now - lastSaveMs > SAVE_PERIOD_MS) {
            lastSaveMs = now;
            save();
        }
    }

    // One LOD-4 section -> spans, written into the current tier's layer. Clears the section's Y
    // window first so a re-probe replaces stale data instead of unioning with it.
    private static void probeSection(WorldEngine engine, MapColorSource colors,
            int secX, int secY, int secZ,
            boolean[] opaque, int[] argb, long[] scratch, long[][] synth) {
        Arrays.fill(opaque, false);
        VoxelCloud.fillInto(engine, colors, secX * SEC_CELLS, secY * SEC_CELLS, secZ * SEC_CELLS,
                SEC_CELLS, SEC_CELLS, SEC_CELLS, LVL, opaque, argb, scratch, synth);
        Map<Integer, AbyssSpanStore.Column> map = currentTierIsPrimary ? primaryWorking : fallbackWorking;
        int yTop = secY * SEC_CELLS + SEC_CELLS - 1, yBottom = secY * SEC_CELLS;
        synchronized (LOCK) {
            for (int lz = 0; lz < SEC_CELLS; lz++) {
                for (int lx = 0; lx < SEC_CELLS; lx++) {
                    int key = AbyssSpanStore.packKey(secX * SEC_CELLS + lx, secZ * SEC_CELLS + lz);
                    AbyssSpanStore.Column col = SpanMath.clearRange(map.get(key), yTop, yBottom);
                    int runTop = -1;
                    for (int ly = SEC_CELLS - 1; ly >= 0; ly--) {
                        boolean solid = opaque[(ly * SEC_CELLS + lz) * SEC_CELLS + lx];
                        if (solid && runTop < 0) runTop = ly;
                        if ((!solid || ly == 0) && runTop >= 0) {
                            int runBottom = solid ? ly : ly + 1;
                            col = SpanMath.insertRun(col, secY * SEC_CELLS + runTop,
                                    secY * SEC_CELLS + runBottom,
                                    argb[(runTop * SEC_CELLS + lz) * SEC_CELLS + lx]);
                            runTop = -1;
                        }
                    }
                    if (col == null) map.remove(key);
                    else if (map.size() < MAX_COLUMNS || map.containsKey(key)) map.put(key, col);
                    else if (!overflowLogged) {
                        overflowLogged = true;
                        System.err.println("[MIA Maps] abyss model column cap hit — coordinate bug?");
                    }
                }
            }
        }
        dirty = true;
    }

    // Newly explored terrain: re-probe sections near the player every DIRTY_PERIOD_MS into the
    // current tier. Polling is enough for an overview; there is no event hook into Voxy ingestion.
    private static void enqueueDirtyNearPlayer() {
        long now = System.currentTimeMillis();
        if (now - lastDirtyMs < DIRTY_PERIOD_MS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        lastDirtyMs = now;
        double[] p = MapGeometry.toShiftedColumn(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        int r = DIRTY_RADIUS_BLOCKS;
        int sx0 = Math.floorDiv((int) p[0] - r, SEC_BLOCKS), sx1 = Math.floorDiv((int) p[0] + r, SEC_BLOCKS);
        int sy0 = Math.floorDiv((int) p[1] - r, SEC_BLOCKS), sy1 = Math.floorDiv((int) p[1] + r, SEC_BLOCKS);
        int sz0 = Math.floorDiv((int) p[2] - r, SEC_BLOCKS), sz1 = Math.floorDiv((int) p[2] + r, SEC_BLOCKS);
        for (int sy = Math.max(SEC_Y_MIN, sy0); sy <= Math.min(SEC_Y_MAX, sy1); sy++) {
            for (int sz = Math.max(SEC_XZ_MIN, sz0); sz <= Math.min(SEC_XZ_MAX, sz1); sz++) {
                for (int sx = Math.max(SEC_XZ_MIN, sx0); sx <= Math.min(SEC_XZ_MAX, sx1); sx++) {
                    queue.add(new long[]{sx, sy, sz});
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build (all existing tests must still pass)**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mia/aperture/map/AbyssModelBuilder.java
git commit -m "feat(abyss-model): two-layer builder with composite publish + persistence

```

---

### Task 5: Client wiring — load on init, JOIN tier+sweep, disconnect + shutdown save

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`

No unit test (Fabric/Minecraft-facing). Verified by build + Task 6.

- [ ] **Step 1: Add the model-file path helper**

In `src/main/java/com/mia/aperture/client/MiaApertureModClient.java`, right after the existing `mapConfigPath()` method (ends line 33), add:

```java
    public static java.nio.file.Path abyssModelPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("mia_aperture_abyss_model.bin");
    }
```

- [ ] **Step 2: Load the cache on init**

In `onInitializeClient`, replace:

```java
        mapSettings = com.mia.aperture.map.MapConfig.load(mapConfigPath());
        waypoints = com.mia.aperture.map.WaypointConfig.load(waypointConfigPath());
```

with:

```java
        mapSettings = com.mia.aperture.map.MapConfig.load(mapConfigPath());
        waypoints = com.mia.aperture.map.WaypointConfig.load(waypointConfigPath());

        // Whole-Abyss cache persistence: seed the coarse model from disk (instant-on view) when
        // enabled. When disabled we never set the save file, so every save() is a no-op while the
        // in-memory view still builds normally on join. See the 2026-07-18 persistence spec.
        com.mia.aperture.map.AbyssModelBuilder.setPrimaryServer(mapSettings.primaryServer);
        if (mapSettings.persistAbyssModel) {
            com.mia.aperture.map.AbyssModelBuilder.load(abyssModelPath());
        }
```

- [ ] **Step 3: Bucket by server + full-sweep on join; save on disconnect + shutdown**

In `onInitializeClient`, replace the existing DISCONNECT registration:

```java
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> {
                    com.mia.aperture.map.MapCompositor.reset();
                    com.mia.aperture.map.OrbitScene.reset();
                }));
```

with:

```java
        // On join, route probes into the survive (primary) or build/other (fallback) layer, then
        // sweep so this server's on-disk Voxy data imports into that layer.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    String ip = client.getCurrentServer() != null ? client.getCurrentServer().ip : null;
                    com.mia.aperture.map.AbyssModelBuilder.setPrimaryServer(mapSettings.primaryServer);
                    com.mia.aperture.map.AbyssModelBuilder.setPrimaryTier(
                            com.mia.aperture.map.MapSettings.hostMatches(ip, mapSettings.primaryServer));
                    com.mia.aperture.map.AbyssModelBuilder.ensureStarted();
                    com.mia.aperture.map.AbyssModelBuilder.enqueueFullSweep();
                });

        // DISCONNECT fires on the Netty network thread, but OrbitScene.reset releases a GPU
        // texture and GL only accepts that from the render thread ("Rendersystem called from
        // wrong thread" crash on every server disconnect). Hop to the client thread; execute()
        // runs inline when already on it. The abyss cache is deliberately NOT reset — persisting
        // it across servers/sessions is the whole point — but we do save it (file IO is fine on
        // the Netty thread; it is not a GL call).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    com.mia.aperture.map.AbyssModelBuilder.save();
                    client.execute(() -> {
                        com.mia.aperture.map.MapCompositor.reset();
                        com.mia.aperture.map.OrbitScene.reset();
                    });
                });

        // Final flush on client shutdown (synchronous — last chance before the JVM exits).
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(
                client -> com.mia.aperture.map.AbyssModelBuilder.save());
```

- [ ] **Step 4: Build**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/client/MiaApertureModClient.java
git commit -m "feat(abyss-model): wire persistence load/save + per-server tier on join

```

---

### Task 6: Install and verify in game

**Files:** none (verification only). Ships in the same jar as the whole-Abyss view; no version bump in this plan (folds into the pending v0.1.8-beta).

- [ ] **Step 1: Build and install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
ls "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/" | grep mia-maps
```

Expected: exactly one `mia-maps-*.jar`.

- [ ] **Step 2: Build server import + persistence**

Connect to **build**. Open the 3D view, set 3D Area → "Whole Abyss", turn on 3D Stats, and let the sweep run. Expected: cache fills ("building N%"), then quit to desktop.

Expected on disk: `config/mia_aperture_abyss_model.bin` exists and is non-trivial in size.

- [ ] **Step 3: Cross-server seeding + survive priority**

Relaunch, connect to **survive**. Open the whole-Abyss view immediately.

Expected: build's map is visible from the first second (instant-on from the file). As you move around survive, near-terrain updates. Where survive has mapped terrain it should take priority; below your explored depth, build fills. No holes below explored depth.

- [ ] **Step 4: Freshness + toggle**

Walk into unmapped survive terrain for a minute, wait ~20 s: the overview updates. Set `persistAbyssModel` to `false` in `config/mia_aperture_map.json`, relaunch: the view still builds live in-memory, and no file is written/updated.

- [ ] **Step 5: Report findings**

Report each step's outcome. If build data does NOT appear on survive, capture the 3D Stats line and whether `mia_aperture_abyss_model.bin` exists + its size (distinguishes a save failure from a load/priority failure).

---

## Notes for the implementer

- **Do not** create a branch or worktree. Work on `main`.
- **No narrating inline comments** — comments explain constraints and why.
- `AbyssModelCodec` and `AbyssComposite` must not import `me.cortex.voxy.*` or `net.minecraft.*`.
- The **anti-clone invariant** is load-bearing: never let anything finer than LOD 4 into the layers or the file. If a future change adds a finer probe, it must NOT flow into these maps.
- Persistence is off ⇒ `saveFile` stays null ⇒ every `save()` is a no-op; the in-memory view is unaffected. Do not add a second enabled-flag.
- Fine-LOD sharing is explicitly **out of scope** (separate future spec) — do not add live cross-server fine reads here.
```
