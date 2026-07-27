# Cascaded LOD for the 3D view — fine voxels over a wide area

**Status:** design only, nothing implemented. Written 2026-07-27.
**Goal (owner):** fine voxels **and** wide coverage at once — currently mutually exclusive.

## Why the current design cannot deliver it

`OrbitScene` picks **one** LOD level for the whole sampled box (`OrbitLod.plan` →
`VoxelCloud.sampleGrid(..., lvl)`), so cost is **cubic** in (span ÷ cell). Widening the area and
finening the cell multiply against each other.

At the owner's Area 2048, 8-block voxels means an 8-block cell across the 6144-block sampled span:

```
768 x 768 x 768 = 452,984,832 cells
```

Against the measured ~5 bytes/cell and 1.23 s per 191M (see the 2026-07-26 entry in
`project_memory.md`), that is **~2.2 GB and ~2.9 s per rebuild**, versus `ULTRA.maxCells = 40M`.
**~11x over budget — this is not reachable by raising the cap.** Confirmed by running `OrbitLod.plan`
against the real tier constants: every Area >= 1024 yields 16-block voxels at *every* quality tier,
because quality buys coverage, not detail.

## Design — nested shells (clipmap / cascaded-shadow-map shape)

Fine grid near the focus, progressively coarser outward. Cost becomes **additive across shells**
instead of cubic. Worked against the owner's current settings:

| shell | cell | span | grid | ~cells |
|---|---|---|---|---|
| inner | 8blk (L3) | ~1536 | 192³ | ~7M |
| outer | 32blk (L5, synthesized) | ~5152 | 161x161x192 | ~5M |
| **total** | | | | **~12M** |

**Under a third of the 40M already being spent**, while giving 8-block detail near the camera *and*
wider coverage than today's 16-block/5152. The budget was never the problem; spending it uniformly
was.

### What is already in our favour

- **`VoxelCloud.Grid` is self-describing** — `record Grid(opaque, argb, gX, gY, gZ, cell,
  originCellX, originCellY, originCellZ)`. A cascade is just a **list of Grids**. No type change.
- **`sampleGrid(engine, colors, focusX/Y/Z, extentXZ, extentUp, extentDown, lvl)`** is already
  parameterised by level and extent — sampling a shell is another call, not new machinery.
- **`OrbitMesher.build(opaque, argb, gX, gY, gZ, cellSize, originX, originY, originZ)`** is already
  per-grid and cell-size aware.
- **`MapNative.nMeshGrid(handle, opaque, argb, gx, gy, gz, cell, ox, oy, oz)`** already carries cell
  and origin, and `nCreateContext`/`nDestroyContext` mean multiple contexts already exist.

### The one real native change

**`render()` unconditionally clears** (`map-native/src/renderer.rs:325`,
`gl::Clear(COLOR_BUFFER_BIT | DEPTH_BUFFER_BIT)`), and `nMeshGrid` replaces the context's mesh. So N
shells cannot simply be N `nRender` calls — the second would wipe the first.

**Preferred:** make a context hold **N grids** — `nMeshGrid` appends, `render()` clears once and
draws all of them. This keeps the clear, the depth setup and the FBO handling in exactly one place,
which matters given the history below. The alternative (a `clear:bool` on `nRender`) spreads that
state discipline across call sites and is not worth it.

### Java-side shape

1. `OrbitLod.planCascade(extentXZ, vertUp, vertDown, quality, maxLevel, maxCells)` →
   `List<Plan>`, innermost first, each with its own level and span, total cells within budget.
   Keep the existing `plan()` for the single-shell/whole-Abyss paths.
2. `OrbitScene` samples one `Grid` per shell and keeps `List<Grid>`; `gpuGridSig` becomes a hash over
   all shells.
3. **Outer shells must skip the inner shell's volume** (hollow), or the overlap is wasted cells and
   overdraw. Cheapest form: pass an exclusion AABB (in cells) to `sampleGrid`/`build` and skip cells
   inside it.
4. CPU raster path composites shells through the existing `bufDepth`; coarse-first is cheaper.

## Risks and constraints — read before implementing

- **Seams.** Cracks where cell sizes meet are the classic clipmap failure. Mitigate by overlapping
  shells by one coarse cell, or emitting skirt geometry at shell boundaries. **Expect this to be the
  bulk of the visual polish work.**
- **Voxy stores nothing coarser than L4** (`WorldEngine.MAX_LOD_LAYER = 4`). Outer shells at L5 need
  the synthesis path, and it **must** use `LodUpsampler.mipInto`'s drawable-child predicate — the
  plain topmost-non-zero version is what caused the pin-art holes fixed in `9b519db`.
- **Do NOT disturb the draw-path fixes.** The orbit draw's depth-func/blend setup (`a6fb204`), the
  per-frame FBO texture detach (`380c6fc`) and `GlStateGuard` were each hard-won against real
  corruption. **Rule stands: the orbit draw must inherit NOTHING and leave NOTHING.**
- **`smooth3d`** meshing across differing cell sizes is unproven; the Surface-Nets path may need
  per-shell handling. Consider shipping cascades cube-only first.
- **macOS.** Everything here is GL 3.3-era (VAO/VBO/FBO/DrawElements) and stays within Apple's 4.1 —
  no new platform risk. Verified live on the M4 2026-07-26.

## Staging

1. `planCascade` + unit tests (pure, like the rest of `OrbitLod`) — no rendering changes.
2. Multi-grid native context (append + single clear), single shell still, to prove no regression.
3. Two shells, cubes only, no seam treatment — measure cells/MB/rebuild against the table above.
4. Seam treatment, then `smooth3d`, then tune shell count/spans per quality tier.

## Open questions

- Shell count per tier: 2 is enough for the worked example; 3 may help at Area 4096.
- Whether shell spans should follow zoom or stay fixed multiples of the inner span.
- Whether the quality tiers should be re-expressed as "cells per shell" rather than one total cap,
  once cost is additive.
