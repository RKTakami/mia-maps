# Map Depth Slicing + Reset + Fullscreen FPS Cleanup — Design

**Date:** 2026-07-09
**Status:** Approved (conversational brainstorm)

## Problem

1. **Grey maps in ceilinged biomes.** Both maps scan top-down from `playerY + 96`
   and stop at the first opaque block. In the Inverted Forest (stone/dirt ceiling
   above the player) the scan hits the ceiling, so the map shows grey rock instead
   of the forest floor. Root cause: `AbyssMapState.defaultBandTopY` starts the scan
   96 blocks above the player.

2. **No depth control on the minimap.** The fullscreen map honours a custom slice
   (`mapBandCustom` + `scrollTargetCenterY`), but the minimap always uses the fixed
   player-relative band, so there is no way to look at a different layer from the HUD.

3. **FPS stays low after closing the fullscreen map (>30s).** `AbyssWorldMapScreen`
   has no `removed()` cleanup and `MapWorker`/`MapCompositor` state is only reset on
   world disconnect. The tile-worker backlog keeps churning and the 2048² map texture
   (16 MB VRAM) stays resident after the screen closes.

## Design

### Surface-at-cut depth slicing (both maps)

A single shared "cut" line drives both maps. The map scans **downward from the cut
to the first solid surface** — always a readable top-down surface, never a thin
cross-section. Scrolling the cut peels through layers: rock ceiling → canopy →
forest floor → lower cave floors.

- **Default (no slice engaged):** the cut follows the player at eye level, so the
  map shows the floor you are standing on. This auto-follows as you travel up/down.
- **Engaged (Ctrl/Alt + scroll):** the cut becomes an absolute abyss-Y line you can
  drive from your feet down through the whole layer. Reuses the existing gameplay and
  fullscreen scroll handlers — no new scroll bindings.

Shared state (`AbyssMapState`):
- `PLAYER_CEILING_OFFSET = 2` — blocks above the reference line where the scan starts
  (replaces the old `+96`).
- `SCROLL_STEP = 8.0` — blocks per scroll notch (was 16; finer for map use).
- `mapDepthActive` (boolean) — false = cut follows player, true = absolute cut.
- Pure helper `mapBandTopShifted(playerWorldY, sector, depthActive, cutAbyssY)` →
  shifted block Y of the band top. Both `MinimapRenderer` and `AbyssWorldMapScreen`
  call it, removing the duplicated band math and making the minimap honour the cut.
- Band height (scan depth below the cut) stays 320.

Both scroll handlers (`InputHandler.onScroll`, `AbyssWorldMapScreen.mouseScrolled`)
step `scrollTargetCenterY` by `SCROLL_STEP` and set `mapDepthActive = true`. Engaging
the slice keeps the existing Voxy aperture-cull coupling ("what the map shows is what
Voxy culls").

### Reset to player

`AbyssMapState.resetDepth(playerX, playerY)`:
- `scrollTargetCenterY` → the player's current abyss depth
- `mapDepthActive = false`, `scrollActive = false` (cull off), trigger Voxy re-eval
- Fullscreen only additionally recenters pan (`mapX = mapZ = 0`)

Triggers:
- New rebindable keybind `key.mia_aperture_mod.reset_view` (default `R`), handled in
  the client tick (gameplay) and in `AbyssWorldMapScreen.keyPressed` (map open).
- A "Reset" button in the fullscreen map + a mention in the help line for discovery.

### Fullscreen FPS cleanup

- `MapWorker.cancelPending()` — bump generation, clear the queue and pending set,
  **keep** the tile cache so re-opening stays fast.
- `MapCompositor.freeMapTexture()` — unregister + close the 2048² map texture (the
  256² HUD texture stays).
- `AbyssWorldMapScreen.removed()` calls both.
- Temporary on-HUD diagnostic (FPS + worker queue depth) to confirm the residual cost
  is gone in-game; removed before the release build.

## Testing

- Unit-test the pure `mapBandTopShifted`: follow-player mode vs. absolute-cut mode,
  and that a cut equal to the player's abyss depth yields the same band top as follow
  mode (reset seamlessness).
- In-game: verify grey→floor in the Inverted Forest, Ctrl+scroll peels layers on both
  maps, reset returns to the player layer, and FPS recovers after closing the map.

## Out of scope

Thin cross-section ("tomography") rendering, per-block scroll granularity, and a
distinct cave-render mode remain deferred.
