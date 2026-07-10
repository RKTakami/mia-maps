# Player Marker (Fullscreen Map) + X/Y/Z Readout — Design

**Date:** 2026-07-09
**Status:** Approved (conversational brainstorm)

## Purpose

Answer "where am I?" on both maps. First of the post-v1.6.0 backlog items. Adds a
"you are here" marker to the fullscreen map and a shareable raw-coordinate readout to
both the HUD and the fullscreen map. Purely additive overlays — no change to the
compose / worker / depth pipeline.

## Feature 1 — Player marker on the fullscreen map

A dot with a facing chevron (matching the minimap's yellow arrow) drawn at the
player's real screen position on the fullscreen map.

### Screen position (pure, testable)

The fullscreen map centers on `player + pan`, so the player is at screen center when
unpanned and shifts by the pan otherwise. The compose maps a world block to a texture
pixel as `frac = 0.5 + (block − centerShifted) / blocksAcross`, and the texture fills
the screen, so the same fraction is the screen fraction. Since
`centerShifted − playerShifted = mapX` (pan; `shiftX` is linear), the player's screen
fraction is `0.5 − mapX/blocksAcrossX` on X and `0.5 − mapZ/blocksAcrossZ` on Z.

New pure helper `MapGeometry`:
- `playerMarkerX(double mapX, int blocksAcrossX, int width)` → `(int) Math.round(width * (0.5 - mapX / blocksAcrossX))`
- `playerMarkerY(double mapZ, int blocksAcrossZ, int height)` → `(int) Math.round(height * (0.5 - mapZ / blocksAcrossZ))`

`blocksAcrossX`/`blocksAcrossZ` are the same values `AbyssWorldMapScreen.render`
already computes (`base = 256/mapZoom`, X scaled by screen aspect).

### Facing chevron

Rotate the chevron by `Math.toRadians(yaw + 180)` — the same convention
`MinimapRenderer` uses for the north-up arrow (the fullscreen map is always north-up).
Draw a small dot beneath it. Rendered with `ctx.pose()` push/translate/rotate/pop,
reusing the minimap's chevron `fill` pattern.

### Off-screen clamping

If the raw marker pixel falls outside `[0,width) x [0,height)` (panned far away),
clamp X and Y independently to the nearest edge (with a few px inset) so the marker
stays visible at the edge pointing toward the player's location, instead of vanishing.
Clamping is applied to the drawn dot/chevron position; the facing rotation is
unchanged.

## Feature 2 — X/Y/Z readout (HUD + fullscreen)

Raw, floored Minecraft coordinates — the copy-pasteable line players share:
`X <floor getX>  Y <floor getY>  Z <floor getZ>`.

- **HUD** (`MiaApertureModClient.drawHud`): one new line under the existing
  `Depth:` / `Layer:` lines below the minimap, same `textX` column. Row order becomes
  `Depth` (`textY`), `Layer` (`+10`), `X/Y/Z` (`+20`), and the optional `View:` line
  moves to `+30` so it does not collide. Colour: white (`0xFFFFFFFF`). Bump
  `textBlockH` (currently 34) to ~44 so the bottom-edge flip-above logic still keeps
  the taller block on-screen.
- **Fullscreen** (`AbyssWorldMapScreen.drawMapOverlay`): one new line in the info
  overlay block (below Slice), white.
- The Abyss `Depth:` / `Layer:` readouts are unchanged.

## Files

- `MapGeometry.java` — add `playerMarkerX` / `playerMarkerY` (pure).
- `AbyssWorldMapScreen.java` — draw the marker (dot + chevron, clamped) in `render`
  after the map blit; add the X/Y/Z line in `drawMapOverlay`.
- `MiaApertureModClient.java` — add the X/Y/Z line in `drawHud`.
- `MapGeometryTest.java` — unit tests for the marker helpers.

## Testing

- `playerMarkerX`/`playerMarkerY`: unpanned (`mapX=0`) → screen center; pan by half the
  view span (`mapX = blocksAcrossX/2`) → screen edge (`x=0`); positive `mapX` yields
  `x < width/2` (consistent with the compose transform).
- In-game: marker sits on the player when unpanned, tracks correctly while panning and
  zooming, chevron points where you face, clamps to the edge when panned away; X/Y/Z
  line matches F3 on both HUD and fullscreen map.

## Out of scope

Player marker on the minimap (already centered there with an arrow), coordinate systems
other than raw MC X/Y/Z (decided: raw is the shareable form), and any persistence.
