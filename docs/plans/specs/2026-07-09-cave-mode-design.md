# Cave Mode (Cut-Away Roof + Depth Shading) — Design

**Date:** 2026-07-09
**Status:** Approved (conversational brainstorm)

## Purpose

Second post-v1.6.0 backlog item. A Xaero-style cave view: when you are underground the
maps cut away the roof above you, draw the cave floors below, and blend a depth gradient
over the real block colours so the 3D cave layout reads at a glance. Builds directly on
the v1.6.0 surface-at-cut renderer — the cut is auto-placed under the roof and a new
depth-blended render mode is used. Also adds a language file so every keybind is
named and easily rebindable in the vanilla Controls menu.

## Activation & scope

- New `MapSettings.CaveMode { AUTO, ON, OFF }`, persisted by `MapConfig` (default `AUTO`).
- **Effective cave-active** = `ON`, or (`AUTO` **and** enclosure detected); `OFF` always off.
- Cycled by a new rebindable keybind `key.mia_aperture_mod.cave_mode` (default **C**),
  which advances AUTO → ON → OFF → AUTO and shows a status message. Also selectable in
  the map settings panel.
- Applies to **both** the minimap and the fullscreen map.

## Enclosure detection

Impure part (client tick, `MiaApertureModClient`): scan straight up from the player's head
through the real world (`mc.level.getBlockState`, checking `isSolidRender`/collision) up to
`ROOF_SCAN = 48` blocks. The first solid block's world Y is the **roof**; its distance is
stored. `-1`/none-found means open sky.

Pure, testable parts (`CaveDetector`):
- `debounce(boolean rawEnclosed)` — holds state and only flips after
  `DEBOUNCE_TICKS = 8` consecutive ticks of the new value, preventing flicker at cave
  mouths / overhangs.
- `caveActive(CaveMode mode, boolean stableEnclosed)` — `ON`→true, `OFF`→false,
  `AUTO`→`stableEnclosed`.

The tick handler feeds the raw scan result through `debounce`, stores the stable
`enclosed` flag and the roof world-Y in `AbyssMapState`.

## Cut placement (cut-away roof)

When cave-active, the depth cut is placed just **below the roof above the player**:
`caveCutShiftedY(roofWorldY, sector) = MapGeometry.shiftY(roofWorldY - 1, sector)`.
Scanning down from there reveals the whole chamber/passage — tunnel floors above eye level
included — not just the floor underfoot. The cut follows the player as the roof changes.

**Precedence** (both maps compute band top the same way):
1. Manual Ctrl+scroll slice, if engaged (`mapDepthActive`) — wins.
2. Cave roof-cut, if cave-active and a roof was found.
3. Normal eye-level follow (v1.6.0 default).

`Reset`/`R` clears `mapDepthActive` back to auto, as today. If cave-active but no roof was
found (edge case in `ON` mode under open sky), fall back to the eye-level follow cut.

## Depth shading (blend)

- New `MapMode.CAVE` alongside `RELIEF`/`VANILLA`. When cave-active, both maps render in
  `CAVE` automatically; the player's `V` relief/vanilla choice applies only when not
  cave-active.
- In `MapTileRenderer.renderTile`, the `CAVE` branch keeps the surface-finding logic, then
  for each floor cell blends the real block colour with a depth-gradient colour:
  `out = ColorMath`-style blend, `base` weight ~0.55, `depth` weight ~0.45.
- **Depth palette** (pure `depthPalette(double t)`, `t` clamped to `[0,1]`): a two-stop
  gradient, deep = cool/dark (e.g. `0x203A66`) → shallow = warm/light (e.g. `0xE0C060`),
  linear-interpolated. `t = (h - bandBottom) / CAVE_DEPTH_RANGE`, where
  `bandBottom = bandTopY - CAVE_DEPTH_RANGE` and `CAVE_DEPTH_RANGE = 128`. Floors just
  under the roof read warm/light, deep floors read cool/dark; same-height floors share a
  wash so the layout is legible while block hues remain.
- Tiles are cached per `mode` + quantized band (`bandKey`), so `CAVE` tiles cache exactly
  like the others — no pipeline changes.

## Keybind language file

