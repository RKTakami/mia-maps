package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Interp2DTest {
    @Test
    void bilinearAtCornersReturnsCornerValues() {
        float[] f = {0, 10, 20, 30}; // 2x2: (0,0)=0 (1,0)=10 (0,1)=20 (1,1)=30
        assertEquals(0, Interp2D.bilinear(f, 2, 2, 0, 0), 1e-5);
        assertEquals(10, Interp2D.bilinear(f, 2, 2, 1, 0), 1e-5);
        assertEquals(30, Interp2D.bilinear(f, 2, 2, 1, 1), 1e-5);
    }

    @Test
    void bilinearAtCentreAveragesFour() {
        float[] f = {0, 10, 20, 30};
        assertEquals(15, Interp2D.bilinear(f, 2, 2, 0.5, 0.5), 1e-5);
    }

    @Test
    void clampsOutOfRange() {
        float[] f = {0, 10, 20, 30};
        assertEquals(0, Interp2D.bilinear(f, 2, 2, -1, -1), 1e-5);
        assertEquals(30, Interp2D.bilinear(f, 2, 2, 5, 5), 1e-5);
    }

    @Test
    void bilerpCornersAndCentre() {
        assertEquals(0, Interp2D.bilerp(0, 10, 20, 30, 0, 0), 1e-9);
        assertEquals(10, Interp2D.bilerp(0, 10, 20, 30, 1, 0), 1e-9);
        assertEquals(20, Interp2D.bilerp(0, 10, 20, 30, 0, 1), 1e-9);
        assertEquals(30, Interp2D.bilerp(0, 10, 20, 30, 1, 1), 1e-9);
        assertEquals(15, Interp2D.bilerp(0, 10, 20, 30, 0.5, 0.5), 1e-9);
    }
}
