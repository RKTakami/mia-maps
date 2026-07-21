# GPU Map Renderer (Rust-native) — Design Spec

**Date:** 2026-07-20
**Status:** Draft for owner review
**Supersedes (for the 3D view):** the CPU point-cloud rasterizer in `OrbitScene` (`drawCube`/`drawMesh`)
**Related:** Phase 1 smooth-map work (`2026-07-20-smooth-map-rendering-design.md`) — kept as the
fallback path.

## 1. Goal

Render the 3D orbit map as **crisp, solid, gap-free** terrain at **every zoom**, by replacing the
CPU point-cloud rasterizer with a **self-contained GPU renderer** driven by a **Rust native module**.

**Performance bar:** render as quickly and efficiently as the **Axiom** mod (GPU instancing + indirect
draws). See §5.5 — the map views *static* terrain, so meeting this bar is very achievable.

The current CPU path draws each surface voxel as an individual cube — a point cloud — which is:
- **gappy** (separate projected cubes don't tile; unexplored columns show as black stripes),
- **chunky** at wide zoom (LOD cells become 16–32-block cubes),
- **mushy** when iso-smoothed (Surface Nets rounds the same cloud).

All three are symptoms of having **no solid mesh**. The fix is **greedy voxel meshing** (merge coplanar
same-material faces into solid quads — how Minecraft/Voxy render chunks) drawn on the GPU, where full
detail at all zooms is affordable.

## 2. Non-goals (v1)

- The **2D fullscreen map / minimap** — stays on the CPU tile renderer. Any 2D GPU work is a later spec.
- Smoothing the **actual in-world Minecraft terrain** — that is Voxy's domain (fork thread), not MIA Maps.
- Replacing overlays (waypoints, route, dig markers, mob markers) — they keep projecting via
  `BeaconGeometry`; the GPU camera must match so they still line up.
- Reusing the fork's `voxy_native` module directly — it renders Voxy's in-world sections in its own
  format/camera. We build a **separate** `map-native` crate (may lift patterns/code from it).

## 3. What stays (unchanged)

- **Voxy read path:** `acquireIfExists(lvl,x,y,z) → copyDataTo(long[]) → release`, `MAX_LOD_LAYER = 4`,
  `AbyssUtil` shift math. The GPU renderer consumes the SAME occupancy+colour grid the CPU path builds.
- **`OrbitCamera`** (yaw/pitch/distance/focus) — becomes an MVP matrix handed to Rust.
- **The worker/handoff model:** grid sampling stays off-thread on the orbit worker; the texture is
  blitted into the GUI by the render thread exactly as today.
- **Overlays and HUD** in `OrbitView` — draw over the rendered texture as now.
- **`smooth3d` + LOD-gate CPU path** — retained as the fallback when the native module is unavailable.

## 4. Architecture

### 4.1 Data flow

```
Voxy (read-only)
  ├─ live orbit:  VoxelCloud.sampleGrid(...)          ┐
  └─ whole-Abyss: AbyssSpanStore.toGrid(level)        ┘→ occupancy[] + argb[] grid  (Java, worker thread)
        → JNI  MapNative.uploadGrid(handle, opaque, argb, gX,gY,gZ, cell, ox,oy,oz)
                     → Rust: greedy-mesh → VBO/IBO (GPU)                              (render thread)
        → JNI  MapNative.render(handle, mvp[16], glTexId, width, height)
                     → Rust: draw mesh into FBO(colour=glTexId, depth) on MC's GL context
        → Java: blit glTexId into the map GUI  (existing DynamicTexture blit path)
```

**Self-contained:** our own grid, our own greedy mesh, our own shader, our own FBO. It never binds or
mutates Voxy's render state. This is the crucial difference from the **retired** v1.0 FBO/viewport path,
which hijacked Voxy's GPU pipeline and fought its fog/occlusion/layer isolation.

### 4.2 The Rust crate — `map-native/`

New crate in the MIA Maps repo, mirroring `voxy-native`:

- `Cargo.toml`: `crate-type = ["cdylib"]`, deps `jni = "0.21"`, `rust-embed` (embed shaders),
  build-dep `gl_generator` (generate GL 4.x bindings). No separate GL context — bindings are loaded
  into **MC's current context** via `gl::load_with(proc_address)`.
- `src/lib.rs` — JNI entry points (see 4.4).
- `src/mesher.rs` — greedy meshing of the occupancy grid → interleaved vertex buffer
  (position, normal, packed RGBA) + index buffer. May start from `voxy-native/src/mesher.rs`.
- `src/renderer.rs` — owns per-map GL objects (VAO/VBO/IBO, FBO, depth renderbuffer), uploads mesh,
  binds the Java-provided colour texture to the FBO, draws with an orthographic/perspective MVP.
- `src/shader.rs` + embedded `.vsh`/`.fsh` — trivial: transform by MVP, N·L directional + ambient
  shade, output the vertex colour. Matches the current look (`LX/LY/LZ`, `AMBIENT`, saturation/contrast
  can be baked into vertex colour Java-side or done in-shader).
- generated `gl` bindings module.

### 4.3 Java side

- **`MapNative`** — native loader (extract `map_native.{dll,so,dylib}` for the current OS/arch from the
  jar to a temp dir, `System.load`), `native` method declarations, and `initGL(long procAddress)` called
  once on the render thread with GLFW's `glfwGetProcAddress` so Rust can resolve GL symbols. Exposes a
  boolean `available()` — false if load/init failed (drives the fallback).
- **`OrbitGpuRenderer`** — the render-thread half: allocates the target texture (a `DynamicTexture`-style
  GpuTexture, same as today's `OrbitScene.TEXTURE`), obtains its **raw GL texture id** (accessor/reflection
  on the 1.21.11 `GpuTexture`/`GlTexture`), and calls `uploadGrid` (when the grid changed) + `render`
  each frame the camera moves. Returns the texture id/Identifier to blit.
- **`OrbitScene` rewire** — when `MapNative.available()` and the GPU path is enabled, route the live and
  whole-Abyss frames through `OrbitGpuRenderer` instead of the CPU rasterizer; otherwise keep the CPU
  path. The worker still produces the grid (both sources); the GL upload/draw runs on the render thread.

### 4.4 JNI surface (initial)

```
long  createContext()                         // allocate a renderer handle (GL objects lazily on first render)
void  destroyContext(long handle)
void  initGL(long procAddress)                // once; load GL entry points into MC's context
void  uploadGrid(long handle,
                 boolean[] opaque, int[] argb,
                 int gX, int gY, int gZ, int cell,
                 int originX, int originY, int originZ)   // greedy-mesh + (re)upload VBO
void  render(long handle, float[] mvp16, int glTexId, int width, int height)  // draw into FBO(colour=glTexId)
```

`mvp16` is column-major, built Java-side from `OrbitCamera` + the same FOV/near/far the CPU path uses.
Grid coordinates are cell-space; Rust multiplies by `cell` and adds the origin (matches
`OrbitMesher`/`sampleGrid`).

### 4.5 Grid sources

- **Live orbit:** `VoxelCloud.sampleGrid(engine, colors, focus…, extent…, lvl)` — already exists.
- **Whole-Abyss:** new `AbyssSpanStore.toGrid(level)` returns `VoxelCloud.Grid` by materializing the
  span model's chosen mip into a dense `opaque[]`+`argb[]`. Level is chosen (as `buildWholeCloud` does
  today) so the grid stays a **bounded single VBO** at coarse cells (16–64 blocks) — correct for that
  zoom, and greedy meshing makes it solid rather than gappy.
  - **Spike / open:** if a single whole-column grid is too large at the desired level, chunk the column
    into a few vertical grids, each its own upload+draw into the shared FBO. Decided during the spike;
    does not block v1 (fall back to a coarser level that fits).

