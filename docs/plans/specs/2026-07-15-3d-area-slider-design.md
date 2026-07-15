# 3D Area Slider — Design

**Date:** 2026-07-15
**Status:** Approved (owner), ready for implementation plan
**Target release:** v0.1.8-beta

## Goal

Let the 3D voxel view cover **more of the Abyss than the current ~2048-block ceiling**, via a **slider** that sets the maximum area the view can reach. Wider settings trade detail (chunkier voxels) for reach, at roughly **unchanged performance**.

## Why today's ceiling exists

- `OrbitScene.EXTENT = 128`; the sampled area is `extentXZ = EXTENT * zoom`.
- `OrbitView` clamps `zoom` to **16.0** → max ~**2048** blocks.
- The sampler bounds the grid at `G_MAX = 128` cells per axis by choosing a coarser LOD as you zoom out:
  `while ((extentXZ >> lvl) > G_MAX && lvl < MapGeometry.MAX_LVL) lvl++;`
  but `MapGeometry.MAX_LVL = 4` (16-block cells). 128 cells × 16 blocks = **2048 blocks** — the ceiling.
- Going wider at the *same* cell size would explode the grid (4096 blocks → ~50M cells → hundreds of MB), which is the class of problem behind the old 3D freeze. **Rejected.**

## The approach: coarser voxels, constant grid

Allow the 3D path to pick **coarser levels than 4**. The grid cell count stays the same; only the world size of each voxel grows:

| Area | Voxel size | LOD | Grid (x·y·z) | Cost |
|---|---|---|---|---|
| 1024 | 8 | 3 | 128×384×128 | ~same |
| **2048 (default, today's max)** | 16 | 4 | 128×384×128 | current |
| 4096 | 32 | 5 | 128×384×128 | ~same |
| 8192 | 64 | 6 | 128×384×128 | ~same |

Vertical follows the same maths (`VERT_UP/DOWN = 1.5`), so `gY` stays 384 at every step. Point counts (and therefore `OrbitQuality.maxPoints` trimming) stay comparable — the wide view is **chunkier, not sparser**.

## Required supporting fix: downsample fallback in `VoxelCloud`

`VoxelCloud.acquireFinest` only falls back to **coarser** Voxy levels (`lvl + k`, upsampled). Voxy frequently lacks aggregates at levels 5–6, so a wide view would return `null` sections and render **empty** — the same root cause fixed for the 2D map last session in `MapWorker`.

Port that fix: when neither the requested level nor coarser exists, **synthesize the section by downsampling the 8 finer child sections** via `LodUpsampler.mipInto` (topmost-non-air representative), recursive and bounded (`MAX_FINER_DEPTH = 2`), mirroring `MapWorker.synthesizeFromFiner`.

**Aliasing note:** `VoxelCloud.acquireFinest` documents that its `k == 0` result *aliases* `scratch` (unlike `MapWorker`, which clones). The synthesis loop must therefore **consume each child immediately** (`mipInto` it into the parent octant) before acquiring the next child. This is already the natural structure of the loop.

## The setting

- New `MapSettings.orbitAreaBlocks`, default **2048** (preserves today's behaviour exactly).
- Discrete steps: **1024 / 2048 / 4096 / 8192**. (Owner chose to stop at 8192; 16384 would mean 128-block voxels — too chunky to be useful.)
- Persisted via `MapConfig` (Gson picks the field up automatically); `fromJson` clamps/snaps to the nearest valid step so hand-edited or legacy configs can't produce a bad value.
- **UI:** a **slider** in `MapSettingsScreen` (owner asked for a slider), snapping across the four steps and labelled with the current value, e.g. `3D Area: 4096 blocks`. Mirrors the existing minimap-size slider pattern (`AbstractSliderButton`).

## Wiring

- `OrbitView`: derive the zoom ceiling from the setting instead of the hard-coded 16 —
  `zoomMax = orbitAreaBlocks / OrbitScene.EXTENT` (1024→8, 2048→16, 4096→32, 8192→64). Keep the 0.15 lower clamp. Clamp the live `zoom` down when the setting is reduced so the view can't be left beyond the new ceiling.
- `OrbitScene`: raise the level ceiling used when choosing the LOD from `MapGeometry.MAX_LVL` (4) to a 3D-specific `ORBIT_MAX_LVL = 6`. `MapGeometry.MAX_LVL` stays 4 — it governs the 2D map's display-level cap and must not change.

## Interaction with existing overview→drill-down (no work needed)

Right-click already un-projects the pixel under the cursor and moves the orbit focus there (`OrbitView.mouseClicked`, `OrbitScene.unprojectOffset`), and sampling is centred on `player + focusOffset`. So the loop **already works** and simply gets better with more reach: zoom out wide → right-click a distant region → zoom in → fine detail there → `R` to recentre. No changes required.

## Testing

- Pure + unit-tested: the step snap/clamp helper on `MapSettings` (`setOrbitAreaBlocks` snaps to the nearest allowed step; out-of-range clamps).
- `LodUpsampler.mipInto` already has round-trip + topmost-solid tests from the 2D map fix; the `VoxelCloud` synthesis reuses it.
- The rest (zoom ceiling, LOD choice, slider) is verified in-game.

## Files

- `map/MapSettings.java` — `orbitAreaBlocks` + steps + snapping setter.
- `map/MapConfig.java` — clamp/snap guard on load.
- `client/MapSettingsScreen.java` — the slider.
- `client/OrbitView.java` — zoom ceiling from the setting + clamp on change.
- `map/OrbitScene.java` — `ORBIT_MAX_LVL = 6` for LOD choice.
- `map/VoxelCloud.java` — downsample-from-finer fallback in `acquireFinest`.
- Test: `test/.../MapSettingsTest.java` — step snapping/clamping.

## Non-goals

- No box/marquee region select (right-click focus already covers overview→drill-down).
- No change to the 2D map's `MapGeometry.MAX_LVL`, quality tiers, or `maxPoints`.
- Not raising the area beyond 8192.
