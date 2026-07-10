package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointConfigTest {

    @Test
    void roundTripPreservesPerServerWaypoints() {
        WaypointStore s = new WaypointStore();
        s.add("srv1", new Waypoint("Camp", -60, -38, -438, WaypointColor.AQUA));
        s.add("srv2", new Waypoint("Home", 10, 20, 30, WaypointColor.GREEN));
        WaypointStore back = WaypointConfig.fromJson(WaypointConfig.toJson(s));
        assertEquals(1, back.list("srv1").size());
        Waypoint w = back.list("srv1").get(0);
        assertEquals("Camp", w.name);
        assertEquals(-438, w.z);
        assertEquals(WaypointColor.AQUA, w.color);
        assertEquals(1, back.list("srv2").size());
    }

    @Test
    void nullOrGarbageGivesEmptyStore() {
        assertTrue(WaypointConfig.fromJson(null).raw().isEmpty());
        assertTrue(WaypointConfig.fromJson("not json {{{").raw().isEmpty());
    }
}
