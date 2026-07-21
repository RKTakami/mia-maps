# Interop — MIA Maps ↔ Voxy fork

Two-way coordination between this mod (`mia_aperture_mod`) and the **Voxy fork** `mia-map-voxy`
(`D:\Users\dev\VSCode-Projects\MIA_MAP_VOXY_FORK_project`), developed in a separate the editor
thread.

**Protocol**
- **Broadcast:** when something here affects Voxy (a new dependency on Voxy behavior, a suspected
  Voxy-side bug, a request for a Voxy change or a new jar), append a dated entry to the **Outbound
  log** below (this file, committed in this repo).
- **Awareness:** at session start, read the fork's log at
  `D:\Users\dev\VSCode-Projects\MIA_MAP_VOXY_FORK_project\docs\INTEROP.md`.
- Keep entries short: what/why/action.

---

## What this mod needs from Voxy (summary)

Read-only access, kept fast: `engine.acquireIfExists(lvl,x,y,z)` → `section.copyDataTo(scratch)` →
`section.release()`. Depends on `WorldEngine.MAX_LOD_LAYER = 4`, the `AbyssUtil` shifted-column
math, and the composable storage config (incl. `ReadonlyCachingLayer` for a read-only shared LOD
base). Full contract: the fork's `docs/INTEROP.md`.

---

## Outbound log (MIA Maps → Voxy fork)

### 2026-07-18 — Now running the fork jar; whole-Abyss + persistence context
- The modpack now runs the fork build `voxy-mia-edition-2.5-0c21e3b.jar` (stock jar backed up).
  This mod's read path is unchanged; **in-game verification of the fork is pending** (whole-Abyss
  sweep speed, colors, RAM). If map colors/terrain look wrong or "section unable to load" appears,
  it's likely Voxy-side — log it here for the fork thread.
- **Whole-Abyss 3D view** (this mod, code-complete, WA-T7 verify pending) reads LOD 4 across the
  whole shifted column via `acquireIfExists` — it is the heaviest reader (~17k-section sweep), so it
  is the main stress test of the fork's hot/cold cache and read speed.
- **Cross-world sharing** (build's LOD visible on survive) is desired. Decision: use the fork's
  fixed `ReadonlyCachingLayer` as a **read-only** base so build stays pristine (NOT the destructive
  unified/shared-folder merge). A working `config.json` recipe is in the fork's
  `docs/STORAGE-CONFIG.md`. If MIA Maps automates this later (a mixin/toggle), it will depend on
  that Voxy behavior staying stable.
- No requested Voxy changes open right now. The fork's flush fix already unblocked the read-only
  base.

### 2026-07-18 (later) — INCIDENT: ReadonlyCachingLayer spike corrupted survive's mapper
- A hand-config `ReadonlyCachingLayer` spike on survive (cache=survive, onMiss=build) **corrupted
  survive's Voxy store**: world-join crashed in `Mapper.loadFromStorage` ("Block entry not ordered"
  — an id gap). Cause: `ReadonlyCachingLayer.getIdMappingsData()` serves the **base's (build's)**
  id-mappings while `putIdMapping` writes to the **cache (survive)** → build-space ids mix into
  survive's mapper table; loading survive standalone then sees gaps. (The old broken `flush()`, now
  fixed, worsened it by tearing down mid-session.)
- **Recovery:** reverted stock jar; renamed survive's `…/3b2faae3…/storage` →
  `storage.corrupt-2026-07-18` (786 MB preserved); Voxy rebuilds fresh. Build store untouched.
- **DESIGN IMPLICATION for the cross-world base (the option-2 feature):** the flush fix is
  **necessary but NOT sufficient**. Before "build LOD on survive" is safe, the fork's
  `ReadonlyCachingLayer` must handle mappers correctly — keep the cache's mapper separate from the
  base's, or translate base ids into the cache's id space on read-through (its existing
  `getIdMappingsData` "TODO: replicate onto the cache" is exactly this gap). Do NOT re-enable a
  writable survive store over a build base until solved. A read-only/ephemeral overlay (survive
  never persists base-derived data) is an alternative to explore.

### 2026-07-18 (later) — Handed cross-world hybrid LOD merge to the fork thread
Design direction: offline re-ingest merge (VoxyStoreImporter), survive-precedence, build pristine —
NOT the live ReadonlyCachingLayer (which corrupts mappers). Full brief is in the fork:
`…/MIA_MAP_VOXY_FORK_project/docs/PROPOSED-cross-world-hybrid-lod-merge.md`. Fork thread owns
design+build; MIA Maps will provide any UX/trigger later. Whole-Abyss view is the main beneficiary.

### 2026-07-19 — 2D map waypoint create + navigate (mod-side; no Voxy impact)
- Added to the fullscreen 2D map (`AbyssWorldMapScreen`): **Shift+right-click** to create a waypoint
  at the clicked world X/Z (Y prefilled from player, editable) and **left-click a waypoint** to route
  to it — mirroring the 3D view. New pure `MapGeometry.worldDeltaFromPixel` (screen→world inverse,
  unit-tested); reuses the existing `WaypointEditScreen` + `RouteService.setDestination`.
