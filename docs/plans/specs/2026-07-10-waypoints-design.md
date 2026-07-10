# Waypoints — Design

**Date:** 2026-07-10
**Status:** Approved (conversational brainstorm)

## Purpose

The last roadmap backlog item for MIA Maps: placeable, named, colour-coded waypoints —
created at the player or from pasted coordinates, rendered on both maps, shareable through
game chat with an accept/reject prompt, and shown as in-world HUD beacons. Built and
released in three independently-shippable phases.

## Data model & persistence (foundation for all phases)

- `Waypoint` — immutable record: `Waypoint(String name, int x, int y, int z, WaypointColor color)`.
  `x/y/z` are raw Minecraft coordinates (the shareable form the X/Y/Z readout already uses).
- `WaypointColor` — enum of 8 presets, each exposing `int argb()`:
  RED, ORANGE, YELLOW, GREEN, AQUA, BLUE, PURPLE, WHITE.
- `WaypointStore` — holds `Map<String, List<Waypoint>>` (serverKey → waypoints). Resolves
  the current server's list, and add/update/remove mutate it and persist. Keyed **per
  server**: `serverKey(Minecraft)` = the multiplayer server address, or `sp:<levelName>`
  for singleplayer, sanitized to `[A-Za-z0-9._-]`.
- **Persistence**: `config/mia_maps_waypoints.json` via Gson (same pattern as `MapConfig`),
  a `{ "<serverKey>": [ …waypoints… ] }` object. Loaded on client init; saved on every
  mutation. A corrupt/missing file yields an empty store (never crashes).

## Phase 1 — Local waypoints (create, render, manage)

### Create
- **Mark here**: a new rebindable keybind (default **B**) opens `WaypointEditScreen`
  prefilled with the player's floored X/Y/Z, a default name ("Waypoint"), and a colour;
  Save adds it to the current server's list.
- **Add from coordinates**: an "Add" button in the manager opens the same
  `WaypointEditScreen` with editable X/Y/Z text fields (for pasting shared coords) plus
  name and colour.
- `WaypointEditScreen`: text fields (name, x, y, z), a colour selector (a row of 8 swatch
  buttons, or a cycling button), Save / Cancel. Save validates the coords parse as ints.

### Render on both maps
- Generalised screen-projection helper in `MapGeometry`:
  `screenOffsetPixel(double deltaBlocks, int blocksAcross, int dim)` =
  `round(dim * (0.5 + deltaBlocks / blocksAcross))`. The existing `playerMarkerX/Y` become
  the `deltaBlocks = -pan` special case (refactor them to call it, keeping their tests).
- **Fullscreen** (`AbyssWorldMapScreen`): for each waypoint, `deltaX = wp.x - centerWorldX`,
  `deltaZ = wp.z - centerWorldZ`; draw a coloured diamond at that pixel plus the name label
  beside it; clamp to the screen edge when off-view (as the player marker does).
- **Minimap** (`MinimapRenderer`): for waypoints within the HUD block radius, compute
  `(wp - player)` scaled to minimap pixels, apply the heading rotation
  (`MinimapMarkers.headingRotationRad`) in heading-up mode, and draw a small coloured dot.
  No labels (too cluttered). Waypoints outside the radius are omitted.
- A pure `MapGeometry.withinRadius`-style check is unit-tested; the draw calls are in-game
  verified.

### Manage
- `WaypointListScreen`, opened from a "Waypoints" button added to the fullscreen map:
  a scrollable list where each row shows a colour swatch + name + `X Y Z`, with **Edit**
  (→ `WaypointEditScreen`) and **Delete** buttons, plus **Add** and **Done**. (Share is
  added in Phase 2.)

## Phase 2 — Chat sharing (export + accept/reject import)

- **Format** (human-readable and parseable): `[MIA:WP] "<name>" <x> <y> <z> <colour>`,
  e.g. `[MIA:WP] "Second Camp" -60 -38 -438 aqua`.
- Pure `WaypointCodec`: `encode(Waypoint)` → the string; `decode(String)` → `Optional<Waypoint>`
  (regex parse; rejects malformed lines and unknown colours). Unit-tested (round-trip +
  rejection).
- **Export**: a **Share** button per row in `WaypointListScreen` sends the encoded line to
  chat via `client.player.connection.sendChat(...)` (the user clicking Share is the
  consent to post publicly).
- **Import**: a `ClientReceiveMessageEvents.ALLOW_CHAT` listener scans incoming player-chat
  for a `[MIA:WP]` line. On a hit it registers the decoded waypoint in a pending map under
  a short id, and re-renders the chat line with two appended clickable buttons —
  `[✓ Accept]` (runs client command `/miawp accept <id>`) and `[✗ Reject]`
  (`/miawp reject <id>`). Client commands are registered via
  `ClientCommandRegistrationCallback`. **Accept** adds the pending waypoint to the current
  server's list and confirms; **Reject** drops it. Nothing enters the list without an
  explicit Accept, so shares can't be injected. Unknown/duplicate ids are ignored safely.

## Phase 3 — In-world HUD beacons

- `BeaconRenderer` projects each waypoint's world position to screen space using the game
  camera's projection × modelview matrices (relative to the camera position), rendered in a
  `HudRenderCallback` pass. Points behind the camera (clip w ≤ 0) and off-screen points are
  drawn as an arrow clamped to the screen edge pointing toward the waypoint; on-screen
  points draw a coloured icon + name + integer distance (`player.position().distanceTo`).
- **Toggle**: `MapSettings.showBeacons` (persisted) with a rebindable keybind (default **N**)
  and a settings-panel button. When off, no beacons render.
- The projection math is the fiddly core; the screen-clamp/arrow-direction helper is pure
  and unit-tested, the projection itself is in-game verified.

## Testing

- Unit: `WaypointColor.argb` presence for all 8; `WaypointCodec` encode→decode round-trip,
  and rejection of malformed/unknown-colour strings; `WaypointStore` add/update/remove and
  `serverKey` sanitisation; `WaypointConfig` (or store) Gson round-trip incl. per-server
  keys and corrupt-input fallback; `MapGeometry.screenOffsetPixel` (center when delta 0,
  edge at ±half span) and the minimap radius check; the beacon edge-clamp direction helper.
- In-game per phase: (1) mark-here + add-from-coords create; markers on both maps track
  correctly while panning/zooming; edit/delete persist across restart and are per-server.
  (2) Share posts the line; a received `[MIA:WP]` line shows Accept/Reject; Accept adds,
  Reject dismisses. (3) beacons appear at the right spots with name + distance, edge-arrow
  when off-screen, toggle hides/shows them.

## Files (by phase)

- **Foundation/Phase 1**: `Waypoint.java`, `WaypointColor.java`, `WaypointStore.java`,
  `WaypointConfig.java`, `MapGeometry` (generalised helper), `WaypointEditScreen.java`,
  `WaypointListScreen.java`; edits to `AbyssWorldMapScreen` (draw + button),
  `MinimapRenderer` (dots), `MiaApertureModClient` (B keybind, store load).
- **Phase 2**: `WaypointCodec.java`, chat listener + `/miawp` client commands (in a new
  `WaypointChat.java`), Share button in `WaypointListScreen`.
- **Phase 3**: `BeaconRenderer.java`, `MapSettings.showBeacons` + toggle keybind + settings
  button.

## Out of scope

Click-on-map placement (decided against); freeform RGB colour (presets only); waypoint
teleport/commands; grouping/folders; a beacon "beam" column (icon + label only);
cross-server sync beyond the manual chat share.
