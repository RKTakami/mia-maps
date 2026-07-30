package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceFidelityTest {
    private static int[] tile(int... px) { return px; }
    private static final int OPAQUE = 0xFF000000;

    private static int rgb(int r, int g, int b) {
        return OPAQUE | (r << 16) | (g << 8) | b;
    }

    /**
     * A drawn pixel is one with alpha, not one that is non-zero. Black terrain is real terrain, and
     * counting it as missing would make the store look worst in exactly the dark places this mod
     * exists to map — a bias that would push the wrong way on the decision this measures.
     */
    @Test
    void blackIsDrawnButTransparentIsNot() {
        assertTrue(SourceFidelity.drawn(0xFF000000));
        assertFalse(SourceFidelity.drawn(0x00000000));
        assertFalse(SourceFidelity.drawn(0x00FFFFFF), "fully transparent white is still not drawn");
    }

    @Test
    void shadeDifferencesAreNotCountedAsDisagreement() {
        SourceFidelity.Tally t = new SourceFidelity.Tally();
        int[] h = new int[]{10, 10, 10};
        t.tile(tile(rgb(100, 100, 100), rgb(100, 100, 100), rgb(100, 100, 100)), h,
               tile(rgb(100, 100, 100), rgb(104, 104, 104), rgb(10, 200, 30)), h);
        SourceFidelity.Result r = t.result();
        assertEquals(1, r.pixelsMatch(), "identical");
        assertEquals(1, r.pixelsShade(), "12 total across channels is within tolerance");
        assertEquals(1, r.pixelsDiffer(), "a different material is a real disagreement");
        assertEquals(3, r.pixelsBothDrawn());
        assertEquals(2.0 / 3.0, r.agreement(), 1e-9);
    }

    /**
     * Coverage counts what the store is missing, and deliberately does not count what it has in
     * addition. Terrain Voxy has and we do not is a hole in the map; terrain we have and Voxy does
     * not is a bonus, and averaging the two together would let a surplus hide a hole.
     */
    @Test
    void coverageMeasuresMissingTerrainNotSurplus() {
        SourceFidelity.Tally t = new SourceFidelity.Tally();
        int[] h = new int[]{0, 0, 0, 0};
        t.tile(tile(rgb(1, 1, 1), rgb(2, 2, 2), 0, 0), h,
               tile(rgb(1, 1, 1), 0, rgb(3, 3, 3), 0), h);
        SourceFidelity.Result r = t.result();
        assertEquals(1, r.pixelsBothDrawn());
        assertEquals(1, r.pixelsVoxyOnly(), "one pixel the store is missing");
        assertEquals(1, r.pixelsStoreOnly(), "one the store has spare");
        assertEquals(1, r.pixelsNeither());
        assertEquals(0.5, r.storeCoverage(), 1e-9, "half of what Voxy drew, surplus not credited");
    }

    @Test
    void aMissingSourceIsCountedAsAMissingTileNotAnEmptyOne() {
        SourceFidelity.Tally t = new SourceFidelity.Tally();
        int[] h = new int[]{0};
        t.tile(tile(rgb(9, 9, 9)), h, null, null);
        t.tile(null, null, tile(rgb(9, 9, 9)), h);
        t.tile(null, null, null, null);
        SourceFidelity.Result r = t.result();
        assertEquals(3, r.tilesCompared());
        assertEquals(1, r.tilesVoxyOnly());
        assertEquals(1, r.tilesStoreOnly());
        assertEquals(1, r.tilesNeither());
        assertEquals(0, r.tilesBothPresent());
        assertEquals(0, r.pixelsBothDrawn(), "a tile only one source has cannot contribute agreement");
    }

    @Test
    void heightsAreOnlyMeasuredWhereBothSourcesDrew() {
        SourceFidelity.Tally t = new SourceFidelity.Tally();
        // Second pixel: only Voxy drew, and its height is wildly different. It must not be counted,
        // because a height for a pixel one source never drew is not a measurement of anything.
        t.tile(tile(rgb(1, 1, 1), rgb(2, 2, 2)), new int[]{50, 900},
               tile(rgb(1, 1, 1), 0), new int[]{53, 0});
        SourceFidelity.Result r = t.result();
        assertEquals(1, r.heightClose(), "3 apart is close");
        assertEquals(0, r.heightFar());
        assertEquals(3, r.heightWorst(), "the undrawn pixel must not set the worst case");
    }

    @Test
    void anEmptyComparisonReportsPerfectRatherThanDividingByZero() {
        SourceFidelity.Result r = new SourceFidelity.Tally().result();
        assertEquals(1.0, r.agreement(), 1e-9);
        assertEquals(1.0, r.storeCoverage(), 1e-9);
        assertNotNull(r.summary());
    }
}
