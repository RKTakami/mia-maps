package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSettingsTest {
    @Test
    void defaults() {
        MapSettings s = new MapSettings();
        assertEquals(MapSettings.Orientation.NORTH_UP, s.orientation);
        assertEquals(MapSettings.FrameShape.SQUARE, s.shape);
        assertEquals(100, s.minimapSize);
    }

    @Test
    void sizeClampsToRange() {
        MapSettings s = new MapSettings();
        s.setMinimapSize(20);
        assertEquals(80, s.minimapSize);
        s.setMinimapSize(9999);
        assertEquals(256, s.minimapSize);
        s.setMinimapSize(150);
        assertEquals(150, s.minimapSize);
    }
}
