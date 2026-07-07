# Data-Driven Map Renderer — Design Spec

**Date:** 2026-07-07
**Status:** Draft for owner review
**Supersedes:** the Voxy-viewport-hijack map rendering path (v1.0–v1.1.20)

## 1. Goal

Replace the fullscreen map and HUD minimap rendering with a CPU-side tile renderer that
reads Voxy's world database directly, instead of hijacking Voxy's GPU render pipeline
with a second viewport.

Yesterday's session (2026-07-06) proved the viewport approach can display content, but it
fights an undocumented, GPU-driven, temporally-converging renderer at every step, and
layer isolation (the stacked-column aqua-sea problem) remains unsolved. The data path is
read-only, bounded, and under our control.

### Non-goals (v1)

- Side-view cross-section (deferred; same data source can serve it later via an X/Z scan).
- Persistence of map mode or view state across sessions.
- Waypoints, markers, or any annotation features.
- Rendering areas never ingested into Voxy's DB (the map shows what you have explored).

### Kept from the existing mod (unchanged)

- Aperture culling of the live world (`NodeManagerMixin`, `RenderDistanceTrackerMixin`,
  `H` toggle, in-game Ctrl+scroll) — works on the real Voxy renderer and stays.
- Input handling: `KeyboardMixin` modifier tracking, `MouseMixin`, Ctrl as slice modifier.
- `AbyssWorldMapScreen` shell: open with `M`, drag-pan, scroll-zoom, overlay text.
- HUD layout: minimap frame position, player arrow, depth/layer text, layer sidebar.
- The corrected `GuiGraphics.blit` calls (1.21.11 corner+UV signature, v1.1.17).

### Retired (deleted) by this design

- `MinimapFbo` (the entire Voxy-viewport render pass, FBO, fog/frameId/depth-bound hacks).
- `LevelRendererVoxyBypassMixin`, `ChunkBoundRendererMixin`, `TraversalAccessor`,
  `ViewportSelectorInvoker`, `WorldRendererMixin` (only existed to drive the FBO pass).
- `VoxyRenderSystemMixin` accessors for `viewportSelector`/`pipeline`/`traversal`
  (keep `renderDistanceTracker` for the aperture reload).
- `MiaApertureModClient.MinimapTexture` (replaced by a vanilla `DynamicTexture`).
- All `[MIA Aperture diag]` logging and `RenderStatistics` enabling.

## 2. Data source facts (verified against voxy-clone source 2026-07-07)

- `WorldEngine.acquireIfExists(lvl, x, y, z)` returns a ref-counted `WorldSection` or
  null. Sections are 32×32×32 cells at every LOD level; level L cells cover `1<<L`
  blocks. Voxy's `Mipper` maintains the mip levels; the ingest services already call
  acquire from background threads, so off-thread access is a supported pattern.
- `WorldSection.getIndex(x, y, z) = (y<<10)|(z<<5)|x` (y-major); `copyDataTo(long[])`
  snapshots the 32768-entry data array while holding the acquire. Always
  `acquire → copyDataTo → release` in try/finally; never retain the section object.
- Each cell is a mapping id: `Mapper.getBlockId(id)`, `getBiomeId(id)`, `getLightId(id)`;
  `mapper.getBlockStateFromBlockId(blockId)` → real `BlockState`;
  `mapper.getBlockStateOpacity(mappingId)` → opacity for the surface scan.
  Mapper lookups are ConcurrentHashMap-backed (thread-safe reads).
- Coordinates are in Voxy's shifted DB space. Conversion from world coords (verified
  live in v1.1.8+): `sector = AbyssUtil.getSection(worldX)`;
  `shiftedX = worldX - (sector<<14)`; `shiftedY = worldY + (240 - sector*30)*16`;
  Z unshifted.
- **Plan-time check:** confirm `getIndex` layout and `copyDataTo` exist unchanged in the
  shipped `voxy-mia-edition-2.15-fcd6dda.jar` (javap), since the clone lags it.

## 3. Architecture

Five small units in a new package `com.mia.aperture.map`:

