# Map Colours & Resolution Implementation Plan

> **Project convention:** work directly on `main` in `<project-root>` — NO worktrees, NO branches.

**Goal:** Replace the vanilla MapColor palette with resource-pack-accurate, per-biome-tinted colours sampled from the block atlas, and raise the fullscreen map to 2048² composed on-change, per `docs/plans/specs/2026-07-08-map-colours-and-resolution-design.md`.

**Architecture:** A render-thread colour bake (`BlockColorBake`) averages each block's top/side atlas sprite into an immutable table indexed by Voxy `blockId`; a `BiomeTintResolver` supplies per-biome grass/foliage/water tints; `VoxyColorSource` combines them (extracting block+biome from the mapping id) behind the existing `MapColorSource` interface, which grows a `Face` argument. The pure `MapTileRenderer` and the cache/worker are unchanged except for the `Face` plumbing and a tiles-completed counter. `MapCompositor` grows to 2048² and recomposes only on change.

**Tech Stack:** Java 21, Fabric/loom, Mojang mappings (MC 1.21.11), Voxy MIA-edition API, JUnit 5.

**Build/test (always the vendored JDK):**
```powershell
cd "<project-root>"
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew test    # unit tests
.\gradlew build   # full build incl. mod jar
```

**Confirmed API facts (from spec + javap 2026-07-08):**
- `Minecraft.getModelManager().getBlockModelShaper()` → `getParticleIcon(BlockState)` : `TextureAtlasSprite`; `getBlockModel(BlockState)` : `BlockStateModel`.
- `TextureAtlasSprite.contents()` : `SpriteContents`; `SpriteContents.width()` / `.height()` are public; the pixel `NativeImage` (`originalImage`) is **private** — access method is resolved in Task 1.
- `NativeImage.getPixel(int,int)` : int (channel order confirmed in Task 1); `setPixel(int,int,int)` already used by the compositor.
- `BiomeSpecialEffects` is a record with public `waterColor()` (plain int, always present), `grassColorOverride()`/`foliageColorOverride()` (`Optional<Integer>`), `grassColorModifier()`.
- `Voxy Mapper`: `getBlockStateCount()`, `getBlockStateFromBlockId(int)`, `static getBlockId(long)`, `static getBiomeId(long)`; biomeId→biome reverse path resolved in Task 1.

---

### Task 1: API spike — resolve the three uncertain access paths

**Files:**
- Create: `docs/plans/notes/2026-07-08-colour-api-findings.md`

No production code. Decompile the shipped jars and MC classes to lock the exact calls the later tasks depend on. The controller threads these findings into Tasks 3–6.

- [ ] **Step 1: Sprite pixel access + channel order.** Determine how to read pixels from a `SpriteContents`. Preferred: reflection on the private `originalImage` field (`com.mojang.blaze3d.platform.NativeImage`), then `NativeImage.getPixel(x,y)`. Verify with javap on the shipped MC jar:
```bash
S="$HOME/AppData/Local/Temp/colourspike"; mkdir -p "$S" && cd "$S"
JAR="<home>/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-54f2c19a0c/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-merged-54f2c19a0c-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar"
JP="<project-root>/libs/jdk21/jdk-21.0.11+10/bin/javap.exe"
unzip -o -q "$JAR" "net/minecraft/client/renderer/texture/SpriteContents.class" "com/mojang/blaze3d/platform/NativeImage.class"
"$JP" -p "net/minecraft/client/renderer/texture/SpriteContents.class" | grep -iE "NativeImage|originalImage|image"
"$JP" -p -c "com/mojang/blaze3d/platform/NativeImage.class" | grep -A3 -iE "public int getPixel|getPixelABGR"
```
Record: the exact field name/type to reflect, and whether `getPixel` returns ABGR (`0xAABBGGRR`) or ARGB. NativeImage in modern MC stores ABGR — confirm and record the conversion to ARGB (swap R/B).

- [ ] **Step 2: Up/side face sprite from a model.** Determine how to get an up-face and a side-face sprite from `BlockStateModel` in 1.21.11 (quads carry a `TextureAtlasSprite`). If clean extraction isn't available, the fallback for BOTH faces is `getParticleIcon(state)`. Record the exact method(s) or "particle-only fallback".
```bash
"$JP" -p net/minecraft/client/renderer/block/model/BlockStateModel.class 2>/dev/null | grep -iE "public|quad|sprite|particle" | head
```

