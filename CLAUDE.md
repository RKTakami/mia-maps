# MIA Maps (`mia_aperture_mod`) — Project Instructions

Client-side Minecraft **1.21.11** (Fabric) mod for the *Mine in Abyss* server: a data-driven map
(2D minimap + fullscreen), a 3D orbit view, waypoints/routing, and mob tracking. It reads Voxy's
LOD data and does its own CPU rasterization.

## First thing every session

1. **Read `project_memory.md`** (repo root) — the deep continuity file; its newest "RESUME HERE"
   is the current state. This is the source of truth for what's in flight.
2. **Read the sister project's status:** the Voxy fork's `docs/INTEROP.md` — what's happening
   in the fork that might affect this mod. Checkout location per machine:
   - Windows: `D:\Users\dev\VSCode-Projects\mia-voxy-fork`
   - macOS: `/Users/rkt/mia-voxy-rust`
3. Read this repo's `docs/INTEROP.md` (your own outbound log to the fork).

## Build / conventions

- **Build:** set `JAVA_HOME` to a JDK 21, then `./gradlew build`. Jar → `build/libs/`; install into
  the modpack `mods/`. Tests: `./gradlew test` (JUnit 5, pure map classes).
  - Windows (vendored JDK): `export JAVA_HOME="D:/Users/dev/VSCode-Projects/MIA map mod project/libs/jdk21/jdk-21.0.11+10"`
  - macOS (Homebrew `openjdk@21`): `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- **Voxy API surface (`compileOnly`, never bundled).** The build prefers a sibling fork checkout's
  Mojang-mapped DEV jar (`../mia-voxy-rust/build/devlibs/*-dev.jar`), so **build the fork once and
  this repo compiles** — a new fork API is usable immediately, with no artifact to regenerate per
  machine. Override with `-PvoxyDevJar=<path>`; falls back to `libs/voxy-stripped.jar` if present.
  The build logs which it chose. Symptom if neither exists:
  `package me.cortex.voxy.client.core does not exist`.
  - **⚠ Never commit either jar.** The fork is *"Copyright 2025 MCRcortex, All rights reserved, Do
    not redistribute"* and **this repo is PUBLIC** — `libs/` is gitignored deliberately, and the
    jars contain real Voxy bytecode, not stubs.
  - It must be the **DEV (Mojang-mapped)** jar, not the distributable/intermediary one — the latter
    breaks compilation of any Voxy method with MC types in its signature. Loom remaps our calls back
    to intermediary at build time.
- **Rust native (`map-native/`)**: built by the `buildMapNative` Gradle task, which copies the
  result into `src/main/resources/natives/`. Requires `cargo` on PATH. `MapNative` picks the
  artifact by OS — `map_native.dll` / `libmap_native.dylib` / `libmap_native.so` — and **those
  committed files are what a release jar ships**, so a build on one OS only refreshes that OS's
  artifact. Rebuild on the relevant machine before releasing a change to `map-native/`.
  - On macOS the task builds both Darwin slices and `lipo`s them into one universal dylib. That
    needs rustup, not Homebrew's `rust` (host std only): `rustup target add aarch64-apple-darwin
    x86_64-apple-darwin`. Check with `lipo -info` → `x86_64 arm64`.
  - The Gradle daemon caches the PATH it started with; run `./gradlew --stop` after changing the
    Rust toolchain or `cargo` will appear missing to the build while working fine in the shell.
- **Branch policy:** develop on `main` (or the repo's default working branch). Do not create
  branches/worktrees. Commit locally; **push only when the owner asks**. Remote
  `crkt/mia-maps` (renamed from `MIA-Voxy-map-mod` 2026-07-22 — Voxy is a separate project).
  The repo is **PUBLIC** (owner's explicit choice 2026-07-22) but **owner-only writable** (no other
  collaborators; non-collaborators can only read/fork). Releases are public prereleases the owner cuts.
- Update `project_memory.md`'s RESUME-HERE after meaningful changes (owner's continuity convention).
- Commit messages end with:
- MC stdout swallows `System.out.printf` (no flush) — use `println` for diagnostics.
- Coordinate model: the "shifted column" (`MapGeometry`) mirrors Voxy's `AbyssUtil`; a world-space
  delta is only valid within one section. Suspect this first for coordinate bugs.

## Relationship to the Voxy fork

This mod reads its LOD data from a **Voxy fork** (remote `crkt/mia-voxy-rust`; its own
`AGENTS.md` still calls itself `mia-map-voxy`), checked out at the per-machine paths in
"First thing every session" above. It is developed in its own the editor thread.
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
