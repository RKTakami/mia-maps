package com.mia.aperture.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Animated Victorian machinery for the margins: signal levers, windlasses hauling baskets, and brass
 * wire scrollwork.
 *
 * <p>Separate from {@link SteamTheme} on purpose. That file is structural — the frames and panels the
 * interface is actually built from. This one is ornament, and keeping them apart means ornament can
 * never be load-bearing: nothing here reports state, and removing any of it would change nothing but
 * the look.
 *
 * <p><b>Everything here is drawn in the margins.</b> These are large, moving, and would be genuinely
 * annoying over a readout or under a button, so callers place them outside the content and clear of
 * any control's hit area.
 *
 * <p><b>The mechanisms are geared to each other honestly.</b> The windlass crank angle is a function
 * of how much rope is paid out, so it counter-rotates on the way up without that being coded as a
 * special case — the reversal falls out of the arithmetic, which is both less code and more
 * convincing. The basket travels at constant speed on a triangle wave rather than a sine, because a
 * hand-cranked drum does not ease in and out.
 */
public final class SteamOrnament {
    private SteamOrnament() {}

    private static final double TAU = Math.PI * 2;

    /** Position in a cycle, 0..1. */
    private static double cycle(long periodMs) {
        return (System.currentTimeMillis() % periodMs) / (double) periodMs;
    }

    /** Constant-speed there-and-back, 0..1..0. What a crank produces; a sine is not. */
    private static double triangle(double t) {
        return t < 0.5 ? t * 2 : 2 - t * 2;
    }

    // ---- strokes ---------------------------------------------------------------------------------

