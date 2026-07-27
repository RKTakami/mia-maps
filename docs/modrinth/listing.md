# Modrinth listing — copy/paste helper

Everything for filling out the MIA Maps project + version on modrinth.com.
The full **Description** lives in `description.md` (paste that into the Description field).

---

## ⏳ Pending for the NEXT Modrinth upload (not yet pushed to Modrinth)

Modrinth currently reflects **0.1.6-beta**. Newer builds exist on GitHub only. When you next
update Modrinth, upload the latest jar and fold these into the version changelog:

**0.1.13-beta — macOS support & sharper 3D detail**
- **Runs on macOS.** The mod now ships its native renderer for Apple Silicon and Intel Macs as well
  as Windows — a single jar, with the right one picked automatically. Previously Mac users silently
  fell back to a slower path.
- **Sharper 3D view.** The 3D map now draws fine detail close to the camera and coarser detail
  further out, instead of one detail level everywhere. At the default area that is twice the
  resolution where you are actually looking, with the same coverage.
- **Smoother panning and movement.** The 3D view used to rebuild its terrain every single block you
  moved, producing an identical picture fifteen times out of sixteen. It now rebuilds only when the
  view genuinely changes.
- **Right-click to move the 3D focus now always works.** Clicking terrain could silently do nothing
  if the click missed an internal pick target; it now moves the focus regardless, and shows how far
  you have panned. Press `R` to recentre on yourself.
- Note for Mac users: Voxy itself cannot render terrain on macOS (Apple's OpenGL stops at 4.1, Voxy
  needs 4.3+), so in-world LOD terrain is unavailable there. The map, 3D view and routing all work.

**0.1.12-beta — 3D view performance & clearer settings**
- **Much faster 3D view.** Terrain sampling now runs across all CPU cores instead of one, and the
  sampled volume is properly budgeted — a wide view used to allocate ~900 MB and stall for over a
  second per rebuild. Orbiting and zooming are smooth, and close-up views are far more detailed.
- **3D Quality and 3D Area now tell you what you get.** Quality shows the resulting voxel size
  ("High (8-blk voxels)") and Area warns when the detail budget can't reach the area you asked for.
  Previously a 4096-block setting could quietly map only ~2100 blocks.
- Quality tiers rebalanced so each one is a real cost budget rather than an arbitrary width cap.

**0.1.11-beta — 3D view fixes**
- Fixed the **3D view rendering black**. It inherited depth and blending state from Minecraft, which
  silently discarded every pixel it drew.
- Fixed the 3D view **staying black when first opened** — it now shows the CPU-rendered map until the
  GPU renderer has something ready, instead of a blank screen.
- Fixed **holes across terrain surfaces when zoomed out**. Coarse detail levels picked the lit air
  above a surface instead of the ground beneath it, punching gaps through hillsides and leaving solid
  interiors intact. Also improves the 2D map at low zoom.
- Fixed the 3D view **corrupting other textures** (in-game map murals could render with garbled text).
- Fixed the GPU renderer **failing to start**, which left the slower CPU renderer doing all the work.

> (Superseded by 0.1.12, which rebalanced the quality tiers — the settings screen now reports the
> voxel size each combination actually produces, so there is no need to guess.)

**0.1.10-beta — removed X-ray / cave-finder**
- Removed the map's **X-ray** render mode, the **cave-finder** (Cave Mode / `C` key), and the 3D
  view's **X-ray** modes. Publishing a see-through-terrain feature on Modrinth requires the server
  administrator's written permission, so it has been removed. Relief/Vanilla map modes, the solid 3D
  view, waypoints, routing, and mob tracking are unchanged.

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
> Live map, 3D view, waypoints, and safe-descent routing for the Mine in Abyss modpack.

**Alternates:**
> Map the Abyss, mark waypoints, and route safely down — for the Mine in Abyss server.

> Navigate and descend the Abyss: live map, 3D view, waypoints, and routing.

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

**⭐ Featured (preview thumbnail — use your best-looking shot, e.g. the fullscreen map or 3D view):**
- **Title:** Map the Abyss at a glance
- **Description:** The fullscreen map with live depth and layer readout — pan, zoom, and slice down through the layers.

**3D orbit view:**
- **Title:** 3D view of the terrain
- **Description:** A rotatable, zoomable voxel render of the Abyss around you.

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
- Feature the most visually striking image (the fullscreen map or 3D view usually reads best as a thumbnail).
- Prefer a 16:9-ish landscape shot for the featured image so the card crops nicely.
- 3–5 images is plenty; titles help moderators see the mod's value quickly.

---

## Upload checklist

1. Description tab → paste `description.md`, save.
2. Versions → Create version → attach `build/libs/mia-maps-0.1.13-beta.jar`
   (also on the GitHub release: https://github.com/crkt/mia-maps/releases/tag/v0.1.13-beta).
3. Version number `0.1.13-beta`, channel **Beta**, loader **Fabric**, game version **1.21.11** (+ 1.21.1 if supported).
4. Version Settings → Environment → **Client only** (Client Required / Server Unsupported).
5. Paste the changelog into the version notes.
6. Gallery → upload screenshots with the titles/descriptions above; mark one **Featured**.
7. Summary field → paste the one-line summary.
8. Resubmit for review.
