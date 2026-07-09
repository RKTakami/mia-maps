package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapConfigTest {
    @Test
    void roundTripPreservesValues() {
        MapSettings s = new MapSettings();
        s.orientation = MapSettings.Orientation.HEADING_UP;
        s.shape = MapSettings.FrameShape.ROUND;
        s.setMinimapSize(180);
        MapSettings back = MapConfig.fromJson(MapConfig.toJson(s));
        assertEquals(MapSettings.Orientation.HEADING_UP, back.orientation);
        assertEquals(MapSettings.FrameShape.ROUND, back.shape);
        assertEquals(180, back.minimapSize);
    }

    @Test
    void fromNullOrGarbageGivesDefaults() {
        MapSettings a = MapConfig.fromJson(null);
        assertEquals(MapSettings.Orientation.NORTH_UP, a.orientation);
        MapSettings b = MapConfig.fromJson("not json {{{");
        assertEquals(100, b.minimapSize);
        assertEquals(MapSettings.FrameShape.SQUARE, b.shape);
    }

    @Test
    void fromJsonClampsSize() {
        MapSettings s = MapConfig.fromJson("{\"minimapSize\": 5000}");
        assertEquals(256, s.minimapSize);
    }

    @Test
    void positionRoundTrips() {
        MapSettings s = new MapSettings();
        s.setMinimapPos(0.25, 0.75);
        MapSettings back = MapConfig.fromJson(MapConfig.toJson(s));
        assertEquals(0.25, back.minimapX, 1e-9);
        assertEquals(0.75, back.minimapY, 1e-9);
    }

    @Test
    void positionDefaultsWhenAbsent() {
        MapSettings s = MapConfig.fromJson("{\"minimapSize\": 120}");
        assertEquals(1.0, s.minimapX, 1e-9);
        assertEquals(0.0, s.minimapY, 1e-9);
    }

    @Test
    void positionClampedWhenOutOfRange() {
        MapSettings s = MapConfig.fromJson("{\"minimapX\": 5.0, \"minimapY\": -3.0}");
        assertEquals(1.0, s.minimapX, 1e-9);
        assertEquals(0.0, s.minimapY, 1e-9);
    }
}