    /** A thick line between two points, stepped so diagonals stay solid. */
    private static void stroke(GuiGraphics g, double x0, double y0, double x1, double y1,
                               int thickness, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) return;
        int half = Math.max(1, thickness) / 2;
        for (int i = 0; i <= steps; i++) {
            int px = (int) Math.round(x0 + dx * i / steps);
            int py = (int) Math.round(y0 + dy * i / steps);
            g.fill(px - half, py - half, px - half + thickness, py - half + thickness, color);
        }
    }

    /** An arc, stepped by arc length so the spacing does not thin out on a large radius. */
    private static void arc(GuiGraphics g, double cx, double cy, double r,
                            double from, double to, int thickness, int color) {
        int steps = Math.max(4, (int) Math.ceil(Math.abs(to - from) * r));
        for (int i = 0; i <= steps; i++) {
            double a = from + (to - from) * i / steps;
            int px = (int) Math.round(cx + Math.cos(a) * r);
            int py = (int) Math.round(cy + Math.sin(a) * r);
            g.fill(px, py, px + thickness, py + thickness, color);
        }
    }

    // ---- levers ---------------------------------------------------------------------------------

    /**
     * A signal lever swinging in a slotted quadrant plate.
     *
     * @param sweepDeg total travel; the arm rocks half of this either side of vertical
     */
    public static void lever(GuiGraphics g, int px, int py, int len, long periodMs, double sweepDeg,
                             double offset) {
        double t = triangle((cycle(periodMs) + offset) % 1.0);
        double a = Math.toRadians(-90 + (t - 0.5) * sweepDeg);

        // The quadrant the lever runs in: a slot with a brass rim, drawn first so the arm sits in it.
        arc(g, px, py, len * 0.78, Math.toRadians(-90 - sweepDeg / 2),
                Math.toRadians(-90 + sweepDeg / 2), 2, SteamTheme.BRASS_SHADOW);
        arc(g, px, py, len * 0.78, Math.toRadians(-90 - sweepDeg / 2),
                Math.toRadians(-90 + sweepDeg / 2), 1, SteamTheme.BRASS_MID);

        int ex = px + (int) Math.round(Math.cos(a) * len);
        int ey = py + (int) Math.round(Math.sin(a) * len);
        stroke(g, px, py, ex, ey, 3, SteamTheme.BRASS_SHADOW);
        stroke(g, px, py, ex, ey, 2, SteamTheme.BRASS);
        // Highlight down one side of the arm, offset toward the light.
        stroke(g, px - 1, py - 1, ex - 1, ey - 1, 1, SteamTheme.BRASS_LIGHT);

        SteamTheme.disc(g, ex, ey, 4, SteamTheme.BRASS_SHADOW);   // the ball grip
        SteamTheme.disc(g, ex, ey, 3, SteamTheme.BRASS);
        g.fill(ex - 2, ey - 2, ex, ey, SteamTheme.BRASS_HI);

        SteamTheme.disc(g, px, py, 5, SteamTheme.BRASS_MID);      // pivot boss
        SteamTheme.screw(g, px, py);
    }

    /**
     * A bank of levers stacked down a margin, each on its own period and phase.
     *
     * <p>Stacked vertically rather than side by side because the space these live in is a narrow
     * margin, and because levers all moving in step would look like one mechanism rather than a
     * bank. The differing periods mean the pattern never quite repeats.
     */
    public static void leverBank(GuiGraphics g, int x, int y, int count, int len) {
        int spacing = len + 12;
        for (int i = 0; i < count; i++) {
            lever(g, x, y + i * spacing, len, 5200 + i * 900L, 46, i * 0.31);
        }
    }

    // ---- windlass and basket --------------------------------------------------------------------

    /**
     * A windlass hauling a basket up and down a shaft — the Abyss's own means of moving cargo.
     *
     * @param yTop   where the drum sits
     * @param travel how far below it the basket descends
     */
    public static void windlassBasket(GuiGraphics g, int x, int yTop, int travel, long periodMs) {
        windlassBasket(g, x, yTop, travel, periodMs, false);
    }

    /**
     * @param mirror put the crank on the left instead of the right, so a facing pair has both cranks
     *               outboard rather than one reaching across its own basket
     */
    public static void windlassBasket(GuiGraphics g, int x, int yTop, int travel, long periodMs,
                                      boolean mirror) {
        double t = triangle(cycle(periodMs));
        int basketY = yTop + 14 + (int) Math.round(t * travel);

        // Frame: two uprights and a crossbeam carrying the drum.
        stroke(g, x - 11, yTop - 8, x - 11, yTop + 10, 2, SteamTheme.BRASS_DARK);
        stroke(g, x + 11, yTop - 8, x + 11, yTop + 10, 2, SteamTheme.BRASS_DARK);
        SteamTheme.roundRect(g, x - 13, yTop - 11, 26, 4, 1, SteamTheme.BRASS_MID);

        // The drum, with ribs to read as a cylinder rather than a bar.
        SteamTheme.roundRect(g, x - 9, yTop - 4, 18, 9, 3, SteamTheme.BRASS_MID);
        SteamTheme.roundRect(g, x - 9, yTop - 4, 18, 4, 3, SteamTheme.BRASS);
        for (int i = -6; i <= 6; i += 4) {
            g.fill(x + i, yTop - 3, x + i + 1, yTop + 4, SteamTheme.BRASS_DARK);
        }

        // The crank. Its angle is a function of rope paid out, so it counter-rotates on the way up
        // without that being a special case — the reversal comes from the arithmetic.
        double crank = t * TAU * 3;
        int side = mirror ? -1 : 1;
        int hx = x + side * 12 + (int) Math.round(Math.cos(crank) * 5) * side;
        int hy = yTop + (int) Math.round(Math.sin(crank) * 5);
        stroke(g, x + side * 10, yTop, hx, hy, 2, SteamTheme.BRASS_DARK);
        SteamTheme.disc(g, hx, hy, 3, SteamTheme.BRASS);
        g.fill(hx - 1, hy - 1, hx, hy, SteamTheme.BRASS_HI);

        // Rope, paid out to exactly the basket's height.
        g.fill(x - 1, yTop + 4, x, basketY - 5, SteamTheme.BRASS_DARK);

        // Basket: a tapered pannier with a bail handle and a woven body.
        int bw = 15, bh = 10;
        int bx = x - bw / 2 - 1;
        arc(g, x - 1, basketY - 4, 6, Math.PI, TAU, 1, SteamTheme.BRASS_MID);   // handle
        for (int row = 0; row < bh; row++) {
            int taper = row * 2 / bh;                     // narrower at the bottom
            g.fill(bx + taper, basketY + row, bx + bw - taper, basketY + row + 1,
                    row % 3 == 0 ? SteamTheme.BRASS_DARK : SteamTheme.BRASS_MID);
        }
        for (int c = 2; c < bw - 2; c += 4) {              // vertical weave
            g.fill(bx + c, basketY, bx + c + 1, basketY + bh, SteamTheme.BRASS_DARK);
        }
        SteamTheme.roundRect(g, bx - 1, basketY - 1, bw + 2, 3, 1, SteamTheme.BRASS);   // rim
    }

    // ---- brass wire flourishes ------------------------------------------------------------------

    /**
     * Draw a path three times to make it look like lit brass wire: a shadow pass offset down-right, the
     * body, then a thinner highlight offset up-left.
     *
     * <p>This is the whole trick, and the reason the first attempt at flourishes was invisible: a
     * single mid-tone line reads as a scratch. Polished metal is legible because it has a bright edge
     * and a dark one, and on a dark panel the highlight is what the eye actually catches.
     */
    private static void litPath(GuiGraphics g, double[][] pts, int thickness) {
        for (int pass = 0; pass < 3; pass++) {
            int dx = pass == 0 ? 1 : pass == 2 ? -1 : 0;
            int dy = dx;
            int t = pass == 2 ? Math.max(1, thickness - 1) : thickness;
            int color = pass == 0 ? SteamTheme.BRASS_SHADOW
                    : pass == 1 ? SteamTheme.BRASS : SteamTheme.BRASS_HI;
            for (int i = 1; i < pts.length; i++) {
                stroke(g, pts[i - 1][0] + dx, pts[i - 1][1] + dy,
                        pts[i][0] + dx, pts[i][1] + dy, t, color);
            }
        }
    }

    /**
     * Points along a volute — a spiral whose radius decays. This is what the curl at the end of
     * scrollwork actually is, so building it as a spiral makes it read correctly at any size instead
     * of looking like an arc that stops.
     */
    private static double[][] volute(double cx, double cy, double r, double from, double turns,
                                     int dir, int steps) {
        double[][] pts = new double[steps][2];
        for (int i = 0; i < steps; i++) {
            double f = i / (double) (steps - 1);
            double a = from + dir * f * turns * TAU;
            double rr = r * (1 - 0.72 * f);
            pts[i][0] = cx + Math.cos(a) * rr;
            pts[i][1] = cy + Math.sin(a) * rr;
        }
        return pts;
    }

    /** An acanthus lobe: a teardrop off the main stem, the standard leaf of Victorian ironwork. */
    private static void lobe(GuiGraphics g, double cx, double cy, double r, double angle, int dir) {
        double[][] pts = new double[14][2];
        for (int i = 0; i < pts.length; i++) {
            double f = i / (double) (pts.length - 1);
            double a = angle + dir * f * Math.PI * 1.15;
            double rr = r * Math.sin(Math.PI * f) * 1.15 + 1;
            pts[i][0] = cx + Math.cos(a) * rr;
            pts[i][1] = cy + Math.sin(a) * rr;
        }
        litPath(g, pts, 2);
    }

    /**
     * A corner flourish in the illuminated-manuscript manner: a sweeping stem that curls into a
     * volute, with acanthus lobes off it and a polished boss at the eye of the curl.
     *
     * @param sx,sy which corner it grows from, as signs
     */
    public static void flourish(GuiGraphics g, int x, int y, int size, int sx, int sy) {
        // The stem: a quarter sweep away from the corner. Mirrored properly this time — the previous
        // version had both branches of the mirror identical, so it always curled the same way.
        double a0 = sx > 0 ? Math.PI : 0;
        double a1 = sx > 0 ? (sy > 0 ? Math.PI * 1.5 : Math.PI * 0.5)
                           : (sy > 0 ? Math.PI * -0.5 : Math.PI * 0.5);
        int steps = 18;
        double[][] stem = new double[steps][2];
        for (int i = 0; i < steps; i++) {
            double f = i / (double) (steps - 1);
            double a = a0 + (a1 - a0) * f;
            stem[i][0] = x + sx * size + Math.cos(a) * size;
            stem[i][1] = y + Math.sin(a) * size * (sy > 0 ? 1 : -1);
        }
        litPath(g, stem, 2);

        // The curl at the free end of the stem.
        double ex = stem[steps - 1][0], ey = stem[steps - 1][1];
        double cx = ex - sx * size * 0.38, cy = ey - sy * size * 0.10;
        litPath(g, volute(cx, cy, size * 0.44, sy > 0 ? -Math.PI / 2 : Math.PI / 2,
                1.35, sx > 0 ? 1 : -1, 26), 2);

        // Two lobes off the stem, and a polished boss at the eye of the curl.
        lobe(g, stem[steps / 3][0], stem[steps / 3][1], size * 0.28,
                sy > 0 ? -Math.PI / 3 : Math.PI / 3, sx > 0 ? 1 : -1);
        lobe(g, stem[steps * 2 / 3][0], stem[steps * 2 / 3][1], size * 0.22,
                sy > 0 ? -Math.PI / 2 : Math.PI / 2, sx > 0 ? -1 : 1);
        SteamTheme.disc(g, (int) Math.round(cx), (int) Math.round(cy), 3, SteamTheme.BRASS_SHADOW);
        SteamTheme.disc(g, (int) Math.round(cx), (int) Math.round(cy), 2, SteamTheme.BRASS_HI);
    }

    /**
     * A horizontal divider: mirrored volutes running out from a central lozenge, of the kind that
     * separates sections in a Victorian title page.
     */
    public static void flourishBar(GuiGraphics g, int cx, int y, int halfWidth) {
        // The two rules, tapering out from the centre.
        for (int side = -1; side <= 1; side += 2) {
            double[][] rule = new double[12][2];
            for (int i = 0; i < rule.length; i++) {
                double f = i / (double) (rule.length - 1);
                rule[i][0] = cx + side * (12 + f * (halfWidth - 22));
                rule[i][1] = y - Math.sin(f * Math.PI) * 2;
            }
            litPath(g, rule, 2);
            // A volute at each outer end, curling back toward the centre.
            double ex = rule[rule.length - 1][0];
            litPath(g, volute(ex + side * 4, y + 3, 6, -Math.PI / 2, 1.2, side, 20), 2);
        }
        // Central lozenge: a brass diamond with a lit top-left face.
        for (int i = 0; i < 6; i++) {
            int w = 6 - Math.abs(i - 3) * 2;
            if (w <= 0) continue;
            g.fill(cx - w, y - 4 + i, cx + w, y - 3 + i,
                    i < 3 ? SteamTheme.BRASS_HI : SteamTheme.BRASS_MID);
        }
        SteamTheme.disc(g, cx, y - 1, 2, SteamTheme.BRASS_LIGHT);
    }

    /** A mirrored pair of corner flourishes, for framing a title. */
    public static void flourishPair(GuiGraphics g, int cx, int y, int gap, int size) {
        flourish(g, cx - gap, y, size, -1, 1);
        flourish(g, cx + gap, y, size, 1, 1);
    }
}
