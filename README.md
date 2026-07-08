# Mine in Abyss - Aperture & World Map Mod

A client-side Minecraft Fabric mod for version **1.21.1** designed to interface with the **Voxy** GPU-driven LOD rendering engine. This mod enables players in the **Mine in Abyss** server (utilizing the DeeperWorld plugin architecture) to selectively view, slice, and pan vertically through layer sections.

---

## Features

- **Dynamic Y-Aperture Culling**: Filter Voxy's rendering thread to load only sections within a custom vertical slice (default 64-block thickness).
- **HUD Minimap**: A live top-down minimap rendered from Voxy's world database (updates as you explore).
- **Resource-Pack-Accurate Colors**: Map colors are sampled directly from the active resource pack's block textures (top + side faces baked from the atlas), so custom texture packs — including custom MIA blocks — render correctly instead of a flat vanilla palette.
- **Per-Biome Tinting**: Grass, foliage, and water colors are tinted per-biome (matching the game's own grass/foliage/water colormaps), so different biomes and layers show distinct, accurate greens and blues.
- **Interactive Fullscreen World Map**: Data-driven map of everything you've explored, now rendered at high-resolution **2048²** — pan, zoom out to the whole explored layer, Y-slice control, relief or vanilla coloring.
- **Vertical Layer Sidebar**: High-fidelity visual sidebar representing the player's physical depth and the active scrolling culling aperture height.

---

## Keybindings & Controls

- **`M`**: Open the Abyss World Map Screen (data-driven; shows everything ingested into Voxy's database).
- **`H`**: Toggle vertical aperture culling of the live world (On/Off).
- **`Ctrl + Scroll Wheel`**: Scroll the culling aperture / map slice Y-level (Alt also works where the OS delivers it).
- **`V`** *(Inside Map Screen)*: Toggle map coloring between height-shaded relief and vanilla cartography style.
- **`Left Click + Drag`** *(Inside Map Screen)*: Pan the map.
- **`Scroll Wheel`** *(Inside Map Screen)*: Zoom (out to the entire explored layer).

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

The compiled mod JAR will be located at `build/libs/mia-aperture-mod-1.3.0.jar`. Copy it to your client's `mods/` directory.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
