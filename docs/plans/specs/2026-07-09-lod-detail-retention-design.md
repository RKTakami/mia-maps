# LOD Detail Retention + Coverage-First Zoom — Design

**Date:** 2026-07-09
**Status:** Approved (conversational brainstorm)

## Problem

`MapGeometry.lvlForView` drops to a coarser Voxy LOD tier once the view exceeds 512
blocks across, averaging away detail long before the 2048px map texture needs it — so
zooming out looks coarse too early. Naively lowering the threshold blanks distant areas,
because Voxy only stores fine (level-0) data where the player has been close; far terrain
exists only at coarse tiers. And at the extreme zoom-out the coarsest tier (level 4,
16-block cells) dissolves into abstract soup.

## Design

Four coordinated changes. Net effect: crisp near/mid zoom, gracefully coarse-but-readable
far, and a wide (coverage-first) zoom-out that tops out at legible 8-block cells instead
of 16-block soup.

### 1. Per-section "finest available" LOD fallback (no holes)

`MapWorker.renderJob` currently acquires each 32³ section at the tile's display level and
skips it (leaving air) when Voxy has no data there. Change it to walk **coarser** levels
until data is found, upsampling the relevant sub-cube into the display grid:

For each section slot at display level `lvl`, section coords `(sx, secY, sz)`:
- For `k = 0, 1, … MAX_FALLBACK_K` (until data found):
  - `clvl = lvl + k`; acquire `engine.acquireIfExists(clvl, sx>>k, secY>>k, sz>>k)`.
  - If found: `k==0` → use the data directly; `k>0` → upsample its octant (below).

`MAX_FALLBACK_K = 4` (Voxy returns null for non-existent levels, so over-reaching is
harmless).

**Octant upsampling** (pure, unit-tested `LodUpsampler.upsampleOctant(long[] coarse,
int sx, int secY, int sz, int k)` → `long[32*32*32]`): the fine section occupies octant
`(sx & (2^k−1), secY & (2^k−1), sz & (2^k−1))` of the coarse 32³ array; each coarse cell
in that `(32>>k)³` sub-cube replicates to a `2^k` cube of fine cells. Voxel index layout
is `(y<<10)|(z<<5)|x` (matching `MapTileRenderer.cellAt`):

```
int scale = 1 << k, span = 32 >> k;
int ox = (sx & (scale-1)) * span, oy = (secY & (scale-1)) * span, oz = (sz & (scale-1)) * span;
long[] out = new long[32*32*32];
for (int y=0; y<32; y++) for (int z=0; z<32; z++) for (int x=0; x<32; x++) {
    int cx = ox + (x >> k), cy = oy + (y >> k), cz = oz + (z >> k);
    out[(y<<10)|(z<<5)|x] = coarse[(cy<<10)|(cz<<5)|cx];
}
return out;
```

The renderer treats the upsampled array as a normal display-level section; coarse data
shows as blocky-but-present cells rather than holes.

### 2. Cap the display grid at level 3 (8-block cells)

`lvlForView` clamps at a new `MAX_DISPLAY_LVL = 3` (was `MAX_LVL = 4`). The finest cell
ever rendered is 8 blocks, so the map never shows 16-block soup — even fully zoomed out.
Where only level-4 data exists, the fallback upsamples it into the level-3 grid (blocky
but legible). `MAX_LVL = 4` stays as the fallback ceiling reference.

### 3. Retain LOD 0–2 longer (near/mid detail)

Raise the tile-budget multiplier in `lvlForView` from `16` to `DETAIL_TILES = 24`, so the
step thresholds become: level 0 ≤ 768, level 1 ≤ 1536, level 2 ≤ 3072, level 3 above that.
Crisp detail now survives to 768 blocks across instead of 512. Safe because of the
fallback. Bump `MapTileCache` capacity 1024 → 4096 (≈32 MB) so the larger visible tile
set doesn't thrash.

### 4. Coverage-first zoom-out floor

Raise the fullscreen min zoom in `AbyssWorldMapScreen.mouseScrolled` from `0.0125`
(base ≈ 20,480 blocks) to `MIN_ZOOM = 0.03f` (base ≈ 8,530 blocks; widescreen X ≈ 15,000).
At level 3 that is ≈ 57 × 33 ≈ 1,900 tiles — within the 4096 cache — so the widest view
covers a large explored area while staying legible. Max zoom unchanged (20×).

## Files

- `MapGeometry.java` — `MAX_DISPLAY_LVL`, `DETAIL_TILES`, update `lvlForView`.
- `LodUpsampler.java` (new, pure) — `upsampleOctant`.
- `MapWorker.java` — per-section fallback loop using `LodUpsampler`; cache 1024 → 4096.
- `AbyssWorldMapScreen.java` — `MIN_ZOOM = 0.03f`.
- Tests: `LodUpsamplerTest` (new), `MapGeometryTest` (updated `lvlForView` thresholds).

## Testing

- `LodUpsampler`: k=1 replicates each of the 4 octants correctly (distinct values in the
  chosen octant's sub-cube expand to 2×2×2 fine cells); a uniform coarse section upsamples
  to a uniform fine section; octant offset selects the right sub-cube.
- `lvlForView`: new thresholds (768/1536/3072) and the level-3 clamp (never returns 4).
- In-game: zoom out slowly — detail persists further before coarsening; distant/unvisited
  areas show coarse-but-present (no black holes); fully zoomed out is legible 8-block cells
  over a wide area, not soup; no lag returns; near-zoom and the minimap look unchanged.

## Out of scope

Smooth cross-fade between LOD tiers (hard cut is fine); increasing the map texture beyond
2048px; changing minimap zoom (always level 0). LOD thresholds, cache size, `MIN_ZOOM`,
and `DETAIL_TILES` are all tunable constants.
