package com.mia.aperture.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteamOrnamentTest {
    /**
     * A stroke stamps a thickness-square at each step. Striding more than the thickness would leave
     * gaps between the stamps — the ornament would come out dotted, and only on diagonals, which is
     * exactly the kind of thing that is invisible in a screenshot and obvious in motion.
     */
    @Test
    void strokeStampsAlwaysOverlap() {
        for (int thickness = 1; thickness <= 8; thickness++) {
            for (double len = 1; len <= 400; len += 1) {
                for (double[] d : new double[][]{{len, 0}, {0, len}, {len, len}, {-len, len}}) {
                    int steps = SteamOrnament.strokeSteps(d[0], d[1], thickness);
                    assertTrue(steps >= 1, "a visible run needs at least one step");
                    double stride = Math.max(Math.abs(d[0]), Math.abs(d[1])) / (double) steps;
                    assertTrue(stride <= thickness,
                            "gap: thickness " + thickness + " stamps " + stride + "px apart");
                }
            }
        }
    }

    /**
     * The whole point of the change: fewer stamps than one per pixel once the line has real width.
     *
     * <p>The stride is half the thickness, not the whole of it — stamps have to overlap rather than
     * abut, because on a diagonal the perpendicular step is what opens a gap. So a 2px line saves
     * nothing and only the wide passes get cheaper, which is where the cost was anyway.
     */
    @Test
    void thickStrokesCostFewerStampsThanPixels() {
        assertEquals(100, SteamOrnament.strokeSteps(100, 0, 1), "a hairline still steps per pixel");
        assertEquals(100, SteamOrnament.strokeSteps(100, 0, 2), "half of 2 is 1: no saving here");
        assertEquals(50, SteamOrnament.strokeSteps(100, 0, 4));
        assertEquals(25, SteamOrnament.strokeSteps(100, 0, 8));
    }
}
