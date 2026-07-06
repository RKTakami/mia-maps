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
* **Target Output**: `build/libs/mia-aperture-mod-1.1.6.jar`
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
* **Last Release**: `v1.1.6` — Alt+scroll fix: since the MC 1.21.9+ input rework, `InputConstants.isKeyDown` (live `glfwGetKey`) is unreliable for modifier checks (vanilla removed `Screen.hasAltDown()` and now carries modifier bits on `KeyEvent`/`InputWithModifiers`). Symptom: Alt+scroll in the map screen zoomed instead of slicing. Fix: new `KeyboardMixin` on `KeyboardHandler.keyPress(long, int action, KeyEvent)` tracks `AbyssMapState.altHeld`; both scroll paths (`InputHandler`, `AbyssWorldMapScreen`) read it (old poll kept as fallback).
* **v1.1.5** (root-cause fix for blank map, found by reading Voxy source in `<voxy-clone>`):
  1. `FogParameters.NONE` has `environmentalEnd == -Float.MAX_VALUE`, which makes Voxy's `NormalRenderPipeline.finish()` compute `fogCoversAllRendering = true` and SKIP the final blit into the caller's framebuffer every frame. Replaced with a custom FogParameters of all `+Float.MAX_VALUE` (blit runs, fog uniforms degenerate to no-op).
  2. Voxy's `initDepthStencil` binds the source FBO's depth attachment as a `sampler2D`; our depth attachment was a renderbuffer (invalid as a texture), so the stencil-priming pass sampled garbage and could mask all terrain. Replaced with a `GL_DEPTH_COMPONENT32F` depth TEXTURE (mirrors vanilla's main framebuffer), explicitly cleared to 1.0 (= FAR for Voxy's standard-Z properties: `isReverseZ=false`).
  3. `viewport.frameId` was never incremented (Voxy's `setupViewport` does it every frame); now incremented per map render pass for the GPU temporal visibility/traversal logic.
* Camera grid-shift math was verified CORRECT against Voxy's `setupViewport` (sector formulas match for positive X).
* **Known limitation (unchanged)**: HUD minimap only shows the last frame rendered while the map screen was open (`renderMinimap` early-returns unless `AbyssWorldMapScreen` is open, to avoid the double-viewport GPU TDR from v1.1.2). Needs a proper design (e.g. reduced-frequency HUD pass) — do not "fix" by removing the guard.
* **Next Steps**:
  1. In-game verify v1.1.5: fullscreen map (`M`) shows terrain; pan/zoom/side-view (`P`); Alt+scroll slice.
  2. Then design the HUD minimap rendering strategy.
