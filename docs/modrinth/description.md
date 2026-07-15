# MIA Maps

**MIA Maps is a client-side mapping, navigation, and cave-finding add-on for the [Mine in Abyss modpack](https://modrinth.com/modpack/mineinabyss-modpack).** The Abyss is thousands of blocks deep, pitch-dark, riddled with caves, and getting *down* it safely is half the challenge. MIA Maps gives you a live map of where you are, a 3D view of the terrain around you, x-ray vision for the caves hidden in the rock, saveable waypoints, and a router that plots a safe way down the cliffs — so you can explore and descend with a plan instead of guessing.

If you play on the Mine in Abyss server, this mod turns "I'm lost somewhere in the Great Fault" into "I can see exactly where I am, which layer I'm in, where the caves are, and how to get to my waypoint."

---

## ✨ What it adds

### 🗺️ A live map of the Abyss
- **Minimap** on your HUD — square or round, north-locked or rotating with you, resizable, and repositionable to any corner.
- **Fullscreen map** you can pan and zoom, with a scrollable depth "slice" so you can look through the layers below you.
- **Depth & layer readout** — always know your exact depth and which Abyss layer you're in (Edge of the Abyss, Forest of Temptation, Great Fault, Goblets of the Giants, Sea of Corpses…), shown in blocks or metres.
- Three render styles (press `V`): **Relief** (shaded terrain), **Vanilla** (flat colours), and **X-ray**.

### 🔦 X-ray / cave-finder
- A map mode that **sees through solid rock and dirt to reveal the caves and tunnels beneath you**, glowing brighter the more hollow the ground is — perfect for finding a way down or spotting a cavern before you dig.
- In the 3D view, x-ray can **ghost the outer surface** so you see interior voids through it, or show **caves only**.

### 🧊 3D orbit view
- A rotatable, zoomable, voxel-cube render of the Abyss around you, drawn on a background thread so it stays smooth. Quality tiers from **Potato** to **Ultra** so it runs on anything.

### 📍 Waypoints
- Mark where you stand with one key, then **edit, colour, delete, and share** waypoints with friends in chat.
- In-world beacons and on-map markers, each individually toggleable.

### 🧭 Routing & safe descent
- **Route to any waypoint** — a background pathfinder finds a walkable way and draws a glowing trail you follow in first-person (bright in line of sight, dim through rock). Breadcrumbs erase as you pass them, and your **next step is always the brightest marker**.
- **Cliff descent** — routes come *down* the Abyss face in safe hops, using a configurable safe-fall distance so you don't take a killing drop.
- **Dig guidance** — where the face overhangs with no natural way down, it highlights an amber tunnel path showing exactly where to mine. *It only highlights — it never places or breaks blocks for you.*
- **Auto re-route** — the path re-plans as you travel, and instantly if you get knocked off it.

### 👹 Mob tracking
- Nearby creatures appear on the map, **coloured by threat** (red = hostile, green = passive) with their **real names** (Vinebinder, Cyatoria, and friends), up/down arrows for ones above or below you, and an optional on-screen "nearby" list. Filter by hostiles / players / passives.

### ❓ Built-in help
- A tabbed **Help** screen right on the fullscreen map explains every control, button, and keybind — no wiki needed.

---

## ⚠️ For the Mine in Abyss modpack only

This is a **client-side** add-on built specifically for the Mine in Abyss server. It reads world data from the modpack's **Voxy — Mine in Abyss Edition** fork and is tuned to the MIA server's custom layers, depth scale, and mob set. It will **not** work with regular Minecraft, regular Voxy, or other servers.

**Requirements**
- Fabric Loader + Fabric API
- The **Voxy — Mine in Abyss Edition** fork (bundled with the MIA modpack)
- Client-side only — no server install needed

---

## ⌨️ Controls

| Key | Action |
|-----|--------|
| `M` | Open the fullscreen map |
| `B` | Mark a waypoint where you stand |
| `N` | Toggle in-world waypoint beacons |
| `C` | Cycle cave mode (Auto / On / Off) |
| `R` | Reset the map depth to your level |

On the fullscreen map: drag to pan, scroll to zoom, **Ctrl/Alt + scroll** to move the depth slice, `V` to switch render mode, and the **3D View / Waypoints / Settings / Help** buttons. In the 3D view: drag to orbit, `X` for x-ray, right-click to move focus, click a waypoint to route to it. Full details are in the in-app **Help** screen.