## 5. Greedy meshing

Per axis (±X, ±Y, ±Z), sweep slices of the occupancy grid and merge maximal rectangles of adjacent
cells that are solid, share the same `argb`, and whose neighbour across the face is air (an exposed
face). Emit one quad per merged rectangle (two triangles) with the face normal and the cell colour.
Result: **crisp block ledges, fully solid surfaces, no gaps**, and far fewer primitives than per-cube
faces. Pure and unit-testable (Rust `#[test]`): all-air → 0 quads; a solid slab → its exposed faces
merged into 6 big quads; a solid ball → a closed surface with no interior faces.

## 5.5 Performance target — Axiom-class

**Bar:** render as quickly and efficiently as the Axiom mod. Axiom's verified technique (from the jar
inspection logged in `docs/INTEROP.md`) is **pure GPU instancing + indirect draws** — no compute, no
native code required for the speed; the lever is *how* geometry is fed to the GPU.

**Our advantage:** Axiom's cost is **dynamic editing** (re-meshing as the user edits). The map views
**static** terrain — while orbiting, the mesh is unchanged and **only the camera moves**. So the
steady-state per-frame cost is a **single draw with a new MVP uniform** — nothing to re-upload. Meeting
Axiom's throughput is very achievable; we match its *draw* path and skip its *edit* path.

