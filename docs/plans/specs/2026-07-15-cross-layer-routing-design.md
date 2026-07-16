# Cross-Layer Routing — Design

**Status:** proposed
**Date:** 2026-07-15
**Goal:** Route to a waypoint on any Abyss layer, not just the one you are standing on.

## Problem

Clicking a waypoint on another layer does not fail — it silently routes you somewhere meaningless.

The Abyss is built as 15 sections. Each is a 512-block-tall world band (`abyss_wy = -256`,
`abyss_wh = 512`) laid out **side by side along world X, 16384 blocks apart**, and each steps
**480 blocks deeper** than the last (`abyss_dx = 16384`, `abyss_dy = 480`). 15 × 480 = 7200, which
is exactly the deepest mapped depth. Because the bands are 512 tall but step 480, adjacent sections
**overlap by 32 blocks** (`abyss_overlap = 32`) — the owner confirms you simply walk or fall down
through that overlap, so the world is physically joined and a path across a boundary is walkable.

`RouteService.compute` already builds its grid in the shifted column, but it shifts the **goal** by
the *player's* sector:

```java
int sector = AbyssUtil.getSection(x);        // the PLAYER's sector
...
Pathfinder.Cell goal = clampCell(grid,
        (int) Math.floor(dst[0]) - shiftX - originX,     // ...applied to the DESTINATION
```

For an off-layer destination that lands ~16384 cells outside the grid, and `clampCell` clamps
rather than rejects, so A* runs happily toward an arbitrary box edge. The search-box bias is wrong
the same way — `dx = dst[0] - x` is a world delta, so an off-layer target yields `horiz ≈ 16384`
and biases the box due east regardless of where the target actually is.

Any waypoint more than ~480 blocks of depth away is on another section, so this is close to every
waypoint.

## Key insight

**Voxy's database is the continuous Abyss.** Voxy applies the shift at ingest, so every section is
stored in one shifted column where they stack vertically and overlap by 32 blocks.
`VoxelCloud.fill` clamps only X to `[-8192, 8192)` — never Y. A traversability grid built in
shifted space that spans a section boundary therefore reads **both layers already stitched
together**, with no special casing, and A* can cross a boundary natively.

The fix is to stop mixing spaces: do all routing math in the shifted column, and convert to world
only at the renderers that need it.

## Why not simply widen the existing box

The full mapped band is ~7968 blocks tall (`ABYSS_SHIFTED_Y_TOP - ABYSS_SHIFTED_Y_BOTTOM`).
Keeping today's `BOX = 192` horizontal and `LVL = 0`:

| | cells | as `boolean[]` |
|---|---|---|
| today (192 × 192 × 192) | ~7.1M | ~7 MB |
| full column (192 × 7968 × 192) | ~294M | ~294 MB |

Rebuilt up to 4×/sec while walking, and `NODE_CAP = 200_000` would explore under a thousandth of
it. Block-accurate footing over the whole Abyss is not reachable by scaling the current router.

But block-accurate footing 5000 blocks away is not needed — only *which shaft to head down* is.

## Architecture: two tiers

**Tier 1 — corridor (new, coarse, whole column).** Picks the coarsest LOD that fits a cell budget
over the padded player↔destination box in the shifted column, then searches it for an open
corridor. Answers "which way down". Cached; recomputed rarely.

**Tier 2 — local route (existing, block-accurate).** The current LOD 0 router, unchanged in
character, but working in the shifted column and aimed at the next corridor point inside its box
rather than at the raw destination. Answers "where do I put my feet". Keeps safe-drop, the descent
planner, and the dig hints exactly as they are today.

The 3D view draws the whole corridor across layers; the near portion is the block-accurate route.

### LOD selection

Mirrors the trick `OrbitScene` already uses:

```java
while (cells(spanX, spanY, spanZ, lvl) > CELL_BUDGET && lvl < MAX_CORRIDOR_LVL) lvl++;
```

