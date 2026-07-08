# Colour/Biome/Atlas API Spike Findings (Task 1)

MC 1.21.11, official Mojang mappings (loom). javap on the merged jar shows
intermediary names for return/param types not spelled out on the class we
named directly (`class_XXXX`/`method_XXXX`) — expected; loom remaps these at
build time. Where a Mojang name for such a type/method could be inferred
(from field names, known constants, or the project's own already-compiled
source, which itself is proof of the deobfuscated name) it is stated below
with the confidence level noted.

Tools used:
```
JP="<project-root>/libs/jdk21/jdk-21.0.11+10/bin/javap.exe"
MCJAR=".../minecraft-merged-54f2c19a0c-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar"
VOXYJAR=".../voxy-mia-edition-2.15-fcd6dda.jar"
```
Scratch dir: `<temp>\colourspike`

---

## A. Sprite pixel access + channel order

**Confirmed field (javap, no `-c` needed):**
```
public class net.minecraft.client.renderer.texture.SpriteContents implements ... {
  ...
  private final com.mojang.blaze3d.platform.NativeImage originalImage;
  com.mojang.blaze3d.platform.NativeImage[] byMipLevel;
  ...
}
```
Field name to reflect: **`originalImage`**, type `com.mojang.blaze3d.platform.NativeImage`, `private final`. Matches the earlier inspection exactly — no drift between versions checked.

**`NativeImage` pixel accessors (javap):**
```
public final class com.mojang.blaze3d.platform.NativeImage implements java.lang.AutoCloseable {
  private int getPixelABGR(int, int);
  public int getPixel(int, int);
  public void setPixelABGR(int, int, int);
  public void setPixel(int, int, int);
  ...
}
```
`public int getPixel(int, int)` exists and is directly callable via reflection's `Method.invoke` (or, since it's public, can even be called directly once the `NativeImage` instance is obtained by reflecting `originalImage` — no further reflection needed for the pixel read itself).

**Channel order — disassembly of `getPixel` (`-c`):**
```
public int getPixel(int, int);
    Code:
       0: aload_0
       1: iload_1
       2: iload_2
       3: invokevirtual #331   // Method getPixelABGR:(II)I
       6: invokestatic  #336   // Method net/minecraft/util/ARGB.fromABGR:(I)I
       9: ireturn
```
and `ARGB.toABGR` (which `fromABGR` delegates to, it's a self-inverse swap):
```
public static int toABGR(int);
    Code:
       0: iload_0
       1: ldc  #189      // int -16711936   (0xFF00FF00)
       3: iand
       4: iload_0
       5: ldc  #190      // int 16711680    (0x00FF0000)
       7: iand
       8: bipush 16
      10: ishr
      11: ior
      12: iload_0
      13: sipush 255     // 0x000000FF
      16: iand
      17: bipush 16
      19: ishl
      20: ior
      21: ireturn
```
i.e. `toABGR(x) = (x & 0xFF00FF00) | ((x & 0x00FF0000) >>> 16) | ((x & 0x000000FF) << 16)` — keeps the G (bits 8‑15) and A (bits 24‑31) bytes in place and swaps the R/B bytes.

**Definitive channel order:** `NativeImage`'s raw internal storage (`getPixelABGR`) is `0xAABBGGRR` (A high byte, then B, G, R low byte — MC's usual in-memory native-image format). **`getPixel(x,y)` already performs the R/B swap for you and returns a standard Java ARGB int, `0xAARRGGBB`** — the same layout `java.awt.Color`/`BufferedImage.TYPE_INT_ARGB` use.

**Chosen approach:** reflect `SpriteContents.originalImage` → `NativeImage`, then call the already-public `getPixel(x, y)`. **No manual channel conversion is needed** — treat the returned int directly as ARGB `0xAARRGGBB` for averaging. (Do not use `getPixelABGR`/`getPixelsABGR` for this — those still need the manual `ARGB.fromABGR` swap; `getPixel`/`getPixels` do that for you already.)

No fallback needed — this path is exact and vendor-verified via bytecode.

---

## B. Up-face / side-face sprite from a block model