**Techniques (all in v1):**
- **Upload once, draw many.** `uploadGrid` (greedy-mesh + VBO/IBO upload) runs **only when the grid
  changes** (zoom/pan/level change, or a new whole-Abyss snapshot). Orbiting/rotating re-runs only
  `render(mvp,…)` — no re-mesh, no re-upload.
- **Immutable / persistent GPU buffers** (`glBufferStorage`) for the mesh; a small persistently-mapped
  uniform buffer (or a plain uniform) for the MVP. Zero per-frame CPU→GPU geometry copy.
- **Single indirect draw** for the live grid (`glDrawElements` / `glDrawElementsIndirect`); the chunked
  whole-Abyss uses **`glMultiDrawElementsIndirect(Count)`** — one call for all vertical sub-grids
  (the same primitive `voxy_native` uses), so draw-call count stays O(1).
- **Greedy meshing to cut primitives AND overdraw** — merged exposed-face quads mean far fewer vertices
  than per-cube instancing and no hidden interior geometry. (This is why we greedy-mesh rather than
  Axiom-style per-block instancing: for static terrain a merged mesh is *more* efficient than instancing
  every cell.)
- **Compact interleaved vertex format** — position as grid-local ints/normalized shorts, packed normal
  (a few bits), packed RGBA8; small vertices = better cache/bandwidth.
- **Back-face cull** + depth test; no per-frame state churn (bind VAO/program once, draw, unbind).
- **Meshing off the critical path** — greedy meshing runs in Rust triggered from `uploadGrid`; the grid
  itself is produced on the worker thread, so only the (fast) buffer upload touches the render thread.

**Acceptance:** steady-state orbit frame adds **sub-millisecond** GPU time for the map draw; a grid
change (re-mesh + upload) is a one-off cost that does not stall the render thread beyond a normal
texture upload. Measured in-game vs the CPU path (§9) — this is the number that proves the Axiom bar.

## 6. Threading & lifecycle

