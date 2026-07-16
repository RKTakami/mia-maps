package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteBoxTest {
    private static final int BOX = 192, VBOX = 96, MARGIN = 24;

    @Test
    void aGoalOnTheLayerBelowBiasesTheBoxDownNotSideways() {
        // THE regression this class exists to prevent. A waypoint one section down sits 480 blocks
        // BELOW you in the shifted column, directly overhead in x/z. The old code took the delta in
        // WORLD space, where that same waypoint is 16384 blocks EAST, and biased the whole box east
        // -- squarely away from the target.
        RouteBox.Box b = RouteBox.place(0, 0, 0, 0, -MapGeometry.SECTOR_DEPTH, 0, BOX, VBOX, MARGIN);
        assertEquals(-96, b.originX(), "no sideways bias: the target is straight down");
        assertEquals(-96, b.originZ(), "no sideways bias: the target is straight down");
        assertTrue(b.originY() + b.gy() / 2 < 0, "box centre must sit BELOW the player");
        assertEquals(-168, b.originY()); // centre -72 (bias capped at VBOX-MARGIN), minus gy/2
    }

    @Test
    void theBoxAlwaysContainsThePlayer() {
        // A partial route is useless if it cannot start under your feet.
        RouteBox.Box b = RouteBox.place(0, 0, 0, 5000, -5000, 5000, BOX, VBOX, MARGIN);
        assertTrue(0 >= b.originX() && 0 < b.originX() + b.gx());
        assertTrue(0 >= b.originY() && 0 < b.originY() + b.gy());
        assertTrue(0 >= b.originZ() && 0 < b.originZ() + b.gz());
    }

    @Test
    void aDistantHorizontalGoalBiasesTowardItButCapsAtTheMargin() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 1000, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(72 - 96, b.originX()); // bias capped at BOX/2 - MARGIN = 72
        assertEquals(-96, b.originZ());
    }

    @Test
    void aNearbyGoalIsBiasedByHalfTheDistance() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 20, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(10 - 96, b.originX());
    }

    @Test
    void aGoalAtThePlayerCentresTheBox() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 0, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(-96, b.originX());
        assertEquals(-96, b.originY());
        assertEquals(-96, b.originZ());
    }

    @Test
    void dimensionsFollowTheRequestedExtents() {
        RouteBox.Box b = RouteBox.place(0, 0, 0, 0, 0, 0, BOX, VBOX, MARGIN);
        assertEquals(BOX, b.gx());
        assertEquals(2 * VBOX, b.gy());
        assertEquals(BOX, b.gz());
    }
}
