# Vertical Descent Routing — Design Spec

**Date:** 2026-07-12
**Status:** Approved (design); awaiting owner review of this spec before writing plans.
**Depends on:** Phase 1 routing (`2026-07-12-3d-routing-design.md`) — `TraversabilityGrid`, `Pathfinder`, `Route`, `RouteService`, and the route trail in the 3D view / minimap HUD / fullscreen map.

## Overview

Extend waypoint routing so it can find a survivable way **down cliff faces** in Mine in Abyss.
Today the pathfinder only descends via drops of ≤3 blocks and gives up (PARTIAL) on sheer
faces. This feature makes descent a first-class outcome:

- **Plan A — Natural descent:** route down existing ledges/staircases in safe hops. This is
  the common case — most of the Abyss can be descended down its faces.
- **Plan B — Dig/tunnel fallback:** where the face **overhangs** (the rock juts out above the
  lower ledges, so no open descent exists), recommend an **L-shaped dig path** — dig straight
  down through the overhang mass, then a short horizontal tunnel to break out toward the open
  face/ledge below. Highlight-only — the mod never places or breaks blocks.

## Empirical fall model (measured in-game, this server)

The published DeeperWorld config (`maxSafeDist: 40`) does **not** match the live server.
Owner testing established the real curve (from full health, 10 hearts):

| Drop height | Result |
|---|---|
| **≤ 4 blocks** | **0 damage (free)** |
| 5 | ½ heart |
| 35 | 6.5 hearts lost (survives, barely) |
| 40 | death |

Consequences that shape this design:

- **Safe drop = 4 blocks.** This is essentially the vanilla low-end (vanilla is 3).
- Damage ramps steeply; 35 is already near-lethal, so drops beyond the safe distance are
  **not** used for routine descent.
- **Armor is irrelevant** to fall survival on this server (fall damage bypasses the armor
  pipeline; armor grants no bonus health — only the curse changes max health, downward).
  There is therefore **no armor input** anywhere in this feature.
- Free-falling down real Abyss cliffs is impossible. Descent = 4-block hops down natural
  structure, or digging straight down (safe, one block at a time).

## Goals

- Route a player standing above a below-them waypoint down to it whenever a natural
  ≤safe-drop descent exists (Plan A).
- When no natural descent makes downward progress — typically an **overhang** — recommend one
  bounded **L-shaped dig path** (dig down + tunnel out) for the next leg of the descent
  (Plan B), rendered as highlighted blocks-to-mine with an entry beacon.
- Everything highlight-only. The mod never breaks or places blocks.
- One tunable setting: **safe fall distance** (default 4).

## Non-goals

