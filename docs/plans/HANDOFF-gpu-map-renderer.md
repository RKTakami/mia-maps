# HANDOFF — GPU Map Renderer (Rust-native) — 2026-07-20

Resume the **subagent-driven** execution of `docs/plans/plans/2026-07-20-gpu-map-renderer.md`
(spec: `docs/plans/specs/2026-07-20-gpu-map-renderer-design.md`). Work on `main`, NO worktrees.

## Where we are — Tasks 0–5 done; Task 5 awaiting ONE in-game confirmation

| Task | State |
|---|---|
| 0. GL-texture-id spike | ✅ PASSED in-game. Accessor: `((com.mojang.blaze3d.opengl.GlTexture) tex.getTexture()).glId()`. **Renderer must `glColorMask(TRUE×4)`** (MC leaves alpha-writes off). |
| 1. `map-native` crate skeleton + JNI loader | ✅ committed `c454621` |
| 2. Greedy mesher (Rust, TDD) | ✅ committed `e6be385` |
| 3. Rust initGL + FBO clear | ✅ committed `b545675`, blue-clear confirmed in-game |
| 4. MapMatrix (MVP) | ✅ committed `9e9f54f` **+ Y-flip fix (this handoff commit)** |
| 5. uploadGrid + render (mesh draw) | ✅ committed `a641e89`; **greedy-meshed terrain renders solid+colored in-game** — see "PENDING" below |
| 6. OrbitScene clean rewire + `gpuRender` toggle + fallback | ⬜ next |
| 7. Whole-Abyss `AbyssSpanStore.toGrid` | ⬜ |
| 8. Multiplatform native build (win/mac/linux CI) | ⬜ (only `map_native.dll` built so far) |
| 9. Build/install/verify + measure Axiom bar | ⬜ |

## PENDING — first thing tomorrow (in-game, needs the owner)
The greedy-meshed terrain **renders correctly** (solid, crisp, colored — big win). Two bugs were found
and a **fix is installed but NOT yet visually confirmed**:
- It rendered **upside-down** AND back-face cull removed everything (both = one Y-mirror).
- **Fix applied (`MapMatrix.java`, committed):** negate the projection Y row. This flips it right-side-up
  AND corrects winding parity, so `CullFace(BACK)` was re-enabled in `renderer.rs`.
- **Owner must relaunch + open the 3D view (`J`) and confirm:** right-side-up + solid.
  - If right-side-up + solid → Task 5 fully done → proceed to Task 6.
  - If right-side-up but see-through/holes/inside-out → flip winding: in `renderer.rs render()` add
    `gl::FrontFace(gl::CW);` (one line). Rebuild+install.
  - If still upside-down → move the flip to the blit V instead of the projection.

The installed jar (`…/Mine In Abyss Modpack/mods/mia-maps-0.1.8-beta.jar`) has the Y-flip build.

## UNCOMMITTED throwaway — `OrbitScene.java` (do NOT ship as-is)
`OrbitScene.java` has a **TEMP Task 5 verify wire** (uncommitted) that routes the LIVE (non-whole) path
through the GPU renderer: added volatile fields `gpuReady, gpuFocusX/Y/Z, gpuYaw, gpuPitch, gpuDist`;
in `buildFrame` (worker) under `if (!whole && MapNative.available())` it calls
`VoxelCloud.sampleGrid(...)` + `OrbitGpuRenderer.submit(grid)` and stashes the camera; in `render()`
(render thread) after `texture.upload()` it builds `MapMatrix.orbit(gpuFocusX/Y/Z, gpuYaw,gpuPitch,gpuDist,
toRadians(70), 1f, near=1f, far=20000f)` and calls
`OrbitGpuRenderer.render(mvp, ((GlTexture) texture.getTexture()).glId(), texSize)`.
**Task 6 replaces this** with the clean, `gpuRender`-toggle-gated integration (default on when
`MapNative.available()`), and keeps the CPU path (LOD-gated cubes + `smooth3d`) as the fallback. Then
`git checkout -- OrbitScene.java` is NOT enough (it has the LOD-gate commit) — reimplement cleanly.

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
