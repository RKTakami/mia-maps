# Help / Tutorial Screen — Design

**Date:** 2026-07-15
**Status:** Approved (owner), ready for implementation plan
**Target release:** v0.1.6-beta

## Goal

Add an in-app **Help / tutorial** screen, opened from a **"Help"** button on the fullscreen map, that documents every operation, button, and keybind in MIA Maps. Organized as **tabbed categories with an Overview landing page**, each tab's content scrollable.

## Access

- A new **"Help"** button on the fullscreen map's bottom button row in `AbyssWorldMapScreen.init` (currently: 3D View / Waypoints / Reset / Settings). The row shifts to make room (buttons are laid out at `this.width - 90/180/270/360, height-30`; add one more slot, or compress spacing so all fit).
- Opening it does `this.minecraft.setScreen(new HelpScreen(this))`; closing (Esc / Done / Back) returns to the fullscreen map (`parent`), mirroring how `MapSettingsScreen` and `WaypointListScreen` return to their parent.
- No keybind and no first-run auto-open (out of scope; button only).

## Structure

`HelpScreen extends Screen`, holding a `parent` Screen and a current-tab enum.

- **Tab bar:** a row of small buttons across the top, one per tab; clicking sets the active tab and rebuilds. The active tab is visually indicated (e.g. its button disabled/highlighted).
- **Content area:** the active tab's lines rendered in a scrollable, scissor-clipped region — reuse the `MapSettingsScreen` scroll mechanism (a list of laid-out entries, a `scrollOffset` adjusted by `mouseScrolled`, clamped to `maxScroll`, `applyScroll()` on the widgets/positions). Content here is mostly **text** (headings + wrapped body lines) rather than interactive widgets, so it can be a simpler scroll of drawn strings with a scroll offset, clipped by `enableScissor`.
- **Background:** `g.fill(0, 0, width, height, 0xE0101018)` — NOT `renderBackground()` (the modpack's blur mod throws "Can only blur once per frame").
- **Fixed chrome:** title at top, tab bar below it, a **Done** button pinned at the bottom (like Settings). Content scrolls between the tab bar and Done.

### Tabs (enum `Tab`)

1. **OVERVIEW** — 2–3 lines on what MIA Maps does; a one-line pointer to each other tab; the three starter keys (open map, mark waypoint, cycle map view).
2. **MAP** — fullscreen map: drag = pan, scroll = zoom, Ctrl/Alt + scroll = depth slice, `V` = render mode (Relief → Vanilla → X-ray); the four buttons (3D View, Waypoints, Reset, Settings); the depth + layer readout and blocks⇄metres toggle; a line on X-ray (dim terrain + cyan cave glow) and Cave auto-mode.
3. **THREED** ("3D View") — drag = orbit, scroll = zoom, right-click = move focus, Shift + right-click = drop waypoint, click a waypoint = navigate to it, `R` = recenter on player, `X` = x-ray cycle (Off / Ghost shell / Caves only), Esc = close; note Orbit Quality lives in Settings.
4. **WAYPOINTS** ("Waypoints & Routing") — `B` = mark a waypoint at your position, `N` = toggle in-world beacons; the Waypoints list actions (Add / Edit / Delete / Share / Go) and per-waypoint + universal marker toggles; routing: choose a destination (Go, or click one in 3D) to draw a route; breadcrumbs erase as you pass and the brightest marker is your next step; amber markers = a suggested dig-down for cliffs.
5. **SETTINGS** — a short "what it does" for each option: orientation, shape, size, corner/reposition, cave mode, beacons, orbit quality, safe-fall distance, mob toggles (Hostiles / Players / Passive / Labels / Nearby List), nav markers, depth units, map mode.
6. **KEYS** — a consolidated keybind card.

## Content model

- Content lives in **one place** as a static structure keyed by `Tab`, so it stays easy to keep in sync as features change. A pure builder `HelpContent.lines(Tab, KeyResolver)` returns an ordered list of entries; an entry is either a **heading** or a **body line** (optionally a `key`/`control` label + description). Rendering maps entries to drawn strings (headings brighter/larger-styled via color, body indented).
- Because the builder is pure (given a key resolver), it is **unit-testable**: every tab returns a non-empty list; the KEYS/OVERVIEW tabs include the resolved open-map key; no entry text is empty.

## Keybinds: live for rebindable, static for fixed

- **Live** for the global rebindable binds — resolve the current display name from the registered `KeyMapping`s (`MiaApertureModClient.resetKeyBind`, `caveKeyBind`, `markWaypointKeyBind`, `toggleBeaconsKeyBind`, plus `mapKeyBind`, `toggleCullKeyBind`). Use each `KeyMapping.getTranslatedKeyMessage()` (or equivalent current-mapping accessor) so rebinds show correctly. Defaults today: `M` open map, `B` mark waypoint, `N` beacons, `C` cave mode, `H` Aperture cull, `R` reset depth.
  - This needs the relevant KeyMappings reachable from `HelpScreen`. `resetKeyBind`/`caveKeyBind`/`markWaypointKeyBind`/`toggleBeaconsKeyBind` are already `public static`; `mapKeyBind` and `toggleCullKeyBind` are private — widen to public (or add a small accessor) so the resolver can read them.
- **Static** for the fixed in-screen keys that are not KeyMappings: `V` (map render mode), `X` (3D x-ray), Esc (close), and mouse actions (drag, scroll, Ctrl/Alt+scroll, right-click, Shift+right-click).
- A `KeyResolver` abstraction (interface returning a display string per logical action) keeps `HelpContent` pure and lets tests pass a stub, while production reads the live KeyMappings.

## Non-goals / out of scope

- No first-run/auto tutorial, no interactive walkthrough, no persistence.
- No localization framework beyond the existing string usage (content is English literals, consistent with the rest of the mod's screens).
- Not documenting Voxy/modpack controls — only MIA Maps' own features.

## Files

- Create: `client/HelpScreen.java` (tabbed UI + scroll + render).
- Create: `map/HelpContent.java` (pure content model + `lines(Tab, KeyResolver)` + `Tab` enum + `KeyResolver` interface + entry type).
- Modify: `client/AbyssWorldMapScreen.java` (add the Help button).
- Modify: `client/MiaApertureModClient.java` (widen `mapKeyBind` / `toggleCullKeyBind` visibility or add accessors for the live key resolver).
- Test: `test/.../HelpContentTest.java` (pure content assertions with a stub `KeyResolver`).

## Open decisions (owner may confirm at review)

- Button label: "Help" (default) vs "?" vs "Guide".
- Exact tab titles / order as listed above.
