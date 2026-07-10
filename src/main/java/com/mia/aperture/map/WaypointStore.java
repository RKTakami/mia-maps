package com.mia.aperture.map;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WaypointStore {
    private final Map<String, List<Waypoint>> byServer;

    public WaypointStore() { this(new LinkedHashMap<>()); }

    public WaypointStore(Map<String, List<Waypoint>> byServer) {
        this.byServer = byServer != null ? byServer : new LinkedHashMap<>();
    }

    public Map<String, List<Waypoint>> raw() { return byServer; }

    public List<Waypoint> list(String serverKey) {
        return byServer.computeIfAbsent(serverKey, k -> new ArrayList<>());
    }

    public void add(String serverKey, Waypoint w) { list(serverKey).add(w); }

    public void replace(String serverKey, int index, Waypoint w) {
        List<Waypoint> l = list(serverKey);
        if (index >= 0 && index < l.size()) l.set(index, w);
    }

    public void remove(String serverKey, int index) {
        List<Waypoint> l = list(serverKey);
        if (index >= 0 && index < l.size()) l.remove(index);
    }

    // Key the current world: multiplayer server address, or sp:<level>, sanitized.
    public static String currentServerKey(Minecraft mc) {
        if (mc.getCurrentServer() != null) return sanitize(mc.getCurrentServer().ip);
        if (mc.getSingleplayerServer() != null) {
            return sanitize("sp:" + mc.getSingleplayerServer().getWorldData().getLevelName());
        }
        return "unknown";
    }

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "unknown";
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "unknown" : s;
    }
}
