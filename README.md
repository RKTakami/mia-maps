# Mine in Abyss - Aperture & World Map Mod

A client-side Minecraft Fabric mod for version **1.21.1** designed to interface with the **Voxy** GPU-driven LOD rendering engine. This mod enables players in the **Mine in Abyss** server (utilizing the DeeperWorld plugin architecture) to selectively view, slice, and pan vertically through layer sections.

---

## Features

- **Dynamic Y-Aperture Culling**: Filter Voxy's rendering thread to load only sections within a custom vertical slice (default 64-block thickness).
- **HUD Minimap**: An offscreen top-down FBO rendering pass displaying surrounding loaded Voxy chunks with an player arrow and center crosshair.
- **Interactive Fullscreen World Map**:
  - **Top-Down Perspective**: View the map from above, pan around by dragging, and scroll to zoom.
  - **Side-View Perspective**: View the vertical shaft cross-section (Z-Y plane), letting you inspect layers stacked on top of each other.
- **Vertical Layer Sidebar**: High-fidelity visual sidebar representing the player's physical depth and the active scrolling culling aperture height.

---

## Keybindings & Controls

- **`M`**: Open the Abyss World Map Screen.
- **`H`**: Toggle vertical aperture culling (On/Off).
- **`Alt + Scroll Wheel`**: Scroll the culling aperture Y-level up/down (works both in-game and in the World Map Screen).
- **`P`** *(Inside Map Screen)*: Toggle map perspective between **Top-Down** and **Side-View**.
- **`Left Click + Drag`** *(Inside Map Screen)*: Pan the map.
- **`Scroll Wheel`** *(Inside Map Screen)*: Zoom in and out.

---

## Technical Requirements

This mod is designed to run in conjunction with the following:
- **Minecraft**: `1.21.1`
- **Fabric Loader**: `0.15.x` or newer
- **Voxy**: Mine in Abyss Edition
- **Sodium**: Compatible version
- **Iris**: Compatible version

---

## Compilation

To compile the mod locally, run the Gradle wrapper with Java 21:

```powershell
# Set JAVA_HOME to JDK 21 and build
$env:JAVA_HOME="/path/to/jdk-21"
.\gradlew build
```

The compiled mod JAR will be located at `build/libs/mia-aperture-mod-1.0.0.jar`. Copy it to your client's `mods/` directory.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