- No auto-building / auto-mining (owner constraint, unchanged).
- No armor / gear modelling (the game doesn't use it for falls).
- No lava/hazard detection in v1 — lava reads as solid rock, so a recommended dig column may
  pass through it. Plan B output carries a "verify the column" caveat. Hazard detection is a
  future stretch (needs a new baked block predicate, like the existing `isWater`).
- No climbing-mechanic integration (the MIA stamina climb is manual and hard to model).
- No "risky drops beyond safe distance" in v1. A future optional toggle could allow opt-in
  damaging drops keyed off **current health** (never armor), but it is out of scope here.

## Design

### Settings

Add to `MapSettings`:

- `int safeDropBlocks = 4` — the largest drop the router treats as free. Drives both the
  pathfinder's `maxFall` and the landing spacing in Plan B. Exposed in `MapSettingsScreen`
  as a small stepper/slider (range ~2–8). Persisted via `MapConfig` like other settings.

### Plan A — Natural descent

The Phase 1 pathfinder already has a drop move bounded by `Params.maxFall`. Plan A is mostly
configuration + sampling reach:

1. **Drive `maxFall` from the setting.** `RouteService.PARAMS` becomes
   `new Pathfinder.Params(1, safeDropBlocks, 1)` (was `maxFall = 3`). Chains of ≤4 drops now
   form a legal natural descent.
2. **Bias the sample box downward toward a below-you destination.** `RouteService.compute`
   already offsets the box center toward the destination in Y (`bcy`). Verify/adjust so that
   when the destination is well below the player, the box's vertical span reaches meaningfully
   downward (extend `VBOX` downward bias so the reachable frontier includes the descent, not
   just ±96 symmetric around the player). Keep the horizontal box (`BOX`) as-is.
3. **Progressive descent, unchanged.** The existing "always contains the player, re-route as
   you move (`REROUTE_DIST`)" loop handles deep descents: A routes to the reachable bottom of
   the current box, you climb/drop down, it recomputes. No new mechanism needed.
4. **Rendering, unchanged.** The route trail already draws in the 3D view, minimap HUD, and
   fullscreen map. Staged drops appear as vertical runs of trail points. (If a single drop
   spans several blocks the trail already emits one point per cell along the path.)

Plan A succeeds when A\* returns `FOUND`, or a `PARTIAL` whose best-reached cell is
meaningfully **below** the start and closer to the goal.

### Trigger — when to fall back to Plan B

After A\* runs in `RouteService.compute`, invoke Plan B only when **all** hold:

- The destination is below the player by more than `2 * safeDropBlocks` (it's a real descent),
- A\* did not reach the goal (`status != FOUND`), and
- A\*'s best-reached cell made **little downward progress** — its Y is within ~`safeDropBlocks`
  of the start's Y (the frontier is stuck on a ledge above a sheer face).

Otherwise, keep the Plan A route (`FOUND` or a genuinely-descending `PARTIAL`).

### Plan B — Dig/tunnel fallback (overhangs)

Goal: recommend **one** bounded **L-shaped dig path** — dig straight down through the overhang
mass, then a short horizontal tunnel to break out toward the open face/ledge below. Digging
straight down in MC descends one block at a time (each a ≤1 drop → free); tunneling is on foot
(no drops), so a solid, gap-free dig path is a safe descent even under an overhang.

Algorithm (`DescentPlanner`, pure, unit-tested — grid coordinates):

1. **Anchor.** Start from A\*'s best-reached standable cell `F` (nearest the goal). The dig
   column is at `F`'s `(x, z)` (or one cell toward the goal if that keeps you on solid footing
   to start).
2. **Dig down** the column from `F.y - 1`, collecting each opaque cell as a dig cell, until one
   of:
   - **Break-out reached:** the current level has a standable cell that **opens toward the
     goal** (air + headroom on the goal-ward side, i.e. past the overhang) → done, no tunnel
     needed.
   - **Overhang floor:** the column hits a level whose cell is air below but the goal-ward side
     is still capped by solid rock (you've cleared under the overhang but are boxed in) → switch
     to the tunnel step at this level.
   - `bottomY` reached (goal's Y or the leg cap `MAX_DIG` below `F.y`) with no exit → this
     column fails; try the next candidate.
3. **Tunnel out.** From the break level, collect horizontal opaque cells stepping **toward the
   goal** (with headroom: the cell and the cell above it), up to `MAX_TUNNEL` cells, until a
   standable cell that opens downward/onward is reached. If the tunnel caps out without an
   exit, the candidate fails.
4. **Reject & retry** up to a few candidate columns (F's column, then columns one step toward
   the goal in each horizontal direction). If none produce a valid dig path within
   `MAX_DIG` / `MAX_TUNNEL`, emit **no** dig plan and keep the Plan A `PARTIAL` (the trail still
   shows how far you can get).
5. **Emit** a `DigPlan` — the ordered list of world-coordinate cells to mine (vertical run then
   horizontal run) plus the entry cell — un-shifting X/Y like the rest of `RouteService`.

Bounds keep each leg small and compute cheap: `MAX_DIG` (e.g. 24) and `MAX_TUNNEL` (e.g. 8).
Because this runs inside the progressive re-route loop, Plan B emits **one leg** at a time: you
dig the recommended path, you're now lower and past the overhang, `RouteService` recomputes, and
Plan A (or the next Plan B leg) takes over.

### Data model

Extend `Route` with an optional dig recommendation:

```java
// Ordered cells (world coords) to mine for the next descent leg: vertical run, then the
// horizontal break-out tunnel. `entry` is the shaft mouth (for the beacon/label).
public record DigPlan(double[] entry, List<double[]> cells) {}

public record Route(List<double[]> points, List<double[][]> bridges,
                    DigPlan dig, Pathfinder.Status status) {
    public static final Route EMPTY =
        new Route(List.of(), List.of(), null, Pathfinder.Status.NO_ROUTE);
}
```

`dig` is `null` unless Plan B fired. `bridges` stays unused (reserved from Phase 1).
Update the `Route` constructor call sites (Phase 1 passes 3 args → now 4).

### Rendering

- **3D orbit view (`OrbitView`):** when `dig != null`, draw each `cells` block as a distinct
  **dig marker** (a small square/cube outline in a warm dig color, e.g. amber, to read apart
  from the cyan route trail), occluded by terrain via the existing `OrbitScene.depthAt`, and a
  **descent-entry beacon** (a downward chevron / "▼ Dig here") at `entry`. Reuse `projectHud` +
  the existing depth test.
- **Minimap HUD + fullscreen map:** the dig path is near-vertical, so top-down it collapses to
  roughly one spot. Draw a distinct **"dig here" icon** (downward triangle, amber) at `entry`'s
  `(x, z)`, clipped like the route dots. No trail change.
- **Status note:** the existing HUD status line ("Route: partial …") gains a Plan-B variant:
  "Descend: dig down at the marked shaft, then tunnel to break out."

## Testing

Pure, unit-testable pieces (JUnit, no Minecraft):

- **Pathfinder with `maxFall = 4`:** a fixture with a 4-block ledge chain routes through it;
  a 5-block sheer drop does not (stays a step above).
- **`DescentPlanner`:**
  - Straight-down case: solid column below a stuck frontier with an open ledge directly below →
    emits a vertical-only dig path to that exit (no tunnel).
  - **Overhang case:** column clears under an overhang but the exit is offset → emits an
    L-shaped path (vertical run + horizontal tunnel toward the goal to the break-out).
  - Column with a big air gap (void) → rejected; tries the next candidate.
  - Exit beyond `MAX_DIG`/`MAX_TUNNEL` bounds → candidate fails; if all fail, emits no dig plan
    (null), status stays PARTIAL.
  - Emitted dig-cell coordinates round-trip grid↔world correctly (shift math).
- **Trigger logic:** goal far below + stalled frontier → Plan B invoked; goal reachable or
  frontier descending → not invoked.

In-game verification (owner): route to a waypoint at the base of a cliff; confirm Plan A walks
down natural ledges, and where the face **overhangs**, Plan B marks an L-shaped dig path
(down + tunnel out) + beacon that, once dug, lets the route continue down.

## Implementation plans

Two plans, each independently shippable:

- **Plan A (`...-vertical-descent-plan-a.md`):** `safeDropBlocks` setting + settings UI;
  `RouteService` uses it for `maxFall`; downward box-bias check; Pathfinder descent test.
  Delivers natural cliff descent.
- **Plan B (`...-vertical-descent-plan-b.md`):** `DigPlan` on `Route`; `DescentPlanner`
  (dig-down + break-out tunnel for overhangs) + trigger in `RouteService`; dig-marker + beacon
  rendering in 3D + HUD + fullscreen; tests. Delivers the dig/tunnel fallback.

## Risks & limitations

- **Lava/hazards** are invisible to the opaque-only grid; a recommended dig path could pass
  through lava or a mob cavity. Mitigation: the dig plan carries a "verify the blocks" caveat in
  v1; real hazard detection is a future baked-predicate stretch.
- **Progressive legs:** deep descents surface one dig leg at a time; the player must dig a leg
  before the next appears. This is intentional (bounded compute, always accurate to where you
  are) but should be explained in the HUD note.
- **Very deep goals** still rely on re-routing as you descend; a single compute never plans the
  whole 1000-block descent at once.
