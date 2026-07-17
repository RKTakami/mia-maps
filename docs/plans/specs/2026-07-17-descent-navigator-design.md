# Descent Navigator — Design

**Status:** approved (owner, 2026-07-17)
**Date:** 2026-07-17
**Goal:** Route multilayer descents the way the Abyss is actually traversed — down survivable drops, and where a drop is unsafe or blocked, along dug stairs and tunnels that bridge to the next safe drop, all marked amber.

## Problem (confirmed by debugging, 2026-07-17)

Multilayer routing produces incomplete routes that ignore usable staircases and never offer a
dig. Root cause, confirmed from instrumented logs plus the owner's in-game observation:

- The A* router (`Pathfinder`) only takes drops up to `safeDropBlocks` (default 4). The Abyss's
  descents are a **mix** of walkable steps, multi-block drops, and open shafts. A* descends the
  gentle parts, then **refuses** the first drop larger than 4 and wanders off along short ledges —
  the "route through rock, ignoring the stairs" symptom.
- When it stalls, the current `DescentPlanner` only digs **straight down through solid, then
  tunnels once**. At the stuck ledge the block below is a thin floor over an **open shaft**; it
  mines through, hits air, tries to tunnel through the shaft wall, fails, and returns null — so no
  amber dig appears. It is behaving correctly: it refuses to "dig into a void." The gap is that
  **nothing can express "drop down this shaft," which is how you descend the Abyss.**

Log evidence at the stuck point: `status=PARTIAL path=214 frontierY=26 goalY=1 drop=25
descentRemains=true dig=false` — the route descended 214 cells, still had 25 to go, but produced
no dig.

A prerequisite fix already landed in debugging (staged, uncommitted): the corridor was demoted
from steering the router to a **visual guide only**, so `RouteService.compute` aims the
block-accurate search at the destination again. That is part of this feature.

## Design

Approach A: extend the pathfinder's fall model and replace the descent-only `DescentPlanner`
with a capable, pure `BridgePlanner`. Both are pure and unit-testable (Voxy is `compileOnly`,
off the test classpath); all Voxy/Minecraft contact stays in `RouteService`.

### 1. Two-tier fall model — `Pathfinder`

`Pathfinder` already models a drop as "step to an adjacent column and land on the highest
standable cell within `[y+stepUp, y-maxFall]`," costing `1 + (dropHeight)·0.5`. Change:

- `Params(int stepUp, int maxFall, int maxJumpGap)` → `Params(int stepUp, int safeFall, int
  survivableFall, int maxJumpGap)`.
- The drop scan bound becomes `survivableFall` (the largest drop allowed).
- Move cost for a drop of height `h`:
  `1 + h·SAFE_DROP_COST + max(0, h − safeFall)·EXTRA_DROP_PENALTY`
  with `SAFE_DROP_COST = 0.5` (unchanged gentle cost) and `EXTRA_DROP_PENALTY = 1.5`.

Effect: gentle drops (≤ `safeFall`) stay cheap, so A* prefers staircases and small hops; a
survivable drop is allowed but progressively expensive, chosen only when nothing gentler reaches
the goal. Drops beyond `survivableFall` remain impossible.

`Pathfinder` is pure and already unit-tested — this extends `Params`, the drop scan, and the cost,
with new tests. All existing `Pathfinder.Params(...)` call sites (RouteService, tests) update to
the new 4-arg form.

### 2. `BridgePlanner` (new pure class, replaces `DescentPlanner`)

When A* stalls with descent remaining, `BridgePlanner` runs a **bounded, cost-minimising
mini-search from the frontier** over two mining moves, to reach the nearest point where natural
descent resumes:

- **Dig-a-stair-step:** toward the goal's dominant horizontal direction, mine the foot (and head,
  if solid) of the down-and-forward cell and descend one — builds a diagonal staircase through
  solid rock or an overhang lip.
- **Dig-a-tunnel-block:** mine the foot (and head) of the forward cell and step through — builds a
  horizontal shaft across a wall.

**Resume test (goal of the mini-search):** a standable cell that is (a) strictly lower than the
frontier, (b) no farther from the goal than the frontier (horizontal Manhattan), and (c) offers a
natural continuation toward the goal without further digging — a walkable step down/across, OR a
drop of ≤ `survivableFall` that lands standable, heading goalward. Conditions (a)+(b) guarantee
the bridge makes real descent progress rather than wandering.

