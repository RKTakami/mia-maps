# Modrinth listing — copy/paste helper

Everything for filling out the MIA Maps project + version on modrinth.com.
The full **Description** lives in `description.md` (paste that into the Description field).

---

## ⏳ Pending for the NEXT Modrinth upload (not yet pushed to Modrinth)

Modrinth currently reflects **0.1.6-beta**. Newer builds exist on GitHub only. When you next
update Modrinth, upload the latest jar and fold these into the version changelog:

**0.1.7-beta — performance & stability (no behaviour change)**
- Fixed a rare data race in the map colour baker that could corrupt map colours while exploring
  new terrain (it's now thread-safe across the map, 3D, and routing workers).
- Smoother fullscreen map: panning/zooming no longer re-rasterises the whole map every frame
  (capped to ~30 fps), reducing render-thread load.
- Mob tracking now resolves nearby creatures once per game tick instead of several times per
  frame, cutting overhead in busy areas.

(If more versions land before the next Modrinth upload, append their notes here too, then move
them into the version changelog at upload time.)

---

## Summary (the one-line blurb under the title, max 256 chars)

**Primary:**
> Live map, 3D view, cave x-ray, waypoints, and safe-descent routing for the Mine in Abyss modpack.

**Alternates:**
> Map, x-ray your way to caves, mark waypoints, and route safely down the Abyss — for the Mine in Abyss server.

> Navigate and descend the Abyss: live map, 3D view, cave-finder x-ray, waypoints, and routing.

---

## Environment (Version Settings → Environment)

**Client only** — Client: **Required**, Server: **Unsupported**.
(The jar declares `"environment": "client"` with a client-only entrypoint; it never installs on the server. "Requires the MIA modpack/server" is a compatibility note for the description, NOT a server-side environment.)

---

## Version changelog (0.1.6-beta version notes)

```markdown
### New: in-app Help / tutorial
A **Help** button on the fullscreen map opens a tabbed guide to every control, button, and keybind — Overview / Map / 3D View / Waypoints & Routing / Settings / Keys. The Keys and Overview tabs show your actual keybinds, so they stay correct if you rebind them.

### Corrected Abyss layer depths
Verified deep-layer boundaries: Great Fault 2580–4020, Goblets of the Giants 4020–5850, Sea of Corpses 5850–7200. The Capital of the Unreturned and Final Whirlpool aren't built on the server yet, so they show as one "unmapped" band below 7200 blocks. The depth/layer readout now matches reality.
```

> Note: since earlier uploads were rejected (never public), 0.1.6 is effectively the first live
> version. If a "what changed" changelog reads oddly to first-time viewers, swap in a short
> "Initial public beta — here's what MIA Maps does" intro instead.

---

## Gallery entries (Gallery tab — each image has a Title + Description)

**⭐ Featured (preview thumbnail — use your best-looking shot, e.g. the fullscreen map or x-ray):**
- **Title:** Map the Abyss at a glance
- **Description:** The fullscreen map with live depth and layer readout — pan, zoom, and slice down through the layers.

**X-ray / cave-finder:**
- **Title:** X-ray vision for caves
- **Description:** See through solid rock and dirt to find the caves and tunnels beneath you, glowing brighter the more hollow the ground is.

**3D orbit view:**
- **Title:** 3D view of the terrain
- **Description:** A rotatable, zoomable voxel render of the Abyss around you — with x-ray to ghost the surface or show caves only.

**Routing / descent:**
- **Title:** Safe routes down the cliffs
- **Description:** A glowing trail down the Abyss in safe hops; breadcrumbs erase as you pass, and amber markers show where to dig through overhangs.

**Waypoints & mobs:**
- **Title:** Waypoints and mob tracking
- **Description:** Mark and share waypoints, with nearby creatures shown by threat color and real name.

**Help screen:**
- **Title:** Built-in help
- **Description:** A tabbed in-app guide to every control, button, and keybind — no wiki needed.

**Gallery tips:**
- Feature the most visually striking image (x-ray or fullscreen map usually reads best as a thumbnail).
- Prefer a 16:9-ish landscape shot for the featured image so the card crops nicely.
- 3–5 images is plenty; titles help moderators see the mod's value quickly.

---

## Upload checklist

1. Description tab → paste `description.md`, save.
2. Versions → Create version → attach `build/libs/mia-maps-0.1.6-beta.jar`
   (also on the GitHub release: https://github.com/crkt/MIA-Voxy-map-mod/releases/tag/v0.1.6-beta).
3. Version number `0.1.6-beta`, channel **Beta**, loader **Fabric**, game version **1.21.11** (+ 1.21.1 if supported).
4. Version Settings → Environment → **Client only** (Client Required / Server Unsupported).
5. Paste the changelog into the version notes.
6. Gallery → upload screenshots with the titles/descriptions above; mark one **Featured**.
7. Summary field → paste the one-line summary.
8. Resubmit for review.