```
MapView (what to show: center, zoom, band, mode)
   │ requests visible tiles
   ▼
MapTileCache (LRU, keyed lvl+sx+sz+bandKey+mode) ──miss──▶ MapWorker (1 daemon thread)
   │ hit: int[32*32] ARGB                                     │ acquireIfExists sections,
   ▼                                                          │ copyDataTo, release
MapCompositor (assembles visible tiles into one NativeImage,  ◀─ MapTileRenderer (pure:
  uploads to a DynamicTexture, throttled)                        long[] → int[] colors)
   ▼
AbyssWorldMapScreen / HUD draw the DynamicTexture (existing blit calls)
```

### 3.1 MapTileRenderer (pure, unit-testable)

Input: the band-covering section data arrays for one tile column (top to bottom), the
band top/bottom in shifted Y, mode, and a color-resolver callback (mapping id → base
ARGB + water flag). Output: `int[32*32]` ARGB plus `int[32*32]` surface heights.

Per column (x, z), scan cells top→bottom within the band:
1. Skip cells whose `getBlockStateOpacity(id) <= 0` (air and fully transparent).
2. First hit = surface: record height and base color.
3. If the surface block is water: continue scanning to the floor beneath (bounded, e.g.
   32 cells), then blend water color over floor color with depth-based darkening.
4. No hit in band → transparent pixel (unexplored / empty column).

Shading applied to the base color:
- **RELIEF (default):** brightness = 1.0 + k·(h − h_north) clamped to [0.55, 1.35]
  (slope shading against the northern neighbor, like classic relief maps), plus a
  subtle vertical gradient across the band (deeper = slightly darker). Column heights
  from the tile's own scan; for the tile's north edge row, use the neighbor tile's
  cached heights when available, else flat shading.
- **VANILLA:** MapColor three-tone stepping exactly like cartography maps: brighter
  than the north neighbor → HIGH, lower → LOW, equal → NORMAL brightness multiplier.

### 3.2 Color resolution

`blockId → BlockState → state.getMapColor(level, BlockPos.ZERO).col`. Resolved lazily
per blockId into an int array cache (blockId count is small and append-only), on the
worker thread with the current `ClientLevel` captured per job; if the level is gone the
job aborts. Water detection: `state.getFluidState().is(FluidTags.WATER)` cached the
same way.

### 3.3 MapWorker and cache

- One daemon worker thread, LinkedBlockingQueue of tile jobs, newest-first for tiles
  near the view center; duplicate suppression by tile key.
- Tile key: `(lvl, sectionX, sectionZ, bandKey, mode)` where bandKey quantizes the band
  top to 16 blocks (so slice scrolling reuses tiles per notch, and a band change
  naturally repopulates).
- LRU capacity ~512 tiles (~4 MB). Tiles also carry a timestamp; tiles within ~96
  blocks of the player re-render when older than 5 s (live updates as you explore);
  farther tiles refresh only on evict/miss.
- A tile job acquires the ≤N sections covering the band for its column
  (`bandBlocks / (32<<lvl)` + 1, clamped ≤ 12), snapshots each via `copyDataTo` into
  reused arrays, releases immediately, then rasterizes.

### 3.4 MapCompositor and texture

- One `NativeImage`-backed `DynamicTexture` per consumer: 512×512 for the fullscreen
  map, 128×128 for the HUD minimap, registered under the existing identifiers.
- Compose pass (render thread, throttled to 10 Hz for the map screen, 2 Hz for HUD, and
  skipped entirely when nothing changed): for the current view rectangle, copy the
  visible region of each cached tile into the image (nearest-neighbor scaling),
  transparent where tiles are missing, then `DynamicTexture.upload()`. Missing tiles
  are enqueued — the map fills in progressively.
- The map screen keeps its existing pan/zoom state; the compositor derives the view
  rectangle from `mapX/mapZ/mapZoom` exactly as the FBO camera did (same shift math).

### 3.5 Zoom → LOD level

