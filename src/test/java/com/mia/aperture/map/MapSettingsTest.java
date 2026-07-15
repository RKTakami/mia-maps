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

    @Test
    void positionDefaultsTopRight() {
        MapSettings s = new MapSettings();
        assertEquals(1.0, s.minimapX, 1e-9);
        assertEquals(0.0, s.minimapY, 1e-9);
    }

    @Test
    void positionClampsToUnitRange() {
        MapSettings s = new MapSettings();
        s.setMinimapPos(-0.5, 2.0);
        assertEquals(0.0, s.minimapX, 1e-9);
        assertEquals(1.0, s.minimapY, 1e-9);
        s.setMinimapPos(0.3, 0.7);
        assertEquals(0.3, s.minimapX, 1e-9);
        assertEquals(0.7, s.minimapY, 1e-9);
    }

    @Test
    void orbitAreaDefaultsTo2048() {
        assertEquals(2048, new MapSettings().orbitAreaBlocks);
    }

    @Test
    void orbitAreaSnapsToNearestStep() {
        MapSettings s = new MapSettings();
        s.setOrbitAreaBlocks(2048);
        assertEquals(2048, s.orbitAreaBlocks);   // exact step kept
        s.setOrbitAreaBlocks(3000);
        assertEquals(2048, s.orbitAreaBlocks);   // nearer 2048 than 4096
        s.setOrbitAreaBlocks(5000);
        assertEquals(4096, s.orbitAreaBlocks);   // nearer 4096 than 8192
    }

    @Test
    void orbitAreaClampsOutOfRange() {
        MapSettings s = new MapSettings();
        s.setOrbitAreaBlocks(0);
        assertEquals(1024, s.orbitAreaBlocks);    // below the lowest step
        s.setOrbitAreaBlocks(99999);
        assertEquals(16384, s.orbitAreaBlocks);   // above the highest step (one full Abyss sector)
    }

    @Test
    void orbitAreaReachesOneFullSector() {
        MapSettings s = new MapSettings();
        s.setOrbitAreaBlocks(16384);
        assertEquals(16384, s.orbitAreaBlocks);   // 16384 = a whole layer's shifted-X width
    }
}
