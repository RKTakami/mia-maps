# 3D Route-Finding to Waypoints — Design

**Date:** 2026-07-12
**Status:** Approved (conversational brainstorm)

## Purpose

Navigational aids that route the player to a chosen waypoint through the Abyss terrain:
find a traversable path over the local terrain, optionally suggesting where to **build
bridges** to span gaps (e.g. between trees), shown both in the **3D Orbit View** (for
planning) and as an **in-world trail** (for walking it heads-up). The route recomputes as
the player moves. The mod only **highlights** where to build — it never places blocks.

Builds on the existing 3D Orbit View (`OrbitScene`/`OrbitView`, Voxy voxel sampling via
`VoxelCloud`) and the waypoint system (`WaypointStore`, `Waypoint`, `WaypointColor`).

## Scope (decided in brainstorm)

- **Range:** local / in-view. A full step-by-step route over the terrain the mod can sample
  around the player, oriented toward the destination. **Progressive**: if the destination is
  beyond the routing box, route to the reachable point nearest the goal and extend as the
  player travels closer (and more terrain is known).
- **Gaps:** **walkable-only by default**, with a **toggle to allow bridges**. Bridges are
  highlighted "build here" spans — never auto-built.
- **Display:** in the **3D view** (planning) **and** an **in-world trail** (walking). Re-routed
  as the player moves.
- **Destination:** one **active destination** waypoint, chosen via a **"Navigate"** button in
  the waypoint list or by clicking a waypoint marker in the 3D view; **"Stop"** clears it.
  Active destination lives in the client session (not persisted for v1).

## Approach

**A\* over Voxy voxel data** (chosen over live-world routing and coarse height-field
alternatives). Reuses the Voxy opaque/air data `VoxelCloud` already reads; keeps the heavy
logic in a pure, unit-testable pathfinder; bridging falls out naturally from solid-vs-air
knowledge. LOD-accuracy limitation (1-block near the player, coarser far) is acceptable for
planning — the route sharpens as the player closes in.

## Components

- **`TraversabilityGrid` (pure, unit-tested):** given a bounded box of Voxy opaque data (an
  `opaque[]` grid from the same `acquireFinest` sampling `VoxelCloud` uses), expose
  `standable(x,y,z)` = ground below is opaque **and** the cell + the cell above are air
  (2-block headroom). Provides the node set + neighbour queries for the pathfinder.
- **`Pathfinder` (pure A\*, unit-tested):** A\* over standable cells from a start cell toward
  a goal cell. Move types + default costs (all tunable):
  - **Walk** to an adjacent standable cell, step **up 1** / down 1 — cost 1 (diagonal ~1.4).
  - **Drop** to a standable cell up to **`maxFall`=3** blocks below — cost + fall penalty.
  - **Jump** a horizontal air gap up to **`maxJumpGap`=1** (tunable ~3 for sprint-jumps) to a
    standable landing at ≤ current height — cost + jump penalty.
  - **Bridge** (only when the bridging toggle is on): cross a gap up to **`maxBridge`=16**
    blocks to a reachable landing — high cost (prefers real terrain) + the spanned cells are
    recorded as bridge segments ("build here").
  - Heuristic: octile / 3D distance to the goal. **Node-expansion cap** for bounded cost.
  - Returns a `Route` (below), including the **partial** path to the nearest reachable node
    when the goal isn't reached within the box.
- **`Route` model:** ordered list of world points (the path) + list of **bridge spans**
  (start/end world points to highlight) + status: `FOUND` / `PARTIAL` / `NO_ROUTE`.
- **`RouteService`:** samples the traversability box (bounded size + LOD, oriented from the
  player toward the destination), runs the pathfinder, caches the `Route`. Runs on a
  **background thread** (pattern from `MapWorker`); **recomputes** when the player moves past
  a threshold or the destination/bridging-toggle changes — no render-thread stalls.
- **Destination selection:** an active-destination waypoint reference held in the client
  (e.g. a static in `MiaApertureModClient` or a small `RouteState`). Set via a **"Navigate"**
  button per waypoint in `WaypointListScreen` and via clicking a waypoint marker in
  `OrbitView`; cleared via **"Stop"**.

## Rendering

- **3D view (`OrbitView` + `OrbitScene`):** draw the route as a bright line through the
  projected path points (occluding into terrain via the depth buffer, like the compass arms);
  **bridge spans** in a distinct colour/style; the destination waypoint highlighted. Projected
  through the same orbit camera (`OrbitScene.projectHud`).
- **In-world trail:** in the HUD render pass (like `BeaconRenderer`), draw a trail of
  path-point markers + **"build here"** markers at bridge spans, projected via the player-eye
  camera basis `BeaconRenderer` already derives. Follows/updates as the player moves.

## Traversal parameters (tunable, live)

`stepUp=1`, `maxFall=3`, `maxJumpGap=1`, `maxBridge=16`, routing-box size + LOD, node cap,
re-route movement threshold, bridging toggle (default **off**).

## Testing

- **Unit (pure):** `TraversabilityGrid.standable` on synthetic opaque grids (ground+headroom
  cases, edges). `Pathfinder` on synthetic grids: finds a straight walk path; steps up/down
  within limits and not beyond; drops within `maxFall`; jumps a `maxJumpGap` gap and refuses
  a wider one when bridging off; **bridges** a wide gap only when the toggle is on and records
  the span; reports `NO_ROUTE` when boxed in; returns `PARTIAL` to the nearest node toward an
  unreachable goal.
- **In-game per phase:** route appears in 3D and matches walkable terrain; in-world trail is
  followable; bridge flags land on real gaps; re-routes sensibly as you move.

## Phasing (each independently shippable)

1. **Core + 3D route:** `TraversabilityGrid` + `Pathfinder` + `RouteService` + destination
   selection + route drawn in the 3D view (walkable-only).
2. **In-world trail:** render the route + follow it while playing.
3. **Bridging:** toggle + bridge edges in the pathfinder + "build here" flags in both views.

## Out of scope

- **Auto-building** (mod only highlights where to build).
- **Long-range full pathfinding** across the whole Abyss (local + progressive only).
- **Block-accurate live-world routing** (Approach 2) — could layer in later for pinpoint
  accuracy near the player.
- Persisting the active destination across sessions (v1 keeps it in-session).
