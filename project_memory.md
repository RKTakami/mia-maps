# Project Memory - MIA Aperture & Map Mod

This document serves as the compact, high-density memory state for this project. Future sessions must read and update this file to preserve continuity without context expansion.

---

## 1. Project Metadata
* **Mod Name**: Abyss Aperture & Map Mod
* **Loader & Version**: Fabric / Minecraft `1.21.1` (compiled against custom client pack `1.21.11`)
* **Mappings**: Official Mojang Mappings
* **GitHub Repository**: `github.com/crkt/MIA-Voxy-map-mod`
* **Local Workspace**: `<project-root>`

---

## 2. Compilation & Toolchain
* **Required JDK**: Java 21
* **Build Command**:
  ```powershell
  $env:JAVA_HOME="<project-root>\libs\jdk21\jdk-21.0.11+10"; .\gradlew build
  ```
* **Target Output**: `build/libs/mia-aperture-mod-1.4.0.jar`
* **Unit tests**: `.\gradlew test` (JUnit 5, pure map classes)
* **Install Target**: `<mods-dir>\` (the real client instance; its `logs/latest.log` is the authoritative test log)

---

## 3. Core Architecture (v1.2.0, data-driven map)
* **Map data path (NO Voxy render hijacking)**: `com.mia.aperture.map` reads Voxy's world DB directly. `MapWorker` (daemon thread) does `WorldEngine.acquireIfExists → WorldSection.copyDataTo → release`, `MapTileRenderer` (pure, unit-tested) column-scans a Y band into 32x32 ARGB tiles (RELIEF slope shading / VANILLA 3-tone; water depth blend), `MapTileCache` (LRU 1024) holds them, `MapCompositor` composes visible tiles into `DynamicTexture`s (512² map @10Hz, 128² HUD @2Hz) on the render thread. Zoom picks Voxy mip lvl 0-4 (`MapGeometry.lvlForView`).
* **Coordinate identity (IMPORTANT, avoids re-litigating)**: shifted X = worldX − (sector<<14), valid only within the sector's domain [-8192, 8192) — the compositor clamps to it (cross-layer aliasing guard). Shifted Y = abyssDepth + 3840 for EVERY sector (the 480·sector and (240−30·sector)·16 terms cancel) — band Y values are sector-invariant; a final reviewer flagged a "sector mismatch" here that is mathematically impossible.
* **Culling Mechanism (unchanged live feature)**: `NodeManagerMixin` intercepts `processGeometryResult`, returns empty geometry outside the Y-aperture, preserving child masks. `H` toggles; Ctrl+scroll adjusts.
* **Scroll Interception**: `MouseMixin` (in-game scroll) + `KeyboardMixin` (Ctrl/Alt state from KeyEvents — glfwGetKey polling is unreliable since MC 1.21.9). Aperture changes reload Voxy's ring trackers via `RenderDistanceTrackerMixin`/duck.
* **Mixins (only 5)**: MouseMixin, KeyboardMixin, VoxyRenderSystemMixin (renderDistanceTracker accessor only), RenderDistanceTrackerMixin, NodeManagerMixin.
* **Known instance issue (not mod code)**: `config/voxy_mia_light_zones.json` in the Modrinth instance is malformed (JsonSyntaxException each boot) — harmless to the map (we don't use Voxy lighting) but the owner may want to fix/refresh it from the modpack.

---

## 4. Current Status & Next Actions

### RESUME HERE (2026-07-08, v1.4.0)
**v1.4.0: MINIMAP ORIENTATION, SHAPE, SIZE, CARDINALS, SETTINGS SHIPPED (pending owner in-game verification).** Minimap orientation is now configurable: north-up (N always at top; the position arrow rotates with facing) or heading-up (`MinimapMarkers.headingRotationRad` rotates the whole map so facing is always up; the arrow points straight up). N/E/S/W cardinal markers (`MinimapMarkers.cardinalPos`, drawn by `MinimapFrame.drawCardinals`) sit around the minimap edge and orbit correctly in heading-up mode. Minimap frame can be square or round (`MinimapFrame` applies a round mask + border overlay when round is selected). Minimap size is user-adjustable (`MapSettings.minimapSize`, clamped). All of this is configured from a new settings panel (`MapSettingsScreen`), opened via a "Settings" button added to the fullscreen map screen, and persists to `config/mia_aperture_map.json` via `MapConfig` (Gson round-trip, loaded on init). The HUD texture is now oversampled to 256²/radius 96 to give rotation headroom without clipping at 45° headings. Pure helpers (`MapSettings` clamp logic, `MinimapMarkers` angle math, `MapConfig` round-trip) are unit-tested; 34 JUnit tests pass total (up from 24 in v1.3.0).
Also carried in this release: the v1.3.1 fixes — air/missing-texture blocks no longer bake to opaque magenta on the map, and the HUD facing-arrow rotation was corrected (`yaw+180`).
Docs: spec `docs/plans/specs/2026-07-08-minimap-orientation-shape-settings-design.md`, plan `docs/plans/plans/2026-07-08-minimap-orientation-shape-settings.md`.
**Owner verification checklist for v1.4.0 (report results):**
1. Minimap shows N/E/S/W; north-locked: N at top, position arrow rotates with facing.
2. Settings (button on M screen) → Rotate with facing: map spins so facing is up, arrow points up, cardinals orbit (turn E/W to confirm N moves correctly).
3. Square vs Round frame both correct (round hides corners + has border, no clipped data at 45° headings).
4. Size slider resizes the minimap smoothly; depth/layer text follows; setting persists after a full game restart.
5. Fullscreen map (M) shows edge cardinals + working Settings button.
6. No `[MIA Aperture]` errors; no magenta over open air (v1.3.1 air fix holds).

### v1.3.0 (2026-07-08)
**v1.3.0: RESOURCE-PACK COLOURS + 2048 MAP SHIPPED (pending owner in-game verification).** Map colours now come from the active resource pack instead of a flat vanilla palette. `BlockColorBake` runs on the render thread, walks the block atlas, and averages the top + side sprite pixels per Voxy blockId into per-face colours, publishing the result as an immutable `Snapshot` so worker threads can read it safely without locking; it grows on demand with `Mapper.getBlockStateCount()` as new blocks are seen. `BiomeTintResolver` maps biomeId → grass/foliage/water tint via `Biome.getGrassColor/getFoliageColor/getWaterColor`, cached in a `ConcurrentHashMap`, with a graceful default for unresolved biomes. `ColorMath` is a small pure-function module (alpha-weighted sprite averaging + tint multiply) and is unit-tested. A new `Face` enum (TOP/SIDE) formalizes per-face colour lookup — side colours are baked now but not yet consumed (groundwork for a deferred side/X-scan view). The fullscreen map resolution was raised 512→**2048²**; composition is now driven by a view-signature + the `MapWorker` completed-tile counter so recompose only happens on an actual change, rate-limited to avoid a stutter storm while streaming in fresh terrain. The old `MapColor`-based colour path was fully retired. 24 JUnit tests pass (5 ColorMath + existing geometry/cache/renderer suites).
Docs: spec `docs/plans/specs/2026-07-08-map-colours-and-resolution-design.md`, plan `docs/plans/plans/2026-07-08-map-colours-and-resolution.md`, API spike findings `docs/plans/notes/2026-07-08-colour-api-findings.md`.
**BUILD GOTCHA reminder still applies**: `libs/voxy-stripped.jar` must be the Mojang-mapped DEV jar (gitignored) — see the v1.2.0 gotcha below, unchanged.
**Semantics change to watch in verification**: `isOpaque` now means "the averaged top sprite has non-zero alpha," not MC's actual light-opacity value — glass and cross-model plants (flowers, tall grass) should be spot-checked to make sure this doesn't make glass read as solid on the map.
**Known limitation (accepted, future work)**: colours re-bake only on disconnect (`MapCompositor.reset()` via the DISCONNECT event), so swapping a resource pack while connected (F3+T) won't refresh map colours until reconnect. Add a resource-reload listener if this becomes desired.
**Owner verification checklist for v1.3.0 (report results):**
1. Map colours match the resource pack (custom MIA blocks read correctly, not vanilla-palette flat).
2. Grass/foliage/water tinted; different layers/biomes show different greens/blues.
3. Colours not R/B-swapped (spike confirmed getPixel returns ARGB, so they should be correct; if swapped, that's the signal something regressed).
4. Fullscreen map visibly sharper (2048²); pan/zoom/slice responsive; no stutter storm when opening over fresh terrain.
5. Idle = no lag (no recompose when standing still); tiles still fill in progressively as you explore.
6. Glass / cross-model plants (flowers, tall grass) render sensibly — flag if glass now opaquely hides what's beneath (isOpaque semantics change).
7. No crashes, no `[MIA Aperture]` error spam in the log.

### v1.2.0 (2026-07-07)
**v1.2.0: DATA-DRIVEN MAP SHIPPED (pending owner in-game verification).** The viewport-hijack approach was ABANDONED per spec `docs/plans/specs/2026-07-07-data-driven-map-design.md` (plan `docs/plans/plans/2026-07-07-data-driven-map.md`, executed via subagents with per-task review). New `com.mia.aperture.map` package reads Voxy's WorldSection DB directly on a worker thread (acquire→copyDataTo→release), rasterizes 32x32 tiles CPU-side (column scan within a Y band; RELIEF slope shading default, VANILLA 3-tone via V key; water depth blending), LRU-caches them, and composes into DynamicTextures (512 map @10Hz, 128 HUD @2Hz — HUD is finally LIVE). Zoom out to ~20k blocks via Voxy mip levels (lvl 0-4); composition clamped to the centre sector's shifted-X domain [-8192,8192) to prevent cross-layer aliasing. Ctrl+scroll slices the map band (shares scrollTargetCenterY with the world aperture). Old FBO path + 5 mixins DELETED (mixins.json down to 5 entries); aperture culling feature intact. 19 JUnit tests (first tests in this project; `gradlew test`).
**BUILD GOTCHA (2026-07-07): `libs/voxy-stripped.jar` must be built from Voxy's DEV (Mojang-mapped) jar, not the distributable (intermediary) jar** — the intermediary one breaks compilation of any Voxy method with MC types in its signature (e.g. `Mapper.getBlockStateFromBlockId`). Current file was regenerated from `voxy-clone/build/devlibs/voxy-mia-edition-2.15-dev.jar`. `libs/` is gitignored: other machines must repeat this. Loom remaps our calls back to intermediary at build time (verified via javap on the built jar).
**Owner verification checklist for v1.2.0 (report results):** (1) HUD minimap shows live terrain within ~1s of joining; (2) M map shows explored terrain, pans/zooms, full zoom-out shows the layer footprint (verifies Voxy mips exist at lvl 2-4 — spec risk); (3) V toggles relief/vanilla, colors sane (if red/blue swapped: switch setPixel→setPixelABGR in MapCompositor, one line); (4) Ctrl+scroll in map moves the slice; with H off the world is unaffected; (5) in-game Ctrl+scroll aperture culling still works; (6) no diag spam, no crashes (no GL readbacks remain). Side view (P) is DEFERRED — returns later from the same data (X-scan).

**The 7 root causes found+fixed this session (2026-07-06, v1.1.5→v1.1.20):**
1. `FogParameters.NONE` → Voxy's finish() skips its final blit (environmentalEnd=-MAX < renderDistance). Fixed: all-+MAX FogParameters (v1.1.5).
2. FBO depth was a renderbuffer; Voxy's initDepthStencil binds the source depth attachment as sampler2D. Fixed: D32F depth TEXTURE cleared to 1.0 (v1.1.5).
3. `viewport.frameId` never incremented → GPU temporal visibility stalls. Fixed (v1.1.5).
4. Alt NEVER reaches this client (0 events via mixin+screen+poll). Fixed: Ctrl+scroll modifier via KeyboardMixin tracking (volatile) + screen key events (v1.1.6/1.1.12); OWNER CONFIRMED slicing works.
5. Tight ortho near/far frustum-culled the entire traversal. Fixed: ±16000 range (v1.1.12); vanilla chunk-bound occlusion pass also skipped for map viewport (ChunkBoundRendererMixin, v1.1.13).
6. AMD driver (atio6axx.dll) access-violates on full-size glReadPixels (3 crashes, hs_err files). Fixed: only 1x1 reads / glGetTextureSubImage in diagnostics (v1.1.10+).
7. **THE SCREEN KILLER: GuiGraphics.blit legacy overload changed meaning in 1.21.x** — only Identifier-first overload is `blit(id, x1, y1, x2, y2, u0, u1, v0, v1)` (corners + normalized UVs); old-convention args made 0x0 quads → NOTHING ever drew since v1.0 regardless of texture content. Fixed in AbyssWorldMapScreen + drawHud, V flipped (v1.1.17).

**Current pipeline (temporary diagnostic parts to clean up later):** renderMinimap clears Voxy's internal colour buffer (Voxy never clears it; garbage showed as backdrop) → renderOpaque → DIRECT glBlitNamedFramebuffer of Voxy's internal colourTex into our FBO (bypasses Voxy's picky composite; decide later whether to keep vs fix Voxy's own blit which also worked partially) → GUI blit. Extensive [MIA Aperture diag] logging ~1/s while map open (strip when stable). Smaller arrow shipped (v1.1.10, owner request).

* **v1.1.17: THE SCREEN-SIDE ROOT CAUSE.** The only Identifier-first `GuiGraphics.blit` overload in 1.21.11 is `blit(id, x1, y1, x2, y2, u0, u1, v0, v1)` (corner coords + normalized UVs, forwards to innerBlit with GUI_TEXTURED). The mod's calls used the OLD `(id, x, y, u, v, w, h, texW, texH)` convention → silently resolved to width=0/height=0 quads → **both the map screen and HUD minimap drew NOTHING every frame since v1.0 regardless of texture content**. Fixed in AbyssWorldMapScreen.render + drawHud (V flipped: FBO is bottom-up). ALSO in v1.1.16: direct `glBlitNamedFramebuffer` copy of Voxy's internal colourTex into our FBO after renderOpaque (bypasses Voxy's picky composite — keep or refine later). Diagnostics confirmed the RENDER side works: opaqueDraws ~1000-1500/frame, depthCoverage 29/64 of the internal buffer has terrain depth.
* **Diag gotchas learned**: `drawCountCallBuffer` offset 0 is cmdgen dispatchX (= ceil(renderList/128)), real opaque/translucent/temporal counts at offsets 12/16/20. Voxy RenderStatistics HTC/HRS stayed all-zero (shader likely compiled without the stats define in shipped jar) — useless. Reflection on NormalRenderPipeline fields returned -2 (name mismatch in shipped jar?) — internal peeks via fb attachment reads instead.
* **v1.1.13**: skips Voxy's vanilla chunk-bound occlusion pass for the map viewport (`ChunkBoundRendererMixin` cancels `ChunkBoundRenderer.render` when `isRenderingMap`, clearing `depthBoundingBuffer` to 0.0) — that pass punched a hole in the map centre covering the whole vanilla render distance. AWAITING owner verification.
* **v1.1.12 BREAKTHROUGHS (verified in log 2026-07-06 ~16:00)**: (1) **Ctrl+scroll slicing WORKS** — Alt key events NEVER reach this client (0 events via mixin+screen+polling across full sessions; OS/overlay-level swallow), Ctrl is now the primary slice modifier. (2) **Widening the map ortho depth range to ±16000 un-stuck the traversal**: map viewport went renderList 0 → 387, drawCount 4, FBO pixels lit 1/5 (only off-centre — hence the chunk-bound fix in v1.1.13). Main viewport healthy: renderList 9-49, topNodes 4048. The tight 0.05/2000 near-far was frustum-culling everything.
* **BLANK MAP ROOT CAUSE #1 (found 2026-07-06 via v1.1.7-1.1.9 diagnostic builds)**: Voxy's local LOD database for `survive.mineinabyss.com` was EMPTY (1.1 MB, 0 sections, 0 MB geometry per `addDebugInfo` stats) because `config/voxy-config.json` had `"ingest_enabled": false`. Voxy had nothing to render in-world OR on the map — the map render path itself (fixed in v1.1.5) was working correctly against an empty world. Fixed by setting `ingest_enabled: true` in the instance config; the DB populates from received chunks as the player moves around. Terrain appears in-world beyond vanilla render distance first; the map shows the same data.
* **v1.1.7-1.1.9 (diagnostic builds)**: pressing M crashed the client natively — `EXCEPTION_ACCESS_VIOLATION` in `atio6axx.dll` (AMD GL driver, see `hs_err_pid*.log` in the instance dir) inside the full 512x512 `glReadPixels` diagnostic readback. 1x1 reads are safe; v1.1.10 samples five 1x1 points instead. Boundary markers proved Voxy's `renderOpaque` + `glFinish` complete cleanly on the map viewport (GPU work NOT poisoned) and camera grid-shift math is correct (verified live: raw x=49040.7 section=3 → cam -111.3, exactly matching Voxy's own setupViewport formula).
* **v1.1.10 (current, installed in Modrinth instance)**: safe sampled diagnostics + smaller map-style HUD arrow (owner request: chevron was too big, wanted more arrow-like).
* **v1.1.6** — Alt+scroll fix: since the MC 1.21.9+ input rework, `InputConstants.isKeyDown` (live `glfwGetKey`) is unreliable for modifier checks (vanilla removed `Screen.hasAltDown()` and now carries modifier bits on `KeyEvent`/`InputWithModifiers`). Symptom: Alt+scroll in the map screen zoomed instead of slicing. Fix: new `KeyboardMixin` on `KeyboardHandler.keyPress(long, int action, KeyEvent)` tracks `AbyssMapState.altHeld`; both scroll paths (`InputHandler`, `AbyssWorldMapScreen`) read it (old poll kept as fallback).
* **v1.1.5** (root-cause fix for blank map, found by reading Voxy source in `<voxy-clone>`):
  1. `FogParameters.NONE` has `environmentalEnd == -Float.MAX_VALUE`, which makes Voxy's `NormalRenderPipeline.finish()` compute `fogCoversAllRendering = true` and SKIP the final blit into the caller's framebuffer every frame. Replaced with a custom FogParameters of all `+Float.MAX_VALUE` (blit runs, fog uniforms degenerate to no-op).
  2. Voxy's `initDepthStencil` binds the source FBO's depth attachment as a `sampler2D`; our depth attachment was a renderbuffer (invalid as a texture), so the stencil-priming pass sampled garbage and could mask all terrain. Replaced with a `GL_DEPTH_COMPONENT32F` depth TEXTURE (mirrors vanilla's main framebuffer), explicitly cleared to 1.0 (= FAR for Voxy's standard-Z properties: `isReverseZ=false`).
  3. `viewport.frameId` was never incremented (Voxy's `setupViewport` does it every frame); now incremented per map render pass for the GPU temporal visibility/traversal logic.
* Camera grid-shift math was verified CORRECT against Voxy's `setupViewport` (sector formulas match for positive X).
* **Known limitation (unchanged)**: HUD minimap only shows the last frame rendered while the map screen was open (`renderMinimap` early-returns unless `AbyssWorldMapScreen` is open, to avoid the double-viewport GPU TDR from v1.1.2). Needs a proper design (e.g. reduced-frequency HUD pass) — do not "fix" by removing the guard.
* **Next Steps**:
  1. In-game verify v1.1.5: fullscreen map (`M`) shows terrain; pan/zoom/side-view (`P`); Alt+scroll slice.
  2. Then design the HUD minimap rendering strategy.
