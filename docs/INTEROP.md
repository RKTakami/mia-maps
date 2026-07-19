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