**This is cleanly obtainable — no particle-only fallback needed.** Confirmed via `BlockModelShaper`, `BlockStateModel`, `BlockModelPart`, `BakedQuad` javap, and cross-checked against vanilla's own `ModelBlockRenderer.renderModel(...)` bytecode (the exact same call chain vanilla uses to render an inventory/GUI block icon without a live world position).

**Signatures (javap):**
```
public class net.minecraft.client.renderer.block.BlockModelShaper ... {
  public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon(BlockState);
  public net.minecraft.client.renderer.block.model.BlockStateModel getBlockModel(BlockState);
  public net.minecraft.client.renderer.block.BlockModelShaper getBlockModelShaper(); // via BlockRenderDispatcher
}

public interface net.minecraft.client.renderer.block.model.BlockStateModel ... {
  public abstract void collectParts(RandomSource, List<BlockModelPart>);
  public default List<BlockModelPart> collectParts(RandomSource);
  public abstract TextureAtlasSprite particleIcon();
}

public interface net.minecraft.client.renderer.block.model.BlockModelPart ... {
  public abstract List<BakedQuad> getQuads(Direction);   // Direction may be null (unculled quads)
  public abstract boolean useAmbientOcclusion();
  public abstract TextureAtlasSprite particleIcon();
}

public final class net.minecraft.client.renderer.block.model.BakedQuad extends Record {
  ...
  public int tintIndex();
  public Direction direction();
  public TextureAtlasSprite sprite();
  ...
}
```
`BlockModelShaper` is obtained from `Minecraft.getInstance().getBlockRenderer().getBlockModelShaper()` (`BlockRenderDispatcher.getBlockModelShaper()` confirmed by javap: `private final BlockModelShaper blockModelShaper; public BlockModelShaper getBlockModelShaper();`).

**Vendor-verified algorithm** — disassembly of `ModelBlockRenderer.renderModel(PoseStack.Pose, VertexConsumer, BlockStateModel, float, float, float, int, int)` (the real code vanilla uses to render a block as an item/GUI icon, i.e. with no live world position):
```
   0: aload_2
   1: ldc2_w   #435   // long 42l
   4: invokestatic  RandomSource.create:(J)LRandomSource;
   7: invokeinterface BlockStateModel.collectParts:(LRandomSource;)Ljava/util/List;
  ... for each BlockModelPart part:
        for each Direction d : DIRECTIONS (all 6):
            part.getQuads(d)   -> renderQuadList(...)
        part.getQuads(null)    -> renderQuadList(...)   // unculled ("always render") quads, e.g. cross models
```
i.e. vanilla itself uses a **fixed seed `RandomSource.create(42L)`** for `collectParts` when there's no real block position to derive a per-position seed from — exactly the situation we're in for atlas baking. This is not a guess; it's lifted straight from vanilla's own icon-rendering code path.

