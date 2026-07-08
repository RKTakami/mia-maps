# Map Colours & Resolution — Design Spec

**Date:** 2026-07-08
**Status:** Draft for owner review
**Builds on:** `docs/plans/specs/2026-07-07-data-driven-map-design.md` (the shipped v1.2.0 data-driven map)

## 1. Goal

Two visual improvements to the data-driven map:

1. **Resource-pack-accurate colours.** Replace the vanilla `MapColor` cartography palette
   with colours sampled from the actual block textures in the loaded atlas, tinted
   per-biome — so the map reflects Mine in Abyss's custom resource pack and each Abyss
   layer renders in its own colours.
2. **Higher resolution.** Sharpen the fullscreen map from 512² to 2048², composing only
   when something changed so the larger texture stays affordable.

The pure `MapTileRenderer`, the LRU tile cache, and the `MapWorker` keep their structure.
This is a colour-source swap plus a compositor resolution change.

### Non-goals

- Side view / vertical cross-section rendering (deferred — see §10; this spec bakes the
  side colours it will need but does not render it).
- Keeping the old flat `MapColor` palette as a selectable mode (retired; §7).
- HUD minimap resolution change (stays 128²).

### Kept unchanged

- `MapTileRenderer` column-scan, relief/flat shading, water depth blending, and its unit
  tests (only the colour-source call signature grows a face parameter — §5).
- `MapTileCache`, `MapWorker` (acquire→copy→release, generation invalidation, dedupe).
- Ctrl+scroll slice band, zoom range, sector-domain clamp, disconnect reset.

## 2. The colour bake (`BlockColorBake`, new)

A table indexed by Voxy's internal `blockId`. Each entry holds:

- `topColor` — int ARGB, alpha-weighted average of the block's up-face texture.
- `sideColor` — int ARGB, alpha-weighted average of a side-face texture.
- `tintType` — enum-like byte: `NONE`, `GRASS`, `FOLIAGE`, `WATER`.
- `opaque` — boolean (folded in so `VoxyColorSource` no longer needs a separate opacity call).

**Averaging rule (pure, unit-tested):** sum `r,g,b` weighted by each pixel's alpha,
divide by total alpha; fully-transparent pixels contribute nothing. An all-transparent
sprite yields "no colour" (ARGB 0, treated as non-opaque). This stops cross-model plants,
glass, and holed leaves from washing toward black.

**Face resolution (render thread, in-game verified):**
`blockId → Mapper.getBlockStateFromBlockId → Minecraft.getModelManager().getBlockModelShaper()`.
- Top: the up-face quad's sprite when the model exposes one; fallback to
  `getParticleIcon(state)`.
- Side: a horizontal-face quad's sprite; fallback to `getParticleIcon(state)`.
- Sprite pixels via `TextureAtlasSprite.contents()` → `SpriteContents` → its `NativeImage`.

`tintType` is determined at bake time from Minecraft's block-colour registry
(whether the block has a tint provider and for which category); exact detection API is a
plan-time verification item (§9).

## 3. Bake lifecycle

Voxy's block set grows as it ingests chunks, so the bake cannot be fully eager at load.

- At the **top of each compose** (render thread), compare `Mapper.getBlockStateCount()` to
  the number of baked entries. If it grew, bake only the new `blockId` range.
- A **resource-reload listener** clears and rebuilds the whole table (resource pack change).
- The worker thread only ever **reads** baked entries. A `blockId` that first appears
  mid-frame (before its bake) renders with a neutral fallback colour that frame and is
  correct on the next compose.

This keeps **all atlas/model access on the render thread**; the worker touches only
immutable baked data (array snapshot handed in at `VoxyColorSource` construction).

## 4. Biome tint (`BiomeTintResolver`, new)

For entries whose `tintType != NONE`, multiply the baked (grayscale-ish) colour by the
cell's biome tint colour:

- Cell biome id via `Mapper.getBiomeId(mappingId)`.
- `biomeId → grass/foliage/water tint colour`, resolved once per biome id and cached.
- Multiply channel-wise into `topColor`/`sideColor` at lookup time (not baked in, so the
  block table stays biome-independent and small).

**Graceful degradation:** if a biome's tint colour cannot be resolved, return a default
tint (e.g. a temperate green / standard water blue) rather than failing the cell. The
exact biome→tint API path is the riskiest unknown and is a plan-time verification item
(§9).

