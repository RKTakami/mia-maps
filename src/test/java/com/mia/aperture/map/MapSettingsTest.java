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
    void smooth3dDefaultsOn() {
        assertTrue(new MapSettings().smooth3d);
        assertTrue(MapConfig.fromJson("{}").smooth3d);
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
        assertEquals(1024, s.orbitAreaBlocks);   // below the lowest step
        s.setOrbitAreaBlocks(99999);
        assertEquals(MapSettings.ORBIT_AREA_WHOLE, s.orbitAreaBlocks); // above the highest step
    }

    @Test
    void liveStepsStopAt4096AndWholeAbyssIsCacheBacked() {
        // Voxy hard-codes MAX_LOD_LAYER = 4 and never stores coarser, so 4096 remains the widest
        // LIVE-sampled view (2048 native + one synthesis step) — that cap is still principled.
        // ORBIT_AREA_WHOLE is different in kind: OrbitScene renders it from AbyssSpanStore's
        // background-built cache, never from live sampling. See the 2026-07-16 whole-abyss spec.
        int[] steps = MapSettings.ORBIT_AREA_STEPS;
        assertArrayEquals(new int[]{1024, 2048, 4096, MapSettings.ORBIT_AREA_WHOLE}, steps);
        assertEquals(4096, steps[steps.length - 2], "widest live step");
    }

    @Test
    void maxSurvivableDropDefaultsAndClamps() {
        MapSettings s = new MapSettings();
        assertEquals(16, s.maxSurvivableDrop);
        s.setMaxSurvivableDrop(999);
        assertEquals(MapSettings.MAX_SURVIVABLE_DROP, s.maxSurvivableDrop);
        s.setMaxSurvivableDrop(0);
        assertEquals(MapSettings.MIN_SURVIVABLE_DROP, s.maxSurvivableDrop);
    }

    @Test
    void survivableDropNeverBelowSafeDrop() {
        MapSettings s = new MapSettings();
        s.setSafeDropBlocks(8);
        s.setMaxSurvivableDrop(5);          // below safe -> raised to safe
        assertEquals(8, s.maxSurvivableDrop);
    }
}
