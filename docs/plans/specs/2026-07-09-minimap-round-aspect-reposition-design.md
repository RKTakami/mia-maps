# Minimap Polish: Round Frame, Fullscreen Aspect, Repositioning — Design Spec

**Date:** 2026-07-09
**Status:** Draft for owner review
**Builds on:** v1.4.0 minimap (orientation/shape/size/cardinals/settings)

## 1. Goal

Three fixes/enhancements to the map presentation, from owner feedback on v1.4.0:

1. **Truly round minimap** — round frame with transparent corners (world shows through),
   not a round map sitting in a square dark frame.
2. **Fullscreen map aspect** — blocks currently render horizontally stretched; make the
   fullscreen map aspect-correct while still filling the screen.
3. **Repositionable HUD minimap** — free drag-and-drop placement plus four corner presets,
   controlled from the settings panel; persisted.

Ships as **v1.5.0**, bundling all prior unreleased work (v1.2.0 data-driven map, v1.3.0
colours, v1.3.1 fixes, v1.4.0 minimap) and retiring the broken v1.3.0 GitHub release.

### Non-goals

- Backlog items deferred to their own specs: minimap depth-follow bug, cave mode, fullscreen
  player marker, X/Y/Z readout, placeable named waypoints (see project_memory backlog).
- Fullscreen map shape/rotation/resize (stays north-up, screen-filling).

## 2. Truly round minimap

**Problem:** `drawHud` always draws `MinimapFrame.drawSquareFrame` (opaque dark square + border),
then for ROUND overlays `drawRoundMask` which paints the corners DARK — so the corners are an
opaque dark square, not transparent.