Add `src/main/resources/assets/mia_aperture_mod/lang/en_us.json` naming every keybind so
they are identifiable and rebindable in Controls → Miscellaneous:
- `key.mia_aperture_mod.open_map` → "Open Abyss Map"
- `key.mia_aperture_mod.toggle_cull` → "Toggle Aperture Cull"
- `key.mia_aperture_mod.reset_view` → "Reset Map Depth"
- `key.mia_aperture_mod.cave_mode` → "Cycle Cave Mode"

(The keybinds were already registered as rebindable `KeyMapping`s; this only fixes their
display names.)

## Files

- `MapSettings.java` — `CaveMode` enum + `caveMode` field + clamp in `fromJson` path.
- `MapConfig.java` — persist `caveMode`.
- `MapMode.java` — add `CAVE`.
- `MapTileRenderer.java` — `CAVE` depth-blend branch + pure `depthPalette`.
- `CaveDetector.java` (new, pure) — `debounce`, `caveActive`.
- `AbyssMapState.java` — `enclosed` flag, `roofWorldY`, `caveCutShiftedY`, and cave-aware
  band-top selection (extend the existing `mapBandTopShifted` usage / add a wrapper).
- `MinimapRenderer.java` + `AbyssWorldMapScreen.java` — use the cave cut + `CAVE` mode when
  cave-active.
- `MiaApertureModClient.java` — register the `C` keybind (cycle), per-tick roof/enclosure
  scan feeding `CaveDetector`.
- `MapSettingsScreen.java` — a Cave Mode cycle button.
- `assets/mia_aperture_mod/lang/en_us.json` (new) — keybind names.
- Tests: `CaveDetectorTest` (debounce hysteresis; `caveActive` truth table),
  `MapTileRendererTest` (CAVE column: two floors at different heights get different
  depth-blended colours; higher floor reads warmer), `MapMode`/`MapSettings`/`MapConfig`
  round-trip for `caveMode`.

## Testing

- Unit: debounce needs `DEBOUNCE_TICKS` consistent ticks to flip; `caveActive` truth table;
  `depthPalette` endpoints + midpoint; CAVE render blends toward the palette and orders by
  height; `caveMode` persists through a `MapConfig` round-trip.
- In-game: descend into a cave — within ~8 ticks the maps switch to depth-shaded cave view
  showing floors under the roof; surface again and it reverts. `C` cycles AUTO/ON/OFF with a
  message; `ON` forces it above ground, `OFF` disables underground. Ctrl+scroll still
  overrides the cut; `R` resets. All four keybinds show friendly names in Controls.

## REVISION 2026-07-09 (v1.9.0) — after in-game + Xaero side-by-side

The v1.8.0 build was wrong: pastel/flat, no tunnels, laggy. A Xaero caves screenshot
comparison identified three fixes, now shipped in v1.9.0:

1. **Skip-overburden cave scan (not surface-at-cut).** `MapTileRenderer` cave scan now
   descends past the solid overburden until it enters an air void, then draws the first
   solid below it (the cave floor). Columns that never open into air stay transparent —
   rendering **black**, which is what makes the tunnel network (single-block passages
   included) pop. This replaced "first solid below the cut", which filled every column
   and killed contrast.
2. **Height-brightness shading, real colours (not a colour tint).** Cave floors render as
   the real block colour scaled by brightness over a tight range (`CAVE_DEPTH_RANGE=48`,
   `CAVE_MIN_BRIGHT=0.30`→`CAVE_MAX_BRIGHT=1.35`) — grey height-relief like Xaero. The
   blue→gold `depthPalette` blend is removed.
3. **No roof-tracking cut → lag fixed.** Cave mode reuses the normal stable eye-level (or
   manual-slice) cut; `AbyssMapState.effectiveBandTop`/`caveCutShiftedY` and the
   `caveRoofFound`/`caveRoofWorldY` fields are removed. The roof scan now only sets
   `caveEnclosed` for AUTO activation. Ctrl+scroll moves the plane to explore depths.

Also (separate from caves): **global colour punch.** `ColorMath.punch` boosts saturation
(`1.4`) and contrast (`1.12`) on every land block colour in `MapTileRenderer`, fixing the
washed-out muddy look of the normal RELIEF/VANILLA maps. Grey stays grey, so cave relief
is unaffected. Palette/brightness/punch strengths are all tunable constants.

## Out of scope

True thin-slab cross-section rendering (decided against — cut-away floors read better);
per-column independent roof detection (single player-roof cut is enough and cheap);
auto-detection tuning UI (scan distance / debounce are constants, tunable in code).
