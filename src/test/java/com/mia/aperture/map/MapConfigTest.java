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
}
