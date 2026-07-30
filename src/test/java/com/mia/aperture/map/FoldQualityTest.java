package com.mia.aperture.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoldQualityTest {
    private static final int OPAQUE = 0xFF000000;
    private static int rgb(int r, int g, int b) { return OPAQUE | (r << 16) | (g << 8) | b; }

    /**
     * Undrawn pixels are excluded from the reference, not counted as black. A coarse cell half in
     * open air should read as the terrain it contains — averaging in the empty half would darken
     * the reference and then penalise whichever source picked the correct block.
     */
    @Test
    void meanIgnoresUndrawnPixelsRatherThanTreatingThemAsBlack() {
        int[] fine = {rgb(200, 100, 50), 0, 0, 0};
        assertEquals(rgb(200, 100, 50), FoldQuality.meanColor(fine, 2, 0, 0, 2));
    }

    @Test
    void meanOfNothingDrawnIsBlankNotBlack() {
        assertEquals(0, FoldQuality.meanColor(new int[]{0, 0, 0, 0}, 2, 0, 0, 2));
    }

    @Test
    void meanAveragesTheDrawnPixels() {
        int[] fine = {rgb(100, 0, 0), rgb(200, 0, 0), rgb(100, 0, 0), rgb(200, 0, 0)};
        assertEquals(rgb(150, 0, 0), FoldQuality.meanColor(fine, 2, 0, 0, 2));
    }

    @Test
    void squareAgreementRequiresBothDrawnAndClose() {
        int[] a = {rgb(100, 100, 100), rgb(50, 50, 50)};
        assertTrue(FoldQuality.squareAgrees(a, new int[]{rgb(102, 100, 100), rgb(50, 50, 50)}, 2, 0, 0, 2));
        assertFalse(FoldQuality.squareAgrees(a, new int[]{rgb(9, 200, 9), rgb(50, 50, 50)}, 2, 0, 0, 2),
                "a different material is a disagreement");
        assertFalse(FoldQuality.squareAgrees(a, new int[]{0, rgb(50, 50, 50)}, 2, 0, 0, 2),
                "drawn versus not drawn is a disagreement");
    }

    /** The store must not be marked against its own fine data where the two sources disagree. */
    @Test
    void pixelsWhereTheSourcesDisagreeAtLevelZeroAreNotScored() {
        FoldQuality.Tally t = new FoldQuality.Tally();
        t.pixel(rgb(10, 10, 10), false, rgb(10, 10, 10), rgb(250, 250, 250));
        FoldQuality.Verdict v = t.verdict();
        assertEquals(0, v.scored());
        assertEquals(1, v.disagreedAtLevelZero());
        assertEquals(0, v.storeCloser(), "no credit from ground truth we do not trust");
    }

    @Test
    void aBlankOnOneSideIsCountedApartFromColourDistance() {
        FoldQuality.Tally t = new FoldQuality.Tally();
        t.pixel(rgb(10, 10, 10), true, 0, rgb(10, 10, 10));
        FoldQuality.Verdict v = t.verdict();
        assertEquals(0, v.scored(), "missing terrain is a different fault from a wrong colour");
        assertEquals(1, v.oneSideBlank());
    }

    @Test
    void closerToTruthWins() {
        FoldQuality.Tally t = new FoldQuality.Tally();
        t.pixel(rgb(100, 100, 100), true, rgb(100, 100, 100), rgb(200, 200, 200));
        FoldQuality.Verdict v = t.verdict();
        assertEquals(1, v.scored());
        assertEquals(1, v.storeCloser());
        assertEquals(0.0, v.storeMeanDistance(), 1e-9);
        assertEquals(300.0, v.voxyMeanDistance(), 1e-9);
        assertEquals(1.0, v.storeWinShare(), 1e-9);
    }

    @Test
    void nearlyEqualAnswersAreATieAndTheVerdictSaysSo() {
        FoldQuality.Tally t = new FoldQuality.Tally();
        for (int i = 0; i < 10; i++) {
            t.pixel(rgb(100, 100, 100), true, rgb(101, 100, 100), rgb(102, 100, 100));
        }
        FoldQuality.Verdict v = t.verdict();
        assertEquals(10, v.tie());
        assertEquals(0, v.storeCloser());
        assertTrue(v.verdict().contains("equivalent"), "got: " + v.verdict());
    }

    @Test
    void anEmptyRunConcludesNothingRatherThanDividingByZero() {
        FoldQuality.Verdict v = new FoldQuality.Tally().verdict();
        assertEquals(0, v.scored());
        assertTrue(v.verdict().contains("nothing to conclude"));
        assertNotNull(v.summary());
    }
}
