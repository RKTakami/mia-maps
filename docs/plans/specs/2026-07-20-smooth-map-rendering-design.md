# Smooth Map Rendering (3D Marching Cubes + 2D interpolation) — Design

**Date:** 2026-07-20
**Goal:** Make MIA Maps render smooth (non-blocky) at **all** zoom levels — the 3D orbit view via
Marching-Cubes iso-surface meshing, and the 2D map via interpolation/supersampling. This is
**Phase 1** (CPU, reusing the existing rasterizer). A GPU-meshed renderer is **Phase 2**, scheduled
as its own spec.

## Problem

- **3D orbit view** draws every surface voxel as a **hard axis-aligned cube** (`OrbitScene.drawCube`)
  of size `cellSize = 1 << lvl`. `lvl` climbs with zoom (`while ((extentXZ >> lvl) > G_MAX) lvl++`),
  so cubes are 1 block up close but **16–32-block cubes at wide zoom** (16/32/64 in the whole-Abyss
  view) → chunky. LOD steps in hard jumps, and `VoxelCloud:260` decimates the point cloud (`stride`)
  when it exceeds `maxPoints`, so some zooms look worse than others.
- **2D map** is an orthographic per-cell colored-tile texture, nearest-neighbor scaled, with per-cell
  relief shading → blocky when zoomed and stepped in its shading gradients.

## Scope

**In:** (A) 3D orbit Marching Cubes over the existing CPU rasterizer; (B) 2D-map interpolation
smoothing. The two are independent and could ship separately.

**Out (noted, not built here):**
- Smoothing the **actual Minecraft world** — MIA Maps does not render it. Distant terrain is Voxy's
  domain (fork thread; smooth-LOD meshing is a large future effort — logged in `docs/INTEROP.md`);
  near/real-block smoothing is a separate NoCubes-style mod. Out of scope.
- **Phase 2 GPU-instanced/meshed renderer** — separate spec; it becomes the throughput path if
  Phase 1's CPU meshing/rasterizing proves too slow at full detail.
- **Rust mesh-gen** — Phase 2+, only if measured as the CPU bottleneck.

---

## Component A — 3D orbit Marching Cubes

### `OrbitMesher` (new, pure, unit-tested)

A dependency-free class (no `me.cortex.voxy.*` / `net.minecraft.*`) that turns a voxel occupancy
grid into a smooth triangle mesh.

- **Input:** `boolean[] opaque` + `int[] argb` (the grid `VoxelCloud.fill`/`sample` already builds),
  dimensions `gX,gY,gZ`, `cellSize`, and the grid origin in world/shifted coords.
- **Output:** a mesh as flat primitive arrays for cheap rasterization —
  `Mesh(float[] positions /*x,y,z per vertex*/, float[] normals, int[] colors /*per vertex*/, int[] tris /*index triples*/)`.
- **Smoothness mechanism:**
  1. Build a **density field** at grid corners by low-pass filtering the binary occupancy (each
     corner density = fraction of the 8 incident cells that are solid; equivalently a 2×2×2 box).
     This replaces hard 0/1 steps with a continuous field.
  2. Run **Marching Cubes** at iso = 0.5 over the field (standard 256-case edge table; vertices
     interpolated along edges by the density crossing). Produces a rounded surface instead of cubes.
  3. **Normals** from the density gradient (central differences) → smooth per-vertex shading.
  4. Optional light **Laplacian smoothing** pass (average each vertex with its edge neighbours, 1–2
     iterations) for extra organic smoothness. Toggle/const so it can be tuned or disabled.
- **Colors:** each emitted vertex takes the ARGB of the nearest solid cell to its position.
- **Tests (pure):** a solid sphere occupancy → a closed, roughly spherical mesh with outward
  normals and vertex count in a sane range; a single solid cell → a small closed mesh; all-air →
  zero triangles; a flat solid slab → a near-planar mesh with up-facing normals; the classic
  ambiguous MC cases resolve without holes (watertight for a filled region).

### Rendering integration (`OrbitScene`)

- Replace the `drawCube`-per-point path with: build the `OrbitMesher.Mesh` from the occupancy/color
  grid, then rasterize its triangles through the **existing `fillTri`** (z-buffer + shading already
  present). Reuse the camera projection (`BeaconGeometry.project`).
- **Both data sources feed the mesher:** the live sample path (`VoxelCloud` grid) and the
  whole-Abyss path (`AbyssSpanStore` → occupancy grid built from the span cells).
- **Drop decimation** on the mesh path — a mesh has far fewer primitives than cube faces, so full
  detail is affordable and consistent across zoom (removes the `VoxelCloud:260` stride behavior for
  this path).
- `VoxelCloud` gains a small entry point that returns the occupancy+color grid (it already computes
  it internally for `sample`); the mesher consumes that instead of the `List<Point>` cube list.
- Shading (`LX/LY/LZ` light, saturation/contrast) is preserved; smooth vertex normals replace the
  6 fixed face normals.

### Settings

- `MapSettings.smooth3d` (boolean, default **true**). When false, keep the legacy cube path — cheap
  insurance and a nice before/after comparison. Config-persisted; a Settings toggle.

---

## Component B — 2D map smoothing

Layered, cheapest-first — each stands alone:

1. **Bilinear filtering on the map blit.** The composed map `DynamicTexture` is currently sampled
   nearest-neighbour; switch its GL MIN/MAG filter to LINEAR so upscaling is smooth. Small change,
   large visual win.
2. **Interpolated relief shading.** `MapTileRenderer` computes slope/relief per cell; interpolate
   the height/normal field (bilinear) so shading gradients are smooth instead of per-cell steps.
   The interpolation helper is pure/unit-tested.
3. *(Optional, may defer)* **Supersampled compose** — render the map texture at a higher internal
   resolution and downscale, for extra sharpness+smoothness. Heavier; only if 1–2 aren't enough.

Applies to both the fullscreen map and the minimap (same composed texture / renderer).

---

## Components / files

| File | Change | Tested? |
|---|---|---|
| `map/OrbitMesher.java` | new — pure Marching-Cubes mesher (+ density field, normals, smoothing) | yes |
| `map/VoxelCloud.java` | expose occupancy+color grid entry point for the mesher | existing |
| `map/OrbitScene.java` | render the mesh via `fillTri`; drop decimation on mesh path; smooth normals | no (raster) |
| `map/AbyssSpanStore.java` | occupancy-grid accessor for the whole-Abyss mesh path | maybe |
| `map/MapSettings.java` / `MapConfig.java` | `smooth3d` toggle | yes |
| `map/MapTileRenderer.java` | interpolated relief shading (+ pure interp helper) | partial |
| `client/AbyssWorldMapScreen.java` / `map/MapCompositor.java` / `map/MinimapRenderer.java` | LINEAR blit filter | no |

## Testing

- **Pure unit tests:** `OrbitMesher` (sphere/slab/cell/empty/watertight/normals); the 2D
  interpolation helper (bilinear correctness).
- **In-game:** 3D orbit is smooth at every zoom incl. whole-Abyss (no chunk, no holes, no
  decimation gaps); colors correct; the `smooth3d` toggle flips cleanly. 2D map is smooth when
  zoomed with smooth relief gradients. **Measure frame time** on a whole-Abyss frame — this number
  decides whether Phase 2 (GPU) is needed.

## Honest expectation-set

Meshing makes it **smooth** at every zoom; *fineness* is still bounded by Voxy's 16-block data floor
(`MAX_LOD_LAYER = 4`) — genuinely detailed up close, smooth/organic/low-poly at the whole-Abyss
extreme. Still a night-and-day change from chunky cubes.