- **No action for the fork.** Read contract unchanged (`acquireIfExists → copyDataTo → release`,
  `MAX_LOD_LAYER=4`, AbyssUtil). Purely MIA Maps UI. Built + installed for in-game test; not yet
  released (would be v0.1.9-beta). Root cause of the original "waypoints missing" report: the 6
  existing waypoints were user-toggled invisible (working as intended) — the real gap was the missing
  2D click interaction, now added.

### 2026-07-19 (handoff) — Instance jar/store state after this MIA session (fork thread: read this)
- **Active Voxy is STOCK** (`mods/voxy-mia-edition-2.5-normal-version.jar`) — reverted during the
  mapper-corruption incident recovery. The fork's merge jar **`e89df66` is present but DISABLED** as
  `mods/voxy-mia-edition-2.5-e89df66.bak`; fork `0c21e3b` is in `_voxy-fork-hold/`. **To test the
  `/voxy merge-build` feature, re-activate e89df66** (rename `.bak` → `.jar`, remove/hold the stock jar
  so exactly one voxy jar is loaded).
- **Survive's LOD store was RESET** (fresh; corrupt one preserved as `…/survive…/3b2faae3…/storage.corrupt-2026-07-18`).
  A near-empty survive is actually *convenient* for verifying the merge — build data should visibly fill it.
- Design constraint for the merge stands (see the 2026-07-18 incident entry): re-ingest through the
  engine's mapper, never raw-id copy. Build store is intact (1.2 GB).
- MIA Maps jar in `mods/` = `mia-maps-0.1.8-beta.jar`, a dev build with the new 2D-map waypoint
  create/navigate feature (unreleased; would be v0.1.9-beta).

### 2026-07-20 — IDEA for the fork: smooth Minecraft-world/LOD rendering (iso-surface meshing; GPU-first, Rust for mesh-gen)
Out of a MIA Maps rendering brainstorm (making our 3D map non-blocky). This is Voxy's domain, not MIA Maps' — logging it as a downstream idea, not a request.
- **Goal:** render Voxy's distant/LOD terrain **smooth (non-blocky)** via **iso-surface meshing** (Marching Cubes / Surface Nets) instead of blocky cube meshes.
- **Verified finding worth knowing:** **Axiom's** impressive real-time rendering is **pure-Java GPU instancing** — NOT native code and NOT compute shaders. Confirmed by inspecting `Axiom-5.4.2` jar: only natives are `zstd-jni` (compression) + `lwjgl-nfd` (file dialogs); shaders are standard `.vsh/.fsh` incl. `instanced_block.vsh` (no `.comp`). So the biggest lever is **GPU instanced/meshed rendering in Java** — Axiom-class results need no native backend.
- **Recommended architecture IF pursued:** GPU-instanced/meshed rendering (Java) does the *draw*; a **Rust backend** is an OPTIONAL accelerator for the CPU-heavy **mesh generation** (surface extract / marching cubes / greedy meshing) behind a clean `GeometryBuilder` interface, with a **Java fallback** and **multiplatform build from day one** (cross-compile matrix + natives loader via Java 21 Panama FFI). Discipline: **GPU-in-Java first, measure, add Rust mesh-gen only if it's the proven CPU bottleneck** (Rust can't beat the GPU at rasterizing; it shines at CPU mesh-gen).
- **Transferable asset:** MIA Maps is building a pure **Marching-Cubes mesher** (Phase 1) for its 3D orbit view + a **GPU meshed renderer** (Phase 2, scheduled) — see MIA `docs/plans/…smooth-3d…`. That mesher + GPU learnings could seed the fork's world-LOD smoothing.
- **Caveat:** LARGE future effort (Voxy's mesher is mature; smooth LOD is a big change). Near/real-block smoothing (NoCubes-style) is a *separate mod* entirely, out of scope.

### 2026-07-20 (later) — DROP the cross-world LOD merge; MIA Maps moving to a Rust-native GPU map renderer
- **Owner decision: STOP the cross-world hybrid LOD merge** (`/voxy merge-build`, build→survive,
  survive-precedence — fork's 2026-07-19 work, ported to 2.15 at `9031168`). MIA Maps no longer wants
  it. **Nothing to remove on the MIA Maps side** — MIA Maps only ever CONSUMED read data; the merge
  was entirely fork-side. **Fork thread: you may park/drop the merge port + borrowed-section machinery.**
  The read contract MIA Maps relies on (`acquireIfExists → copyDataTo → release`, `MAX_LOD_LAYER=4`,
  AbyssUtil) is unchanged and unaffected by dropping it.
- **MIA Maps direction (Phase 2, approved):** the 3D map's CPU point-cloud renderer (chunky at wide
  zoom / mushy when iso-smoothed / gappy) is being replaced by a **SELF-CONTAINED GPU renderer** —
  greedy-meshed occupancy grid → VBO → our own trivial shader → offscreen FBO → texture, built as a
  **Rust native module** (mirroring your `voxy_native` approach; multiplatform from the outset). It
  does **NOT** hijack Voxy's renderer (unlike the retired v1.0 FBO/viewport path), and still consumes
  Voxy strictly read-only. **No Voxy change requested.** Cross-project ask: any reusable cross-platform
  **JNI / native-loader / cargo build-matrix patterns from `voxy_native`** are worth sharing — MIA
  Maps will need the same Win/Mac/Linux packaging.
