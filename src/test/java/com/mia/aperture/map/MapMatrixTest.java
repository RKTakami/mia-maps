package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapMatrixTest {
    @Test
    void pointAtFocusProjectsToCentre() {
        // Camera orbiting a focus at origin, straight-on, distance 10; the focus point (0,0,0)
        // projects to the centre of the view (NDC ~ 0,0) and is in front of the camera (w>0).
        float[] mvp = MapMatrix.orbit(0,0,0, /*yawDeg*/0, /*pitchDeg*/0, /*dist*/10,
                /*fovRad*/(float)Math.toRadians(70), /*aspect*/1f, /*near*/0.1f, /*far*/4000f);
        float[] clip = MapMatrix.mul(mvp, 0,0,0,1);
        assertTrue(clip[3] > 0, "focus in front of camera");
        assertEquals(0.0, clip[0]/clip[3], 1e-4, "centre X");
        assertEquals(0.0, clip[1]/clip[3], 1e-4, "centre Y");
    }

    @Test
    void columnMajorSixteenFloats() {
        assertEquals(16, MapMatrix.orbit(1,2,3, 30,20, 50, (float)Math.toRadians(70), 1.6f, 0.1f, 4000f).length);
    }

    @Test
    void pointClearlyBehindHasNonPositiveW() {
        // A point far on the opposite side of the focus from the camera is behind or at the camera.
        float[] mvp = MapMatrix.orbit(0,0,0, 0,0, 10, (float)Math.toRadians(70), 1f, 0.1f, 4000f);
        // Camera sits +dist from focus along its forward axis; a point at 2*that distance beyond
        // the focus should have w <= 0 (behind the near plane / camera). Use a big offset along
        // the view direction; assert it is not a normal in-front projection.
        float[] clip = MapMatrix.mul(mvp, 0,0,-1000,1); // if forward is -Z-ish this is behind
        // Accept either sign convention: at least assert the focus (w>0) and this point differ in front/back.
        float[] front = MapMatrix.mul(mvp, 0,0,0,1);
        assertTrue(front[3] > 0);
        assertNotEquals(Math.signum(front[3]), Math.signum(clip[3]) == 0 ? front[3] : Math.signum(clip[3]),
                "a far point along view axis is not in front like the focus");
    }
}