Choose `lvl` so the view spans ≤ ~640 columns: `lvl = clamp(ceil(log2(blocksAcross/512)), 0, 4)`.
Examples: ≤512 blocks across → lvl 0; ~1100 (yesterday's 0.23× zoom) → lvl 2 (128-block
sections of 4-block cells). Missing mips at high lvl simply yield empty tiles
(acquireIfExists), so zoom-out never blocks.

### 3.6 The Y band (layer isolation — the aqua-blob fix)

- Default band: top = `playerShiftedY + 96`, height 320 blocks (covers the walkable
  layer without reaching the next stacked band ~480 away).
- Ctrl+scroll in the map screen moves the band top by 16 per notch (reuses
  `scrollTargetCenterY` in abyss coordinates, converted with the standard shift), and
  the overlay line shows the band as "Slice: <top>m … <bottom>m". This gives the map
  slicing UX with zero shader involvement. Because the value is shared with the world
  aperture, when aperture culling (`H`) is active, scrolling in the map moves the world
  slice too — intended v1 behavior (one slice concept everywhere). The map-screen scroll
  handler keeps calling `triggerReevaluation()` only when `scrollActive` is on.
- HUD minimap always uses the default player-centered band.

### 3.7 HUD minimap

Same cache and renderer; fixed view: 128×128 blocks centered on the player at lvl 0,
composed at 2 Hz into the 128×128 texture. Existing frame, crosshair, arrow, and text
draw unchanged on top. This finally makes the HUD live (the old design could only show
stale map-screen frames).

### 3.8 Controls and UX summary

- `M` map, drag pan, scroll zoom, `P` reserved (side view later; hidden from overlay
  text in v1), Ctrl+scroll band slice, **`V` toggles RELIEF/VANILLA** (map screen only;
  applies to both map and HUD; not persisted in v1).
- Overlay adds mode name and band range; footer becomes
  "Drag to pan | Scroll to zoom | Ctrl+scroll to slice | V: relief/vanilla".

## 4. Threading and failure modes

- All DB access on the worker thread; all GL (NativeImage upload) on the render thread;
  handoff via completed-tile queue drained in the compositor tick. No GL from workers.
- `acquireIfExists` null → transparent tile (unexplored). Any exception in a tile job
  logs once per key and yields a transparent tile; the worker never dies.
- World/dimension change or Voxy shutdown: listener clears cache and queue; jobs check
  a generation counter and abort stale work (WorldEngine instance captured per job,
  never held statically).
- Memory: tile cache ~4 MB, compose images ~1.3 MB total, snapshot arrays reused —
  negligible next to Voxy's 4 GB geometry buffer.

## 5. Testing

Add JUnit 5 (test scope only) for the pure parts — the project currently has no tests:
- `getIndex`/column-scan math against hand-built synthetic section arrays (surface
  found, band clipping, water blending, empty column).
- Relief and vanilla shading given synthetic height fields (slope brightening/darkening,
  clamps, north-edge fallback).
- Zoom→lvl mapping table.
In-game verification stays the owner-run loop with the instance log, per project
convention.

## 6. Risks

| Risk | Mitigation |
|---|---|
| Shipped jar's WorldSection/Mapper differs from clone | javap verification at plan step 1; the APIs used are core and stable across the fork's history |
| `getMapColor` needing real pos context for some blocks | acceptable for a map; falls back to the state's default material color |
| Band heuristics wrong for some MIA layers (overhangs, ceilings) | Ctrl+scroll band control is the escape hatch; constants in one place for tuning |
| Worker thread contention with Voxy's ingest | single worker, small jobs, acquireIfExists only (never triggers generation or mip builds) |
| MIA `voxy_mia_light_zones.json` malformed in instance (boot error) | irrelevant to this path (we don't use Voxy lighting); flag to owner separately |

## 7. Future work (explicitly deferred)

- Side-view cross-section from the same data (X-scan per column into a Z/Y image).
- Mode/state persistence; configurable band height/colors; biome tinting (`getBiomeId`
  is available); light-level overlay (`getLightId` is available); waypoint markers.
- Map slice ↔ world aperture linkage (one control driving both).