**Chosen approach for Task 4/5:**
```java
BlockModelShaper shaper = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper();
BlockStateModel model = shaper.getBlockModel(state);
List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));

TextureAtlasSprite top = null, side = null;
for (BlockModelPart part : parts) {
    for (BakedQuad q : part.getQuads(Direction.UP)) { top = q.sprite(); break; }
    for (Direction horiz : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
        for (BakedQuad q : part.getQuads(horiz)) { side = q.sprite(); break; }
        if (side != null) break;
    }
    if (top == null) { for (BakedQuad q : part.getQuads(null)) { top = side = q.sprite(); break; } } // cross-model fallback
}
if (top == null) top = shaper.getParticleIcon(state);
if (side == null) side = shaper.getParticleIcon(state);
```
`BakedQuad.tintIndex()` (>= 0 means "needs biome tint applied", matches `BlockColors.getColor(state, level, pos, tintIndex)`'s last param) tells us whether the sampled sprite's raw pixels need biome tinting — feed straight into Part C/D's classification instead of re-deriving it.

**Per-block fallback (not "fallback for both faces globally"):** if a block's model genuinely has no `UP`/horizontal/null quads for some part (rare — mostly fully custom entity-block-renderer blocks with no baked model at all), fall back to `shaper.getParticleIcon(state)` for whichever face is missing. This is a per-block edge case, not the general rule.

---

## C. Tint-type classification (grass / foliage / water / none)

**Water — confirmed already in production code**, not just theory. `src/main/java/com/mia/aperture/map/VoxyColorSource.java` (current MapColor-based implementation, to be rewired in Task 6) already does exactly this and it's shipped/verified:
```java
import net.minecraft.tags.FluidTags;
...
boolean water = state.getFluidState().is(FluidTags.WATER);
```
Recommendation: keep this exact call for WATER classification.

**Grass/Foliage — `BlockColors` does *not* expose a position-independent classification API.** Both public methods require a live `Level`/`BlockAndTintGetter` + `BlockPos`:
```
public class net.minecraft.client.color.block.BlockColors {
  public final net.minecraft.core.IdMapper<BlockColor> blockColors;
  public int getColor(BlockState, Level, BlockPos);
  public int getColor(BlockState, BlockAndTintGetter, BlockPos, int);
  public Set<Property<?>> getColoringProperties(Block);
  ...
}
```
`blockColors` (`IdMapper<BlockColor>`) maps *block-registry id → BlockColor lambda instance*, but the individual `BlockColor` instances are opaque method-reference lambdas (`invokedynamic` call sites) with no reflective way to identify "is this the grass resolver" vs "is this the foliage resolver" at runtime without depending on private-method identity, which is fragile across MC versions/obfuscation. **This path is not cleanly reachable — confirmed by disassembling `createDefault()` and reading the actual bootstrap-method targets, see below.**

**Extracted the exact real vanilla registration list instead**, by disassembling `BlockColors.createDefault()` (`-c` + `-v` for the `InvokeDynamic` bootstrap-method table) and reading off every `Blocks.XXXX` field reference per `register(...)` call site, then mapping each call site to its backing private static method and checking what color source it calls:

| Group (bootstrap→resolver) | Blocks | Resolver body (confirmed) |
|---|---|---|
| `method_1686` | `LARGE_FERN`, `TALL_GRASS` | `BiomeColors.getAverageGrassColor(level,pos)` (upper half looks one block below) |
| `method_1693` | `GRASS_BLOCK`, `FERN`, `SHORT_GRASS`, `POTTED_FERN`, `BUSH` | `BiomeColors.getAverageGrassColor(level,pos)`, else `GrassColor.getDefaultColor()` |
| `method_49295` | `PINK_PETALS`, `WILDFLOWERS` | grass-color-like, tint-index-gated (not needed for map) |
| `method_1695` | `SPRUCE_LEAVES` | **fixed constant** `ldc -10380959` = **`0xFF619961`** (no biome tint at all) |
| `method_1687` | `BIRCH_LEAVES` | **fixed constant** `ldc -8345771` = **`0xFF80A755`** (no biome tint at all) |
| `method_1692` | `OAK_LEAVES`, `JUNGLE_LEAVES`, `ACACIA_LEAVES`, `DARK_OAK_LEAVES`, **`VINE`**, `MANGROVE_LEAVES` | `BiomeColors.getAverageFoliageColor(level,pos)`, else constant `-12012264` = `0xFF48B518` |
| `method_68159` | `LEAF_LITTER` | its own resolver (foliage-adjacent, minor block, skip) |
| `method_68158` | `WATER`, `BUBBLE_COLUMN`, `WATER_CAULDRON` | `BiomeColors.getAverageWaterColor(level,pos)`, else `-1` |
| `method_1688` | `REDSTONE_WIRE` | power-based, not biome |
| `method_1685` | `SUGAR_CANE` | own resolver, not biome-grass |
| `method_67245`, `method_1696` | melon/pumpkin stems | age/stage-based, not biome |
| `method_1684` | `LILY_PAD` | special-cased, not biome |

Important, non-obvious finding: **`VINE` is FOLIAGE-tinted, not grass-tinted**, and **`SPRUCE_LEAVES`/`BIRCH_LEAVES` are NOT biome-tinted at all** — they use fixed constant colors regardless of biome. This would have been wrong if guessed from the `minecraft:leaves` tag alone.

**Recommended classification rule (hardcoded set, keyed on `Block`, matches real vanilla registration above):**
```java
GRASS  = { GRASS_BLOCK, SHORT_GRASS, FERN, TALL_GRASS, LARGE_FERN, POTTED_FERN, BUSH }
FOLIAGE = { OAK_LEAVES, JUNGLE_LEAVES, ACACIA_LEAVES, DARK_OAK_LEAVES, MANGROVE_LEAVES, VINE }
FOLIAGE_FIXED = { SPRUCE_LEAVES: 0xFF619961, BIRCH_LEAVES: 0xFF80A755 }  // no biome lookup, just use these constants directly
WATER  = via state.getFluidState().is(FluidTags.WATER)   // covers WATER, BUBBLE_COLUMN, water-logged states etc. — more robust than a static block list
NONE   = everything else — use the sampled sprite's own averaged pixel colour, no tint
```
This is the recommended approach (not a "fallback" — it's the accurate reproduction of vanilla's real behavior, extracted directly from bytecode rather than guessed), because the registry itself isn't queryable without a live position.

---

## D. biomeId → grass/foliage/water tint colours

### D.1 — Voxy `Mapper`: biome id → biome identifier string

`me/cortex/voxy/common/world/other/Mapper` (javap):
```
public class me.cortex.voxy.common.world.other.Mapper {
  ...
  public int getIdForBiome(Holder<Biome>);         // class_6880<class_1959> in intermediary
  public Mapper$BiomeEntry[] getBiomeEntries();
}
public final class me.cortex.voxy.common.world.other.Mapper$BiomeEntry {
  public final int id;
  public final java.lang.String biome;
}
```
Disassembly of `registerNewBiome` shows `id` is assigned as `biomeId2biomeEntry.size()` at registration time (simple append-only array, indexed directly by id):
```
32: new  Mapper$BiomeEntry
...
36: biomeId2biomeEntry.size()   // -> id
43: aload_1                      // -> biome string
44: invokespecial Mapper$BiomeEntry."<init>":(ILjava/lang/String;)V
```
and `getIdForBiome` shows the `biome` string is derived from `holder.unwrapKey().get().location().toString()` (a `Holder<Biome>` → `ResourceKey<Biome>` → `Identifier` → its string form, e.g. `"minecraft:plains"`):
```
0: aload_1
1: invokeinterface Holder.unwrapKey:()Ljava/util/Optional;    // class_6880.method_40230 in intermediary
6: invokevirtual  Optional.get:()Ljava/lang/Object;
9: checkcast      ResourceKey                                  // class_5321
12: invokevirtual  ResourceKey.location:()LIdentifier;         // method_29177
15: invokevirtual  Identifier.toString:()Ljava/lang/String;
```
(`Holder`/`ResourceKey`/`Identifier` names inferred from well-established MC mapping conventions and cross-checked against `SpriteContents.name: Identifier` seen directly in Part A's javap in this same jar — confidence: high, but worth a quick double-check against genSources when Task 4/5 code is written, since Voxy's own jar still shows raw intermediary `class_6880`/`class_5321`/`class_2960` here.)

**Exact call chain, biomeId → `Biome`:**
```java
String biomeKey = mapper.getBiomeEntries()[biomeId].biome;      // e.g. "minecraft:plains"
Identifier id = Identifier.parse(biomeKey);
Biome biome = level.registryAccess()
                   .lookupOrThrow(Registries.BIOME)
                   .getValue(ResourceKey.create(Registries.BIOME, id));
```
(Biome registry is data-driven per-world, so this needs a live `RegistryAccess`, not a static built-in registry — that's expected and unavoidable, and the mod already has a live client `Level`/`RegistryAccess` available at bake time per the design doc.)

### D.2 — `Biome`: grass/foliage/water tint accessors

`net/minecraft/world/level/biome/Biome` (javap) exposes exactly what's needed, **fully resolved (override + climate colormap + grass modifier already applied)** — no manual colormap math required:
```
public final class net.minecraft.world.level.biome.Biome {
  ...
  public int getGrassColor(double x, double z);   // x,z used only for the SWAMP grass-color-modifier's noise variation
  public int getFoliageColor();
  public int getDryFoliageColor();
  public int getWaterColor();
  public BiomeSpecialEffects getSpecialEffects();
  ...
}
```
`getGrassColor(double,double)` takes world x/z — for our per-pixel map bake we have the real block x/z the map pixel represents, so this can be passed through for correct `SWAMP` (noise-blended) and `DARK_FOREST` (fixed darken) grass-color-modifier behavior — see `BiomeSpecialEffects$GrassColorModifier` (enum: `NONE`, `DARK_FOREST`, `SWAMP`, abstract `modifyColor(double,double,int)`).

`BiomeSpecialEffects` (javap) confirms the override fields (used internally by `getGrassColor`/`getFoliageColor`, but also directly accessible if a lower-level path is ever wanted):
```
public final class net.minecraft.world.level.biome.BiomeSpecialEffects extends Record {
  private final int waterColor;                                    // always present
  private final Optional<Integer> foliageColorOverride;
  private final Optional<Integer> dryFoliageColorOverride;
  private final Optional<Integer> grassColorOverride;
  private final BiomeSpecialEffects$GrassColorModifier grassColorModifier;
  public int waterColor();
  public Optional<Integer> foliageColorOverride();
  public Optional<Integer> grassColorOverride();
  public GrassColorModifier grassColorModifier();
}
```
`waterColor` is a plain `int`, always present (confirmed non-Optional field) — water tint is always reliable directly off the `Biome`, no fallback ever needed.

**Chosen approach:** call `biome.getGrassColor(blockX, blockZ)`, `biome.getFoliageColor()`, `biome.getWaterColor()` directly — these already do override-lookup → climate-colormap (`GrassColor.get`/`FoliageColor.get`, loaded from `grass.png`/`foliage.png` at runtime) → grass-color-modifier, exactly matching in-game rendering. **No fallback needed for water** (`waterColor` field always present). For grass/foliage, if for any reason a `Biome` can't be resolved from the id (e.g. datapack/biome-mod id not found in the registry lookup), fall back to the flat constants:
```
default grass:   0x91BD59
default foliage: 0x77AB2F
default water:   0x3F76E4
```
(These match `GrassColor.getDefaultColor()`'s sample point (`get(0.5, 1.0)`) and `FoliageColor.FOLIAGE_DEFAULT`/vanilla's commonly-cited plains-biome defaults.)

---

## Summary for Tasks 4/5

- **A (pixel access):** clean, no fallback. Reflect `SpriteContents.originalImage` → call public `NativeImage.getPixel(x,y)` → already ARGB `0xAARRGGBB`, no conversion math needed.
- **B (per-face sprite):** clean, no fallback (vendor-verified against vanilla's own icon-rendering bytecode). `shaper.getBlockModel(state).collectParts(RandomSource.create(42L))` → per-part `getQuads(Direction.UP / horizontal / null)` → `BakedQuad.sprite()` + `tintIndex()`. Per-block edge case only: `getParticleIcon(state)` if a part has no relevant quads.
- **C (tint classification):** no live-position-independent registry API exists (confirmed) — use the hardcoded set above, extracted directly from vanilla's real `BlockColors.createDefault()` bytecode (not guessed), plus `state.getFluidState().is(FluidTags.WATER)` for water (already in production code).
- **D (biomeId → colours):** clean, two-hop, no real fallback needed except the resolved-registry-miss edge case. `Mapper.getBiomeEntries()[id].biome` (string) → `Identifier` → `registryAccess().lookupOrThrow(Registries.BIOME)` → `Biome` → `getGrassColor(x,z)`/`getFoliageColor()`/`getWaterColor()`.

No area resolved to "fully stuck, needs a workaround" — B and D, the two areas flagged as most uncertain going in, both turned out to have clean direct APIs once disassembled, better than the pessimistic particle-icon-only / hardcoded-color fallbacks originally anticipated.