- [ ] **Step 3: Tint-type detection.** Determine how to tell whether a block is biome-tinted and for which category. Check `Minecraft.getBlockColors()` (`BlockColors`) for a per-block provider registry, and/or the block's tint via `BlockColors.getColor(state, level, pos, tintIndex)`. Record how to classify a block as GRASS / FOLIAGE / WATER / NONE without a live position (e.g. water = `state.getFluidState().is(FluidTags.WATER)`; grass/foliage via `BlockColors` provider presence or a small block/tag check). Record the chosen classification rule.

- [ ] **Step 4: biomeId → tint colours.** Determine how to go from Voxy's `biomeId` to a `Biome`/`Holder<Biome>` and then to grass/foliage/water tint colours. Check Voxy `Mapper` for a reverse biome lookup; check `Biome` for `getWaterColor()`/`getFoliageColor()`/`getGrassColor(double,double)` or access to `BiomeSpecialEffects`. Record: the reverse-lookup call, the three tint accessors (or the override-else-climate-colormap path for grass/foliage), and note `waterColor` is always present.
```bash
unzip -o -q "<mods-dir>/voxy-mia-edition-2.15-fcd6dda.jar" "me/cortex/voxy/common/world/other/Mapper.class"
"$JP" -p me/cortex/voxy/common/world/other/Mapper.class | grep -iE "biome|Holder"
unzip -o -q "$JAR" "net/minecraft/world/level/biome/Biome.class"
"$JP" -p net/minecraft/world/level/biome/Biome.class | grep -iE "grass|foliage|water|SpecialEffects|getSpecialEffects"
```

- [ ] **Step 5: Write findings + commit.** Record every confirmed signature/decision in `docs/plans/notes/2026-07-08-colour-api-findings.md` under headings: Sprite pixels & channel order; Face sprites; Tint-type rule; Biome tint resolution. For any path that turns out unavailable, record the graceful fallback to use. Commit:
```bash
git add docs/plans/notes/2026-07-08-colour-api-findings.md
git commit -m "docs(map): colour/biome/atlas API spike findings"
```

**Report back:** paste the four findings verbatim — Tasks 3–6 are written against them and the controller will reconcile any differences before dispatch.

---

### Task 2: `Face` enum + `MapColorSource` gains a face; thread through the renderer

**Files:**
- Create: `src/main/java/com/mia/aperture/map/Face.java`
- Modify: `src/main/java/com/mia/aperture/map/MapColorSource.java`
- Modify: `src/main/java/com/mia/aperture/map/MapTileRenderer.java`
- Test: `src/test/java/com/mia/aperture/map/MapTileRendererTest.java`

- [ ] **Step 1: Update the test fake to the new signature (fail first).** In `MapTileRendererTest.java`, replace the `colors` field's anonymous `MapColorSource` so `baseColor` takes a `Face`:
```java
    private final MapColorSource colors = new MapColorSource() {
        @Override public int baseColor(long id, Face face) { return id == 1 ? STONE : id == 2 ? WATER : 0; }
        @Override public boolean isWater(long id) { return id == 2; }
        @Override public boolean isOpaque(long id) { return id == 1 || id == 2; }
    };
```
(No assertion changes — every existing behavioural assertion stands.)

- [ ] **Step 2: Run tests to verify they fail.** Run `.\gradlew test`. Expected: compile failure — `MapColorSource`/`MapTileRenderer` don't yet know `Face`.

- [ ] **Step 3: Create `Face`.**
```java
package com.mia.aperture.map;

public enum Face {
    TOP,
    SIDE
}
```

- [ ] **Step 4: Update `MapColorSource`.**
```java
package com.mia.aperture.map;

public interface MapColorSource {
    int baseColor(long mappingId, Face face);
    boolean isWater(long mappingId);
    boolean isOpaque(long mappingId);
}
```

- [ ] **Step 5: Pass `Face.TOP` at every `baseColor` call in `MapTileRenderer`.** Change line 54 `colors.baseColor(id)` → `colors.baseColor(id, Face.TOP)`; line 57 `base = colors.baseColor(id);` → `base = colors.baseColor(id, Face.TOP);`; inside `waterColor` line 96 `floorColor = colors.baseColor(id);` → `floorColor = colors.baseColor(id, Face.TOP);`. No other logic changes.

- [ ] **Step 6: Run tests to verify they pass.** Run `.\gradlew test`. Expected: BUILD SUCCESSFUL, 19 tests pass.

