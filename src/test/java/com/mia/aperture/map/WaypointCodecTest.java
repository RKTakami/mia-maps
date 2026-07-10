package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class WaypointCodecTest {

    @Test
    void roundTrip() {
        Waypoint w = new Waypoint("Second Camp", -60, -38, -438, WaypointColor.AQUA);
        Optional<Waypoint> back = WaypointCodec.decode(WaypointCodec.encode(w));
        assertTrue(back.isPresent());
        assertEquals("Second Camp", back.get().name);
        assertEquals(-60, back.get().x);
        assertEquals(-438, back.get().z);
        assertEquals(WaypointColor.AQUA, back.get().color);
    }

    @Test
    void decodesWhenPrefixedBySenderInChat() {
        Optional<Waypoint> w = WaypointCodec.decode("Steve: [MIA:WP] \"Home\" 1 2 3 green");
        assertTrue(w.isPresent());
        assertEquals("Home", w.get().name);
        assertEquals(WaypointColor.GREEN, w.get().color);
    }

    @Test
    void rejectsNonWaypointAndUnknownColour() {
        assertTrue(WaypointCodec.decode("just chatting").isEmpty());
        assertTrue(WaypointCodec.decode("[MIA:WP] \"X\" 1 2 3 chartreuse").isEmpty());
        assertTrue(WaypointCodec.decode(null).isEmpty());
    }
}
