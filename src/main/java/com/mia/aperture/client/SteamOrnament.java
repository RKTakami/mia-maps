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
        int hx = x + 12 + (int) Math.round(Math.cos(crank) * 5);
        int hy = yTop + (int) Math.round(Math.sin(crank) * 5);
        stroke(g, x + 10, yTop, hx, hy, 2, SteamTheme.BRASS_DARK);
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
     * A scroll of brass wire, of the kind Victorian ironwork puts in every corner.
     *
     * <p>Drawn as a spiral of decreasing radius rather than a fixed curve, because a spiral is what
     * scrollwork actually is and it reads correctly at any size.
     *
     * @param sx,sy which way the scroll turns, as signs
     */
    public static void flourish(GuiGraphics g, int x, int y, int size, int sx, int sy) {
        // The long sweep in from the corner.
        arc(g, x + sx * size, y, size, sy > 0 ? Math.PI : Math.PI, sy > 0 ? Math.PI * 1.5 : Math.PI / 2,
                1, SteamTheme.BRASS_MID);
        // The curl at the end: radius shrinking over about a turn and a half.
        double cx = x + sx * size * 0.35, cy = y + sy * size * 0.75;
        double r = size * 0.42;
        for (int i = 0; i < 26; i++) {
            double a = i * 0.36 * (sx > 0 ? 1 : -1);
            double rr = r * (1 - i / 34.0);
            int px = (int) Math.round(cx + Math.cos(a) * rr);
            int py = (int) Math.round(cy + Math.sin(a) * rr * (sy > 0 ? 1 : -1));
            g.fill(px, py, px + 1, py + 1, i < 18 ? SteamTheme.BRASS : SteamTheme.BRASS_LIGHT);
        }
        SteamTheme.disc(g, (int) Math.round(cx), (int) Math.round(cy), 2, SteamTheme.BRASS_LIGHT);
    }

    /** A mirrored pair, for framing a title or a panel edge. */
    public static void flourishPair(GuiGraphics g, int cx, int y, int gap, int size) {
        flourish(g, cx - gap, y, size, -1, 1);
        flourish(g, cx + gap, y, size, 1, 1);
    }
}
