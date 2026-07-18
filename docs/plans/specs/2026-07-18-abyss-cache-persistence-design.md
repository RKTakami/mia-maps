# Whole-Abyss Cache Persistence — Design

**Date:** 2026-07-18
**Status:** Design approved (pending written-spec review)
**Depends on:** the whole-Abyss 3D view (`2026-07-16-whole-abyss-3d-view-design.md`, code-complete WA-T1..T6) — this feature persists and shares the cache that feature builds in memory.

## Goal

Make the whole-Abyss span cache **survive across sessions** and **share across the servers a single client connects to**, so that data mapped on the `build` server seeds the whole-Abyss overview on `survive`, with **survive's data prioritized** wherever it exists. The picture the owner wants — the entire Abyss in cross-section — should be available immediately on session start, filled in from prior play on either server.

## Non-negotiable constraint: anti-clone

The owner's hard requirement is that the Abyss construction must be as close to impossible to pirate as achievable. The honest reality (established during brainstorming):

- Any data the client renders, the client must hold in readable form — it cannot be encrypted against its own user.
- Voxy **already** stores full LOD data unencrypted on disk per server (`.voxy/saves/<host>/<worldhash>/`, RocksDB). Copying that folder is the pre-existing, easier leak. Nothing this feature does can be *more* secure than that source.

Therefore the design's anti-clone guarantee is structural, not cryptographic:

> **The persisted file is written only from the builder's native LOD-4 working maps. Nothing finer than 16-block cells is ever serialized.** A 16-to-64-block, surface-only silhouette cannot reconstruct a block-level build. The artifact is *incapable* of cloning by construction.

This is why the persisted cache stays **coarse**. Fine-LOD sharing is explicitly out of scope (see "Out of scope").

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| Threat model | Build cloning — defeated by the coarseness cap above. |
| Sharing scope | Local-only, cross-server. One file on the player's machine, shared across the servers their client connects to. Never distributed. |
| File protection | Compact gzip binary, no encryption, no account/machine binding (would not stop cloning; only adds fragility). |
| Persistence approach | Snapshot file: load-seed on start, periodic + lifecycle save (Approach A). |
| Merge priority | Survive prioritized. A `primaryServer` setting (default `survive.mineinabyss.com`); primary outranks all; among non-primary, newest-wins. |
| Priority granularity | Gap-fill per depth: survive wins at depths it has mapped (is solid), build fills the rest of the column. No holes below explored depth. |

## Architecture

### Two-layer store + gap-fill composite

The builder keeps **two** column maps instead of one:

- `primaryWorking` — columns captured while connected to the primary server (survive).
- `fallbackWorking` — columns captured on any other server (build, etc.). Among non-primary servers it is simply last-write-wins: a later probe overwrites the section Y-window via `clearRange`+`insertRun`, so no per-column timestamp is stored.

Each probe is bucketed into one map by the current server's host (see "Tier detection"). Everything else about probing is unchanged from the whole-Abyss builder: `clearRange` the section Y-window, then `insertRun` solid runs — but into the tier-appropriate map.

At snapshot time a **pure composite** merges the two into the single base map the renderer already consumes:

- For a column present in only one layer, use that layer as-is.
- For a column present in both: **primary spans win at every depth they occupy** (primary color); **fallback fills only the depths primary does not occupy** (fallback color). Implemented with existing `SpanMath` primitives — for each fallback span, subtract the primary-occupied depths (`clearRange` against primary's spans) and `insertRun` the remaining fragments with the fallback color, then union with the primary spans.

The composite output feeds the existing `AbyssSpanStore.buildSnapshot` (base → mip1 → mip2 → surface counts), so **`OrbitScene` and `OrbitView` need no changes** — they still consume `Snapshot`.

**Accepted simplification:** where survive has mapped *air* but build has solid rock, build shows through. At 16–64-block cells a player-scale tunnel is sub-cell and invisible anyway, so per-depth *air-suppression* (coverage masks) is a non-goal. Revisit only if a real coarse-scale divergence appears in game.

### Tier detection

A volatile `currentTierIsPrimary` flag, set by connection lifecycle events:

- On client **JOIN**: read `Minecraft.getCurrentServer().ip`, host-match (case-insensitive, port-ignored) against `MapSettings.primaryServer`; set the flag; and **enqueue a full sweep** so the newly joined server's on-disk Voxy data imports into the correct tier.
- On **DISCONNECT**: flag irrelevant (no probing without a live engine).

The builder loop reads the flag to choose which working map a probe writes into. Singleplayer / unknown host → fallback tier.

### Persistence (Approach A)

- **File:** `FabricLoader.getConfigDir().resolve("mia_aperture_abyss_model.bin")` — instance-global, alongside the existing `mia_aperture_map.json`, shared across all servers.
- **Format** (pure `AbyssModelCodec`): magic bytes + format version + the `primaryServer` string it was written under, then two labeled layer sections (primary, fallback). Each layer: column count, then per column `cellX, cellZ, spanCount, spans[], colors[]`. The whole stream is gzip'd (`java.util.zip.GZIPOutputStream` — no new dependency).
- **Load** (mod init): decode → seed `primaryWorking` + `fallbackWorking` → publish an initial composited snapshot immediately, so the whole-Abyss view is **instant-on** from cache before any probing.
- **Save:** briefly lock the builder to shallow-copy both maps (columns are immutable records, so reference copies are safe), release, then encode + write **to a temp file and atomically rename** (crash-safe). Triggers: a periodic timer (~3 min) when dirty, the existing DISCONNECT handler, and client-stopping.

### Seeding / import behavior

Automatic, no manual export/import. While connected to a server, the full sweep probes every LOD-4 section via Voxy's `acquireIfExists`, which reads from that server's **on-disk RocksDB** (verified: `ActiveSectionTracker` calls `storage.loadSection` on a cache miss) — so it imports everything that server already has on disk, not just what is in memory. Play `build`, the sweep imports build's map into `fallbackWorking`, it saves to the file; join `survive` later, the file loads, and build's map fills the overview under survive's prioritized data.

Caveat recorded for honesty: this imports what **your client** accumulated on a server (Voxy is client-side LOD), while that server's engine is live. It is not the server's authoritative master map, and it never reads another server's DB while you are not connected to it — the file is the only cross-session/cross-server bridge.

### Corruption / versioning

Bad magic, truncated stream, or unknown version → log, ignore, start fresh; never crash. Reuse the existing `MAX_COLUMNS` guard as an upper bound while decoding.

## Components / files

| File | Responsibility | New? | Tested? |
|---|---|---|---|
| `map/AbyssModelCodec.java` | Pure encode/decode of the two-layer model ↔ gzip bytes; version + corruption handling | new | yes |
| `map/AbyssComposite.java` | Pure gap-fill merge(primary, fallback) → composited base map | new | yes |
| `map/AbyssModelBuilder.java` | Two working maps, tier bucketing, full-sweep on join, load/seed, save triggers, composite before `buildSnapshot` | modify | no (Voxy-facing) |
| `map/AbyssSpanStore.java` | Unchanged snapshot/mips/surface walk; consumes the composited base map | (minimal) | existing |
| `map/MapSettings.java` | `primaryServer` (default `survive.mineinabyss.com`); `persistAbyssModel` toggle (default true) | modify | yes |
| `map/MapConfig.java` | Serialize the new settings | modify | existing |
| `client/MiaApertureModClient.java` | Load on init; JOIN (set tier + enqueue sweep); DISCONNECT + client-stopping save | modify | no |

`AbyssModelCodec` and `AbyssComposite` must not import `me.cortex.voxy.*` or `net.minecraft.*` — they stay on the test classpath, matching the project's pure/impure split.

## Testing

Pure, unit-tested:

- **`AbyssModelCodec`**: round-trip both layers including empty maps, negative coords, multi-span columns, colors; reject bad magic and truncated input (return empty, no throw); handle version mismatch; confirm the `primaryServer` string round-trips.
- **`AbyssComposite`**: primary-only column passes through; fallback-only passes through; overlapping column keeps primary spans/colors and fills only the gaps with fallback; fallback below primary's lowest span fills (no hole); both-null → null.

File-path resolution, connection-event wiring, and save cadence are verified in game (they touch Fabric/Minecraft).

## Out of scope (recorded for the future)

**Fine-LOD (build detail) sharing into survive.** Requested during brainstorming; deferred as a *separate* future feature, never folded into persistence, because:

- Persisting merged fine LOD would manufacture a portable, cross-server, near-complete copy of the build — exactly the build-cloning artifact this design exists to avoid.
- Whole-world-at-finest is infeasible regardless (LOD-0 full depth ≈ 294M cells; the live 3D view is hard-capped at 4096 blocks for this reason).

If pursued, the only anti-clone-safe shape is **live read-only** of build's *existing* Voxy DB to gap-fill only the near-player region while on survive (survive prioritized), creating **no new persisted artifact** — so the cloning risk stays exactly what Voxy already imposes. That is a substantial feature (a second Voxy storage backend opened read-only, coordinate mapping, prioritized live sampling) and gets its own spec.

## Release

Ships in the same beta line as the whole-Abyss view. Cut after in-game verification; no separate release gate beyond the existing v0.1.8-beta plan.