**Bounds:** total mined blocks ≤ `MAX_BRIDGE_DIG` (32); search radius bounded to the frontier's
neighbourhood. Cost = blocks mined, so the search returns the **cheapest** bridge (fewest blocks).
Returns the ordered mined cells + entry, or **null** when no resume point is reachable within
budget (caller then keeps the partial route, as today — never a tunnel into nowhere).

Output type is the existing `Route.DigPlan(int[] entry, List<int[]> cells)` (grid coords), so
nothing downstream changes.

Pure and unit-testable: synthetic grids for overhang→tunnel, wall→stairs, drop-resume, and
unreachable→null. `DescentPlanner` and its tests are removed (superseded).

### 3. Integration — `RouteService`

- Build `Pathfinder.Params(1, safeDropBlocks, maxSurvivableDrop, 1)` from settings.
- Run the walkable A* (now taking survivable drops).
- Where it returns PARTIAL with descent remaining (`frontier.y() > goal.y() + safeDrop`), call
  `BridgePlanner.plan(grid, frontier, goal, params, MAX_BRIDGE_DIG)`; attach any result as the
  `Route.DigPlan`. Leg-by-leg and progressive: you walk to the frontier, follow the amber bridge
  to the resume point, and the next reroute continues from there.
- The corridor remains a **visual guide only** (the staged debugging fix); it no longer steers
  this router.
- Remove the temporary DIAG instrumentation added during debugging.

### 4. Setting — survivable drop

- Keep `safeDropBlocks` (default 4, range 2–8) as the gentle/preferred tier.
- Add `maxSurvivableDrop` (default 16, clamped 4–28) as the accept tier, with a Settings stepper
  mirroring the existing safe-drop control and `MapConfig` persistence. Enforce
  `maxSurvivableDrop ≥ safeDropBlocks` on load.
- `MapSettings` is pure and tested → add the clamp/ordering test.

### 5. Rendering

No renderer changes. The bridge is a `Route.DigPlan`, already drawn amber on the in-world overlay,
minimap, fullscreen map, and 3D view. The only tweak: relabel the "Dig here" beacon to **"Descend
here"** (it now marks stairs/tunnels/drops, not just a dig), in the fullscreen map and 3D view
strings.

## Architecture / files

| File | Responsibility | Change | Tested? |
|---|---|---|---|
| `map/Pathfinder.java` | A* over standable cells | two-tier `Params` + drop cost | yes (extend) |
| `map/BridgePlanner.java` | Pure stair/tunnel/drop bridge search | new | yes |
| `map/DescentPlanner.java` | old dig-only planner | delete | remove tests |
| `map/RouteService.java` | route worker | params + BridgePlanner wiring; drop DIAG; corridor guide-only | no |
| `map/MapSettings.java` | settings | `maxSurvivableDrop` + clamp | yes |
| `client/MapSettingsScreen.java` | settings UI | survivable-drop stepper | no |
| `map/MapConfig.java` | persistence | persist the new field | no |
| `client/AbyssWorldMapScreen.java`, `client/OrbitView.java` | dig label | "Dig here" → "Descend here" | no |

`BridgePlanner` must not import `me.cortex.voxy.*` or `net.minecraft.*`.

## Testing

- `PathfinderTest` (extend): prefers a staircase over an equal-length big drop; takes a survivable
  drop when it is the only way down; never exceeds `survivableFall`; gentle-drop cost unchanged.
- `BridgePlannerTest` (new): overhang lip → short stair bridge; solid wall over a shaft → tunnel to
  the shaft then resume via drop; no resume within budget → null; prefers the fewest-blocks bridge.
- `MapSettingsTest` (extend): `maxSurvivableDrop` clamps to 4–28 and is raised to `safeDropBlocks`
  when set lower.
- In-game: the owner's "Twilight Forest old stair" repro descends with amber stair/tunnel bridges
  where the shaft is unsafe, instead of dead-ending; the route stops ignoring the walkable steps.

## Out of scope

- Whole-route (non-progressive) dig planning — leg-by-leg only.
- Auto-mining or path execution — the mod only *marks* the bridge; the player digs/descends.
- Bridging horizontally across the whole map — the bridge is a local descent aid, bounded by
  `MAX_BRIDGE_DIG`; distances beyond that keep the existing partial-route behaviour.
- Fall-damage modelling beyond the single `survivableFall` threshold.
