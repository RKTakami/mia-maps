# Whole-Abyss 3D View — Design

**Status:** approved (owner, 2026-07-16)
**Date:** 2026-07-16
**Goal:** Show the entire mapped Abyss — all layers, Orth to the deepest explored terrain — in the existing 3D orbit view, by extending the 3D Area slider past 4096 with a "Whole Abyss" step.

## Problem

The 3D view is capped at 4096 blocks of area. The owner's actual wish, stated since the area
slider shipped, is to see **all mapped layers of the Abyss stacked in one picture**. The cap
exists for a settled reason (`project_memory.md` §3b): Voxy stores nothing coarser than
**LOD 4** (16-block cells), so the live sampler must synthesize anything coarser from LOD 4
**on every camera move**. When 8192/16384 steps were tried, that per-resample synthesis was
slow, GC-heavy, and mostly empty; the steps were removed and the cap was principled.

## Key insight

The cap is a property of the **live path**, not of the data. The whole-column view does not
need live resampling — terrain only changes where the player is exploring. So: build the
mod's **own cached model** of the whole column **once**, in the background, from **native
LOD-4 reads** (no synthesis against the clock), mip it offline, and let the existing renderer
read the cache at the new slider step. The §3b constraint is respected, not re-litigated: the
memory note itself says "anything coarser must be synthesized from level 4 by the mod itself"
— this does that offline and cached instead of per-frame.

Screen math makes coarseness free: ~8,000 blocks of depth on a ~1,000-px viewport is ~8 blocks
per pixel at full zoom-out. 16–64-block cells are at or below pixel scale there.

## Rejected alternatives

- **Push the live sampler harder (deeper `MAX_FINER_DEPTH`, levels 6–7, per-section synth
  caches):** re-treads the exact failure that produced the cap — whole-column walks per camera
  move, unbounded caches, GC pressure.
- **Modify Voxy (`MAX_LOD_LAYER` 4 → higher):** technically one constant, but (1) levels 5+
  would exist only for newly ingested terrain — the owner's entire explored DB stays blank
  without a DB-wide backfill inside Voxy's storage; (2) Voxy ships with the modpack — a fork
  is a permanent distribution/maintenance liability for a client-side companion mod; (3) it
  buys nothing the cache doesn't. **Do not modify Voxy.**
- **Replace the cube rasterizer with a span/wall renderer now:** prettier, but couples the
  goal ("see the whole Abyss") to a new render path. Deferred; it is a pure upgrade on top of
  this store later.

## Architecture

Three units. The store and its math are pure (no Voxy/Minecraft imports — Voxy is
`compileOnly`, off the test classpath); all Voxy contact is quarantined in the builder,
mirroring the `RouteBox`/`RouteService` and `CorridorFinder`/`CorridorPlanner` split.

### 1. `map/AbyssSpanStore` + `map/SpanMath` (pure)

Column-span model of the whole shifted column at **16-block base cells** (= native LOD 4):

- Key: packed `(cellX, cellZ)`; domain `cellX, cellZ ∈ [-512, 512)` (shifted X sector band
  and the same bound applied to Z), `cellY` over the Abyss band
  (`ABYSS_SHIFTED_Y_BOTTOM..ABYSS_SHIFTED_Y_TOP` → ~498 cells).
- Value: sorted solid spans per column, packed `int` per span (`topCellY<<16 | bottomCellY`),
  plus one packed ARGB per span (sampled at the span's top face — the color that matters from
  above).
- Snapshots are **immutable**; the builder publishes by volatile reference swap (the
  established `RouteService` pattern). Renderer and future consumers only ever see a complete
  snapshot.
- `SpanMath` (pure functions): span insert/merge for one column; **mip** a store to 32- and
  64-block levels. Mip rule: **occupancy union** (a coarse cell is solid if any child is) —
  avoids holes in walls at overview scale; color = topmost contributing span's color.
- Each store level records its **surface count** — cells with at least one of their six
  neighbours empty (span ends within the column, plus side exposure against the four
  neighbouring columns) — so the renderer can pick a mip by budget without walking the store.
  The renderer emits exactly these surface cells; cliff walls come from side exposure.