## 5. Existing-code changes

- **`MapColorSource`** interface: `baseColor(long mappingId)` becomes
  `baseColor(long mappingId, Face face)` with `Face` = `TOP | SIDE`. `isWater`/`isOpaque`
  keep taking `mappingId`. The top-down rasterizer passes `Face.TOP`.
- **`MapTileRenderer`**: every `colors.baseColor(...)` call (surface colour and the water
  floor scan) passes `Face.TOP`, since the top-down view always sees tops. Water-surface
  detection and shading are otherwise unchanged. Its existing tests update only to the new
  signature (a fake `MapColorSource` returning fixed colours per face); all behavioural
  assertions stand.
- **`VoxyColorSource`**: rewritten to read `BlockColorBake` + `BiomeTintResolver` instead
  of `getMapColor`. It extracts `blockId` and `biomeId` from `mappingId` internally.
- **Retired:** the `BlockState.getMapColor(...)` path and the vanilla-palette look.

## 6. Resolution & recompose-on-change

- `MapCompositor.MAP_SIZE` 512 → **2048**. HUD 128² unchanged.
- Replace the fixed 100 ms map interval with **change detection**: recompose only when a
  compose input changed — center X/Z, zoom, band top/bottom, mode — **or** a tile the last
  compose requested has since completed (so progressive fill still updates). Implementation:
  hash/compare the compose parameters, plus a "tiles completed" counter bumped by
  `MapWorker` when it fills the cache; recompose when either differs from the last compose.
- The HUD keeps its 2 Hz throttle (small texture, cheap).

Rationale: 2048² is ~4M pixel-ops per compose — too much at 10 Hz, fine when it only fires
on actual change (pan/zoom/slice/new-tiles), which is the real interaction rate.

## 7. The `V` toggle

`V` remains a **shading** toggle: relief slope-shading (default) vs. flat/even shading,
both now applied on top of the real atlas colours. `MapMode` keeps two values but their
meaning is now "shading style," not "palette." The pure-`MapColor` cartography look is
removed entirely.

## 8. Testing

Pure, unit-tested:
- `BlockColorBake` averaging: alpha-weighted average; all-transparent → no colour; solid
  block → exact colour; partial alpha weighting.
- `BiomeTintResolver` multiply: channel-wise multiply; default fallback path.
- `MapTileRenderer` existing tests updated to the `Face` signature; assertions unchanged.

In-game verified (as before): model/sprite resolution, bake lifecycle growth, biome tint
correctness, 2048² sharpness and compose-on-change responsiveness, colour orientation.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Biome→tint-colour API uncertain in 1.21.11 | javap-verify at plan step 1; `BiomeTintResolver` returns a default tint on failure so the feature still ships |
| Tint-type detection (which blocks are tinted, which index) | verify via Minecraft's block-colour registry at plan time; default to `NONE` (untinted) when unknown |
| One-time bake hitch when many new blocks appear at once | incremental per-frame baking bounded by new-blockId count; averaging small sprites is cheap |
| Up/side face sprite not cleanly extractable from every model | fallback to particle icon (always present) |
| 2048² memory (~16 MB) + full-image upload cost | acceptable one texture; recompose-on-change keeps upload frequency low |
| Atlas/model reads off the render thread | enforced render-thread bake lifecycle; worker reads only immutable baked arrays |

## 10. Deferred: exposure-aware side view (brief recorded)

Side view (vertical Z-Y cross-section) is deferred to its own spec, but its requirements
are captured here because this spec lays its colour groundwork (baked `sideColor`):

- Render a **thin slab** along the view axis (analogous to the top-down Y-band). Columns
  with no solid block in the slab render as **open space** (transparent/void) so the
  central shaft, caverns, and tunnels show through — not a solid earthen curtain.
- Colour solid blocks by `sideColor`. Distinguish **exposed** blocks (a neighbour is air —
  computed from the section grid we already read) from **buried interior** fill: exposed
  surfaces at natural colour, interior muted/darkened so real surfaces stand out.
- Surface-indicator blocks (grass etc.) accent walkable ledges so they read at a glance.

"Exposed to air" is geometric (neighbour = air), not a per-block property — grass is a
lucky special case; genuine cave walls are ordinary stone and need air-neighbour detection.
