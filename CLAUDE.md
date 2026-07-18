# MIA Maps (`mia_aperture_mod`) — Project Instructions

Client-side Minecraft **1.21.11** (Fabric) mod for the *Mine in Abyss* server: a data-driven map
(2D minimap + fullscreen), a 3D orbit view, waypoints/routing, and mob tracking. It reads Voxy's
LOD data and does its own CPU rasterization.

## First thing every session

1. **Read `project_memory.md`** (repo root) — the deep continuity file; its newest "RESUME HERE"
   is the current state. This is the source of truth for what's in flight.
2. **Read the sister project's status:**
   `D:\Users\dev\VSCode-Projects\MIA_MAP_VOXY_FORK_project\docs\INTEROP.md` — what's happening in
   the Voxy fork that might affect this mod.
3. Read this repo's `docs/INTEROP.md` (your own outbound log to the fork).

## Build / conventions

- Build with the vendored JDK: `export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"`, then `./gradlew build`. Jar → `build/libs/`; install into the modpack `mods/`.
- **Branch policy:** develop on `main` (or the repo's default working branch). Do not create
  branches/worktrees. Commit locally; **push only when the owner asks** (private remote
  `crkt/MIA-Voxy-map-mod`; releases are private prereleases the owner publishes).
- Update `project_memory.md`'s RESUME-HERE after meaningful changes (owner's continuity convention).
- Commit messages end with:
- MC stdout swallows `System.out.printf` (no flush) — use `println` for diagnostics.
- Coordinate model: the "shifted column" (`MapGeometry`) mirrors Voxy's `AbyssUtil`; a world-space
  delta is only valid within one section. Suspect this first for coordinate bugs.

## Relationship to the Voxy fork

This mod reads its LOD data from a **Voxy fork** (`mia-map-voxy`) at
`D:\Users\dev\VSCode-Projects\MIA_MAP_VOXY_FORK_project`, developed in its own the editor thread.
The modpack currently runs a build of that fork (`voxy-mia-edition-2.5-<sha>.jar`; stock jar backed
up alongside it). This mod is a **read-only** consumer of Voxy:
`acquireIfExists` → `copyDataTo` → `release`. Voxy internals (`MAX_LOD_LAYER=4`, `AbyssUtil` shift,
storage config) are load-bearing — see the fork's `docs/INTEROP.md` for the contract.

## Two-way interop with the Voxy fork

- **When you change something that affects Voxy** (a new dependency on Voxy behavior, a bug you
  suspect is Voxy-side, a request for a Voxy change/jar), append a dated entry to **this repo's**
  `docs/INTEROP.md`.
- **At session start, read** the fork's `docs/INTEROP.md` (path above).
- For the cross-dir reads to work, add each project as an **additional working directory** in the
  other's the editor thread.
