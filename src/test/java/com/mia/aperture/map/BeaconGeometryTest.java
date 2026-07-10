package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeaconGeometryTest {
    // Camera at origin looking +Z, up +Y, left +X, focal 500, 800x600 screen.
    private static BeaconGeometry.Screen proj(double x, double y, double z) {
        return BeaconGeometry.project(x, y, z, 0, 0, 1, 0, 1, 0, 1, 0, 0, 500, 800, 600);
    }

    @Test
    void pointAheadProjectsToCentre() {
        BeaconGeometry.Screen s = proj(0, 0, 10);
        assertTrue(s.onScreen());
        assertEquals(400, s.x());
        assertEquals(300, s.y());
    }

    @Test
    void pointToRightIsRightOfCentre() {
        BeaconGeometry.Screen s = proj(-5, 0, 10);
        assertEquals(650, s.x());
        assertTrue(s.onScreen());
    }

    @Test
    void behindCameraIsOffScreen() {
        assertFalse(proj(0, 0, -10).onScreen());
    }

    @Test
    void edgeClampRightwardHitsRightEdge() {
        int[] p = BeaconGeometry.edgeClamp(1.0, 0.0, 800, 600, 10);
        assertEquals(790, p[0]);
        assertEquals(300, p[1]);
    }
}
