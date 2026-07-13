# Vertical Descent Routing — Design Spec

**Date:** 2026-07-12
**Status:** Approved (design); awaiting owner review of this spec before writing plans.
**Depends on:** Phase 1 routing (`2026-07-12-3d-routing-design.md`) — `TraversabilityGrid`, `Pathfinder`, `Route`, `RouteService`, and the route trail in the 3D view / minimap HUD / fullscreen map.

## Overview

Extend waypoint routing so it can find a survivable way **down cliff faces** in Mine in Abyss.
Today the pathfinder only descends via drops of ≤3 blocks and gives up (PARTIAL) on sheer
faces. This feature makes descent a first-class outcome:

- **Plan A — Natural descent:** route down existing ledges/staircases in safe hops.
- **Plan B — Dig/pillar fallback:** when A can make no downward progress, recommend a
  **descent shaft** the player digs (highlight-only — the mod never places or breaks blocks).

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
- When no natural descent makes downward progress, recommend one **dig-down shaft** for the
  next leg of the descent (Plan B), rendered as a highlight with an entry beacon.
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

### Plan B — Dig-down shaft fallback

Goal: recommend **one** vertical column to dig straight down for the next leg of the descent.
Digging straight down in MC descends one block at a time (each a ≤1 drop → free), so a solid,
gap-free column is a safe descent even down a sheer face.

Algorithm (`DescentPlanner`, pure, unit-tested — grid coordinates):

1. **Anchor.** Start from A\*'s best-reached standable cell `F` (nearest the goal). Choose the
   shaft column at `F`'s `(x, z)`, or one cell toward the goal's `(x, z)` if that column is
   solid at `F`'s level (so you can step to it before digging).
2. **Scan down** the column from `F.y - 1`: find `bottomY` = the first level at/below which the
   player can **exit** — a standable cell that opens toward the goal (air headroom on the
   goal-ward side), or the goal's own Y, whichever comes first.
3. **Validate the column is diggable and gap-free** between `F.y` and `bottomY`: every cell is
   opaque (so you mine controlled, one block per drop — no hidden void that becomes a lethal
   long fall). If a large air gap is found, the column is rejected.
4. **Reject & retry** up to a few candidate columns (F's column, then columns one step toward
   the goal in each horizontal direction). If none validate, emit **no** shaft and keep the
   Plan A `PARTIAL` (the trail still shows how far you can get).
5. **Emit** a `DescentShaft { x, z, topY, bottomY }` in world coordinates (un-shifted X/Y like
   the rest of `RouteService`).

Because this runs inside the progressive re-route loop, Plan B emits **one leg** at a time:
you dig down the recommended shaft, you're now lower, `RouteService` recomputes, and Plan A
(or the next Plan B shaft) takes over.

### Data model

Extend `Route` with an optional descent recommendation:

```java
public record DescentShaft(double x, double z, double topY, double bottomY) {}

public record Route(List<double[]> points, List<double[][]> bridges,
                    DescentShaft shaft, Pathfinder.Status status) {
    public static final Route EMPTY =
        new Route(List.of(), List.of(), null, Pathfinder.Status.NO_ROUTE);
}
```

`shaft` is `null` unless Plan B fired. `bridges` stays unused (reserved from Phase 1).
Update the `Route` constructor call sites (Phase 1 passes 3 args → now 4).

### Rendering

- **3D orbit view (`OrbitView`):** when `shaft != null`, draw a translucent vertical column
  from `(x, topY, z)` to `(x, bottomY, z)` (a stack of short segments, occluded by terrain via
  the existing `OrbitScene.depthAt`), a **landing ring** every `safeDropBlocks` down the shaft,
  and a **descent-entry beacon** (a distinct downward chevron / "▼ Dig here −N") at the shaft
  mouth `(x, topY, z)`. Reuse `projectHud` + the existing depth test.
- **Minimap HUD + fullscreen map:** a vertical shaft is a single point top-down. Draw a
  distinct **"descend here" icon** (downward triangle, route-cyan) at the shaft's `(x, z)`,
  clipped like the route dots. No trail change.
- **Status note:** the existing HUD status line ("Route: partial …") gains a Plan-B variant:
  "Descend: dig down at the marked shaft (~N blocks)."

## Testing

Pure, unit-testable pieces (JUnit, no Minecraft):

- **Pathfinder with `maxFall = 4`:** a fixture with a 4-block ledge chain routes through it;
  a 5-block sheer drop does not (stays a step above).
- **`DescentPlanner`:**
  - Solid column below a stuck frontier → emits a shaft from the ledge to the exit level.
  - Column with a big air gap (void) → rejected; tries the next candidate.
  - No valid column → emits no shaft (null), status stays PARTIAL.
  - Emitted shaft coordinates round-trip grid↔world correctly (shift math).
- **Trigger logic:** goal far below + stalled frontier → Plan B invoked; goal reachable or
  frontier descending → not invoked.

In-game verification (owner): route to a waypoint at the base of a cliff; confirm Plan A walks
down natural ledges, and where the face is sheer, Plan B marks a dig-down shaft + beacon that,
once dug, lets the route continue down.

## Implementation plans

Two plans, each independently shippable:

- **Plan A (`...-vertical-descent-plan-a.md`):** `safeDropBlocks` setting + settings UI;
  `RouteService` uses it for `maxFall`; downward box-bias check; Pathfinder descent test.
  Delivers natural cliff descent.
- **Plan B (`...-vertical-descent-plan-b.md`):** `DescentShaft` on `Route`; `DescentPlanner`
  + trigger in `RouteService`; shaft/ring/beacon rendering in 3D + HUD + fullscreen; tests.
  Delivers the dig-down fallback.

## Risks & limitations

- **Lava/hazards** are invisible to the opaque-only grid; a recommended dig column could pass
  through lava or a mob cavity. Mitigation: the shaft carries a "verify the column" caveat in
  v1; real hazard detection is a future baked-predicate stretch.
- **Progressive legs:** deep descents surface one shaft at a time; the player must dig a leg
  before the next appears. This is intentional (bounded compute, always accurate to where you
  are) but should be explained in the HUD note.
- **Very deep goals** still rely on re-routing as you descend; a single compute never plans the
  whole 1000-block descent at once.
