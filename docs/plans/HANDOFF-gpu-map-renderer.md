# HANDOFF — GPU Map Renderer (Rust-native) — 2026-07-20

Resume the **subagent-driven** execution of `docs/plans/plans/2026-07-20-gpu-map-renderer.md`
(spec: `docs/plans/specs/2026-07-20-gpu-map-renderer-design.md`). Work on `main`, NO worktrees.

## Where we are (2026-07-21) — GPU renderer WORKING for live + whole-Abyss

The GPU greedy-mesh renderer is **live and validated in-game**: solid, crisp, correctly-coloured,
right-side-up, smooth (off-thread meshing), covers the frustum, and whole-Abyss renders the complete
column with no box cutoff.

| Task | State |
|---|---|
| 0. GL-texture-id spike | ✅ `glId()`; renderer must `glColorMask(TRUE×4)` |
| 1. `map-native` crate + JNI loader | ✅ `c454621` |
| 2. Greedy mesher (Rust, TDD) | ✅ `e6be385` |
| 3. Rust initGL + FBO clear | ✅ `b545675` |
| 4. MapMatrix (MVP) | ✅ `9e9f54f`; **projection Y negated** (GPU-only, `652cce6`/`31e1c6b`) for right-side-up |
| 5. Mesh draw (uploadGrid+render) | ✅ renders solid/colored in-game (`652cce6`) |
| — off-thread meshing | ✅ `5df9d05` — worker meshes (`nMeshGrid`+stage), render thread only uploads+draws (`nRender`). Killed the pan/zoom hitch. |
| — colours | ✅ `dc2e495` — fragment shader `.bgr` swizzle (ARGB int → LE bytes = BGRA) |
| — coverage + finer LOD | ✅ `20c30d0`/`cdd32e2` — sample ~3× extentXZ (frustum), `G_MAX_GPU=256`, cap at lod 4 (Voxy has no lod 5) |
| 7. Whole-Abyss via GPU | ✅ `7396274` — `OrbitScene.wholeGrid()` materializes the `AbyssSpanStore` mip → dense grid → same pipeline. No box cutoff. |
| 6. Clean rewire + `gpuRender` toggle | ⬜ NEXT — replace the temp `OrbitScene` wire |
| 8. Multiplatform natives (mac/linux CI) | ⬜ only `map_native.dll` (Windows) built |
| 9. Verify + measure Axiom bar | ⬜ |

## Key facts / decisions (all verified in-game on the owner's AMD RX 6600)
- **AMD FBO quirk (load-bearing):** `DrawElements` no-ops into a freshly-attached FBO until
  `gl::CheckFramebufferStatus` is queried; a transparent clear compounded it. Fix in `renderer.rs draw()`:
  query completeness + **opaque black clear**. Keep both.
- **Back-face cull is OFF** (`gl::Disable(CULL_FACE)`): greedy-mesh winding is reversed vs this MVP; the
  depth test keeps it correct. Perf follow-up: reverse winding (swap 2 indices/tri) or `FrontFace(CW)`, then
  re-enable `CullFace(BACK)`.
- **Two-thread native model:** `Ctx { pending: Mutex<Option<PendingMesh>>, gl: Mutex<GlState> }`. Worker
  calls `nMeshGrid` (CPU greedy mesh → `stage`); render thread `nRender` (adopt staged mesh → upload → draw).
- **CPU render is KEPT** (owner decision) as: (1) the depth-buffer source for 3D-view **click-to-place**
  (Shift+R-click waypoint, R-click focus, via `unprojectOffset`/`depthAt`) + overlay occlusion — GPU depth
  readback is unsafe (`glReadPixels` crashes on this AMD card); (2) the fallback when the native is missing
  (non-Windows) or fails to load. When the GPU path draws it **owns the texture** — the CPU upload is skipped
  so the coarse CPU render no longer flashes during a mesh rebuild.
- Live view still has an inherent **box edge** at extreme zoom (a box is a box); **whole-Abyss** is the
  boxless overview.

## STILL UNCOMMITTED / temp
- `OrbitScene.java`'s GPU wiring is committed but marked **TEMP Task 5/7 verify** — Task 6 replaces it with
  the clean `gpuRender`-toggle-gated integration (default on when `MapNative.available()`), and should also
  consider skipping the CPU *raster* (not just its upload) when the GPU is active to save worker time, while
  keeping a cheap depth pass for click-to-place.
- Remaining residual **hitch on pan/zoom** = the GL VBO upload of a large new mesh on the render thread
  (meshing itself is off-thread). Mitigations if wanted: chunk the upload / cap whole-Abyss mesh size.

## Key facts / gotchas (verified this session)
- Toolchain: `cargo` 1.91 on PATH; `map-native/` mirrors the fork's `voxy-native/` (copied `build.rs`,
  `gl.rs`; GL 4.6 core via gl_generator, accessed `gl::Foo(...)`). Gradle `buildMapNative`/`copyMapNative`
  → `processResources` (and `sourcesJar dependsOn copyMapNative`) bundle the DLL into the jar.
- Map texture is **square** `texSize×texSize`; `OrbitView` blits it `s=min(w,h)` centered (letterbox bars
  on the wide axis are NORMAL — a full-texture clear looks like a centered square, not full screen).
- Mesh worldPos in shader: `(uOrigin + aPos) * uCell` (origin in cells, aPos in cells) → shifted-column
  block coords, matching the OrbitCamera focus (also shifted). Live grid source: `VoxelCloud.sampleGrid`.
- Native `println!` goes to the process stdout, NOT `latest.log` (Log4j only) — don't expect DIAG lines
  in mclo.gs pastes; use on-screen visuals or a Log4j logger if you need captured output.
- Performance bar (spec §5.5): static mesh → upload once, redraw with MVP only; steady frame = one
  `nRender`. Measure in Task 9 (target sub-ms map draw).

## Interop
Logged to `docs/INTEROP.md`: cross-world LOD merge DROPPED (fork thread may park it); MIA Maps building
this self-contained Rust-native GPU renderer (no Voxy change; read contract unchanged).

## Also unreleased (pre-existing, separate)
The 2D-map waypoint create/navigate feature + the Phase-1 LOD-gated smooth3d work are on `main` but not
cut into a release (would be v0.1.9-beta). Don't release mid-GPU-work; cut once the GPU path lands.