- [ ] **Step 7: Commit.**
```bash
git add src/main/java/com/mia/aperture/map src/test
git commit -m "refactor(map): MapColorSource.baseColor takes a Face; renderer passes TOP"
```

---

### Task 3: `ColorMath` — pure averaging + tint multiply

**Files:**
- Create: `src/main/java/com/mia/aperture/map/ColorMath.java`
- Test: `src/test/java/com/mia/aperture/map/ColorMathTest.java`

Pure integer colour math the bake and tint resolver delegate to. Inputs/outputs are ARGB
ints; callers convert at the NativeImage/atlas boundary (per Task 1 channel finding).

- [ ] **Step 1: Write the failing tests.**
```java
package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColorMathTest {

    @Test
    void averageOfSolidOpaqueIsThatColour() {
        int[] px = { 0xFF804020, 0xFF804020, 0xFF804020, 0xFF804020 };
        assertEquals(0xFF804020, ColorMath.alphaWeightedAverage(px));
    }

    @Test
    void fullyTransparentPixelsAreIgnored() {
        // one opaque red + three fully transparent -> opaque red, not diluted
        int[] px = { 0xFFFF0000, 0x00000000, 0x00000000, 0x00000000 };
        int avg = ColorMath.alphaWeightedAverage(px);
        assertEquals(255, (avg >> 24) & 0xFF);
        assertEquals(255, (avg >> 16) & 0xFF);
        assertEquals(0, (avg >> 8) & 0xFF);
        assertEquals(0, avg & 0xFF);
    }

    @Test
    void allTransparentYieldsZero() {
        int[] px = { 0x00112233, 0x00445566 };
        assertEquals(0, ColorMath.alphaWeightedAverage(px));
    }

    @Test
    void averageMixesRgbByAlphaWeight() {
        // half-alpha black + full-alpha white -> weighted toward white (2/3)
        int[] px = { 0x80000000, 0xFFFFFFFF };
        int avg = ColorMath.alphaWeightedAverage(px);
        int r = (avg >> 16) & 0xFF;
        assertTrue(r > 160 && r < 180, "expected ~170, got " + r);
    }

    @Test
    void tintMultiplyScalesChannels() {
        // grey base * green tint -> green-dominant, alpha preserved from base
        int base = 0xFF808080;
        int tint = 0x40FF40; // RGB tint (no alpha significance)
        int out = ColorMath.tintMultiply(base, tint);
        assertEquals(0xFF, (out >> 24) & 0xFF);
        assertEquals(0x80, (out >> 16) & 0xFF); // 0x80*0x40/0xFF = 32 -> wait see impl
    }
}
```
Note: the last assertion's exact value depends on the multiply definition; set it after Step 3 to the true computed value (multiply is `a*b/255` per channel). Keep the test — just pin the number to the real output.

- [ ] **Step 2: Run tests to verify they fail.** `.\gradlew test` → compile failure (`ColorMath` missing).

- [ ] **Step 3: Implement.**
```java
package com.mia.aperture.map;

public final class ColorMath {
    private ColorMath() {}

    // ARGB pixels; fully-transparent pixels contribute nothing. Returns 0 if total alpha 0.
    public static int alphaWeightedAverage(int[] argbPixels) {
        long wr = 0, wg = 0, wb = 0, wa = 0, aSum = 0;
        for (int p : argbPixels) {
            int a = (p >>> 24) & 0xFF;
            if (a == 0) continue;
            wr += (long) ((p >> 16) & 0xFF) * a;
            wg += (long) ((p >> 8) & 0xFF) * a;
            wb += (long) (p & 0xFF) * a;
            wa += a;
            aSum += a;
        }
        if (aSum == 0) return 0;
        int r = (int) (wr / wa);
        int g = (int) (wg / wa);
        int b = (int) (wb / wa);
        int a = (int) (aSum / argbPixels.length);
        // opaque result: map is not translucent-composited, alpha marks presence only
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // Per-channel base*tint/255; keeps base alpha. tint is 0xRRGGBB.
    public static int tintMultiply(int baseArgb, int tintRgb) {
        int a = (baseArgb >>> 24) & 0xFF;
        int r = (((baseArgb >> 16) & 0xFF) * ((tintRgb >> 16) & 0xFF)) / 255;
        int g = (((baseArgb >> 8) & 0xFF) * ((tintRgb >> 8) & 0xFF)) / 255;
        int b = ((baseArgb & 0xFF) * (tintRgb & 0xFF)) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
```