**Approach:** clip in the source texture (an opaque overlay can't reveal the world). When
`shape == ROUND`, `MapCompositor.composeHud` zeroes the **alpha** of HUD-texture pixels
outside the inscribed circle. Mask radius is a constant `texSize / (2 * OVERSAMPLE)` (=
256/(2·1.5) ≈ 85 px of the 256² texture): drawn at `1.5×size` and scissored to the `size`
frame, that circle lands exactly at screen radius `size/2` (the frame edge). A circle is
rotation-invariant, so heading-up mode still works. `drawHud` for ROUND then:
- does **not** draw the square background,
- blits the (circle-alpha'd) map — corners are transparent, world shows through,
- draws a **round border ring** at screen radius `size/2` (replaces the opaque `drawRoundMask`).

SQUARE mode is unchanged (no alpha mask; full square visible; square frame + border).

`composeHud` gains a `boolean round` argument (from `MapSettings.shape`). The HUD recomposes
frequently enough that toggling shape takes effect within a compose cycle.

## 3. Fullscreen map aspect (fill-screen, aspect-correct)

**Problem (confirmed by code):** `composeMap` renders a square block region
(`blocksPerPixel = blocksAcross / imageSize`, same both axes) into the square 2048² texture;
`AbyssWorldMapScreen.render` then blits it stretched to `this.width × this.height`. On a 16:9
screen that's ~1.78× horizontal stretch → rectangular blocks.

**Approach:** give the compositor a **per-axis block span**. `composeMap` takes
`blocksAcrossX` and `blocksAcrossZ`; `compose` computes `blocksPerPixelX = blocksAcrossX /
imageSize` and `blocksPerPixelZ = blocksAcrossZ / imageSize` and uses each for the px→blockX
and py→blockZ mapping respectively. The fullscreen caller passes:
```
base = 256 / mapZoom
blocksAcrossX = round(base * (screenW / screenH))
blocksAcrossZ = base
```
Square blocks on screen require `blocksAcrossX / blocksAcrossZ = screenW / screenH`; with the
above, the stretch cancels and blocks render square while the map fills the screen. LOD level
uses `max(blocksAcrossX, blocksAcrossZ)`. The sector-domain clamp on `blockX` is retained.

The HUD `composeHud` passes equal X/Z spans (aspect 1) — minimap unaffected.

## 4. Repositionable HUD minimap (free drag + corner presets)

**Storage:** the minimap's on-screen position is a **normalized top-left fraction**
`(minimapX, minimapY) ∈ [0,1]` of the screen (resolution-independent). Default = top-right
(matching v1.4.0's fixed position). Added to `MapSettings`.

**Pure layout helper** `MinimapLayout`:
- `originX(fracX, screenW, size, margin)` / `originY(fracY, screenH, size, margin)` → pixel
  coordinate of the minimap's top-left, **clamped** so the whole `size×size` frame stays
  on-screen (`[margin, screen - size - margin]`). Unit-tested.
- `cornerFraction(corner, screenW, screenH, size, margin)` → the `(fx, fy)` for a named
  corner (TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT). Unit-tested.

**`drawHud`:** compute the origin from the clamped fraction; draw the minimap there; place the
depth/layer text on the **inward** side (below the minimap for top positions, above it for
bottom) so it never runs off-screen.

**Drag editor** `MinimapRepositionScreen extends Screen`: a transparent overlay that renders
the live minimap at its current position and lets the user **drag it anywhere**; `mouseDragged`
updates `(minimapX, minimapY)` (clamped live via the helper); instructional text; Done button
and Escape both return to the parent; `removed()` persists. (A dedicated screen is required —
the in-game HUD isn't mouse-interactive during play.)

**Settings panel** `MapSettingsScreen`: add four **corner quick-buttons** (each sets the
fraction via `cornerFraction` + persist) and a **"Reposition (drag)"** button that opens
`MinimapRepositionScreen`.

**Persistence:** `minimapX`/`minimapY` are plain fields on `MapSettings`, so `MapConfig`
(Gson) serializes them automatically; `fromJson` defaults them if absent/out of range.

## 5. Components

- Modify `MapSettings`: add `double minimapX, minimapY` (default top-right fraction);
  add a `MinimapCorner` enum (TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT) used by the corner
  buttons; clamp fractions to [0,1] on set.
- Modify `MapConfig`: null/range-guard the new fields in `fromJson`.
- Create `MinimapLayout` (pure: origin clamp + corner fractions) + unit tests.
- Modify `MapCompositor`: `composeMap` per-axis block span; `composeHud` round alpha-mask;
  extract the per-axis `blocksPerPixel` in `compose`.
- Modify `MinimapFrame`: replace the opaque `drawRoundMask` with a round **border ring** draw;
  keep `drawSquareFrame`, `drawCardinals`.
- Modify `MiaApertureModClient.drawHud`: origin from `MinimapLayout`; round vs square draw
  path (transparent corners for round); text layout per position.
- Modify `AbyssWorldMapScreen`: pass screen-aspect X/Z spans to `composeMap`.
- Create `MinimapRepositionScreen` (drag editor).
- Modify `MapSettingsScreen`: corner quick-buttons + "Reposition (drag)" button.

## 6. Testing

- Pure/unit-tested: `MinimapLayout.originX/originY` clamping (in-bounds, off-screen high/low),
  `cornerFraction` for all four corners; per-axis block-span computation
  (`blocksAcrossX = round(base*aspect)`); `MapSettings` fraction clamp + defaults;
  `MapConfig` round-trip including the new fields and default-on-missing.
- In-game verified: round minimap corners transparent (world visible) at 0° and 45° headings;
  square mode unchanged; fullscreen blocks square on a widescreen; drag editor moves + clamps
  + persists; corner buttons snap correctly; depth/layer text stays on-screen in every corner.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Round alpha-mask radius wrong vs oversample/draw scale | radius = texSize/(2·OVERSAMPLE) derived from the 1.5× draw; verify at 45° in-game |
| Per-axis aspect math inverted (stretch doubles instead of cancels) | pure unit test on the span computation; the ratio identity `blocksAcrossX/Z = screenW/H` is explicit |
| Drag editor lets minimap go off-screen | all placement goes through `MinimapLayout` clamp (drag, corners, and draw) |
| Absolute-pixel positions break on resolution change | store normalized fractions, clamp at draw time |
| NativeImage alpha write for the mask (channel order) | alpha is the high byte in both ARGB/ABGR; unaffected by the R/B ambiguity |
