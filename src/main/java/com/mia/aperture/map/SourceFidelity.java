package com.mia.aperture.map;

/**
 * Measures how far the mia-loddy store's view of the world differs from Voxy's, by rendering the same
 * tiles from both and comparing the pixels.
 *
 * <p>This is the gate on making the store the default source and dropping the Voxy dependency. That
 * decision needs a number, not an impression: two screenshots of the same cavern look identical right
 * up until the moment one of them is missing a passage, and the difference that matters is not
 * "do these look similar" but "would a player be misled".
 *
 * <p><b>Compared at the rendered tile, not at the stored cell.</b> The two sources number their cells
 * in unrelated spaces and colour them through different bakes, so cell-level equality is not even
 * defined between them. What is defined — and what actually matters — is the picture: the same tile
 * coordinates, rendered through each source's own pipeline, then compared pixel for pixel. That also
 * means this measures the whole read path, not just the storage.
 *
 * <p><b>Colour difference and height difference are reported separately, and that separation is the
 * point.</b> Heights come from geometry and colours from the block tables, so the pair localises a
 * disagreement: same height and different colour indicts the colour bake, while a height difference
 * means the two stores genuinely hold different terrain. Reporting one number would have hidden which.
 */
public final class SourceFidelity {
    private SourceFidelity() {}

    /**
     * How far apart two colours can be and still count as the same material.
     *
     * <p>The two bakes light and tint independently, so the same block legitimately lands a shade
     * apart. This is a sum over the three channels, so 24 is about 8 per channel out of 255 — a
     * difference you could not see, and well below the gap between any two block types.
     */
    public static final int SHADE_TOLERANCE = 24;

    /** Blank, in a rendered tile: nothing was drawn for that pixel. */
    private static final int BLANK = 0;

    public record Result(
            int tilesCompared,
            int tilesBothPresent, int tilesVoxyOnly, int tilesStoreOnly, int tilesNeither,
            long pixelsBothDrawn, long pixelsMatch, long pixelsShade, long pixelsDiffer,
            long pixelsVoxyOnly, long pixelsStoreOnly, long pixelsNeither,
            long heightSame, long heightClose, long heightFar, long heightWorst) {

        /** Of the pixels both sources drew, the share that agree to within a shade. */
        public double agreement() {
            return pixelsBothDrawn == 0 ? 1.0
                    : (pixelsMatch + pixelsShade) / (double) pixelsBothDrawn;
        }

        /**
         * The share of drawn pixels the store is missing.
         *
         * <p>The number that actually gates defaulting: terrain Voxy has and we do not is a hole in
         * the map, whereas terrain we have and Voxy does not is only ever a bonus.
         */
        public double storeCoverage() {
            long drawnByVoxy = pixelsBothDrawn + pixelsVoxyOnly;
            return drawnByVoxy == 0 ? 1.0 : pixelsBothDrawn / (double) drawnByVoxy;
        }

        public String summary() {
            return String.format(
                    "tiles %d (both %d, Voxy only %d, store only %d, neither %d) | "
                    + "store coverage %.1f%% | agreement %.1f%% "
                    + "(exact %d, shade %d, differ %d) | "
                    + "height same %d, within 4 %d, beyond %d (worst %d)",
                    tilesCompared, tilesBothPresent, tilesVoxyOnly, tilesStoreOnly, tilesNeither,
                    storeCoverage() * 100.0, agreement() * 100.0,
                    pixelsMatch, pixelsShade, pixelsDiffer,
                    heightSame, heightClose, heightFar, heightWorst);
        }
    }

    /** Accumulates one comparison. Not thread-safe; one per run. */
    public static final class Tally {
        private int tiles, bothPresent, voxyOnly, storeOnly, neither;
        private long bothDrawn, match, shade, differ, vOnly, sOnly, none;
        private long hSame, hClose, hFar, hWorst;

        /**
         * Compare one tile.
         *
         * @param voxyColors null if Voxy had no data for this tile at all, likewise storeColors
         */
        public void tile(int[] voxyColors, int[] voxyHeights, int[] storeColors, int[] storeHeights) {
            tiles++;
            boolean v = voxyColors != null, s = storeColors != null;
            if (v && s) bothPresent++;
            else if (v) voxyOnly++;
            else if (s) storeOnly++;
            else { neither++; return; }

            int n = v ? voxyColors.length : storeColors.length;
            for (int i = 0; i < n; i++) {
                int vc = v ? voxyColors[i] : BLANK;
                int sc = s ? storeColors[i] : BLANK;
                boolean vd = drawn(vc), sd = drawn(sc);
                if (!vd && !sd) { none++; continue; }
                if (vd && !sd) { vOnly++; continue; }
                if (!vd) { sOnly++; continue; }

                bothDrawn++;
                int d = channelDistance(vc, sc);
                if (d == 0) match++;
                else if (d <= SHADE_TOLERANCE) shade++;
                else differ++;

                // Heights only where both drew, since a height for a pixel nothing drew is not a
                // measurement of anything.
                int hd = Math.abs((v ? voxyHeights[i] : 0) - (s ? storeHeights[i] : 0));
                if (hd == 0) hSame++;
                else if (hd <= 4) hClose++;
                else hFar++;
                if (hd > hWorst) hWorst = hd;
            }
        }

        public Result result() {
            return new Result(tiles, bothPresent, voxyOnly, storeOnly, neither,
                    bothDrawn, match, shade, differ, vOnly, sOnly, none,
                    hSame, hClose, hFar, hWorst);
        }
    }

    /**
     * Whether a pixel was drawn at all.
     *
     * <p>Alpha, not "non-zero": a legitimately black pixel is drawn, and treating it as blank would
     * count real terrain as missing data — which would show up as the store looking worse than it is
     * in exactly the dark places this mod exists to map.
     */
    static boolean drawn(int argb) {
        return (argb >>> 24) != 0;
    }

    /** Sum of the per-channel differences. Alpha is ignored; both are opaque by the time we get here. */
    static int channelDistance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }
}
