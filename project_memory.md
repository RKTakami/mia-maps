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
* **Target Output**: `build/libs/mia-aperture-mod-1.1.10.jar`
* **Install Target**: `<mods-dir>\` (the real client instance; its `logs/latest.log` is the authoritative test log)

---

## 3. Core Architecture & Mixin Hooks
* **Culling Mechanism**: Hooks into Voxy's octree builder via `NodeManagerMixin`. Intercepts `processGeometryResult` to return empty geometry if a block coordinate is outside the Y-aperture range, but preserves children mapping to keep the octree integrated.
* **Scroll Interception**: Intercepts in-game scroll actions when `Alt` is held down (`MouseMixin`), updating the culling aperture. Triggers reloading of Voxy's ring trackers (`RenderDistanceTrackerMixin`) to force redraws.
* **Map Screen & Minimap Viewport**: Accesses Voxy's rendering manager through an exposed method (`ViewportSelectorInvoker`) to render orthographic projections into an offscreen FBO texture (`MinimapFbo`), preventing rendering artifacts on the primary viewport.
* **LevelRenderer Injection**: Injects at `LevelRenderer.renderLevel` (Mojang-mapped 1.21.1 naming) at `HEAD` to draw the FBO Minimap.
* **Voxy Bypass**: Mixes into Voxy's `MixinLevelRenderer` to cancel the main world's Voxy rendering pass when the fullscreen map screen is open.
* **Lazy Texture Registration**: Registers our custom HUD `MinimapTexture` lazily on the first draw frame. This ensures registration runs on the render thread when `TextureManager` and `GpuDevice` are fully instantiated, avoiding early initialization resets during Fabric mod bootstrap.

---

## 4. Current Status & Next Actions
* **v1.1.17 (current, installed): THE SCREEN-SIDE ROOT CAUSE.** The only Identifier-first `GuiGraphics.blit` overload in 1.21.11 is `blit(id, x1, y1, x2, y2, u0, u1, v0, v1)` (corner coords + normalized UVs, forwards to innerBlit with GUI_TEXTURED). The mod's calls used the OLD `(id, x, y, u, v, w, h, texW, texH)` convention → silently resolved to width=0/height=0 quads → **both the map screen and HUD minimap drew NOTHING every frame since v1.0 regardless of texture content**. Fixed in AbyssWorldMapScreen.render + drawHud (V flipped: FBO is bottom-up). ALSO in v1.1.16: direct `glBlitNamedFramebuffer` copy of Voxy's internal colourTex into our FBO after renderOpaque (bypasses Voxy's picky composite — keep or refine later). Diagnostics confirmed the RENDER side works: opaqueDraws ~1000-1500/frame, depthCoverage 29/64 of the internal buffer has terrain depth.
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
