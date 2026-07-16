# Whole-Abyss 3D View Implementation Plan


**Goal:** A "Whole Abyss" step on the 3D Area slider that shows every mapped layer at once, rendered from a background-built column-span cache of native LOD-4 data.

**Architecture:** Pure span model (`SpanMath` + `AbyssSpanStore`, no Voxy imports, fully unit-tested) filled by a rate-limited background worker (`AbyssModelBuilder`, Voxy-facing) probing all ~17k LOD-4 sections in the shifted column. `OrbitScene`'s worker builds its point cloud from the cache instead of the live sampler when the Whole Abyss step is active — same `VoxelCloud.Point` list, same cube rasterizer, no render-path changes.

**Tech Stack:** Java 21, Fabric 1.21.11, JUnit 5. Voxy is `compileOnly` and OFF the test classpath — pure classes must not import `me.cortex.voxy.*` or `net.minecraft.*`.

**Spec:** `docs/plans/specs/2026-07-16-whole-abyss-3d-view-design.md`

**Branch policy:** Work directly on `main` in `D:\Users\dev\VSCode-Projects\MIA map mod project`. Do NOT create a branch or worktree (project convention — `AGENTS.md`).

---

## Background an engineer needs

Read the spec first. Load-bearing facts:

- **Shifted column:** the Abyss's 15 sections stack into one continuous vertical space
  (`MapGeometry.toShiftedColumn`). One layer's X domain is `[-8192, 8192)`; the mapped band is
  `MapGeometry.ABYSS_SHIFTED_Y_BOTTOM (-3616) .. ABYSS_SHIFTED_Y_TOP (4352)`.
- **LOD 4 is native 16-block data** and the coarsest Voxy stores (`project_memory.md` §3b). The
  old 8192/16384 slider steps failed because the LIVE sampler synthesized coarser cells per
  camera move. This feature reads LOD 4 **once** into a cache instead. The live path stays
  capped at 4096 — that cap is still correct.
- **Cell coords:** at base level, cell = 16 blocks; `cellX = blockX >> 4` in shifted space,
  exactly the cell coordinates `VoxelCloud.fill` uses at `lvl = 4`. Mip level L has
  `cellSize = 16 << L` (levels 0/1/2 = 16/32/64 blocks).
- **A LOD-4 "section"** is 32³ cells = 512³ blocks. Column domain: `secX, secZ ∈ [-16, 16)`
  (32 each), `secY ∈ [-8, 8]` (17) → **17,408 sections** total.
- **`OrbitScene`** already runs sampling+rasterization on a dedicated worker thread
  (`MIA-Orbit-Raster`) and consumes a `List<VoxelCloud.Point>`:
  `Point(double x, double y, double z, int argb, int cellSize, float nx, float ny, float nz, int faces, boolean covered)`.
  `faces` is a bitmask over `OrbitScene.FACES` order: bit 0..5 = `+X, -X, +Y, -Y, +Z, -Z`.
  `nx/ny/nz` are not read by the cube rasterizer (pass `0, 1, 0`).
