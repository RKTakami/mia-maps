# Minimap Orientation, Shape, Size & Settings Panel — Design Spec

**Date:** 2026-07-08
**Status:** Draft for owner review
**Builds on:** the data-driven map (v1.2.0) and resource-pack colours (v1.3.0 / v1.3.1)

## 1. Goal

Give the HUD minimap user-configurable presentation, controlled from an in-game settings
panel and persisted across sessions:

1. **Cardinal markers** — N / E / S / W ticks shown on both maps, separate from the player
   arrow.
2. **Orientation mode** — north-locked (north at top) or rotate-with-facing (map revolves
   with the player's heading; the cardinal markers orbit together).
3. **Frame shape** — square or round.
4. **Resizable** — minimap on-screen size adjustable via a slider.
5. **Settings panel** — opened from a button on the fullscreen map screen; changes persist.

The fullscreen map stays north-up and pannable (rotation/shape/size are minimap-only). The
player arrow shows facing/heading (already corrected in v1.3.1 to `yaw + 180`).

### Non-goals

- Rotation, shape, or resize for the fullscreen map (stays north-up, screen-filling).
- Minimap zoom / blocks-shown control (fixed for now; possible future work).
- Waypoints/markers beyond the player arrow + cardinal (N/E/S/W) ticks.

### Bundled with this work

- The pending, installed-but-uncommitted v1.3.1 fixes: air/missing-texture no longer
  bakes to opaque magenta (`BlockColorBake` air + missing-sprite guard); facing-arrow
  rotation corrected to `yaw + 180`. These ship in the same release.

## 2. Settings state & persistence

New holder `MapSettings` (in `com.mia.aperture.map` or `state`):
- `orientation`: enum `Orientation { NORTH_UP, HEADING_UP }` (default `NORTH_UP`).
- `shape`: enum `FrameShape { SQUARE, ROUND }` (default `SQUARE`).
- `minimapSize`: int on-screen pixels, clamped `[80, 256]` (default `100`).

`MapConfig` loads/saves these to `config/mia_aperture_map.json` using Gson (bundled with
MC). Loaded once on client init; saved whenever a setting changes in the panel. Missing or
malformed file → defaults (and a fresh file is written). This is the mod's first config
file.

## 3. Minimap rendering: oversample + orientation

**Oversample.** To rotate the minimap without empty corners, the composed HUD texture must
cover more ground than the frame shows. `MapCompositor` composes the HUD texture covering
`OVERSAMPLE × visibleSpan` blocks, where `OVERSAMPLE = 1.5` (≥ √2 so the frame's diagonal is
always within rendered data at any rotation). The HUD `DynamicTexture` internal resolution
is raised to 256² for headroom. Composition remains north-up and on the render thread;
nothing about the bake/worker changes.

**Draw-time orientation** (in `drawHud`, via `context.pose()`):
- `NORTH_UP`: draw the texture unrotated. The **player arrow rotates** to facing
  (`yaw + 180`). The **cardinal markers are fixed**: N top-center, E right-center,
  S bottom-center, W left-center.
- `HEADING_UP`: push pose, translate to minimap center, **rotate so facing points up**,
  draw the centered texture, pop. The **player arrow is fixed pointing up** (no rotation).
  The **cardinal markers orbit together** — N at the screen angle where north lands, with
  E/S/W at +90°/180°/270° from it.

Rotation angle and cardinal-marker angles are computed by a small pure helper (§5) so the
sign/offset is unit-tested rather than guessed (past orientation bugs in this project came
from unchecked signs).

## 4. Frame shape & clipping

The visible minimap is the centered sub-region of the oversampled texture, sized to
`minimapSize`.

- `SQUARE`: clip with `GuiGraphics.enableScissor` to the frame rectangle (screen-space,
  works in both orientations since scissor is axis-aligned and the oversampled texture fills
  the rect). Draw the existing dark background + border.
- `ROUND`: GuiGraphics has no circular scissor, so after drawing the (possibly rotated)
  map, overlay an **opaque bezel ring** (annulus) covering from the circle radius out past
  the square corners — hiding the texture corners and giving a round frame with border. No
  stencil/shader. The ring is drawn procedurally (triangle fan / filled arcs) in the frame
  colour.

## 5. Markers & frame helper

`MinimapMarkers` (pure where possible):
- `cardinalMarkerPos(centerX, centerY, radius, heading, orientation, shape, cardinal)` →
  screen (x, y) for a given cardinal (N/E/S/W). Each cardinal sits at north's screen angle
  + 0/90/180/270°: when `NORTH_UP` these resolve to top / right / bottom / left; when
  `HEADING_UP` they orbit with the map. Placed on the circle for round, clamped to the edge
  for square. Pure, unit-tested for all four cardinals in both modes.
- `headingRotation(orientation, yaw)` → radians to rotate the minimap texture (0 for
  NORTH_UP). Pure, unit-tested.
- Draw helpers for the frame (square/round bezel) and the four cardinal glyphs — in-game
  verified.

The **fullscreen map** shows static cardinal letters at the four screen edges (N top,
E right, S bottom, W left), drawn by `AbyssWorldMapScreen` (always north-up).

## 6. Settings panel

`MapSettingsScreen extends Screen`:
- Orientation cycle button (`North-locked` / `Rotate with facing`).
- Shape cycle button (`Square` / `Round`).
- Size slider (`80`–`256`, label shows current px).
- Done button.
Each change updates `MapSettings` live and calls `MapConfig.save()`.

Opened by a **"Settings" button** added to `AbyssWorldMapScreen` (corner). No new keybind.

## 7. Component list

- Create: `MapSettings` (settings + enums), `MapConfig` (Gson load/save),
  `MapSettingsScreen` (panel), `MinimapMarkers` (angle math + frame/tick draw).
- Modify: `MapCompositor` (oversample HUD texture; 256² internal),
  `MiaApertureModClient.drawHud` (orientation rotate, shape clip/bezel, size, arrow per
  mode, cardinal markers via helper), `AbyssWorldMapScreen` (Settings button + edge
  cardinal letters).
- Also lands: the v1.3.1 air + arrow fixes (already in the working tree, to be committed).

## 8. Testing

- Pure/unit-tested: `MinimapMarkers.headingRotation` (angle per mode, sign correctness at
  the four cardinals) and `cardinalMarkerPos` (N/E/S/W resolve to top/right/bottom/left when
  north-up; correct orbit angle and edge/circle placement for each cardinal when
  heading-up); `MapSettings` clamp of `minimapSize`; `MapConfig` round-trip (write → read →
  equal) and default-on-missing.
- In-game verified: oversample fills the frame at all rotations, round bezel masks corners,
  arrow behaviour per mode, all four cardinal markers placed correctly, slider resize,
  config persists across restart.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Rotation sign/offset wrong (prior project pattern) | isolate in pure `headingRotation`/`cardinalMarkerPos`, unit-test all four cardinals |
| Oversample not enough → corners clip when rotated | factor 1.5 ≥ √2; verify in-game at 45° headings |
| Round bezel leaves corner slivers | bezel outer radius ≥ half-diagonal of the frame; verify in-game |
| `pose()` rotation vs `enableScissor` interaction (scissor is device-space) | square uses screen-space rect scissor independent of pose; round uses overlay, not scissor |
| Config file I/O errors | try/catch → defaults + rewrite; never block the HUD |
