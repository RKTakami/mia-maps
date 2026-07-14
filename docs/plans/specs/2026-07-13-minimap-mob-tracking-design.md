# Minimap Mob Tracking — Design Spec

**Date:** 2026-07-13
**Status:** Approved (design); awaiting owner review of this spec before writing plans.

## Overview

Show nearby entities on the minimap and fullscreen map: colored dots by category, with
up/down cues for entities off your level, optional labels, and per-category filters. Purely
client-side — the client only knows entities the server has sent it (roughly within entity
tracking range, a few chunks), which fits a local minimap radius well.

## Goals

- Track **hostiles**, **players**, and **passive** mobs, each toggleable (defaults: hostiles on,
  players on, passive off).
- **Colored dots**: hostile = red, player = white, passive = green.
- **Vertical band + cue**: only show entities within ±`MOB_BAND` (32) blocks of the player's Y on
  the minimap; mark ones above with **▲**, below with **▼**, and fade the dot with |Δy|.
- **Labels** (toggle, default off): minimap labels the **~3 nearest** tracked mobs; fullscreen map
  labels **all** tracked mobs.
- Render on the **minimap** and the **fullscreen map**.

## Non-goals

- No whole-layer / distant tracking (impossible client-side).
- No 3D-orbit-view mob markers in v1 (could be added later).
- No pathing to mobs.
- Not unit-testing entity classification (needs live MC types); keep that in a thin client helper.

## Data + classification

Each render, enumerate `mc.level.entitiesForRendering()`. Keep an entity if:
- it is a `LivingEntity` and not the local player (`entity != mc.player`),
- within `HUD_RADIUS_BLOCKS` horizontally (minimap) / the fullscreen view span,
- within `±MOB_BAND` vertically (minimap only; fullscreen shows all in its span).

Category (verified APIs):
- `entity instanceof net.minecraft.world.entity.monster.Enemy` → **HOSTILE** (red `0xFFFF3344`)
- `entity instanceof net.minecraft.world.entity.player.Player` → **PLAYER** (white `0xFFFFFFFF`)
- otherwise → **PASSIVE** (green `0xFF33CC44`)

**MIA caveat:** custom Abyss mobs are plugin/geary-driven and may not be vanilla `Enemy`
subclasses — they may fall into PASSIVE. v1 ships this heuristic; the labels reveal their real
names/types, and a follow-up can add MIA-specific rules (name/type-id matching, or "unknown
living = hostile"). Documented, not blocking.

Name: `entity.getName().getString()` (custom name if set, else the type name).

## Architecture

New client helper `client/MobTracker`:

```java
public final class MobTracker {
    public enum Cat { HOSTILE(0xFFFF3344), PLAYER(0xFFFFFFFF), PASSIVE(0xFF33CC44);
        public final int color; Cat(int c){ color=c; } }
    public record Blip(double x, double y, double z, Cat cat, String name, double horizSq) {}

    // Collect tracked entities within horizRadius (blocks) and ±band (blocks, <=0 = no band),
    // honouring the settings toggles. Sorted nearest-first by horizontal distance.
    public static List<Blip> collect(Minecraft mc, double horizRadius, double band, MapSettings s);
}
```

- Filters by `s.trackHostiles/trackPlayers/trackPassive` per category.
- Returns blips sorted by `horizSq` (so "nearest N" is just the head of the list).

## Settings

Add to `MapSettings` (booleans): `trackHostiles = true`, `trackPlayers = true`,
`trackPassive = false`, `mobLabels = false`. Guard nulls in `MapConfig.fromJson` (Gson runs
field initializers, so missing keys keep defaults — booleans are safe; no extra guard needed).
Four cycle/toggle buttons in `MapSettingsScreen` (existing button pattern).

Add `MOB_BAND = 32.0` constant (in `MobTracker` or `MinimapRenderer`).

## Rendering

**Minimap (`MinimapRenderer.draw`)** — after the route/waypoint dots, before cardinals:
- `List<Blip> blips = MobTracker.collect(mc, HUD_RADIUS_BLOCKS, MOB_BAND, s);`
- For each blip: project like a waypoint dot (dx/dz → `wpRot` rotation → clamp to radius),
  draw a 2px dot in `cat.color`, alpha-faded by |Δy|/band. If `Δy > 2`, draw a tiny **▲** just
  above the dot; if `Δy < -2`, a **▼**.
- If `s.mobLabels`, draw the name beside the dot for the **first 3** blips (nearest).

**Fullscreen (`AbyssWorldMapScreen`)** — after the route/waypoint markers:
- `MobTracker.collect(mc, <fullscreen horizontal span/2>, 0, s)` (no vertical band; fullscreen
  spans layers). Project via `MapGeometry.screenOffsetPixel`, cull off-screen.
- Dot in `cat.color`; if `s.mobLabels`, label **all** with the name.

Small shared triangle glyph helper (reuse the `drawDownTriangle` pattern; add an up variant) for
the ▲/▼ cues.

## Testing

Entity classification/rendering needs live MC types, so no JUnit there. One small pure-testable
piece: a `verticalCue(dy)` helper returning `▲`/`▼`/none (trivial). Primary verification is
in-game (owner): confirm dots appear for nearby mobs, colors/among categories look right, band +
▲/▼ behave, labels toggle works, and report what the real Abyss mobs classify as (to tune
HOSTILE detection next).

## Implementation plan (one plan)

1. `MapSettings` fields + settings-screen toggles.
2. `MobTracker` helper (collect + classify + sort).
3. Minimap rendering (dots + band + ▲/▼ + nearest-3 labels).
4. Fullscreen rendering (dots + all labels).
5. Build, install, in-game verify + gather mob-classification feedback.
