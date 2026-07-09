package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mia.aperture.map.MapSettings.Orientation;

class MinimapMarkersTest {
    private static void assertPos(int[] p, int x, int y) {
        assertEquals(x, p[0], "x"); assertEquals(y, p[1], "y");
    }

    @Test
    void northUpCardinalsAreAxisAligned() {
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 0), 100, 50);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 1), 150, 100);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 2), 100, 150);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.NORTH_UP, 37f, 3), 50, 100);
    }

    @Test
    void headingUpFacingEastPutsNorthLeft() {
        float yaw = -90f;
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 0), 50, 100);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 1), 100, 50);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 2), 150, 100);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 3), 100, 150);
    }

    @Test
    void headingUpFacingSouthPutsNorthBottom() {
        float yaw = 0f;
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 0), 100, 150);
        assertPos(MinimapMarkers.cardinalPos(100, 100, 50, Orientation.HEADING_UP, yaw, 1), 50, 100);
    }

    @Test
    void headingRotationZeroForNorthUp() {
        assertEquals(0f, MinimapMarkers.headingRotationRad(Orientation.NORTH_UP, 123f), 1e-6);
    }

    @Test
    void headingRotationHeadingUp() {
        assertEquals((float) Math.toRadians(-180.0), MinimapMarkers.headingRotationRad(Orientation.HEADING_UP, 0f), 1e-4);
        assertEquals((float) Math.toRadians(-90.0), MinimapMarkers.headingRotationRad(Orientation.HEADING_UP, -90f), 1e-4);
    }
}
