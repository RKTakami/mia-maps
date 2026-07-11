# 3D Orbit View — Design

**Date:** 2026-07-12
**Status:** Approved (conversational brainstorm)

## Purpose

A mouse-orbit 3D view of the Abyss around the player — a stylized voxel **point-cloud**
rendered from Voxy's world data — for three goals: **planning descent routes** (spot
shafts/drops), **reading the layer stack** (how the layers stack vertically), and a
compelling way to **see the Abyss in 3D**. New, self-contained view; no changes to the
existing top-down maps.

## Overview

A dedicated screen opened by a keybind, centered on the player. Voxy voxels in a region
around the player are sampled at an LOD chosen by zoom, each projected through a perspective
**orbit camera** and rasterized into an off-screen image on a background thread, then
blitted. Air is empty, so shafts/caves read as voids and layers as colour bands. Three view
modes (Solid / X-ray / Cutaway) control how you see inside the structure.

## Architecture & components

- **`OrbitCamera` (pure, unit-tested):** holds orbit state — focus point (player), yaw,
  pitch, distance — and projects a world point to screen: build the camera basis from
  yaw/pitch, transform `(world − focus)` into camera space, perspective-divide to screen
  `(x, y)` + depth. Same math family as `BeaconGeometry` (which this generalises). Clamps
  pitch to avoid gimbal flip.
- **`VoxelCloud` sampler:** given the focus, an extent (blocks), and an LOD, walk the Voxy
  sections in the region (`WorldEngine.acquireIfExists` → `copyDataTo`, as `MapWorker`
  does) and collect **surface voxels** (opaque cells with at least one air neighbour) as
  `(x, y, z, argb)` using the existing colour bake. Bounded to a max voxel count; coarser
  LOD when zoomed out keeps it in budget.
- **`OrbitRenderer` (background thread + off-screen image):** projects the sampled voxels
  with the current `OrbitCamera` into a `NativeImage`/`DynamicTexture` and the view blits
  it. Recomputed when the camera or player position changes (throttled); reused otherwise —
  the same on-demand pattern as the map compositor, kept off the render thread like
  `MapWorker` so dragging stays smooth. A per-pixel depth buffer resolves occlusion in
  Solid mode.
- **`OrbitView` screen:** owns input (left-drag → yaw/pitch, scroll → zoom/extent/LOD),
  triggers recompute, blits the image, draws the HUD (mode, zoom, a compass), and the
  view-mode cycle control.
- **State/persistence:** the chosen view mode (and any tunables) persist via `MapSettings`
  (Gson), as with the other view settings.

## Controls

- **Open:** a new rebindable keybind (default TBD — a currently-free key; confirm at build).
- **Left-drag:** orbit (yaw + pitch about the player).
- **Scroll:** zoom — moves between a tight, detailed local box and a wide, coarse overview;
  the sampling extent and LOD scale together so the voxel budget holds.
- **Mode key / on-screen button:** cycle Solid → X-ray → Cutaway (persisted).
- **Esc / close:** return to game.

## View modes

- **Solid:** opaque points, nearest-wins via the depth buffer — outer surfaces + layer
  stack. (Phase 1.)
- **X-ray:** points drawn semi-transparent, back-to-front, so dense rock reads solid but
  thin walls reveal internal shafts/caves — a translucent volume for route-finding.
- **Cutaway:** discard voxels on the near side of a cutting plane through the focus (facing
  the camera), exposing an open cross-section face; rotate to reslice.

## Phasing (each phase independently shippable)

- **Phase 1 — Orbit cloud (Solid):** `OrbitCamera`, `VoxelCloud` sampler, background
  `OrbitRenderer` (Solid + depth buffer), `OrbitView` screen, open keybind, left-drag
  orbit, scroll zoom + LOD. A working mouse-orbit 3D view with the layer stack visible.
- **Phase 2 — See inside:** X-ray and Cutaway modes + the persisted mode toggle.
- **Phase 3 — Polish:** a "you are here" marker at the focus, per-layer tint/labels,
  optionally waypoints shown in the cloud.

## Performance

- Bounded voxel count (target a few tens of thousands, tuned live); coarser Voxy LOD as
  zoom widens the extent.
- Projection + rasterization run on a worker thread into an off-screen image; the render
  thread only blits. Recompute is throttled and only on camera/position change, so a static
  view is nearly free and dragging degrades gracefully (the image lags a frame under fast
  orbit rather than stuttering the game).

## Testing

- Unit: `OrbitCamera` projection — a point at the focus projects to screen centre; a point
  directly "ahead" along the view axis lands centre; yaw/pitch rotate a known offset to the
  expected screen side; points behind the camera are flagged off-screen. `VoxelCloud`
  surface-voxel selection (an opaque cell with an air neighbour is included; a fully
  enclosed cell is not) on a synthetic section.
- In-game per phase: (1) orbit + zoom feel smooth, the surrounding Abyss and layer bands
  are recognisable; (2) X-ray reveals shafts, Cutaway shows a clean face, the toggle
  persists; (3) the you-are-here marker sits at the player and labels read correctly.

## Out of scope

Solid lit voxel meshing (point-cloud only); free-fly camera or panning the focus off the
player (focus stays on the player for now); editing/placing in 3D; rendering the whole
descent at full detail at once (bounded region + LOD instead).
