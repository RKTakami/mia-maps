package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrbitCameraTest {

    @Test
    void cameraSitsBackFromFocusAlongForward() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        double[] cam = c.cameraPos();
        assertEquals(0, cam[0], 1e-9);
        assertEquals(0, cam[1], 1e-9);
        assertEquals(-10, cam[2], 1e-9); // yaw0/pitch0 forward=(0,0,1) -> camera at -Z
    }

    @Test
    void focusProjectsToScreenCentre() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        BeaconGeometry.Screen s = c.project(0, 0, 0, 500, 800, 600);
        assertTrue(s.onScreen());
        assertEquals(400, s.x());
        assertEquals(300, s.y());
    }

    @Test
    void pointAboveFocusProjectsAboveCentre() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        BeaconGeometry.Screen s = c.project(0, 3, 0, 500, 800, 600);
        assertEquals(400, s.x());
        assertTrue(s.y() < 300);
    }

    @Test
    void nearerPointHasSmallerDepth() {
        OrbitCamera c = new OrbitCamera(0, 0, 0, 0, 0, 10);
        double near = c.project(0, 0, 0, 500, 800, 600).depth();   // at focus, depth 10
        double far = c.project(0, 0, 20, 500, 800, 600).depth();   // beyond focus, depth 30
        assertTrue(near < far);
    }

    @Test
    void seeThroughAlphaFallsWithStrengthButNeverToNothing() {
        assertEquals(1.0f, OrbitScene.seeThroughAlpha(0), 1e-6, "off means fully solid");
        assertTrue(OrbitScene.seeThroughAlpha(25) > OrbitScene.seeThroughAlpha(50));
        assertTrue(OrbitScene.seeThroughAlpha(50) > OrbitScene.seeThroughAlpha(75));
        // Layers composite, so a zero floor would erase near and far terrain together and leave
        // nothing to read depth from.
        assertTrue(OrbitScene.seeThroughAlpha(100) >= 0.08f);
    }

    @Test
    void cutawayDropsOnlyWhatIsBetweenCameraAndPlayer() {
        // Camera at z=-100 looking toward a player at the origin, so the cut plane is z=0 and the
        // axis points along +z. Getting the sign backwards would cull the half you want to keep,
        // which looks like the terrain vanishing rather than opening up.
        double[] axis = {0, 0, 1, 0, 0, 0};
        assertTrue(OrbitScene.inFrontOfCut(axis, 0, 0, -50), "rock between camera and player goes");
        assertFalse(OrbitScene.inFrontOfCut(axis, 0, 0, 50), "rock beyond the player stays");
        assertFalse(OrbitScene.inFrontOfCut(axis, 0, 0, 0), "the player's own plane stays");
        // Sideways offsets do not matter: the test is projection along the view axis only.
        assertTrue(OrbitScene.inFrontOfCut(axis, 900, -900, -1));
        assertFalse(OrbitScene.inFrontOfCut(axis, 900, -900, 1));
    }

    @Test
    void cutawayIsInertWhenOffOrDegenerate() {
        assertFalse(OrbitScene.inFrontOfCut(null, 0, 0, -50), "off means nothing is culled");
        // Camera sitting exactly on the focus has no view direction to cut along.
        assertNull(OrbitScene.cutawayAxis(new double[]{5, 5, 5}, 5, 5, 5));
    }
}
