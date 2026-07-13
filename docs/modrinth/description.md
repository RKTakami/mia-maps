# MIA Maps

> ⚠️ **For the Mine in Abyss server only.** This is a client-side add-on for the
> [Mine in Abyss modpack](https://modrinth.com/modpack/mineinabyss-modpack). It reads world data
> from the modpack's **Voxy Mine in Abyss Edition** fork and is tuned to the MIA server's layers
> and mechanics. It will **not** work with regular Minecraft, regular Voxy, or other servers.

A mapping and navigation add-on built for descending the Abyss.

## Features

### 🗺️ Maps
- **Minimap** with square/round frames, north-locked or rotating, repositionable, resizable.
- **Fullscreen map** with a scrollable vertical "aperture" so you can slice down through the layers.
- **3D orbit view** — a rotatable, zoomable voxel-cube render of the surrounding Abyss, rendered on a background thread so it stays smooth (quality tiers from *Potato* to *Ultra*).

### 📍 Waypoints
- Create, edit, colour, and share waypoints (`B` to mark where you stand).
- In-world beacons and on-map markers.

### 🧭 Routing & descent
- **Route to any waypoint** — a background pathfinder finds a walkable way there and shows a glowing route you follow directly in your first-person view (bright in line of sight, dim through rock).
- **Cliff descent** — routes come *down* the Abyss face in safe hops (configurable safe-fall distance).
- **Dig guidance** — where the face overhangs and there's no natural way down, it highlights an amber dig/tunnel path showing exactly where to mine. *It only highlights — it never places or breaks blocks for you.*
- **Auto re-route** — the route re-plans as you travel, and instantly if you're knocked off the path.

## Requirements
- Fabric Loader + Fabric API
- The **Voxy Mine in Abyss Edition** fork (bundled with the MIA modpack)
- Client-side only

## Controls
| Key | Action |
|-----|--------|
| `M` | Open the map |
| `B` | Mark a waypoint |
| `N` | Toggle waypoint beacons |
| `C` | Cave mode |
| `R` | Reset view |

Open the fullscreen map for the **Waypoints**, **3D View**, and **Settings** buttons. Start routing with **Go** in the Waypoints list, or click a waypoint in the 3D view.
