# 2D Map Waypoint Interaction — Design

**Date:** 2026-07-19
**Feature:** click-to-create and click-to-navigate for waypoints on the fullscreen 2D map, mirroring
the 3D orbit view's gestures.

## Problem

The fullscreen 2D map (`AbyssWorldMapScreen`) already *displays* waypoints and the active route
trail, but has **no click interaction** — only `mouseDragged` (pan) and `mouseScrolled` (zoom). So a
player cannot create a waypoint by clicking the map, nor click a waypoint to route to it. (Waypoints
can currently only be made via the `B` key, the 3D view, or chat-accept.) Display itself works;
the six existing waypoints are simply toggled invisible on purpose.

## Goal

On the fullscreen 2D map, mirror the 3D view:
- **Shift + right-click** an empty spot → create a waypoint there (opens the edit screen prefilled).
- **Left-click** a waypoint marker → route to it (`RouteService.setDestination`).

## Reference: the 3D view (`OrbitView.mouseClicked`)

- Left-click (button 0): if within 8px of a recorded waypoint hit-box `{sx, sy, wx, wy, wz}` →
  `RouteService.setDestination(wx, wy, wz)`.
- Right-click (button 1) with Shift → build world coords, open
  `new WaypointEditScreen(this, "New Waypoint", "Waypoint", wx, wy, wz, WaypointColor.RED, onSave)`
  where `onSave` does `waypoints.add(key, w)` + `WaypointConfig.save(...)`.

The 2D map reuses the **same** edit screen, `onSave`, and `RouteService.setDestination`.

## Design

### Coordinate mapping (screen → world)

The 2D map is world-X/Z centered on the player: `centerX = player.getX() + AbyssMapState.mapX`,
`centerZ = player.getZ() + AbyssMapState.mapZ`. Markers are drawn with
`screenOffsetPixel(deltaBlocks, blocksAcross, dim) = round(dim * (0.5 + deltaBlocks/blocksAcross))`.

Add the pure inverse to `MapGeometry` (unit-tested):

```java
// Inverse of screenOffsetPixel: pixel -> world-block delta from the map centre.
public static double worldDeltaFromPixel(double pixel, int blocksAcross, int dim) {
    return (pixel / (double) dim - 0.5) * blocksAcross;
}
```

Then a click at screen `(cx, cy)` maps to:
- `worldX = centerX + worldDeltaFromPixel(cx, lastBlocksAcrossX, width)`
- `worldZ = centerZ + worldDeltaFromPixel(cy, lastBlocksAcrossZ, height)`

**Y limitation (accepted):** a top-down slice click cannot determine depth. Prefill the new
waypoint's Y with the player's current world Y (`player.getY()`); the user adjusts it in the edit
screen's Y field. X/Z are exact.

### Create (Shift + right-click)

In `AbyssWorldMapScreen.mouseClicked`, on button 1 with `GLFW_MOD_SHIFT`:
1. Compute `wx = floor(worldX)`, `wz = floor(worldZ)`, `wy = floor(player.getY())`.
2. `key = WaypointStore.currentServerKey(minecraft)`.
3. Open `new WaypointEditScreen(this, Component.literal("New Waypoint"), "Waypoint", wx, wy, wz,
   WaypointColor.RED, w -> { waypoints.add(key, w); WaypointConfig.save(waypointConfigPath(),
   waypoints); })` — identical to the 3D flow. New waypoints default `visible = true`, so the created
   one shows immediately.

### Navigate (left-click a waypoint)

- During the waypoint render loop, record each **drawn** (visible) waypoint's clamped screen
  position + world coords into a per-frame list `waypointHits` (`{sx, sy, wx, wy, wz}`) — analogous
  to `OrbitView.waypointHits`.
- In `mouseClicked` on button 0: if within 8px of any hit, call
  `RouteService.setDestination(wx, wy, wz)` and consume the click (return true, so no pan starts).
- Only visible waypoints are drawn, hence clickable; invisible ones remain reachable via the
  Waypoints list. The route trail already renders on the 2D map.

### Guardrails

- **Buttons first:** let the screen's widgets handle the click before the map logic (so the
  Waypoints/Settings/etc. buttons keep working). Only run map interaction if no widget consumed it.
- **Pan preserved:** left-drag still pans (`mouseDragged` unchanged). Navigate only fires when a
  left-click lands on a waypoint hit-box; otherwise the click falls through and a drag pans normally.
- **Right-click without Shift:** unused on the 2D map (no 3D-style "focus" concept here) — no-op.

### Discoverability

- Extend the map's on-screen hint line (currently "Drag to pan | Scroll to zoom | Ctrl+scroll to
  slice | …") with "Shift+right-click: add waypoint | click waypoint: navigate".
- Add the two gestures to `HelpContent` (Map tab) with a test asserting the text is present.

## Components / files

| File | Change | Tested? |
|---|---|---|
| `map/MapGeometry.java` | `worldDeltaFromPixel` inverse helper | yes (round-trip) |
| `client/AbyssWorldMapScreen.java` | `mouseClicked` (create + navigate); record `waypointHits` in render; hint text | no (LWJGL/screen) |
| `map/HelpContent.java` | Map-tab gesture docs | yes |

## Testing

- Unit: `worldDeltaFromPixel` round-trips against `screenOffsetPixel` (pixel → delta → pixel within
  rounding) across sizes/zooms; `HelpContent` Map tab mentions the gestures.
- In-game: Shift+right-click creates a waypoint at the clicked X/Z (Y = player, editable) and it
  appears; left-click a visible waypoint routes to it (trail draws); buttons and pan still work.

## Out of scope

- Click-to-navigate to an arbitrary (non-waypoint) point — not requested.
- Minimap interaction (it's a non-interactive HUD).
- The separate `0,0,0` waypoint-coordinate-capture bug seen in existing data (distinct issue).
