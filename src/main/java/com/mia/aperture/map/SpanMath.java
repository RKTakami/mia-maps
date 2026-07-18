package com.mia.aperture.map;

// Pure span arithmetic for AbyssSpanStore columns. A span is one int: biased top in the high
// half, biased bottom in the low half (cellY is small — the Abyss band is ~500 base cells — but
// signed, hence the bias). No Voxy or Minecraft imports.
public final class SpanMath {
    private static final int BIAS = 2048;
    private static final int MASK = 0xFFF;

    private SpanMath() {}

    public static int packSpan(int top, int bottom) {
        return ((top + BIAS) << 12) | (bottom + BIAS);
    }

    public static int spanTop(int span) {
        return ((span >>> 12) & MASK) - BIAS;
    }

    public static int spanBottom(int span) {
        return (span & MASK) - BIAS;
    }

    // Insert a solid run [bottom..top] (inclusive), merging any overlapping or touching spans.
    // The merged span's color follows the HIGHEST top — that's the face you see from above.
    public static AbyssSpanStore.Column insertRun(AbyssSpanStore.Column c, int top, int bottom, int color) {
        int newTop = top, newBottom = bottom, newColor = color;
        int n = c == null ? 0 : c.spans().length;
        int[] keepS = new int[n + 1];
        int[] keepC = new int[n + 1];
        int before = 0, kept = 0;
        for (int i = 0; i < n; i++) {
            int s = c.spans()[i];
            int st = spanTop(s), sb = spanBottom(s);
            if (st < bottom - 1) {                       // entirely below, not touching
                keepS[kept] = s; keepC[kept] = c.colors()[i]; kept++; before = kept;
            } else if (sb > top + 1) {                   // entirely above, not touching
                keepS[kept] = s; keepC[kept] = c.colors()[i]; kept++;
            } else {                                     // overlaps or touches: merge
                if (st > newTop) { newTop = st; newColor = c.colors()[i]; }
                if (sb < newBottom) newBottom = sb;
            }
        }
        int[] outS = new int[kept + 1];
        int[] outC = new int[kept + 1];
        System.arraycopy(keepS, 0, outS, 0, before);
        System.arraycopy(keepC, 0, outC, 0, before);
        outS[before] = packSpan(newTop, newBottom);
        outC[before] = newColor;
        int afterCount = kept - before;
        System.arraycopy(keepS, before, outS, before + 1, afterCount);
        System.arraycopy(keepC, before, outC, before + 1, afterCount);
        return new AbyssSpanStore.Column(outS, outC);
    }

    // Remove [clearBottom..clearTop] (inclusive) from the column: drops covered spans, trims
    // straddlers, splits a span the range punches through. Fragments keep the original span's
    // color (a cut face inheriting the old top color is acceptable at overview scale).
    // Returns null when nothing remains.
    public static AbyssSpanStore.Column clearRange(AbyssSpanStore.Column c, int clearTop, int clearBottom) {
        if (c == null) return null;
        int n = c.spans().length;
        int[] outS = new int[n + 1];
        int[] outC = new int[n + 1];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            int s = c.spans()[i];
            int st = spanTop(s), sb = spanBottom(s);
            if (st < clearBottom || sb > clearTop) {     // untouched
                outS[kept] = s; outC[kept] = c.colors()[i]; kept++;
                continue;
            }
            if (sb < clearBottom) {                      // lower fragment survives
                outS[kept] = packSpan(clearBottom - 1, sb); outC[kept] = c.colors()[i]; kept++;
            }
            if (st > clearTop) {                         // upper fragment survives
                outS[kept] = packSpan(st, clearTop + 1); outC[kept] = c.colors()[i]; kept++;
            }
        }
        if (kept == 0) return null;
        int[] rs = new int[kept];
        int[] rc = new int[kept];
        System.arraycopy(outS, 0, rs, 0, kept);
        System.arraycopy(outC, 0, rc, 0, kept);
        return new AbyssSpanStore.Column(rs, rc);
    }

    public static boolean solidAt(AbyssSpanStore.Column c, int cellY) {
        if (c == null) return false;
        for (int s : c.spans()) {
            if (cellY >= spanBottom(s) && cellY <= spanTop(s)) return true;
        }
        return false;
    }

    // Fold a finer column into a coarser accumulator: Y halves (floor division, occupancy
    // union). Called once per child column; pass the running result back in.
    public static AbyssSpanStore.Column mipInto(AbyssSpanStore.Column acc, AbyssSpanStore.Column child) {
        if (child == null) return acc;
        for (int i = 0; i < child.spans().length; i++) {
            int s = child.spans()[i];
            acc = insertRun(acc, Math.floorDiv(spanTop(s), 2), Math.floorDiv(spanBottom(s), 2),
                    child.colors()[i]);
        }
        return acc;
    }
}
