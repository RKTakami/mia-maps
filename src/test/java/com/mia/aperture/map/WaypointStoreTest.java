package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaypointStoreTest {

    @Test
    void addRemoveReplacePerServer() {
        WaypointStore s = new WaypointStore();
        Waypoint a = new Waypoint("A", 1, 2, 3, WaypointColor.RED);
        Waypoint b = new Waypoint("B", 4, 5, 6, WaypointColor.BLUE);
        s.add("srv1", a);
        s.add("srv1", b);
        s.add("srv2", a);
        assertEquals(2, s.list("srv1").size());
        assertEquals(1, s.list("srv2").size());
        s.replace("srv1", 0, new Waypoint("A2", 7, 8, 9, WaypointColor.GREEN));
        assertEquals("A2", s.list("srv1").get(0).name);
        s.remove("srv1", 1);
        assertEquals(1, s.list("srv1").size());
    }

    @Test
    void listNeverNull() {
        assertNotNull(new WaypointStore().list("unknown"));
        assertTrue(new WaypointStore().list("unknown").isEmpty());
    }

    @Test
    void sanitizeKeepsSafeCharsOnly() {
        assertEquals("mc.example.com_25565", WaypointStore.sanitize("mc.example.com:25565"));
        assertEquals("sp_My_World", WaypointStore.sanitize("sp:My World"));
        assertEquals("unknown", WaypointStore.sanitize(""));
    }
}
