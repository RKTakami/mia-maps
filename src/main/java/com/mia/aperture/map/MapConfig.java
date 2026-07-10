package com.mia.aperture.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private MapConfig() {}

    public static String toJson(MapSettings s) {
        return GSON.toJson(s);
    }

    public static MapSettings fromJson(String json) {
        if (json == null) return new MapSettings();
        try {
            MapSettings s = GSON.fromJson(json, MapSettings.class);
            if (s == null) return new MapSettings();
            if (s.orientation == null) s.orientation = MapSettings.Orientation.NORTH_UP;
            if (s.shape == null) s.shape = MapSettings.FrameShape.SQUARE;
            if (s.caveMode == null) s.caveMode = MapSettings.CaveMode.AUTO;
            s.setMinimapSize(s.minimapSize);
            s.setMinimapPos(s.minimapX, s.minimapY);
            return s;
        } catch (Throwable t) {
            return new MapSettings();
        }
    }

    public static MapSettings load(Path file) {
        try {
            if (Files.exists(file)) {
                return fromJson(Files.readString(file));
            }
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to read map config: " + t);
        }
        MapSettings s = new MapSettings();
        save(file, s);
        return s;
    }

    public static void save(Path file, MapSettings s) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(s));
        } catch (Throwable t) {
            System.err.println("[MIA Aperture] failed to write map config: " + t);
        }
    }
}
