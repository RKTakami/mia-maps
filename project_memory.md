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
* **Target Output**: `build/libs/mia-aperture-mod-1.0.1.jar`

---

## 3. Core Architecture & Mixin Hooks
* **Culling Mechanism**: Hooks into Voxy's octree builder via `NodeManagerMixin`. Intercepts `processGeometryResult` to return empty geometry if a block coordinate is outside the Y-aperture range, but preserves children mapping to keep the octree integrated.
* **Scroll Interception**: Intercepts in-game scroll actions when `Alt` is held down (`MouseMixin`), updating the culling aperture. Triggers reloading of Voxy's ring trackers (`RenderDistanceTrackerMixin`) to force redraws.
* **Map Screen & Minimap Viewport**: Accesses Voxy's rendering manager through an exposed method (`ViewportSelectorInvoker`) to render orthographic projections into an offscreen FBO texture (`MinimapFbo`), preventing rendering artifacts on the primary viewport.
* **LevelRenderer Injection**: Injects at `LevelRenderer.renderLevel` (Mojang-mapped 1.21.1 naming) to draw the FBO Minimap.

---

## 4. Current Status & Next Actions
* **Last Release**: `v1.0.1` (contains the LevelRenderer Mixin crash fix and updated filename version mapping).
* **Git State**: All source code, Gradle scripts, configuration files, and assets are cleanly committed to the `main` branch and pushed.
* **Next Steps**:
  1. Test the `v1.0.1` jar directly in-game under a real multiplayer environment to verify FBO performance and octree culling visual feedback.
  2. Optimize FBO viewport rendering if frame rates fluctuate on low-end systems during vertical scrolling.
