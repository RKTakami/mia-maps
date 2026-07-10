package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointColorTest {

    @Test
    void eightPresetsAllOpaqueAndDistinct() {
        WaypointColor[] all = WaypointColor.values();
        assertEquals(8, all.length);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (WaypointColor c : all) {
            assertEquals(0xFF, (c.argb() >>> 24) & 0xFF, c + " must be opaque");
            assertTrue(seen.add(c.argb()), c + " colour must be distinct");
        }
    }

    @Test
    void nextCyclesAndWraps() {
        assertEquals(WaypointColor.values()[1], WaypointColor.values()[0].next());
        WaypointColor last = WaypointColor.values()[WaypointColor.values().length - 1];
        assertEquals(WaypointColor.values()[0], last.next());
    }
}