Size: explored Abyss ≈ low single-digit MB at base level; mips are marginal.

### 2. `map/AbyssModelBuilder` (Voxy-facing)

A dedicated low-priority daemon worker (same lifecycle pattern as the route worker):

- **Initial build:** probe every LOD-4 section in the column domain — 32 × ~16 × 32 ≈ 16k
  `acquireExact` probes, most absent — converting solid cells to spans. Rate-limited (batch of
  ~256 probes, then sleep) so the game never hitches; full build completes in seconds and the
  view fills in progressively while it runs.
- **Freshness:** sections within ~512 blocks of the player are marked dirty and re-probed on a
  ~10 s cadence, so newly explored terrain appears in the overview shortly after Voxy ingests
  it. No event hook into Voxy; polling is sufficient for an overview.
- After any batch that changed columns, rebuild the affected mips and publish a new snapshot.
- Builder starts lazily the first time the Whole Abyss step is selected (no cost for players
  who never use it) and keeps running for the session once started.

### 3. Render + UI integration

- `MapSettings.ORBIT_AREA_STEPS` gains a final step `ORBIT_AREA_WHOLE = 16384`, labelled
  **"Whole Abyss"** in the slider (snapping logic unchanged; `MapConfig` guard already snaps
  legacy values).
- At that step `OrbitScene` **skips `VoxelCloud.sample`** and builds its point/cube arrays
  from the store snapshot: surface cells only, choosing the **finest mip whose surface count
  fits the active quality tier's point budget**. Same arrays, same cube rasterizer, same
  z-buffer and `depthAt` — the render path is untouched.
- Zoom ceiling at the Whole Abyss step derives from the column height
  (`(ABYSS_SHIFTED_Y_TOP - ABYSS_SHIFTED_Y_BOTTOM)` plus margin) instead of the horizontal
  area, so the full column fits on screen; `clampVerticalToAbyss` continues to bound the band.
- Overlays (waypoints, route spheres, corridor trail, player marker) already project through
  `OrbitScene.projectShifted` — they work in the whole view **unchanged**. This is where
  cross-layer waypoints and the Phase-2 corridor finally appear in one continuous picture.
- **X-ray is forced OFF at the Whole Abyss step** (the cave classifier is a live-sampler
  feature). The X-ray HUD line shows "X-ray: n/a (whole Abyss)".
- Stats overlay (`orbitStats`) gains a cache line: `cache: <columns> cols, <surfaces> surf,
  building N%` while the initial build runs, then `built <age>s ago`.
- `HelpContent` (3D tab): document the Whole Abyss step and the "fills in as it builds /
  updates shortly after you explore" behaviour.

## Degradation

- **Cache still building:** render whatever is built so far (progressive fill-in), plus the
  stats "building N%" line. No blocking, no modal state.
- **Voxy absent / ingest off:** store stays empty; the whole-Abyss step renders nothing —
  the existing "turn on Voxy map data" guidance in Help/Overview already covers the cause.
- **Store overflow (defensive):** hard cap on stored columns (the full domain is ~1M; cap at
  2M defends against a coordinate bug, log-once if hit).

## Testing

- `SpanMathTest` (pure): span insert/merge (overlap, adjacency, containment), column packing
  round-trip, mip union + color selection, surface-count bookkeeping.
- `AbyssSpanStoreTest` (pure): snapshot immutability, key packing round-trip over the domain,
  mip-level surface counts consistent after edits.
- Builder is a thin Voxy shim — no unit tests (established pattern); verified in-game.
- In-game verification: slider → Whole Abyss shows the full column (Orth through the deepest
  explored layer); overlays land correctly across layers; orbiting stays smooth during and
  after the build; newly explored terrain appears in the overview within ~10–20 s.

## Out of scope

- The span/wall renderer (Approach C) — later upgrade on the same store.
- Porting X-ray/cave classification to the cache.
- Disk persistence of the cache (rebuild-per-session is seconds; revisit only if that ever
  feels slow).
- Feeding long-range routing from the cache (natural follow-up; the corridor planner could
  read spans instead of re-probing Voxy).
- Terrain beyond shifted Z ∈ [-8192, 8192).