with `CELL_BUDGET = 4_000_000` (~4 MB as a `boolean[]`, comfortably under today's 7 MB LOD 0 grid),
`PAD = 128` blocks of horizontal margin around the player↔destination box, and
`MAX_CORRIDOR_LVL = 4`.

`MAX_CORRIDOR_LVL = 4` because **Voxy stores nothing coarser than LOD 4** — see
`project_memory.md` §3b. If LOD 4 still does not fit (a destination thousands of blocks away
horizontally *and* vertically), the box is clipped around the player toward the goal and the
corridor comes back `PARTIAL`, extending as you travel. That reuses the progressive behaviour the
router already has.

Typical case — a waypoint straight down the shaft — is small: horizontal span `2 × PAD = 256` → 16
cells at LOD 4, vertical 7968 → 498, so 16 × 498 × 16 ≈ **127k cells**, versus 294M.

### Corridor passability ≠ standability

`TraversabilityGrid.standable` means "solid below, two air cells of headroom" — 1-block semantics
that are meaningless at 8- or 16-block cells. The corridor search instead treats a cell as passable
when it is **not opaque**, and searches air connectivity for the open shaft.

**Accepted limitation:** at LOD 4 a passage narrower than 16 blocks may aggregate to solid and be
missed. The corridor is a guide toward the main shaft, not a promise. The Tier 2 router is what
guarantees footing, and it runs at LOD 0.

## Coordinate model

Add to `MapGeometry` (pure, unit-tested; voxy is `compileOnly` so constants are mirrored, as
`shiftX`/`shiftY` already do):

```java
public static final int SECTION_COUNT = 15;
public static final int SECTION_WORLD_Y_MIN = -256;
public static final int SECTION_WORLD_Y_HEIGHT = 512;   // overlap = HEIGHT - SECTOR_DEPTH = 32

// Does this section's world band contain that shifted Y?
public static boolean sectorContainsShiftedY(int sector, double shiftedY);

// The section owning a shifted Y. In the 32-block overlap BOTH neighbours are valid, so
// `preferred` wins — that keeps a descending route on one layer as long as it legally can
// instead of flickering between two equally correct answers.
public static int sectorForShiftedY(double shiftedY, int preferred);

// Inverse of toShiftedColumn for a known section.
public static double[] toWorld(double sx, double sy, double sz, int sector);
```

Already shipped (2026-07-15, marker fix): `sectorForX`, `toShiftedColumn`, `abyssDepth`,
`SECTOR_SPAN_X`, `SECTOR_DEPTH`, `RIM_SHIFTED_Y`.

`sectorForShiftedY` is well posed: solving `-256 ≤ sy - 3840 + 480s < 256` gives a window
512/480 = 1.067 sections wide, so exactly one or two integers satisfy it — the two-answer case
being precisely the 32-block overlap.

## Route representation

`Route.points()` moves from world to **shifted** coords. World coords cannot express a multi-layer
path: every layer reuses the same world Y, and `cellToWorld` un-shifts everything with the player's
single sector.

Consumers:

| Consumer | Change |
|---|---|
| `OrbitView` (3D) | Use shifted directly via `OrbitScene.projectShifted` — it already wants shifted after the marker fix. |
| `RoutePathRenderer` (in-world) | Convert with the player's current sector; skip points on other layers (they are not visible in-world anyway). |
| `MinimapRenderer`, `AbyssWorldMapScreen` (2D) | Same as in-world: the 2D map shows one layer. |
| `RouteService.offRoute`, `Route.ahead` | Compare in shifted space; convert the player once. |

## Phasing

Each phase is shippable on its own.

**Phase 1 — shifted-space correctness.** Coordinate helpers, `Route` in shifted coords, renderer
conversions, goal and box-bias fixed to use each point's own section. Delivers cross-layer routing
*progressively*: a partial route in the correct direction that extends as you descend. This is the
bug fix, and it is a prerequisite for Phase 2.

**Phase 2 — corridor tier.** `CorridorPlanner` + LOD selection + caching, with Tier 2 aimed at the
next corridor point. Delivers the full-length route.

## Testing

Pure and unit-testable, following the existing `MapGeometryTest` / `PathfinderTest` pattern:

- `sectorForShiftedY` round-trips against `toShiftedColumn` for all 15 sections; the overlap band
  returns `preferred` when preferred is valid, and the unique answer otherwise.
- `toWorld` inverts `toShiftedColumn` exactly for every section.
- LOD selection never exceeds `MAX_CORRIDOR_LVL`, and never returns a grid over `CELL_BUDGET`.
- A synthetic two-section grid (a shaft with a floor at the boundary) yields a corridor that
  crosses the boundary — the regression that this whole design exists to prevent.
- A goal one section down produces a route heading **down**, not east: the specific failure in the
  current code.

In-game verification: stand on one layer with a waypoint on the next, confirm the route heads down
the shaft rather than east, and confirm it survives the section boundary as you cross it.

## Out of scope

- Routing to unmapped layers (below 7200) — there is no data.
- Server-side pathing, teleports, or commands.
- Changing the descent/dig planner's behaviour; it stays on the Tier 2 LOD 0 grid.
