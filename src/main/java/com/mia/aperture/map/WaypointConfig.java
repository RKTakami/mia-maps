package com.mia.aperture.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class WaypointConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, List<Waypoint>>>() {}.getType();

    private WaypointConfig() {}

    public static String toJson(WaypointStore store) {
        return GSON.toJson(store.raw(), TYPE);
    }

    public static WaypointStore fromJson(String json) {
        if (json == null) return new WaypointStore();
        try {
            Map<String, List<Waypoint>> m = GSON.fromJson(json, TYPE);
            return new WaypointStore(m);
        } catch (Throwable t) {
            return new WaypointStore();
        }
    }

    public static WaypointStore load(Path file) {
        try {
            if (Files.exists(file)) return fromJson(Files.readString(file));
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to read waypoints: " + t);
        }
        return new WaypointStore();
    }

    public static void save(Path file, WaypointStore store) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(store));
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to write waypoints: " + t);
        }
    }
}
