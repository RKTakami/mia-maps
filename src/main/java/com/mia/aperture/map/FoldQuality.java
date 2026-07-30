package com.mia.aperture.map;

/**
 * Scores two coarse-level representations against what the terrain actually looks like at full
 * detail, to answer which fold is <i>better</i> rather than merely that they differ.
 *
 * <p>{@link SourceFidelity} established that the store and Voxy hold the same terrain — heights agree
 * to better than 99.9% — and disagree only about which block should stand for a coarse cell. That is
 * a difference, not a defect, and nothing in a comparison of the two can say which is right. Both
 * have to be measured against something else.
 *
 * <p><b>The something else is level 0.</b> At one-block cells the two sources agree to within 0.04%,
 * so the fine data is effectively uncontested ground truth. A coarse cell covers a square of fine
 * pixels; the honest reference for that cell is what those fine pixels average to, because that is
 * literally what the eye receives when a detailed image is shrunk. Whichever coarse answer lands
 * closer to it is the better prediction of the view it stands in for.
 *
 * <p><b>Only pixels where both sources agree at level 0 are used as truth.</b> Taking the store's own
 * fine data as the yardstick for the store's own fold would be marking its own homework; requiring
 * agreement removes the question. It costs almost nothing, since they nearly always agree.
 *
 * <p><b>This is a relative test, and that is what makes it sound.</b> Rendering a region at one-block
 * cells and at eight-block cells produces different relief shading no matter which store the data
 * came from, so neither coarse answer can ever match the fine reference exactly. But both are scored
 * against the <i>same</i> reference, so that systematic difference applies equally to both and
 * cancels out of the comparison. What survives is the part that differs between them: the choice of
 * block.
 */
public final class FoldQuality {
    private FoldQuality() {}

    /** Colour distance below which two answers are equally good and neither is credited. */
    public static final int TIE_TOLERANCE = 6;

    public record Verdict(long scored, long storeCloser, long voxyCloser, long tie,
                          long storeDistanceSum, long voxyDistanceSum,
                          long truthBlank, long disagreedAtLevelZero, long oneSideBlank) {

        public double storeMeanDistance() {
            return scored == 0 ? 0 : storeDistanceSum / (double) scored;
        }

        public double voxyMeanDistance() {
            return scored == 0 ? 0 : voxyDistanceSum / (double) scored;
        }

        /** Of the pixels where one was clearly closer, the share the store won. */
        public double storeWinShare() {
            long decided = storeCloser + voxyCloser;
            return decided == 0 ? 0.5 : storeCloser / (double) decided;
        }

        /**
         * Which fold to prefer, in words, with the hedge the numbers actually support.
         *
         * <p>A verdict is only worth stating if the gap is bigger than the noise. Mean channel
         * distances within a couple of units of each other mean the two folds are equally good and
         * the choice should be made on other grounds.
         */
        public String verdict() {
            double s = storeMeanDistance(), v = voxyMeanDistance();
            double gap = Math.abs(s - v);
            String who = s < v ? "the store's fold" : "Voxy's fold";
            if (scored == 0) return "no comparable pixels — nothing to conclude";
            if (gap < 2.0) return "the two folds are equivalent (mean distances within " + TIE_TOLERANCE
                    + " of each other); choose on other grounds";
            if (gap < 8.0) return who + " is slightly closer to full detail";
            return who + " is clearly closer to full detail";
        }

        public String summary() {
            return String.format(
                    "scored %d px | store closer %d, Voxy closer %d, tie %d (store wins %.1f%% of "
                    + "decided) | mean distance from full detail: store %.1f, Voxy %.1f | "
                    + "skipped: truth blank %d, level-0 disagreement %d, one side blank %d | %s",
                    scored, storeCloser, voxyCloser, tie, storeWinShare() * 100.0,
                    storeMeanDistance(), voxyMeanDistance(),
                    truthBlank, disagreedAtLevelZero, oneSideBlank, verdict());
        }
    }

    /** Accumulates one run. Not thread-safe; one per comparison. */
    public static final class Tally {
        private long scored, storeCloser, voxyCloser, tie, storeSum, voxySum;
        private long truthBlank, disagreed, oneSideBlank;

        /**
         * Score one coarse pixel.
         *
         * @param truthFine   the fine reference colour for the square this pixel covers, 0 if the
         *                    square held nothing drawn
         * @param truthAgreed whether the two sources agreed at level 0 across that square
         */
        public void pixel(int truthFine, boolean truthAgreed, int store, int voxy) {
            if (!truthAgreed) { disagreed++; return; }
            if (!SourceFidelity.drawn(truthFine)) { truthBlank++; return; }
            boolean sd = SourceFidelity.drawn(store), vd = SourceFidelity.drawn(voxy);
            // One side drawing nothing where full detail says there is terrain is a different fault
            // from picking the wrong block, and averaging it into a colour distance would hide it.
            if (!sd || !vd) { oneSideBlank++; return; }

            int ds = SourceFidelity.channelDistance(truthFine, store);
            int dv = SourceFidelity.channelDistance(truthFine, voxy);
            scored++;
            storeSum += ds;
            voxySum += dv;
            if (Math.abs(ds - dv) <= TIE_TOLERANCE) tie++;
            else if (ds < dv) storeCloser++;
            else voxyCloser++;
        }

        public Verdict verdict() {
            return new Verdict(scored, storeCloser, voxyCloser, tie, storeSum, voxySum,
                    truthBlank, disagreed, oneSideBlank);
        }
    }

    /**
     * The mean colour of a square of fine pixels — the reference a coarse cell is judged against.
     *
     * <p>Undrawn pixels are excluded rather than counted as black. A cell half in shadow and half
     * open sky should read as the terrain it contains, not as that terrain darkened by however much
     * empty space happened to sit beside it.
     *
     * @return the mean, opaque, or 0 if nothing in the square was drawn
     */
    public static int meanColor(int[] fine, int fineW, int x0, int y0, int span) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = y0; y < y0 + span; y++) {
            if (y < 0 || y >= fine.length / fineW) continue;
            int row = y * fineW;
            for (int x = x0; x < x0 + span; x++) {
                if (x < 0 || x >= fineW) continue;
                int c = fine[row + x];
                if (!SourceFidelity.drawn(c)) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    /** Whether every drawn pixel of the square agreed between the two fine renders. */
    public static boolean squareAgrees(int[] fineA, int[] fineB, int fineW, int x0, int y0, int span) {
        int rows = fineA.length / fineW;
        for (int y = y0; y < y0 + span; y++) {
            if (y < 0 || y >= rows) continue;
            int row = y * fineW;
            for (int x = x0; x < x0 + span; x++) {
                if (x < 0 || x >= fineW) continue;
                int a = fineA[row + x], b = fineB[row + x];
                if (SourceFidelity.drawn(a) != SourceFidelity.drawn(b)) return false;
                if (SourceFidelity.drawn(a)
                        && SourceFidelity.channelDistance(a, b) > SourceFidelity.SHADE_TOLERANCE) {
                    return false;
                }
            }
        }
        return true;
    }
}