- **Worker thread (existing `MIA-Orbit-Raster`):** samples/materializes the grid; signals the render
  thread when a new grid is ready (same handoff structure as today's cloud/mesh).
- **Render thread only:** `initGL`, `uploadGrid`, `render`, all FBO/GL. GL is single-threaded on MC's
  context — never call these off the render thread.
- **Lifecycle:** create the renderer handle on first use; `destroyContext` on `OrbitScene.reset()` /
  disconnect (free GL objects). Native temp DLL extracted once per process.

## 7. Multiplatform (from day one)

- Cargo cross-compile matrix → `map_native.dll` (win x64), `libmap_native.so` (linux x64),
  `libmap_native.dylib` (mac universal). Bundled under `src/main/resources/natives/` and loaded by
  OS/arch. Extend the existing macOS CI (`.github/workflows/macos-build.yml`) with a native build step;
  Windows builds locally; Linux via CI.
- Loader picks the right artifact; `available()=false` on any missing/failed native → CPU fallback.

## 8. Fallback

If the native module is missing or `initGL` fails, `OrbitScene` uses the existing CPU path (LOD-gated
cubes + `smooth3d`). The map always renders something; the GPU path is an enhancement, not a hard
dependency. A `MapSettings` toggle (`gpuRender`, default on when available) allows A/B and a manual
disable.

## 9. Testing

- **Rust unit tests** (`mesher.rs`): greedy correctness — all-air/all-solid/slab/ball; watertight
  (no interior faces); quad count sanity; colour per face.
- **Java:** `MapNative.available()` gating; MVP matrix build (pure, unit-tested against a known
  projection); grid-source parity (`toGrid` dims/origin match `sampleGrid`'s conventions).
- **In-game:** crisp + solid + gap-free at close, mid, wide, and whole-Abyss zoom; overlays
  (waypoints/route/dig/mobs) still align; toggle GPU off → CPU fallback renders; frame time vs the CPU
  path; no GL errors; no leak across map open/close and disconnect.

## 10. Risks

- **GL id from a 1.21.11 `GpuTexture`** — the one fiddly integration point (the new blaze3d texture
  abstraction hides the raw id). **First spike task:** obtain the GL id for a `DynamicTexture` and prove
  an FBO renders into it and blits. If no clean accessor exists, Rust owns the texture and returns an id
  Java wraps — fallback plan.
- **FBO/GL state hygiene** — save/restore any GL state we touch so MC's GUI rendering after the blit is
  unaffected (the retired path's lesson: don't leak state). Self-contained draw + explicit unbind.
- **Whole-Abyss grid size** — see 4.5 spike (coarse level or vertical chunking).
- **Native packaging/loading** across OSes — mitigated by mirroring `voxy_native`'s loader + CI.
- **Overlay depth parity** — the GPU MVP must match `BeaconGeometry.project` so `depthAt`/unproject and
  overlay occlusion stay correct (or overlays move onto the same MVP).

## 11. Components / files

| File | Change | Tested? |
|---|---|---|
| `map-native/` (Rust crate) | new — `lib.rs` (JNI), `mesher.rs`, `renderer.rs`, `shader.rs`, gl bindings, shaders | yes (Rust) |
| `map/MapNative.java` | new — loader, JNI decls, `initGL`, `available()` | partial |
| `map/OrbitGpuRenderer.java` | new — render-thread grid upload + draw + texture id | no (GL) |
| `map/MapMatrix.java` (or util) | new — `OrbitCamera` → MVP (pure) | yes |
| `map/AbyssSpanStore.java` | add `toGrid(level)` → `VoxelCloud.Grid` | maybe |
| `map/OrbitScene.java` | route live + whole-Abyss through `OrbitGpuRenderer` when available; keep CPU fallback | no |
| `map/MapSettings.java` / `MapConfig.java` | `gpuRender` toggle (default on when available) | yes |
| `.github/workflows/*` + build | native build/bundle matrix (win/mac/linux) | — |

## 12. Honest expectation-set

Greedy-meshed GPU rendering makes the 3D map **crisp AND solid at every zoom** — it fixes chunky,
mushy, and gappy at once, and full detail (no decimation) becomes affordable. *Fineness* is still
bounded by Voxy's 16-block data floor (`MAX_LOD_LAYER = 4`) at the widest views, but it will be solid
and readable rather than a sparse cloud. On performance, the **static-terrain** nature of the map
(upload-once, redraw-with-a-matrix) puts the **Axiom-class** bar (§5.5) well within reach — the steady
orbit frame is a single indirect draw. The main cost is the new native-build/packaging surface and
the one-time GL-texture-id integration.