- **Build:**
```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

## File Structure

| File | Responsibility | New? | Tested? |
|---|---|---|---|
| `map/SpanMath.java` | Pure span ops: pack, insert-merge, clear-range, mip, solidAt | new | yes |
| `map/AbyssSpanStore.java` | Pure store: Column, Snapshot (base+2 mips), publish/current, surface walk | new | yes |
| `map/AbyssModelBuilder.java` | Voxy-facing background builder + dirty refresh | new | no |
| `map/VoxelCloud.java` | Expose `fillInto` with caller-owned scratch (builder needs churn-free probing) | modify | existing tests |
| `map/MapSettings.java` | `ORBIT_AREA_WHOLE` step | modify | yes (revised) |
| `client/MapSettingsScreen.java` | "Whole Abyss" slider label | modify | no |
| `map/OrbitScene.java` | Whole-mode cloud from cache; zoom ceiling; X-ray off | modify | no |
| `client/OrbitView.java` | HUD X-ray label + stats cache line | modify | no |
| `map/HelpContent.java` | Document the step | modify | yes |

---

### Task 1: SpanMath — pure span operations

**Files:**
- Create: `src/main/java/com/mia/aperture/map/SpanMath.java`
- Create: `src/main/java/com/mia/aperture/map/AbyssSpanStore.java` (the `Column` record only, so SpanMath has its type)
- Test: `src/test/java/com/mia/aperture/map/SpanMathTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/SpanMathTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpanMathTest {

    @Test
    void spanPackingRoundTripsIncludingNegatives() {
        int s = SpanMath.packSpan(271, -226);
        assertEquals(271, SpanMath.spanTop(s));
        assertEquals(-226, SpanMath.spanBottom(s));
        int t = SpanMath.packSpan(0, 0);
        assertEquals(0, SpanMath.spanTop(t));
        assertEquals(0, SpanMath.spanBottom(t));
    }

    @Test
    void insertIntoEmptyColumnCreatesOneSpan() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 0xFF112233);
        assertEquals(1, c.spans().length);
        assertEquals(10, SpanMath.spanTop(c.spans()[0]));
        assertEquals(5, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(0xFF112233, c.colors()[0]);
    }

    @Test
    void disjointRunsStaySeparateAndSorted() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 1);
        c = SpanMath.insertRun(c, -3, -8, 2);
        assertEquals(2, c.spans().length);
        // sorted ascending by bottom
        assertEquals(-8, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(5, SpanMath.spanBottom(c.spans()[1]));
    }

    @Test
    void overlappingAndAdjacentRunsMerge() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 1);
        c = SpanMath.insertRun(c, 4, 0, 2);      // adjacent below (4 touches 5)
        assertEquals(1, c.spans().length);
        assertEquals(10, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
        c = SpanMath.insertRun(c, 15, 8, 3);     // overlapping above
        assertEquals(1, c.spans().length);
        assertEquals(15, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
    }

    @Test
    void mergeKeepsTheColorOfTheHighestTop() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 111);
        c = SpanMath.insertRun(c, 8, 3, 222);    // merges, but 10 is still the top
        assertEquals(111, c.colors()[0]);
        c = SpanMath.insertRun(c, 14, 9, 333);   // new top 14 wins
        assertEquals(333, c.colors()[0]);
    }

    @Test
    void clearRangeRemovesTrimsAndSplits() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 20, 0, 7);
        c = SpanMath.clearRange(c, 12, 8);       // punch a hole in the middle
        assertEquals(2, c.spans().length);
        assertEquals(7, SpanMath.spanTop(c.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(c.spans()[0]));
        assertEquals(20, SpanMath.spanTop(c.spans()[1]));
        assertEquals(13, SpanMath.spanBottom(c.spans()[1]));
        c = SpanMath.clearRange(c, 25, 13);      // remove the upper fragment entirely
        assertEquals(1, c.spans().length);
        AbyssSpanStore.Column gone = SpanMath.clearRange(c, 30, -30);
        assertNull(gone);
    }

    @Test
    void solidAtChecksMembership() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 10, 5, 1);
        assertTrue(SpanMath.solidAt(c, 5));
        assertTrue(SpanMath.solidAt(c, 10));
        assertFalse(SpanMath.solidAt(c, 4));
        assertFalse(SpanMath.solidAt(c, 11));
        assertFalse(SpanMath.solidAt(null, 0));
    }

    @Test
    void mipHalvesYAndUnionsOccupancy() {
        // A span 8..15 at base becomes 4..7 at the next level; a disjoint 0..1 becomes 0..0.
        AbyssSpanStore.Column c = SpanMath.insertRun(null, 15, 8, 5);
        c = SpanMath.insertRun(c, 1, 0, 9);
        AbyssSpanStore.Column m = SpanMath.mipInto(null, c);
        assertEquals(2, m.spans().length);
        assertEquals(7, SpanMath.spanTop(m.spans()[1]));
        assertEquals(4, SpanMath.spanBottom(m.spans()[1]));
        assertEquals(0, SpanMath.spanTop(m.spans()[0]));
        assertEquals(0, SpanMath.spanBottom(m.spans()[0]));
    }

    @Test
    void mipOfNegativeYUsesFloorDivision() {
        AbyssSpanStore.Column c = SpanMath.insertRun(null, -5, -8, 1);
        AbyssSpanStore.Column m = SpanMath.mipInto(null, c);
        assertEquals(-3, SpanMath.spanTop(m.spans()[0]));   // floorDiv(-5,2) = -3
        assertEquals(-4, SpanMath.spanBottom(m.spans()[0])); // floorDiv(-8,2) = -4
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*SpanMathTest*'
```

Expected: FAIL — `cannot find symbol: class SpanMath` / `class AbyssSpanStore`.

- [ ] **Step 3: Implement**

Create `src/main/java/com/mia/aperture/map/AbyssSpanStore.java` with just the Column record for now (the rest arrives in Task 2):

```java
package com.mia.aperture.map;

// Cached column-span model of the whole Abyss shifted column. Filled by AbyssModelBuilder from
// native LOD-4 Voxy data; read by OrbitScene at the "Whole Abyss" area step. This class is pure
// (no Voxy or Minecraft imports) so the span model stays unit-testable.
public final class AbyssSpanStore {
    // One (cellX, cellZ) column: solid runs sorted ascending by bottom, non-overlapping and
    // non-adjacent (touching runs are merged on insert). colors[i] is the ARGB of spans[i]'s top
    // face — the color that matters seen from above; sides reuse it, which is fine at overview
    // scale. Instances are immutable: every mutation returns a new Column.
    public record Column(int[] spans, int[] colors) {}

    private AbyssSpanStore() {}
}
```

Create `src/main/java/com/mia/aperture/map/SpanMath.java`:

```java
package com.mia.aperture.map;

// Pure span arithmetic for AbyssSpanStore columns. A span is one int: biased top in the high
// half, biased bottom in the low half (cellY is small — the Abyss band is ~500 base cells — but
// signed, hence the bias). No Voxy or Minecraft imports.
public final class SpanMath {
    private static final int BIAS = 2048;
    private static final int MASK = 0xFFF;

    private SpanMath() {}

    public static int packSpan(int top, int bottom) {
        return ((top + BIAS) << 12) | (bottom + BIAS);
    }

    public static int spanTop(int span) {
        return ((span >>> 12) & MASK) - BIAS;
    }

    public static int spanBottom(int span) {
        return (span & MASK) - BIAS;
    }

    // Insert a solid run [bottom..top] (inclusive), merging any overlapping or touching spans.
    // The merged span's color follows the HIGHEST top — that's the face you see from above.
    public static AbyssSpanStore.Column insertRun(AbyssSpanStore.Column c, int top, int bottom, int color) {
        int newTop = top, newBottom = bottom, newColor = color;
        int n = c == null ? 0 : c.spans().length;
        int[] keepS = new int[n + 1];
        int[] keepC = new int[n + 1];
        int before = 0, kept = 0;
        for (int i = 0; i < n; i++) {
            int s = c.spans()[i];
            int st = spanTop(s), sb = spanBottom(s);
            if (st < bottom - 1) {                       // entirely below, not touching
                keepS[kept] = s; keepC[kept] = c.colors()[i]; kept++; before = kept;
            } else if (sb > top + 1) {                   // entirely above, not touching
                keepS[kept] = s; keepC[kept] = c.colors()[i]; kept++;
            } else {                                     // overlaps or touches: merge
                if (st > newTop) { newTop = st; newColor = c.colors()[i]; }
                if (sb < newBottom) newBottom = sb;
            }
        }
        int[] outS = new int[kept + 1];
        int[] outC = new int[kept + 1];
        System.arraycopy(keepS, 0, outS, 0, before);
        System.arraycopy(keepC, 0, outC, 0, before);
        outS[before] = packSpan(newTop, newBottom);
        outC[before] = newColor;
        int afterCount = kept - before;
        System.arraycopy(keepS, before, outS, before + 1, afterCount);
        System.arraycopy(keepC, before, outC, before + 1, afterCount);
        return new AbyssSpanStore.Column(outS, outC);
    }

    // Remove [clearBottom..clearTop] (inclusive) from the column: drops covered spans, trims
    // straddlers, splits a span the range punches through. Fragments keep the original span's
    // color (a cut face inheriting the old top color is acceptable at overview scale).
    // Returns null when nothing remains.
    public static AbyssSpanStore.Column clearRange(AbyssSpanStore.Column c, int clearTop, int clearBottom) {
        if (c == null) return null;
        int n = c.spans().length;
        int[] outS = new int[n + 1];
        int[] outC = new int[n + 1];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            int s = c.spans()[i];
            int st = spanTop(s), sb = spanBottom(s);
            if (st < clearBottom || sb > clearTop) {     // untouched
                outS[kept] = s; outC[kept] = c.colors()[i]; kept++;
                continue;
            }
            if (sb < clearBottom) {                      // lower fragment survives
                outS[kept] = packSpan(clearBottom - 1, sb); outC[kept] = c.colors()[i]; kept++;
            }
            if (st > clearTop) {                         // upper fragment survives
                outS[kept] = packSpan(st, clearTop + 1); outC[kept] = c.colors()[i]; kept++;
            }
        }
        if (kept == 0) return null;
        int[] rs = new int[kept];
        int[] rc = new int[kept];
        System.arraycopy(outS, 0, rs, 0, kept);
        System.arraycopy(outC, 0, rc, 0, kept);
        return new AbyssSpanStore.Column(rs, rc);
    }

    public static boolean solidAt(AbyssSpanStore.Column c, int cellY) {
        if (c == null) return false;
        for (int s : c.spans()) {
            if (cellY >= spanBottom(s) && cellY <= spanTop(s)) return true;
        }
        return false;
    }

    // Fold a finer column into a coarser accumulator: Y halves (floor division, occupancy
    // union). Called once per child column; pass the running result back in.
    public static AbyssSpanStore.Column mipInto(AbyssSpanStore.Column acc, AbyssSpanStore.Column child) {
        if (child == null) return acc;
        for (int i = 0; i < child.spans().length; i++) {
            int s = child.spans()[i];
            acc = insertRun(acc, Math.floorDiv(spanTop(s), 2), Math.floorDiv(spanBottom(s), 2),
                    child.colors()[i]);
        }
        return acc;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*SpanMathTest*'
```

Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/SpanMath.java src/main/java/com/mia/aperture/map/AbyssSpanStore.java src/test/java/com/mia/aperture/map/SpanMathTest.java
git commit -m "feat(abyss-model): pure span arithmetic for the whole-Abyss cache

```

---

### Task 2: AbyssSpanStore — snapshot, mips, surface walk

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/AbyssSpanStore.java`
- Test: `src/test/java/com/mia/aperture/map/AbyssSpanStoreTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mia/aperture/map/AbyssSpanStoreTest.java`:

```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AbyssSpanStoreTest {

    @Test
    void keyPackingRoundTripsOverTheDomain() {
        for (int x : new int[]{-512, -1, 0, 511}) {
            for (int z : new int[]{-512, -1, 0, 511}) {
                int k = AbyssSpanStore.packKey(x, z);
                assertEquals(x, AbyssSpanStore.keyX(k), "x for " + x + "," + z);
                assertEquals(z, AbyssSpanStore.keyZ(k), "z for " + x + "," + z);
            }
        }
    }

    @Test
    void aLoneCubeExposesAllSixFaces() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(3, 4), SpanMath.insertRun(null, 7, 7, 0xAA));
        List<int[]> cells = collect(base);
        assertEquals(1, cells.size());
        int[] c = cells.get(0);
        assertEquals(3, c[0]);
        assertEquals(7, c[1]);
        assertEquals(4, c[2]);
        assertEquals(0b111111, c[4], "all six faces exposed");
    }

    @Test
    void aTallPillarExposesSidesAndOnlyItsEnds() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(0, 0), SpanMath.insertRun(null, 5, 0, 0xBB));
        List<int[]> cells = collect(base);
        assertEquals(6, cells.size());
        for (int[] c : cells) {
            boolean top = c[1] == 5, bottom = c[1] == 0;
            assertEquals(top, (c[4] & 0b000100) != 0, "+Y only at the top, y=" + c[1]);
            assertEquals(bottom, (c[4] & 0b001000) != 0, "-Y only at the bottom, y=" + c[1]);
            assertTrue((c[4] & 0b000001) != 0, "+X side exposed");
        }
    }

    @Test
    void aBuriedCellEmitsNothing() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        // 3x3 columns of a 3-tall block: the centre column's middle cell is fully enclosed.
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                base.put(AbyssSpanStore.packKey(x, z), SpanMath.insertRun(null, 2, 0, 1));
        for (int[] c : collect(base)) {
            assertFalse(c[0] == 0 && c[1] == 1 && c[2] == 0, "enclosed centre cell must not emit");
        }
        assertEquals(AbyssSpanStore.countSurfaces(base), collect(base).size());
    }

    @Test
    void publishSwapsTheCurrentSnapshotAndBumpsSeq() {
        AbyssSpanStore.Snapshot before = AbyssSpanStore.current();
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(1, 1), SpanMath.insertRun(null, 3, 1, 5));
        AbyssSpanStore.Snapshot snap = AbyssSpanStore.buildSnapshot(base, 10, 20);
        AbyssSpanStore.publish(snap);
        try {
            assertSame(snap, AbyssSpanStore.current());
            assertTrue(snap.seq() > before.seq());
            assertEquals(10, snap.probedSections());
            assertEquals(20, snap.totalSections());
            assertEquals(3, snap.surfaceCounts().length);
            assertTrue(snap.surfaceCounts()[0] > 0);
        } finally {
            AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(new HashMap<>(), 0, 0));
        }
    }

    @Test
    void mipLevelsHalveTheColumnDomain() {
        Map<Integer, AbyssSpanStore.Column> base = new HashMap<>();
        base.put(AbyssSpanStore.packKey(4, 6), SpanMath.insertRun(null, 8, 8, 1));
        base.put(AbyssSpanStore.packKey(5, 7), SpanMath.insertRun(null, 9, 9, 2));
        Map<Integer, AbyssSpanStore.Column> mip = AbyssSpanStore.mipMap(base);
        assertEquals(1, mip.size(), "both columns fold into (2,3)");
        AbyssSpanStore.Column m = mip.get(AbyssSpanStore.packKey(2, 3));
        assertNotNull(m);
        assertTrue(SpanMath.solidAt(m, 4));
    }

    private static List<int[]> collect(Map<Integer, AbyssSpanStore.Column> map) {
        List<int[]> out = new ArrayList<>();
        AbyssSpanStore.forEachSurface(map, (x, y, z, color, faces) -> out.add(new int[]{x, y, z, color, faces}));
        return out;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*AbyssSpanStoreTest*'
```

Expected: FAIL — `cannot find symbol` for `packKey`, `Snapshot`, `forEachSurface`, etc.

- [ ] **Step 3: Implement**

Replace the body of `src/main/java/com/mia/aperture/map/AbyssSpanStore.java` (keeping the existing class comment and `Column` record) with:

```java
package com.mia.aperture.map;

import java.util.HashMap;
import java.util.Map;

// Cached column-span model of the whole Abyss shifted column. Filled by AbyssModelBuilder from
// native LOD-4 Voxy data; read by OrbitScene at the "Whole Abyss" area step. This class is pure
// (no Voxy or Minecraft imports) so the span model stays unit-testable.
public final class AbyssSpanStore {
    // One (cellX, cellZ) column: solid runs sorted ascending by bottom, non-overlapping and
    // non-adjacent (touching runs are merged on insert). colors[i] is the ARGB of spans[i]'s top
    // face — the color that matters seen from above; sides reuse it, which is fine at overview
    // scale. Instances are immutable: every mutation returns a new Column.
    public record Column(int[] spans, int[] colors) {}

    // Face bit order matches OrbitScene.FACES: +X, -X, +Y, -Y, +Z, -Z.
    public interface SurfaceVisitor { void cell(int cellX, int cellY, int cellZ, int color, int faces); }

    // Immutable published state: base level (16-block cells) plus two mips (32, 64). Maps must
    // not be mutated after publish — the builder copies its working map into each snapshot.
    public record Snapshot(Map<Integer, Column> base, Map<Integer, Column> mip1, Map<Integer, Column> mip2,
                           int[] surfaceCounts, long seq, long builtAtMs,
                           int probedSections, int totalSections) {
        public Map<Integer, Column> level(int l) {
            return l == 0 ? base : l == 1 ? mip1 : mip2;
        }
    }

    public static final int LEVELS = 3;

    private static long seqCounter;
    private static volatile Snapshot current =
            new Snapshot(Map.of(), Map.of(), Map.of(), new int[LEVELS], 0, 0, 0, 0);

    private AbyssSpanStore() {}

    public static Snapshot current() { return current; }

    public static void publish(Snapshot s) { current = s; }

    public static int cellSize(int level) { return 16 << level; }

    // Column key: 12 bits per axis, biased. Domain here is |cell| <= 2047, comfortably beyond
    // the sector band's [-512, 512) at base level.
    public static int packKey(int cellX, int cellZ) {
        return ((cellX + 2048) << 12) | (cellZ + 2048);
    }

    public static int keyX(int key) { return (key >>> 12) - 2048; }

    public static int keyZ(int key) { return (key & 0xFFF) - 2048; }

    // Derive the next-coarser level: 2x2 columns fold into one, Y halves (SpanMath.mipInto).
    public static Map<Integer, Column> mipMap(Map<Integer, Column> src) {
        Map<Integer, Column> out = new HashMap<>();
        for (Map.Entry<Integer, Column> e : src.entrySet()) {
            int key = AbyssSpanStore.packKey(
                    Math.floorDiv(keyX(e.getKey()), 2), Math.floorDiv(keyZ(e.getKey()), 2));
            out.put(key, SpanMath.mipInto(out.get(key), e.getValue()));
        }
        return out;
    }

    // Assemble a snapshot from the builder's working map: copies the map (the builder keeps
    // mutating its own), derives both mips, counts surfaces per level.
    public static Snapshot buildSnapshot(Map<Integer, Column> working, int probed, int total) {
        Map<Integer, Column> base = new HashMap<>(working);
        Map<Integer, Column> mip1 = mipMap(base);
        Map<Integer, Column> mip2 = mipMap(mip1);
        int[] counts = {countSurfaces(base), countSurfaces(mip1), countSurfaces(mip2)};
        synchronized (AbyssSpanStore.class) {
            return new Snapshot(base, mip1, mip2, counts, ++seqCounter,
                    System.currentTimeMillis(), probed, total);
        }
    }

    public static int countSurfaces(Map<Integer, Column> map) {
        int[] n = {0};
        forEachSurface(map, (x, y, z, c, f) -> n[0]++);
        return n[0];
    }

    // Emit every solid cell with at least one of its six neighbours empty. Within a column,
    // merged spans mean "cell above is solid iff below the span top", so +Y/-Y are just the span
    // ends; the four side faces test the neighbouring columns at the same cellY. Cliff walls come
    // from side exposure — a tall span against an empty neighbour emits its whole height.
    public static void forEachSurface(Map<Integer, Column> map, SurfaceVisitor v) {
        for (Map.Entry<Integer, Column> e : map.entrySet()) {
            int x = keyX(e.getKey()), z = keyZ(e.getKey());
            Column xp = map.get(packKey(x + 1, z));
            Column xm = map.get(packKey(x - 1, z));
            Column zp = map.get(packKey(x, z + 1));
            Column zm = map.get(packKey(x, z - 1));
            Column c = e.getValue();
            for (int i = 0; i < c.spans().length; i++) {
                int top = SpanMath.spanTop(c.spans()[i]);
                int bottom = SpanMath.spanBottom(c.spans()[i]);
                int color = c.colors()[i];
                for (int y = bottom; y <= top; y++) {
                    int faces = 0;
                    if (!SpanMath.solidAt(xp, y)) faces |= 1;
                    if (!SpanMath.solidAt(xm, y)) faces |= 1 << 1;
                    if (y == top) faces |= 1 << 2;
                    if (y == bottom) faces |= 1 << 3;
                    if (!SpanMath.solidAt(zp, y)) faces |= 1 << 4;
                    if (!SpanMath.solidAt(zm, y)) faces |= 1 << 5;
                    if (faces != 0) v.cell(x, y, z, color, faces);
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*AbyssSpanStoreTest*' --tests '*SpanMathTest*'
```

Expected: PASS (15 tests total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/AbyssSpanStore.java src/test/java/com/mia/aperture/map/AbyssSpanStoreTest.java
git commit -m "feat(abyss-model): span store with snapshots, mips, and surface walk

```

---

### Task 3: VoxelCloud.fillInto + AbyssModelBuilder

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/VoxelCloud.java`
- Create: `src/main/java/com/mia/aperture/map/AbyssModelBuilder.java`

No unit tests (both touch Voxy). Verification = full build + Task 7 in-game. The `VoxelCloud`
change is a pure refactor: existing tests must still pass.

- [ ] **Step 1: Give `fill` caller-owned scratch and expose it**

In `src/main/java/com/mia/aperture/map/VoxelCloud.java`, find the private `fill` method:

```java
    private static void fill(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb) {
        long[] scratch = new long[32 * 32 * 32];
        // Per-call synthesis buffers (NOT static: fill() is also reached from the route worker,
        // so each caller must own its own). Lazily allocated, reused across every section.
        long[][] synth = new long[MAX_FINER_DEPTH][];
```

Replace the method signature and those first lines with:

```java
    private static void fill(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb) {
        fillInto(engine, colors, originCellX, originCellY, originCellZ, gX, gY, gZ, lvl,
                opaque, argb, new long[32 * 32 * 32], new long[MAX_FINER_DEPTH][]);
    }

    // As fill(), but with caller-owned scratch/synthesis buffers. The Abyss model builder probes
    // ~17k sections in a burst; a fresh 256 KB scratch per probe would be gigabytes of garbage.
    // Per-call buffers stay the rule for one-shot callers (route/corridor grids).
    public static void fillInto(WorldEngine engine, MapColorSource colors,
            int originCellX, int originCellY, int originCellZ, int gX, int gY, int gZ, int lvl,
            boolean[] opaque, int[] argb, long[] scratch, long[][] synth) {
```

The rest of the original method body stays exactly as it is (it already uses `scratch` and
`synth` locals — they simply become parameters).

- [ ] **Step 2: Verify the refactor compiles and existing tests pass**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Create the builder**

Create `src/main/java/com/mia/aperture/map/AbyssModelBuilder.java`:

```java
package com.mia.aperture.map;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

// Background builder for AbyssSpanStore: probes every LOD-4 section in the shifted column once
// (rate-limited so the game never hitches), converts solid cells to column spans, and publishes
// immutable snapshots as it goes — the whole-Abyss view fills in progressively. Sections near
// the player are re-probed on a slow cadence so newly explored terrain appears in the overview.
// All Voxy contact for the whole-Abyss feature is quarantined here.
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

    private static final Map<Integer, AbyssSpanStore.Column> working = new HashMap<>();
    private static final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private static int probed, total;
    private static long lastDirtyMs;
    private static boolean overflowLogged;
    private static Thread thread;

    private AbyssModelBuilder() {}

    public static synchronized void ensureStarted() {
        if (thread != null && thread.isAlive()) return;
        for (int sy = SEC_Y_MIN; sy <= SEC_Y_MAX; sy++) {
            for (int sz = SEC_XZ_MIN; sz <= SEC_XZ_MAX; sz++) {
                for (int sx = SEC_XZ_MIN; sx <= SEC_XZ_MAX; sx++) {
                    queue.add(new long[]{sx, sy, sz});
                }
            }
        }
        total = queue.size();
        probed = 0;
        thread = new Thread(AbyssModelBuilder::loop, "MIA-Abyss-Model");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
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
                    if (probed % PUBLISH_EVERY == 0) {
                        AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(working, probed, total));
                    }
                }
                if (did > 0 && queue.isEmpty()) {
                    AbyssSpanStore.publish(AbyssSpanStore.buildSnapshot(working, probed, total));
                }
                if (queue.isEmpty()) enqueueDirtyNearPlayer();
                Thread.sleep(queue.isEmpty() ? 250 : SLEEP_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                System.err.println("[MIA Maps] abyss model build failed: " + t);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
    }

    // One LOD-4 section -> spans. Clears the section's Y window first so a re-probe replaces
    // stale data instead of unioning with it (fresh terrain, removed blocks).
    private static void probeSection(WorldEngine engine, MapColorSource colors,
            int secX, int secY, int secZ,
            boolean[] opaque, int[] argb, long[] scratch, long[][] synth) {
        java.util.Arrays.fill(opaque, false);
        VoxelCloud.fillInto(engine, colors, secX * SEC_CELLS, secY * SEC_CELLS, secZ * SEC_CELLS,
                SEC_CELLS, SEC_CELLS, SEC_CELLS, LVL, opaque, argb, scratch, synth);
        int yTop = secY * SEC_CELLS + SEC_CELLS - 1, yBottom = secY * SEC_CELLS;
        for (int lz = 0; lz < SEC_CELLS; lz++) {
            for (int lx = 0; lx < SEC_CELLS; lx++) {
                int key = AbyssSpanStore.packKey(secX * SEC_CELLS + lx, secZ * SEC_CELLS + lz);
                AbyssSpanStore.Column col = SpanMath.clearRange(working.get(key), yTop, yBottom);
                int runTop = -1;
                for (int ly = SEC_CELLS - 1; ly >= 0; ly--) {
                    boolean solid = opaque[(ly * SEC_CELLS + lz) * SEC_CELLS + lx];
                    if (solid && runTop < 0) runTop = ly;
                    if ((!solid || ly == 0) && runTop >= 0) {
                        int runBottom = solid ? ly : ly + 1;
                        col = SpanMath.insertRun(col, secY * SEC_CELLS + runTop, secY * SEC_CELLS + runBottom,
                                argb[(runTop * SEC_CELLS + lz) * SEC_CELLS + lx]);
                        runTop = -1;
                    }
                }
                if (col == null) working.remove(key);
                else if (working.size() < MAX_COLUMNS || working.containsKey(key)) working.put(key, col);
                else if (!overflowLogged) {
                    overflowLogged = true;
                    System.err.println("[MIA Maps] abyss model column cap hit — coordinate bug?");
                }
            }
        }
    }

    // Newly explored terrain: re-probe sections near the player every DIRTY_PERIOD_MS. Polling
    // is enough for an overview; there is no event hook into Voxy ingestion.
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

- [ ] **Step 4: Build**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mia/aperture/map/VoxelCloud.java src/main/java/com/mia/aperture/map/AbyssModelBuilder.java
git commit -m "feat(abyss-model): background builder probing native LOD-4 into the span store

```

---

### Task 4: MapSettings "Whole Abyss" step + revised tests

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapSettings.java`
- Modify: `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`
- Test: `src/test/java/com/mia/aperture/map/MapSettingsTest.java`

The existing tests `orbitAreaClampsOutOfRange` and `orbitAreaStopsAtWhatVoxyCanActuallyFeed`
deliberately pin 4096 as the top step — they guard the live-sampler cap. This task revises them
**consciously**: the live cap still stands; the new step is cache-backed, not live-sampled.

- [ ] **Step 1: Update the tests first**

In `src/test/java/com/mia/aperture/map/MapSettingsTest.java`, replace the two tests
`orbitAreaClampsOutOfRange` and `orbitAreaStopsAtWhatVoxyCanActuallyFeed` with:

```java
    @Test
    void orbitAreaClampsOutOfRange() {
        MapSettings s = new MapSettings();
        s.setOrbitAreaBlocks(0);
        assertEquals(1024, s.orbitAreaBlocks);   // below the lowest step
        s.setOrbitAreaBlocks(99999);
        assertEquals(MapSettings.ORBIT_AREA_WHOLE, s.orbitAreaBlocks); // above the highest step
    }

    @Test
    void liveStepsStopAt4096AndWholeAbyssIsCacheBacked() {
        // Voxy hard-codes MAX_LOD_LAYER = 4 and never stores coarser, so 4096 remains the widest
        // LIVE-sampled view (2048 native + one synthesis step) — that cap is still principled.
        // ORBIT_AREA_WHOLE is different in kind: OrbitScene renders it from AbyssSpanStore's
        // background-built cache, never from live sampling. See the 2026-07-16 whole-abyss spec.
        int[] steps = MapSettings.ORBIT_AREA_STEPS;
        assertArrayEquals(new int[]{1024, 2048, 4096, MapSettings.ORBIT_AREA_WHOLE}, steps);
        assertEquals(4096, steps[steps.length - 2], "widest live step");
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew test --tests '*MapSettingsTest*'
```

Expected: FAIL — `cannot find symbol: ORBIT_AREA_WHOLE`.

- [ ] **Step 3: Implement the setting**

In `src/main/java/com/mia/aperture/map/MapSettings.java`, replace:

```java
    // Capped at 4096 by Voxy's data, not by our rendering. Voxy hard-codes MAX_LOD_LAYER = 4
    // (16-block cells) and never builds anything coarser, so with the sampler's 128-cell grid
    // 2048 blocks is the widest NATIVE view (128 x 16). 4096 still works because we synthesize
    // level 5 from level 4 in one cheap step; 8192/16384 needed 2-3 levels of synthesis and came
    // back expensive and mostly empty, so they were removed rather than left as false promises.
    public static final int[] ORBIT_AREA_STEPS = {1024, 2048, 4096};
```

with:

```java
    // 4096 is still the widest LIVE-sampled view: Voxy hard-codes MAX_LOD_LAYER = 4 (16-block
    // cells) and never builds coarser, so 2048 is native and 4096 one cheap synthesis step —
    // deeper live synthesis was removed as slow and mostly empty. ORBIT_AREA_WHOLE is different
    // in kind: the whole mapped column rendered from AbyssSpanStore's background-built cache
    // (native LOD-4 reads, offline mips), never from live sampling.
    public static final int ORBIT_AREA_WHOLE = 16384;
    public static final int[] ORBIT_AREA_STEPS = {1024, 2048, 4096, ORBIT_AREA_WHOLE};
```

- [ ] **Step 4: Run the tests**

```bash
./gradlew test --tests '*MapSettingsTest*'
```

Expected: PASS. (`orbitAreaSnapsToNearestStep` still passes: 5000 is nearer 4096 than 16384.)

- [ ] **Step 5: Label the step in the slider**

In `src/main/java/com/mia/aperture/client/MapSettingsScreen.java`, replace:

```java
    private static Component orbitAreaLabel() {
        return Component.literal("3D Area: " + settings().orbitAreaBlocks + " blocks");
    }
```

with:

```java
    private static Component orbitAreaLabel() {
        int b = settings().orbitAreaBlocks;
        return Component.literal(b == MapSettings.ORBIT_AREA_WHOLE
                ? "3D Area: Whole Abyss" : "3D Area: " + b + " blocks");
    }
```

- [ ] **Step 6: Build and commit**

```bash
./gradlew build
git add src/main/java/com/mia/aperture/map/MapSettings.java src/main/java/com/mia/aperture/client/MapSettingsScreen.java src/test/java/com/mia/aperture/map/MapSettingsTest.java
git commit -m "feat(3d): Whole Abyss step on the 3D Area slider

```

---

### Task 5: OrbitScene — render the cache at the Whole Abyss step

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/OrbitScene.java`

No unit test (Voxy/Minecraft-facing worker). Verified by build + Task 7.

- [ ] **Step 1: Zoom ceiling for the whole column**

In `src/main/java/com/mia/aperture/map/OrbitScene.java`, replace:

```java
    // Highest zoom that keeps the sampled area within `areaBlocks` (extentXZ = EXTENT * zoom).
    public static double maxZoom(int areaBlocks) {
        return Math.max(1.0, areaBlocks / (double) EXTENT);
    }
```

with:

```java
    // Highest zoom that keeps the sampled area within `areaBlocks` (extentXZ = EXTENT * zoom).
    // The Whole Abyss step must frame the full ~8k-block column vertically, so its ceiling comes
    // from the band height rather than a horizontal area.
    public static double maxZoom(int areaBlocks) {
        if (areaBlocks == MapSettings.ORBIT_AREA_WHOLE) {
            return Math.ceil((MapGeometry.ABYSS_SHIFTED_Y_TOP - MapGeometry.ABYSS_SHIFTED_Y_BOTTOM + 512)
                    / (double) EXTENT);
        }
        return Math.max(1.0, areaBlocks / (double) EXTENT);
    }
```

- [ ] **Step 2: Whole-mode flag in the frame signature**

Replace the `computeSig` method:

```java
    private static long computeSig(OrbitCamera cam, double zoom, MapSettings.OrbitQuality quality) {
        int sector = AbyssUtil.getSection(cam.focusX);
        int fx = (int) Math.floor(cam.focusX - (double) (sector << 14));
        int fy = (int) Math.floor(cam.focusY + (240 - sector * 30) * 16.0);
        int fz = (int) Math.floor(cam.focusZ);
        int extentXZ = Math.max(16, (int) Math.round(EXTENT * zoom));
        return Objects.hash(fx, fy, fz, extentXZ, desiredTex,
                (int) Math.round(cam.yawDeg), (int) Math.round(cam.pitchDeg), (int) Math.round(cam.distance),
                xrayMode.ordinal());
    }
```

with:

```java
    private static boolean wholeMode() {
        return com.mia.aperture.client.MiaApertureModClient.mapSettings.orbitAreaBlocks
                == MapSettings.ORBIT_AREA_WHOLE;
    }

    private static long computeSig(OrbitCamera cam, double zoom, MapSettings.OrbitQuality quality) {
        int sector = AbyssUtil.getSection(cam.focusX);
        int fx = (int) Math.floor(cam.focusX - (double) (sector << 14));
        int fy = (int) Math.floor(cam.focusY + (240 - sector * 30) * 16.0);
        int fz = (int) Math.floor(cam.focusZ);
        int extentXZ = Math.max(16, (int) Math.round(EXTENT * zoom));
        boolean whole = wholeMode();
        // In whole mode the frame depends on the cache generation, not the sampled region —
        // a new snapshot (progressive build, dirty refresh) must re-rasterize.
        long snapSeq = whole ? AbyssSpanStore.current().seq() : 0;
        return Objects.hash(fx, fy, fz, extentXZ, desiredTex,
                (int) Math.round(cam.yawDeg), (int) Math.round(cam.pitchDeg), (int) Math.round(cam.distance),
                xrayMode.ordinal(), whole, snapSeq);
    }
```

- [ ] **Step 3: Build the cloud from the cache in whole mode**

In `buildFrame`, the current sampling block is:

```java
        long cs = Objects.hash(shiftedFocusX, shiftedFocusY, focusZ, extentXZ, extentUp, extentDown, lvl);
        if (cloud == null || cs != cloudSig) {
            cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, shiftedFocusY, focusZ,
                    extentXZ, extentUp, extentDown, lvl, quality.maxPoints);
            cloudSig = cs;
            cloudSize = cloud.size();
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (VoxelCloud.Point p : cloud) {
                int y = (int) p.y();
                if (y < lo) lo = y;
                if (y > hi) hi = y;
            }
            statVoxMinY = lo;
            statVoxMaxY = hi;
        }
```

Replace it with:

```java
        boolean whole = wholeMode();
        long cs = whole
                ? Objects.hash(0x5EAB, AbyssSpanStore.current().seq(), quality.maxPoints)
                : Objects.hash(shiftedFocusX, shiftedFocusY, focusZ, extentXZ, extentUp, extentDown, lvl);
        if (cloud == null || cs != cloudSig || whole != cloudWhole) {
            if (whole) {
                AbyssModelBuilder.ensureStarted();
                cloud = buildWholeCloud(quality.maxPoints);
            } else {
                cloud = VoxelCloud.sample(engine, colors, shiftedFocusX, shiftedFocusY, focusZ,
                        extentXZ, extentUp, extentDown, lvl, quality.maxPoints);
            }
            cloudWhole = whole;
            cloudSig = cs;
            cloudSize = cloud.size();
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (VoxelCloud.Point p : cloud) {
                int y = (int) p.y();
                if (y < lo) lo = y;
                if (y > hi) hi = y;
            }
            statVoxMinY = lo;
            statVoxMaxY = hi;
        }
        // Stats must reflect the cache on EVERY frame, not only on cloud rebuilds — the shared
        // assignments a few lines above are live-sampler values and would flicker back in
        // between rebuilds while orbiting.
        if (whole) {
            statLvl = 4 + wholeLevel;
            statBandLo = MapGeometry.ABYSS_SHIFTED_Y_BOTTOM;
            statBandHi = MapGeometry.ABYSS_SHIFTED_Y_TOP;
        }
```

Then add, right after the `private static List<VoxelCloud.Point> cloud;` field near the top:

```java
    private static boolean cloudWhole;
    private static int wholeLevel;
```

And add this method immediately after `buildFrame`:

```java
    // Whole-Abyss cloud: read the cached span model instead of sampling Voxy. Picks the finest
    // mip whose surface count fits the quality tier's point budget (64-block cells are at or
    // below one screen pixel at full zoom-out, so coarseness is invisible there), hard-capping
    // at the budget if even the coarsest level exceeds it.
    private static List<VoxelCloud.Point> buildWholeCloud(int maxPoints) {
        AbyssSpanStore.Snapshot snap = AbyssSpanStore.current();
        int level = AbyssSpanStore.LEVELS - 1;
        for (int l = 0; l < AbyssSpanStore.LEVELS; l++) {
            if (snap.surfaceCounts()[l] <= maxPoints) { level = l; break; }
        }
        int cellSize = AbyssSpanStore.cellSize(level);
        java.util.ArrayList<VoxelCloud.Point> pts = new java.util.ArrayList<>(
                Math.min(maxPoints, snap.surfaceCounts()[level]));
        AbyssSpanStore.forEachSurface(snap.level(level), (x, y, z, color, faces) -> {
            if (pts.size() >= maxPoints) return;
            pts.add(new VoxelCloud.Point(
                    (x + 0.5) * cellSize, (y + 0.5) * cellSize, (z + 0.5) * cellSize,
                    color, cellSize, 0f, 1f, 0f, faces, false));
        });
        wholeLevel = level;
        return pts;
    }
```

- [ ] **Step 4: Force X-ray off in whole mode**

In `rasterizeInto`, replace:

```java
        XrayMode mode = xrayMode;
```

with:

```java
        // The cave classifier is a live-sampler feature; cache points carry covered=false, so in
        // whole mode CAVE_ONLY would render nothing and GHOST everything translucent. Force OFF.
        XrayMode mode = cloudWhole ? XrayMode.OFF : xrayMode;
```

- [ ] **Step 5: Build and commit**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
git add src/main/java/com/mia/aperture/map/OrbitScene.java
git commit -m "feat(3d): render the whole-Abyss cache at the new area step

```

---

### Task 6: OrbitView HUD + HelpContent

**Files:**
- Modify: `src/main/java/com/mia/aperture/client/OrbitView.java`
- Modify: `src/main/java/com/mia/aperture/map/HelpContent.java`
- Test: `src/test/java/com/mia/aperture/map/HelpContentTest.java`

- [ ] **Step 1: X-ray HUD label + cache stats line**

In `src/main/java/com/mia/aperture/client/OrbitView.java`, replace:

```java
        String xrayLabel = switch (OrbitScene.xrayMode()) {
            case OFF -> "X-ray: off";
            case GHOST -> "X-ray: ghost shell";
            case CAVE_ONLY -> "X-ray: caves only";
        };
        guiGraphics.drawString(this.font, xrayLabel + "  (X)", 8, 20, 0xFF88FFFF);
```

with:

```java
        boolean whole = MiaApertureModClient.mapSettings.orbitAreaBlocks
                == com.mia.aperture.map.MapSettings.ORBIT_AREA_WHOLE;
        String xrayLabel = whole ? "X-ray: n/a (whole Abyss)" : switch (OrbitScene.xrayMode()) {
            case OFF -> "X-ray: off";
            case GHOST -> "X-ray: ghost shell";
            case CAVE_ONLY -> "X-ray: caves only";
        };
        guiGraphics.drawString(this.font, xrayLabel + "  (X)", 8, 20, 0xFF88FFFF);
```

Then, inside the existing `if (MiaApertureModClient.mapSettings.orbitStats) {` block, after the
existing `guiGraphics.drawString(...)` call ending at `8, 56, 0xFFFF66FF);`, add:

```java
            if (whole) {
                var snap = com.mia.aperture.map.AbyssSpanStore.current();
                String state = snap.probedSections() < snap.totalSections()
                        ? "building " + (100 * snap.probedSections() / Math.max(1, snap.totalSections())) + "%"
                        : "built " + ((System.currentTimeMillis() - snap.builtAtMs()) / 1000) + "s ago";
                guiGraphics.drawString(this.font,
                        "cache " + snap.base().size() + " cols  surf " + snap.surfaceCounts()[0]
                                + "  " + state,
                        8, 68, 0xFFFF66FF);
            }
```

- [ ] **Step 2: Help content + test**

In `src/test/java/com/mia/aperture/map/HelpContentTest.java`, add a test (before the final `}`;
adjust nothing else unless an existing count assertion fails, in which case update that count):

```java
    @Test
    void threeDTabDocumentsTheWholeAbyssStep() {
        boolean found = HelpContent.lines(HelpContent.Tab.THREED, a -> a).stream()
                .anyMatch(l -> l.text() != null && l.text().contains("Whole Abyss"));
        assertTrue(found, "3D tab must document the Whole Abyss area step");
    }
```

In `src/main/java/com/mia/aperture/map/HelpContent.java`, in the `THREED` case, after the line
`o.add(text("Detail is controlled by Orbit Quality in Settings."));`, add:

```java
                o.add(h("Whole Abyss"));
                o.add(text("Set 3D Area to \"Whole Abyss\" to see every mapped layer at once."));
                o.add(text("A cached model builds in the background - the view fills in over a"));
                o.add(text("few seconds and picks up newly explored terrain shortly after."));
                o.add(text("X-ray is unavailable at this zoom."));
```

- [ ] **Step 3: Run the tests, build, commit**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
git add src/main/java/com/mia/aperture/client/OrbitView.java src/main/java/com/mia/aperture/map/HelpContent.java src/test/java/com/mia/aperture/map/HelpContentTest.java
git commit -m "feat(3d): whole-Abyss HUD state + Help documentation

```

Expected before commit: BUILD SUCCESSFUL, all tests green.

---

### Task 7: Install and verify in game

**Files:** none (verification only)

- [ ] **Step 1: Build and install**

```bash
export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"
./gradlew build
cp build/libs/mia-maps-0.1.8-beta.jar "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/"
ls -la "C:/Users/dev.000/AppData/Roaming/ModrinthApp/profiles/Mine In Abyss Modpack/mods/" | grep mia-maps
```

Expected: exactly one `mia-maps-*.jar`.

- [ ] **Step 2: Whole Abyss appears**

Restart the client, open the 3D view, Settings → 3D Area → far right ("Whole Abyss"), zoom out.

Expected: the column fills in progressively over a few seconds (watch "building N%" with 3D
Stats on) until the whole mapped Abyss — Orth at the top through every explored layer — is one
orbitable picture. X-ray line reads "n/a (whole Abyss)".

- [ ] **Step 3: Overlays land across layers**

With waypoints on multiple layers and a route/corridor active:

Expected: all waypoint markers sit on their terrain (depth-tagged ones below you), the corridor
trail threads the column, and the player marker is at your spot. No marker flung sideways.

- [ ] **Step 4: Freshness + regression**

Walk into unmapped terrain for a minute, wait ~20 s, re-check the whole view — the new terrain
should appear. Then set 3D Area back to 2048/4096: the live view must behave exactly as before
(X-ray works again, detail is fine near you).

- [ ] **Step 5: Report findings**

Report each step's outcome. If the column has holes where you know terrain is mapped, capture
the 3D Stats line (level + cache counts) — that distinguishes a builder gap (cols/surf too low)
from a budget cap (pts CAPPED).

---

## Notes for the implementer

- **Do not** create a branch or worktree. Work on `main`.
- **No narrating inline comments** — comments explain constraints and why.
- `SpanMath` / `AbyssSpanStore` must not import `me.cortex.voxy.*` or `net.minecraft.*`; the
  builder and OrbitScene are the only Voxy-facing pieces.
- The builder publishes **immutable snapshots**; never hand `working` itself to the store.
- `VoxelCloud.sample`'s static scratch is single-thread-only (documented invariant); the builder
  must NOT call `sample` — only `fillInto` with its own buffers.
- Whole mode deliberately renders from cache even when zoomed in; flipping the slider back is
  the way to live detail. Do not add hybrid live/cache blending — YAGNI.
