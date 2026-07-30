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

        // The drum. Lit across its width rather than in two flat halves, so it reads as a cylinder:
        // each row from the top is a step lighter, then darker past the midline.
        int dw = 22, dh = 11, dx0 = x - dw / 2;
        for (int row = 0; row < dh; row++) {
            double f = row / (double) (dh - 1);
            int tone = f < 0.18 ? SteamTheme.BRASS_MID
                    : f < 0.38 ? SteamTheme.BRASS_LIGHT
                    : f < 0.52 ? SteamTheme.BRASS_HI
                    : f < 0.74 ? SteamTheme.BRASS
                    : f < 0.9 ? SteamTheme.BRASS_MID : SteamTheme.BRASS_DARK;
            int in = (row == 0 || row == dh - 1) ? 2 : (row == 1 || row == dh - 2) ? 1 : 0;
            g.fill(dx0 + in, yTop - 5 + row, dx0 + dw - in, yTop - 4 + row, tone);
        }
        for (int i = -8; i <= 8; i += 4) {
            g.fill(x + i, yTop - 4, x + i + 1, yTop + 5, SteamTheme.BRASS_DARK);   // ribs
        }
        // A ratchet wheel on the near end, with a pawl resting on its teeth.
        SteamTheme.disc(g, dx0 + 1, yTop, 5, SteamTheme.BRASS_DARK);
        SteamTheme.disc(g, dx0 + 1, yTop, 4, SteamTheme.BRASS);
        for (int i = 0; i < 8; i++) {
            double a = t * TAU * 3 + i * TAU / 8;
            int rx = dx0 + 1 + (int) Math.round(Math.cos(a) * 5);
            int ry = yTop + (int) Math.round(Math.sin(a) * 5);
            g.fill(rx, ry, rx + 1, ry + 1, SteamTheme.BRASS_HI);
        }
        stroke(g, dx0 - 5, yTop - 8, dx0 + 1, yTop - 4, 2, SteamTheme.BRASS_MID);   // pawl

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
        // Four passes, outermost first. The seat is drawn wider than the body so the wire sits in a
        // dark groove — that separation from the background is what the earlier three-pass version
        // lacked, and it is why the flourishes read as faint scratches rather than as metal.
        int[] dxs = {1, 2, 0, -1};
        int[] widths = {thickness + 2, thickness + 1, thickness, Math.max(1, thickness - 1)};
        int[] colors = {SteamTheme.BRASS_SHADOW, SteamTheme.BRASS_DARK,
                        SteamTheme.BRASS, SteamTheme.BRASS_HI};
        for (int pass = 0; pass < 4; pass++) {
            for (int i = 1; i < pts.length; i++) {
                stroke(g, pts[i - 1][0] + dxs[pass], pts[i - 1][1] + dxs[pass],
                        pts[i][0] + dxs[pass], pts[i][1] + dxs[pass], widths[pass], colors[pass]);
            }
        }
        // Specular: a few bright points along the upper side, where a polished round wire would catch
        // the light. Continuous white would look like paint; intermittent reads as a sheen.
        for (int i = 2; i < pts.length - 2; i += 5) {
            int px = (int) Math.round(pts[i][0]) - 1;
            int py = (int) Math.round(pts[i][1]) - 1;
            g.fill(px, py, px + 1, py + 1, 0xFFFFF6DC);
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

    /**
     * A scroll that hugs the outside of a rounded bezel corner, curling away along the diagonal.
     *
     * <p>Shaped for the frame rather than dropped beside it: the tail runs out along the 45 degree
     * line where a square bezel's corner actually turns, so the metal looks continuous with the
     * frame instead of parked next to it.
     */
    public static void bezelCornerScroll(GuiGraphics g, int cx, int cy, int sx, int sy, int size) {
        double diag = Math.atan2(sy, sx);
        // Tail: outward along the diagonal, then bending to run parallel to the top or bottom edge.
        int steps = 14;
        double[][] tail = new double[steps][2];
        for (int i = 0; i < steps; i++) {
            double f = i / (double) (steps - 1);
            double a = diag + f * 0.9 * (sx * sy > 0 ? 1 : -1);
            double r = size * (0.35 + f * 0.85);
            tail[i][0] = cx + Math.cos(a) * r;
            tail[i][1] = cy + Math.sin(a) * r;
        }
        litPath(g, tail, 2);
        // The curl at the free end, turning back toward the frame.
        double ex = tail[steps - 1][0], ey = tail[steps - 1][1];
        litPath(g, volute(ex - Math.cos(diag) * size * 0.3, ey - Math.sin(diag) * size * 0.3,
                size * 0.5, diag + Math.PI / 2, 1.25, sx * sy > 0 ? -1 : 1, 22), 2);
        lobe(g, tail[steps / 2][0], tail[steps / 2][1], size * 0.34, diag - Math.PI / 2,
                sx * sy > 0 ? 1 : -1);
        SteamTheme.disc(g, (int) Math.round(ex), (int) Math.round(ey), 3, SteamTheme.BRASS_SHADOW);
        SteamTheme.disc(g, (int) Math.round(ex), (int) Math.round(ey), 2, SteamTheme.BRASS_HI);
    }

    /**
     * A lug on a ring bezel: a leaf pointing outward at the given bearing, with a volute curling
     * around it.
     *
     * <p>The round frame gets radial ornament and the square frame gets corner ornament, because a
     * corner scroll on a circle has no corner to sit in and reads as debris stuck to the rim.
     */
    public static void bezelRingLug(GuiGraphics g, int cx, int cy, int radius, double bearing,
                                    int size) {
        int bx = cx + (int) Math.round(Math.cos(bearing) * radius);
        int by = cy + (int) Math.round(Math.sin(bearing) * radius);
        // A short radial stem out from the rim.
        double ox = Math.cos(bearing), oy = Math.sin(bearing);
        litPath(g, new double[][]{{bx, by}, {bx + ox * size, by + oy * size}}, 2);
        // Two leaves either side of it, so the lug reads as cast rather than as a spike.
        lobe(g, bx + ox * size * 0.6, by + oy * size * 0.6, size * 0.5, bearing + Math.PI / 2, 1);
        lobe(g, bx + ox * size * 0.6, by + oy * size * 0.6, size * 0.5, bearing - Math.PI / 2, -1);
        int tx = bx + (int) Math.round(ox * size), ty = by + (int) Math.round(oy * size);
        SteamTheme.disc(g, tx, ty, 3, SteamTheme.BRASS_SHADOW);
        SteamTheme.disc(g, tx, ty, 2, SteamTheme.BRASS_HI);
    }

    /**
     * A vine running between two points: a sinuous brass stem with leaves alternating either side and
     * the occasional curling tendril.
     *
     * <p>Stepped finely and drawn through {@link #litPath}, so the stem is lit metal rather than a
     * line. Leaves alternate sides deliberately — all on one side reads as a comb.
     *
     * @param amp how far the stem wanders off the straight run
     */
    public static void vine(GuiGraphics g, double x0, double y0, double x1, double y1,
                           double amp, int leaves) {
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 8) return;
        // Unit vectors along the run and across it, so the wander is perpendicular whatever the angle.
        double ux = dx / len, uy = dy / len, px = -uy, py = ux;
        int steps = Math.max(16, (int) (len / 3));
        double[][] stem = new double[steps][2];
        for (int i = 0; i < steps; i++) {
            double f = i / (double) (steps - 1);
            double wobble = Math.sin(f * Math.PI * 3) * amp;
            stem[i][0] = x0 + ux * len * f + px * wobble;
            stem[i][1] = y0 + uy * len * f + py * wobble;
        }
        litPath(g, stem, 2);

        for (int l = 0; l < leaves; l++) {
            double f = (l + 0.7) / (leaves + 0.4);
            int idx = Math.min(steps - 1, (int) (f * (steps - 1)));
            int side = (l % 2 == 0) ? 1 : -1;
            double bearing = Math.atan2(py * side, px * side);
            lobe(g, stem[idx][0], stem[idx][1], amp * 1.7 + 4, bearing, side);
            // Every third node gets a tendril, so the run has some variety along it.
            if (l % 3 == 2) {
                litPath(g, volute(stem[idx][0] + px * side * 4, stem[idx][1] + py * side * 4,
                        amp * 1.1 + 3, bearing, 1.1, side, 16), 1);
            }
        }
    }

    /**
     * A vine framing all four sides of a panel, corners left to the corner flourishes.
     *
     * <p>Runs outside the given rectangle, because a vine over a panel would cross its contents. The
     * inset is the caller's business — pass the outside of the bezel, not the panel itself.
     */
    public static void vineFrame(GuiGraphics g, int x, int y, int w, int h, double amp) {
        int m = 14;   // leave the corners clear for the corner ornament
        vine(g, x + m, y, x + w - m, y, amp, Math.max(2, w / 70));
        vine(g, x + m, y + h, x + w - m, y + h, amp, Math.max(2, w / 70));
        vine(g, x, y + m, x, y + h - m, amp, Math.max(2, h / 60));
        vine(g, x + w, y + m, x + w, y + h - m, amp, Math.max(2, h / 60));
    }

    /** A mirrored pair of corner flourishes, for framing a title. */
    public static void flourishPair(GuiGraphics g, int cx, int y, int gap, int size) {
        flourish(g, cx - gap, y, size, -1, 1);
        flourish(g, cx + gap, y, size, 1, 1);
    }
}
