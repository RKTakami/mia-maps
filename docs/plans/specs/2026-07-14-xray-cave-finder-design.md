# X-Ray / Cave-Finder Display Mode — Design

**Date:** 2026-07-14
**Status:** Approved (owner), ready for implementation plan
**Target release:** v0.1.5-beta

## Goal

Add a map display mode that reveals **caves and air pockets embedded in rock/dirt** — an "x-ray" view that sees through solid terrain. It applies to the **minimap**, the **fullscreen map**, and the **3D voxel view**. The 2D map is *layered*: it shows the cave-floor terrain **plus** a "hollow here" tint. The 3D view can ghost the outer shell or show only interior cave surfaces.

## Context (existing code this builds on)

- `MapMode` enum currently has `RELIEF`, `VANILLA`, `CAVE`. `CAVE` is used **automatically** by `CaveDetector`/`MapSettings.caveMode` — the minimap force-switches to it when the player is physically enclosed in a cave. It is **not** on the fullscreen `V` render-mode cycle (which only flips `RELIEF ↔ VANILLA`).
- `MapTileRenderer.renderTile` is the pure, unit-tested column rasterizer: for each of the 32×32 columns in a tile it scans a top-to-bottom stack of Voxy sections, finds a surface cell, and colors it (relief/vanilla/cave shading). This runs on the **background map worker**, never the render thread.
- The existing `CAVE` scan (`caveScan`) descends from the band top, skips solid overburden until it enters an air void (`sawAir`), then draws the first solid below. On **open ground** the sky counts as air, so it just redraws the surface — it only reveals sub-surface caves when the depth slice is already underground. The new X-ray mode fixes this by explicitly skipping the surface.
- The 3D view (`OrbitView` + `OrbitScene`) renders surface voxels sampled by `VoxelCloud.sample` (a cell is "surface" if opaque with an air neighbor). Cave walls are already surface voxels; they are simply occluded by the outer shell.

**Do NOT conflate** this with `CaveDetector`/`caveMode` — that stays as the automatic in-cave minimap switch. X-ray is a new, user-selected mode.

## 1. Detection — new `MapMode.XRAY` column scan

Add `XRAY` to `MapMode`. In `MapTileRenderer.renderTile`, an x-ray branch scans each column top-to-bottom through the **whole section stack** (owner choice: full column to layer floor, not a fixed depth or the slice band):

1. **Skip sky** — descend past air cells above ground.
2. **Find surface** — the first opaque cell from the top. Skip past it (do not treat the surface as a cave).
3. **Scan below the surface** to the stack floor, tracking:
   - `voidCount` — total air cells encountered below the surface (how hollow the column is).
   - **Topmost cave**: the first air cell below the surface is the cave ceiling; the first opaque cell below *that* is the cave **floor** (`floorId`, `floorY`), captured for the detail layer.

A column with `voidCount == 0` is solid rock → output transparent (nothing to reveal). Pure helpers so this is unit-testable in isolation from the color math.

## 2. Rendering — layered (floor detail + hollow tint)

Where a cave exists, each pixel composites two layers:

- **Base (floor detail):** the topmost cave floor's terrain color (`floorId`), shaded by depth + slope like the current `CAVE` branch (`CAVE_*` constants), so you see what's actually down there.
- **Tint (hollowness):** an overlay whose intensity scales with `voidCount`, blended over the base. Small pockets → faint; large caverns → strong. Ramp maps `voidCount` (clamped to a max, e.g. ~24 cells) to a blend factor.

**Tint color:** cool cyan→white ramp (reads as "air/empty"). Route dots are also cyan but are small bright markers drawn *on top* as an overlay, and dig markers are amber — the cave base tint stays distinguishable. (Owner may swap the hue during spec/plan review; single constant.)

Solid columns render transparent/black, which is what makes the tunnel network legible.

## 3. Mode selection — V-cycle + Settings

- The `V` key cycle becomes **Relief → Vanilla → X-ray → (Relief)**, in both `AbyssWorldMapScreen` (fullscreen) and wherever the HUD cycles mode. The minimap and fullscreen both read `AbyssMapState.mapRenderMode`, so both follow automatically.
- `MapSettingsScreen` gets a mode control/label reflecting the three modes (persisted via `MapConfig` like other settings).
- **Auto-CAVE suppression:** the compositor currently picks `caveActive ? CAVE : mapRenderMode`. When the user has explicitly selected `XRAY`, skip the `caveActive` override so their choice sticks (X-ray already reveals caves). All other auto-CAVE behavior is unchanged.

## 4. 3D voxel view — ghost ↔ cave-only toggle

The 3D cloud already includes cave-wall voxels; they are hidden behind the outer shell. Classify each surface voxel cheaply as **outer shell** vs **interior/cave**:

- A surface voxel is **interior** if there is solid terrain **above it in its column** (under cover); **outer shell** if it has open sky above. This is a cheap per-column check against the `boolean[] opaque` grid `VoxelCloud.fill` already builds — no flood fill.

Add a `covered` flag to `VoxelCloud.Point` (or an equivalent parallel signal), computed during sampling. A 3D x-ray toggle in `OrbitView` (own key: **`X`**) cycles three states (owner choice: toggle between both):

- **Off** — normal full surface (today's behavior).
- **Ghost shell** — outer-shell voxels drawn semi-transparent (low alpha), interior/cave voxels opaque/highlighted.
- **Cave-only** — outer shell skipped, only interior cave surfaces drawn.

The 3D toggle is independent of the 2D `mapRenderMode` (a view-local control), so the owner can x-ray in 3D without changing the 2D map mode and vice-versa.

## 5. Architecture & testing

- **Pure + tested:** the x-ray column scan (void detection, cave-floor pick, `voidCount`) and the tint-intensity mapping live in `MapTileRenderer` as pure functions with JUnit tests: solid column → no cave (transparent); a buried void → detected floor + nonzero tint; stacked caves → topmost floor chosen; deeper/bigger void → hotter tint. The voxel `covered` classifier goes in `VoxelCloud` with tests (voxel under overburden → covered; open-sky voxel → not covered).
- **Worker pipeline reuse:** `XRAY` flows through the existing `TileKey` (already includes `mode`) and `MapTileCache` unchanged; all scanning stays on the `MIA-Map-Worker` thread.
- **Files touched:**
  - `map/MapMode.java` — add `XRAY`.
  - `map/MapTileRenderer.java` — x-ray branch + pure helpers.
  - `map/MapCompositor.java` — auto-CAVE suppression when mode is `XRAY`.
  - `client/AbyssWorldMapScreen.java` + HUD mode-cycle site — `V` cycle includes `XRAY`.
  - `client/MapSettingsScreen.java` — mode label/toggle.
  - `map/VoxelCloud.java` — `covered` classification on sampled points.
  - `client/OrbitScene.java` / `client/OrbitView.java` — 3D x-ray toggle (`X`) + ghost/cave-only rendering.
- **Tests:** `MapTileRendererTest` (new x-ray cases), `VoxelCloudTest` (covered classifier).

## Out of scope / deferred

- Connectivity analysis (which caves link to the surface / to each other) — the `covered` heuristic is a cheap proxy, not a flood fill.
- Highlighting ores or specific blocks — this is air-void detection only.
- Changing the automatic in-cave `CAVE` minimap behavior beyond the XRAY suppression above.

## Open decisions (owner may confirm at review)

- Tint hue: cyan→white (default) vs orange vs other.
- 3D x-ray key: `X` (default).
