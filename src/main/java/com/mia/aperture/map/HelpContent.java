package com.mia.aperture.map;

import java.util.ArrayList;
import java.util.List;

// Pure help/tutorial content, keyed by tab. Keybind names come through a KeyResolver so the live
// (possibly rebound) keys show; fixed in-screen keys (V, X, Esc, mouse) are literal.
public final class HelpContent {
    public enum Tab {
        OVERVIEW("Overview"), MAP("Map"), THREED("3D View"),
        WAYPOINTS("Waypoints & Routing"), SETTINGS("Settings"), KEYS("Keys");
        public final String title;
        Tab(String t) { this.title = t; }
    }

    // Resolves a logical action (e.g. "open_map") to a display key (e.g. "M").
    public interface KeyResolver { String key(String action); }

    // heading -> section header (key null). key != null -> a control + its description.
    // key == null && !heading -> a plain body line.
    public record Line(boolean heading, String key, String text) {}

    private HelpContent() {}

    private static Line h(String t) { return new Line(true, null, t); }
    private static Line item(String k, String t) { return new Line(false, k, t); }
    private static Line text(String t) { return new Line(false, null, t); }

    public static List<Line> lines(Tab tab, KeyResolver k) {
        List<Line> o = new ArrayList<>();
        switch (tab) {
            case OVERVIEW -> {
                o.add(h("MIA Maps"));
                o.add(text("A live Abyss map with a 3D view, waypoints, routing, and cave x-ray."));
                o.add(h("Start here"));
                o.add(item(k.key("open_map"), "Open the fullscreen map"));
                o.add(item(k.key("mark_waypoint"), "Drop a waypoint where you stand"));
                o.add(item("V", "On the map: cycle Relief / Vanilla / X-ray"));
                o.add(h("What's in each tab"));
                o.add(text("Map - pan, zoom, depth slice, render modes, buttons"));
                o.add(text("3D View - orbit the terrain and x-ray into caves"));
                o.add(text("Waypoints & Routing - mark places and follow routes"));
                o.add(text("Settings - every option explained"));
                o.add(text("Keys - the full keybind list"));
            }
            case MAP -> {
                o.add(h("Fullscreen map"));
                o.add(item("Drag", "Pan the map"));
                o.add(item("Scroll", "Zoom in and out"));
                o.add(item("Ctrl/Alt+Scroll", "Move the depth slice up or down"));
                o.add(item("V", "Render mode: Relief (shaded), Vanilla (flat), X-ray (caves)"));
                o.add(item(k.key("reset_view"), "Reset the depth slice back to your level"));
                o.add(h("Buttons"));
                o.add(item("3D View", "Open the orbiting 3D voxel view"));
                o.add(item("Waypoints", "Manage your saved waypoints"));
                o.add(item("Reset", "Recenter the view on you"));
                o.add(item("Settings", "Open map settings"));
                o.add(h("Reading it"));
                o.add(text("Top-left shows your depth and current Abyss layer; switch blocks vs metres in Settings."));
                o.add(text("X-ray dims the terrain and lights up caves beneath you in cyan."));
            }
            case THREED -> {
                o.add(h("3D view"));
                o.add(item("Drag", "Orbit the camera"));
                o.add(item("Scroll", "Zoom in and out"));
                o.add(item("Right-click", "Move the focus point"));
                o.add(item("Shift+Right-click", "Drop a waypoint at a spot"));
                o.add(item("Click a waypoint", "Route to that waypoint"));
                o.add(item("R", "Recenter on you"));
                o.add(item("X", "X-ray: Off / Ghost shell / Caves only"));
                o.add(item("Esc", "Close the 3D view"));
                o.add(text("Detail is controlled by Orbit Quality in Settings."));
            }
            case WAYPOINTS -> {
                o.add(h("Waypoints"));
                o.add(item(k.key("mark_waypoint"), "Mark a waypoint at your position"));
                o.add(item(k.key("toggle_beacons"), "Show or hide in-world waypoint beacons"));
                o.add(text("In the Waypoints list: Add, Edit, Delete, Share to chat, or Go (route to it)."));
                o.add(text("Toggle any single waypoint on or off, or all markers at once."));
                o.add(h("Routing"));
                o.add(text("Pick a destination (Go, or click a waypoint in 3D) to draw a walking route."));
                o.add(text("Breadcrumbs erase as you pass them; the brightest marker is your next step."));
                o.add(text("Amber markers suggest where to dig down a cliff you cannot walk around."));
            }
            case SETTINGS -> {
                o.add(h("Map settings"));
                o.add(item("Orientation", "North-locked, or rotate with your facing"));
                o.add(item("Shape", "Square or round minimap"));
                o.add(item("Size / Corner / Reposition", "Minimap size and where it sits"));
                o.add(item("Map mode", "Relief / Vanilla / X-ray (same as V)"));
                o.add(item("Cave mode", "Auto / On / Off in-cave minimap view"));
                o.add(item("Beacons", "In-world waypoint beams"));
                o.add(item("Orbit quality", "3D view detail vs performance"));
                o.add(item("Safe fall", "Drop height the router treats as safe"));
                o.add(item("Depth units", "Show depth in blocks or metres"));
                o.add(h("Mob tracking"));
                o.add(item("Hostiles / Players / Passive", "Which blips to show"));
                o.add(item("Labels", "Name labels on mob blips"));
                o.add(item("Nearby List", "A text list of nearby mobs on the HUD"));
                o.add(item("Nav markers", "Show waypoint markers on the maps"));
            }
            case KEYS -> {
                o.add(h("Keybinds"));
                o.add(item(k.key("open_map"), "Open the fullscreen map"));
                o.add(item(k.key("mark_waypoint"), "Mark a waypoint"));
                o.add(item(k.key("toggle_beacons"), "Toggle waypoint beacons"));
                o.add(item(k.key("cave_mode"), "Cycle cave mode (Auto / On / Off)"));
                o.add(item(k.key("toggle_cull"), "Toggle Aperture cull"));
                o.add(item(k.key("reset_view"), "Reset map depth to you"));
                o.add(h("On the map"));
                o.add(item("V", "Render mode: Relief / Vanilla / X-ray"));
                o.add(item("Drag / Scroll", "Pan / Zoom"));
                o.add(item("Ctrl/Alt+Scroll", "Depth slice"));
                o.add(h("In the 3D view"));
                o.add(item("X", "X-ray: Off / Ghost / Caves only"));
                o.add(item("R", "Recenter"));
                o.add(item("Right-click / Shift+Right-click", "Move focus / Drop waypoint"));
                o.add(item("Esc", "Close"));
            }
        }
        return o;
    }
}
