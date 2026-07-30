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


    @Test
    void theMapReadsFromOurOwnStoreByDefault() {
        assertTrue(new MapSettings().mapFromStore,
                "default since 0.1.20, on the evidence of the fidelity and fold checks");
        // And an explicit false in an existing config is still honoured — changing a default must
        // not override someone who has already chosen.
        assertFalse(MapConfig.fromJson("{\"mapFromStore\": false}").mapFromStore);
        assertTrue(MapConfig.fromJson("{\"minimapSize\": 120}").mapFromStore,
                "absent from an older config means take the new default");
    }

    @Test
    void bezelStylePersistsAndDefaultsForConfigsWrittenBeforeItExisted() {
        MapSettings s = new MapSettings();
        assertEquals(MapSettings.BezelStyle.SOLID, s.bezelStyle, "solid brass by default");
        s.bezelStyle = MapSettings.BezelStyle.WIRE;
        assertEquals(MapSettings.BezelStyle.WIRE,
                MapConfig.fromJson(MapConfig.toJson(s)).bezelStyle);

        // The case that actually matters: every config file already on disk was written before this
        // field existed, so it deserialises to null. Without the fallback the minimap would ask a
        // null enum for its width on the first frame after an update.
        assertEquals(MapSettings.BezelStyle.SOLID,
                MapConfig.fromJson("{\"minimapSize\": 120}").bezelStyle);
        assertEquals(MapSettings.BezelStyle.SOLID,
                MapConfig.fromJson("{\"bezelStyle\": null}").bezelStyle);
    }

    @Test
    void bezelStyleCycles() {
        MapSettings.BezelStyle v = MapSettings.BezelStyle.SOLID;
        for (int i = 0; i < MapSettings.BezelStyle.values().length; i++) v = v.next();
        assertEquals(MapSettings.BezelStyle.SOLID, v, "the cycle wraps");
        for (MapSettings.BezelStyle b : MapSettings.BezelStyle.values()) {
            assertTrue(b.width >= 1, "a frame narrower than a pixel cannot draw: " + b);
        }
    }

    @Test
    void transparencyPersistsAndIsClamped() {
        MapSettings s = new MapSettings();
        assertEquals(0, s.orbitTransparency, "solid by default");
        s.orbitTransparency = 50;
        assertEquals(50, MapConfig.fromJson(MapConfig.toJson(s)).orbitTransparency);

        // A hand-edited config must not be able to ask for something the renderer cannot show.
        s.orbitTransparency = 500;
        assertEquals(MapSettings.MAX_TRANSPARENCY,
                MapConfig.fromJson(MapConfig.toJson(s)).orbitTransparency);
        s.orbitTransparency = -20;
        assertEquals(0, MapConfig.fromJson(MapConfig.toJson(s)).orbitTransparency);
    }

    @Test
    void transparencyStepsStayInTheRangeWhereTheSettingDoesSomething() {
        assertEquals(0, MapSettings.TRANSPARENCY_STEPS[0], "first step is Off");
        for (int v : MapSettings.TRANSPARENCY_STEPS) {
            assertTrue(v == 0 || v >= 60,
                    "below 60% less than a tenth shows through two surface layers, so a step there "
                    + "looks like a setting that does nothing: " + v);
            assertTrue(v <= MapSettings.MAX_TRANSPARENCY, "step beyond the clamp: " + v);
        }
        // Cycling must visit every step and come back to Off.
        int v = 0;
        for (int i = 0; i < MapSettings.TRANSPARENCY_STEPS.length; i++) {
            v = MapSettings.nextTransparency(v);
        }
        assertEquals(0, v, "the cycle wraps");
    }
}
