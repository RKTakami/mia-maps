package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.mia.aperture.map.MapSettings.MinimapCorner;

class MinimapLayoutTest {
    // screenW=400, screenH=300, size=100, margin=10 -> valid X range [10, 290], Y range [10, 190]
    @Test
    void originXEndpointsAndMiddle() {
        assertEquals(10,  MinimapLayout.originX(0.0, 400, 100, 10));
        assertEquals(290, MinimapLayout.originX(1.0, 400, 100, 10));
        assertEquals(150, MinimapLayout.originX(0.5, 400, 100, 10));
    }

    @Test
    void originYEndpointsAndMiddle() {
        assertEquals(10,  MinimapLayout.originY(0.0, 300, 100, 10));
        assertEquals(190, MinimapLayout.originY(1.0, 300, 100, 10));
        assertEquals(100, MinimapLayout.originY(0.5, 300, 100, 10));
    }

    @Test
    void originClampsOutOfRangeFraction() {
        assertEquals(10,  MinimapLayout.originX(-1.0, 400, 100, 10));
        assertEquals(290, MinimapLayout.originX(2.0, 400, 100, 10));
    }

    @Test
    void cornerFractions() {
        assertArrayEquals(new double[]{0.0, 0.0}, MinimapLayout.cornerFraction(MinimapCorner.TOP_LEFT), 1e-9);
        assertArrayEquals(new double[]{1.0, 0.0}, MinimapLayout.cornerFraction(MinimapCorner.TOP_RIGHT), 1e-9);
        assertArrayEquals(new double[]{0.0, 1.0}, MinimapLayout.cornerFraction(MinimapCorner.BOTTOM_LEFT), 1e-9);
        assertArrayEquals(new double[]{1.0, 1.0}, MinimapLayout.cornerFraction(MinimapCorner.BOTTOM_RIGHT), 1e-9);
    }

    @Test
    void fractionFromPixelIsInverseOfOrigin() {
        double fx = MinimapLayout.fractionFromPixelX(150, 400, 100, 10);
        assertEquals(0.5, fx, 1e-9);
        assertEquals(150, MinimapLayout.originX(fx, 400, 100, 10));
        assertEquals(0.0, MinimapLayout.fractionFromPixelX(-50, 400, 100, 10), 1e-9);
        assertEquals(1.0, MinimapLayout.fractionFromPixelX(9999, 400, 100, 10), 1e-9);
    }
}