- [ ] **Step 4: Pin the tint test value + run.** With this multiply, `0x80*0x40/255 = 32 (0x20)`, `0x80*0xFF/255 = 128 (0x80)`, `0x80*0x40/255 = 32`. Set the last test's assertions to `0x20`, `0x80`, `0x20` for R,G,B. Run `.\gradlew test` → BUILD SUCCESSFUL, 23 tests.

- [ ] **Step 5: Commit.**
```bash
git add src/main/java/com/mia/aperture/map/ColorMath.java src/test
git commit -m "feat(map): pure colour math - alpha-weighted average and tint multiply"
```

---

### Task 4: `BlockColorBake` — render-thread atlas bake

**Files:**
- Create: `src/main/java/com/mia/aperture/map/BlockColorBake.java`

**Uses Task 1 findings** for sprite-pixel access, face-sprite selection, tint-type rule, and
NativeImage channel order. The code below is the intended shape; reconcile the marked calls
with the spike before implementing. No unit test (client-render bound; averaging is already
tested via `ColorMath`); verified in-game in Task 8.

- [ ] **Step 1: Implement.**
```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockColorBake {
    public static final int TINT_NONE = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_FOLIAGE = 2;
    public static final int TINT_WATER = 3;

    private int[] topColor = new int[0];
    private int[] sideColor = new int[0];
    private byte[] tintType = new byte[0];
    private boolean[] opaque = new boolean[0];
    private int bakedCount = 0;

    // Render thread only. Bakes any blockIds Voxy has registered beyond bakedCount.
    public void update(Mapper mapper) {
        int count = mapper.getBlockStateCount();
        if (count <= bakedCount) return;
        topColor = grow(topColor, count);
        sideColor = grow(sideColor, count);
        tintType = grow(tintType, count);
        opaque = grow(opaque, count);
        var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
        for (int id = bakedCount; id < count; id++) {
            try {
                BlockState state = mapper.getBlockStateFromBlockId(id);
                topColor[id] = averageSprite(topSprite(shaper, state));   // Task 1: face sprite
                sideColor[id] = averageSprite(sideSprite(shaper, state)); // Task 1: face sprite
                tintType[id] = classifyTint(state);                       // Task 1: tint rule
                opaque[id] = topColor[id] != 0;
            } catch (Throwable t) {
                topColor[id] = 0; sideColor[id] = 0; tintType[id] = TINT_NONE; opaque[id] = false;
            }
        }
        bakedCount = count;
    }

    public int top(int blockId) { return blockId < bakedCount ? topColor[blockId] : 0; }
    public int side(int blockId) { return blockId < bakedCount ? sideColor[blockId] : 0; }
    public int tint(int blockId) { return blockId < bakedCount ? tintType[blockId] : TINT_NONE; }
    public boolean opaque(int blockId) { return blockId < bakedCount && opaque[blockId]; }

    private static int averageSprite(TextureAtlasSprite sprite) {
        if (sprite == null) return 0;
        SpriteContents c = sprite.contents();
        int w = c.width(), h = c.height();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                px[y * w + x] = toArgb(readPixel(c, x, y)); // Task 1: reflection + channel order
        return ColorMath.alphaWeightedAverage(px);
    }

    // --- helpers reconciled with Task 1 ---
    private static TextureAtlasSprite topSprite(net.minecraft.client.renderer.block.BlockModelShaper s, BlockState st) {
        return s.getParticleIcon(st); // replace with up-face sprite if Task 1 found one
    }
    private static TextureAtlasSprite sideSprite(net.minecraft.client.renderer.block.BlockModelShaper s, BlockState st) {
        return s.getParticleIcon(st); // replace with side-face sprite if Task 1 found one
    }
    private static int readPixel(SpriteContents c, int x, int y) {
        return SpriteContentsAccess.getPixel(c, x, y); // Task 1 decides reflection helper location
    }
    private static int toArgb(int nativePixel) {
        // Task 1: if NativeImage.getPixel returns ABGR (0xAABBGGRR), swap R/B:
        int a = (nativePixel >>> 24) & 0xFF, b = (nativePixel >> 16) & 0xFF,
            g = (nativePixel >> 8) & 0xFF, r = nativePixel & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    private static byte classifyTint(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) return TINT_WATER;
        return (byte) classifyTintNonWater(state); // Task 1: grass/foliage detection rule
    }
    private static int classifyTintNonWater(BlockState state) { return TINT_NONE; }

    private static int[] grow(int[] a, int n) { int[] b = new int[n]; System.arraycopy(a,0,b,0,a.length); return b; }
    private static byte[] grow(byte[] a, int n) { byte[] b = new byte[n]; System.arraycopy(a,0,b,0,a.length); return b; }
    private static boolean[] grow(boolean[] a, int n) { boolean[] b = new boolean[n]; System.arraycopy(a,0,b,0,a.length); return b; }
}
```

