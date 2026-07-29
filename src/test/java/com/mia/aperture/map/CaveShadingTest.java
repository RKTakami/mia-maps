package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaveShadingTest {

    private static final int GREY = 0xFF808080;

    private static int r(int argb) { return (argb >> 16) & 0xFF; }
    private static int b(int argb) { return argb & 0xFF; }

    @Test
    void yourOwnLevelIsBrightest() {
        int here = CaveShading.shade(GREY, 0);
        assertTrue(r(here) > r(CaveShading.shade(GREY, 20)));
        assertTrue(r(here) > r(CaveShading.shade(GREY, -20)));
    }

    @Test
    void belowGoesBlueAndAboveGoesRed() {
        int below = CaveShading.shade(GREY, 30);
        int above = CaveShading.shade(GREY, -30);
        assertTrue(b(below) > r(below), "below your level reads blue");
        assertTrue(r(above) > b(above), "above your level reads red");
    }

    @Test
    void bothDirectionsDimProgressively() {
        for (int sign : new int[]{1, -1}) {
            int near = CaveShading.shade(GREY, sign * 5);
            int mid = CaveShading.shade(GREY, sign * 20);
            int far = CaveShading.shade(GREY, sign * 45);
            int nl = r(near) + b(near), ml = r(mid) + b(mid), fl = r(far) + b(far);
            assertTrue(nl > ml && ml > fl, "must keep dimming, sign " + sign);
        }
    }

    @Test
    void equalDistancesDimEquallyEitherWay() {
        // Otherwise one direction reads as nearer than the other at the same distance, and the
        // tint stops being a direction cue and starts being a distance cue too.
        int up = CaveShading.shade(GREY, -30);
        int down = CaveShading.shade(GREY, 30);
        int lumUp = r(up) + ((up >> 8) & 0xFF) + b(up);
        int lumDown = r(down) + ((down >> 8) & 0xFF) + b(down);
        assertTrue(Math.abs(lumUp - lumDown) <= 12, "up " + lumUp + " vs down " + lumDown);
    }

    @Test
    void clampsBeyondTheSlice() {
        assertEquals(CaveShading.shade(GREY, CaveShading.SLICE_BLOCKS),
                CaveShading.shade(GREY, CaveShading.SLICE_BLOCKS * 4));
    }
}