- [ ] **Step 2: Add the pixel-access helper `SpriteContentsAccess`** in the same package, implementing the exact reflection/access decided in Task 1 (returns the block's pixel at x,y as the NativeImage raw int). If Task 1 found a non-reflection path, inline it and delete this helper reference.

- [ ] **Step 3: Build.** `.\gradlew build` → BUILD SUCCESSFUL (compiles against MC/Voxy; 23 tests still pass). If a signature from Task 1 differs, fix it here.

- [ ] **Step 4: Commit.**
```bash
git add src/main/java/com/mia/aperture/map/BlockColorBake.java src/main/java/com/mia/aperture/map/SpriteContentsAccess.java
git commit -m "feat(map): render-thread block colour bake from the atlas"
```

---

### Task 5: `BiomeTintResolver` — biomeId → grass/foliage/water tint

**Files:**
- Create: `src/main/java/com/mia/aperture/map/BiomeTintResolver.java`

**Uses Task 1 findings** for biomeId→biome and the tint accessors. Graceful default on any
failure. No unit test (registry-bound); verified in-game.

- [ ] **Step 1: Implement.**
```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;

import java.util.concurrent.ConcurrentHashMap;

public final class BiomeTintResolver {
    private static final int DEFAULT_GRASS = 0x91BD59;
    private static final int DEFAULT_FOLIAGE = 0x77AB2F;
    private static final int DEFAULT_WATER = 0x3F76E4;

    private final Mapper mapper;
    // key = biomeId, value = {grass, foliage, water}
    private final ConcurrentHashMap<Integer, int[]> cache = new ConcurrentHashMap<>();

    public BiomeTintResolver(Mapper mapper) {
        this.mapper = mapper;
    }

    public int tintFor(int biomeId, int tintType) {
        int[] t = cache.computeIfAbsent(biomeId, this::resolve);
        return switch (tintType) {
            case BlockColorBake.TINT_GRASS -> t[0];
            case BlockColorBake.TINT_FOLIAGE -> t[1];
            case BlockColorBake.TINT_WATER -> t[2];
            default -> 0xFFFFFF;
        };
    }

    private int[] resolve(int biomeId) {
        try {
            // Task 1: biomeId -> Biome, then grass/foliage/water tint colours.
            // waterColor is always present; grass/foliage use override-else-climate.
            return resolveFromBiome(biomeId);
        } catch (Throwable t) {
            return new int[]{DEFAULT_GRASS, DEFAULT_FOLIAGE, DEFAULT_WATER};
        }
    }

    private int[] resolveFromBiome(int biomeId) {
        // Reconciled with Task 1's confirmed reverse-lookup + accessors.
        return new int[]{DEFAULT_GRASS, DEFAULT_FOLIAGE, DEFAULT_WATER};
    }
}
```

- [ ] **Step 2: Fill `resolveFromBiome` with the confirmed Task 1 path** (biomeId → biome → grass/foliage/water). Keep the try/catch default fallback in `resolve` so unresolved biomes never break a cell.

- [ ] **Step 3: Build.** `.\gradlew build` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**
```bash
git add src/main/java/com/mia/aperture/map/BiomeTintResolver.java
git commit -m "feat(map): per-biome grass/foliage/water tint resolver with default fallback"
```

---

### Task 6: Rewire `VoxyColorSource`; wire the bake lifecycle into the compositor

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/VoxyColorSource.java`
- Modify: `src/main/java/com/mia/aperture/map/MapCompositor.java`

- [ ] **Step 1: Rewrite `VoxyColorSource`** to read the bake + tint instead of `getMapColor`. It receives an already-updated `BlockColorBake` and a `BiomeTintResolver` (both created/refreshed on the render thread by the compositor):
```java
package com.mia.aperture.map;

import me.cortex.voxy.common.world.other.Mapper;

public final class VoxyColorSource implements MapColorSource {
    private final BlockColorBake bake;
    private final BiomeTintResolver tints;

    public VoxyColorSource(BlockColorBake bake, BiomeTintResolver tints) {
        this.bake = bake;
        this.tints = tints;
    }

    @Override
    public int baseColor(long mappingId, Face face) {
        int blockId = Mapper.getBlockId(mappingId);
        int base = face == Face.SIDE ? bake.side(blockId) : bake.top(blockId);
        if (base == 0) return 0;
        int tintType = bake.tint(blockId);
        if (tintType == BlockColorBake.TINT_NONE) return base;
        int tint = tints.tintFor(Mapper.getBiomeId(mappingId), tintType);
        return ColorMath.tintMultiply(base, tint);
    }

    @Override
    public boolean isWater(long mappingId) {
        return bake.tint(Mapper.getBlockId(mappingId)) == BlockColorBake.TINT_WATER;
    }

    @Override
    public boolean isOpaque(long mappingId) {
        return bake.opaque(Mapper.getBlockId(mappingId));
    }
}
```

- [ ] **Step 2: Add bake/tint state to `MapCompositor`** (static fields) and refresh them on the render thread before composing:
```java
    private static final BlockColorBake BAKE = new BlockColorBake();
    private static BiomeTintResolver tintResolver;
```
In `compose`, replace `var colors = new VoxyColorSource(engine.getMapper(), mc.level);` with:
```java
        var mapper = engine.getMapper();
        BAKE.update(mapper); // render thread: bake any new blockIds
        if (tintResolver == null) tintResolver = new BiomeTintResolver(mapper);
        var colors = new VoxyColorSource(BAKE, tintResolver);
```

- [ ] **Step 3: Reset bake/tint on resource reload and disconnect.** In `MapCompositor.reset()`, add `tintResolver = null;` (bake is safe to keep; block ids are stable per session, but clear it too for a clean slate): call a new `BAKE.clear()` — add a `clear()` to `BlockColorBake` setting `bakedCount = 0` and arrays to length 0. This also covers resource-pack reloads reached via disconnect/reconnect; a dedicated resource-reload listener is optional and out of scope here.

- [ ] **Step 4: Build + tests.** `.\gradlew build` → BUILD SUCCESSFUL, 23 tests pass (the pure renderer tests use their own fake `MapColorSource`, unaffected).

- [ ] **Step 5: Commit.**
```bash
git add src/main/java/com/mia/aperture/map
git commit -m "feat(map): source colours from the atlas bake + biome tint"
```

---

### Task 7: 2048² resolution + recompose-on-change

**Files:**
- Modify: `src/main/java/com/mia/aperture/map/MapCompositor.java`
- Modify: `src/main/java/com/mia/aperture/map/MapWorker.java`

- [ ] **Step 1: Add a completed-tiles counter to `MapWorker`.** Add `public static final java.util.concurrent.atomic.AtomicInteger COMPLETED = new java.util.concurrent.atomic.AtomicInteger();` and bump it right after the guarded `CACHE.put` in `renderJob`:
```java
        if (job.generation() == GENERATION.get()) {
            CACHE.put(key, new MapTile(colors, heights, System.currentTimeMillis()));
            COMPLETED.incrementAndGet();
        }
```
Also reset it in `reset()`: `COMPLETED.set(0);`.

- [ ] **Step 2: Bump map size + replace the time gate with change detection in `MapCompositor`.** Change `MAP_SIZE` 512 → `2048`. Replace the `MAP_INTERVAL_MS` throttle in `composeMap` with a dirty check over (centerWorldX, centerWorldZ, blocksAcross, bandTopY, bandBottomY, mode, MapWorker.COMPLETED):
```java
    private static long lastMapSig;
    private static int lastCompletedSeen;

    public static void composeMap(double centerWorldX, double centerWorldZ,
                                  int blocksAcross, int bandTopY, int bandBottomY, MapMode mode) {
        long sig = java.util.Objects.hash((int) Math.floor(centerWorldX), (int) Math.floor(centerWorldZ),
                blocksAcross, bandTopY, bandBottomY, mode);
        int completed = MapWorker.COMPLETED.get();
        if (sig == lastMapSig && completed == lastCompletedSeen) return;
        lastMapSig = sig;
        lastCompletedSeen = completed;
        mapTexture = ensure(MAP_TEXTURE, mapTexture, MAP_SIZE);
        compose(mapTexture, MAP_SIZE, centerWorldX, centerWorldZ, blocksAcross,
                bandTopY, bandBottomY, mode);
    }
```
Keep `composeHud` as-is (2 Hz throttle, 128²). Remove the now-unused `MAP_INTERVAL_MS` and `lastMapCompose` fields.

- [ ] **Step 3: Build + tests.** `.\gradlew build` → BUILD SUCCESSFUL, 23 tests pass.

- [ ] **Step 4: Commit.**
```bash
git add src/main/java/com/mia/aperture/map
git commit -m "feat(map): 2048 fullscreen map composed on change, not on a timer"
```

---

### Task 8: v1.3.0 release + in-game verification

**Files:**
- Modify: `gradle.properties`, `README.md`, `project_memory.md`

- [ ] **Step 1: Bump version.** `gradle.properties`: `mod_version=1.3.0`.

- [ ] **Step 2: README.** In the Features section, note the map now uses resource-pack colours with per-biome tint and a high-resolution (2048²) fullscreen view. Update any `mia-aperture-mod-1.2.0.jar` reference to `1.3.0`.

- [ ] **Step 3: Build + install.**
```powershell
cd "<project-root>"
$env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"
.\gradlew build
Remove-Item "<mods-dir>\mia-aperture-mod-*.jar"
Copy-Item "build\libs\mia-aperture-mod-1.3.0.jar" "<mods-dir>\"
```
Verify exactly one `mia-aperture-mod-1.3.0.jar` in the mods folder.

- [ ] **Step 4: Owner in-game verification (report before finalizing):**
  1. Map colours match the resource pack (custom MIA blocks read correctly, not vanilla-palette flat).
  2. Grass/foliage/water are tinted; different layers/biomes show different greens/blues.
  3. Colours aren't R/B-swapped (if they are: the `toArgb` swap in `BlockColorBake` and/or `setPixel`→`setPixelABGR` in `MapCompositor` is the one-line fix — confirm against Task 1's channel finding).
  4. Fullscreen map is visibly sharper (2048²); pan/zoom/slice feel responsive (compose-on-change).
  5. No stale-frame lag when standing still (compose correctly idles) and tiles still fill in progressively as you explore (COMPLETED counter drives recompose).
  6. No crashes, no `[MIA Aperture]` error spam.

- [ ] **Step 5: Update `project_memory.md`.** Under section 4, add a short v1.3.0 entry: resource-pack colours (atlas bake, top+side, per-biome tint via `BiomeTintResolver`), 2048² compose-on-change; note the colour-API findings doc `docs/plans/notes/2026-07-08-colour-api-findings.md`; note side-view colour groundwork (side colours baked, unused) per spec §10.

- [ ] **Step 6: Commit + push.**
```bash
git add -A
git commit -m "chore(release): v1.3.0 - resource-pack colours + 2048 map"
git push origin main
```

---

## Self-Review Notes

- **Spec coverage:** atlas bake top+side (T4), alpha-weighted average (T3), tint types + per-biome tint (T4 classify + T5 resolve + T6 apply), Face plumbing (T2), 2048 + recompose-on-change (T7), bake lifecycle on render thread growing with block count (T4 `update` + T6 wiring), graceful tint fallback (T5), retire MapColor (T6 rewrite removes it), V-toggle unchanged meaning (no task needed — `MapMode` values kept, only their colour base changed), side-view groundwork (side colours baked T4, recorded T8 memo). API-risk items front-loaded (T1).
- **Placeholder honesty:** Tasks 4 & 5 contain marked reconciliation points (`// Task 1: ...`) rather than invented signatures, because the sprite-pixel/biome-tint APIs are genuinely unverified until the spike; the controller threads Task 1's confirmed calls in at dispatch. Pure tasks (2, 3, 7) and wiring (6) are fully concrete.
- **Type consistency:** `BlockColorBake` tint constants (`TINT_NONE/GRASS/FOLIAGE/WATER`) referenced identically in T5 and T6; `MapColorSource.baseColor(long, Face)` consistent T2→T6; `MapWorker.COMPLETED` consistent T7; `VoxyColorSource(BlockColorBake, BiomeTintResolver)` constructor consistent T6.
- **Test count:** 19 → 23 after Task 3 (4 ColorMath tests); Task 2 keeps 19 green via signature-only test change.
