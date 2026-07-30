package com.mia.aperture.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Victorian steam-era chrome: bevelled brass, rivet runs, slotted screws and corner escutcheons.
 *
 * <p>Defined once here rather than scattered through the screens, so the look stays consistent and a
 * change of palette is a change in one file. Drawn entirely from filled rectangles — no textures, so
 * there is no asset to ship and nothing for a resource pack to override.
 *
 * <p><b>What makes it read as metal.</b> A single outline reads as a drawn line; what reads as a
 * raised object is a <i>lit</i> one. So every frame here is built from bands with a consistent light
 * source at the top-left: highlight along the top and left edges, mid-tone across the body, shadow
 * along the bottom and right. Reversing that reads as sunken, which is what recessed readouts use.
 * The map itself is set into a reversed bevel, so it looks inlaid rather than pasted on.
 *
 * <p><b>Legibility comes first.</b> Every panel lays a dark, mostly-opaque backing under its border
 * before anything else, because the status text on top has to stay readable over whatever the map is
 * drawing. Ornament that made the numbers harder to read would be a bad trade — the map is an
 * instrument before it is a decoration.
 */
public final class SteamTheme {
    private SteamTheme() {}

    // Aged brass, five tones. Three is the minimum for a bevel; five lets a thick frame graduate
    // across its width instead of banding visibly.
    public static final int BRASS_HI = 0xFFF4DCA0;
    public static final int BRASS_LIGHT = 0xFFE8C87A;
    public static final int BRASS = 0xFFC49A48;
    public static final int BRASS_MID = 0xFF9C7734;
    public static final int BRASS_DARK = 0xFF6B4E20;
    public static final int BRASS_SHADOW = 0xFF3E2C11;
    /** Verdigris, for accents so the brass does not look freshly polished. */
    public static final int PATINA = 0xFF4E7A6A;
    /** Instrument-panel backing: near-black with a warm cast, opaque enough to read text over. */
    public static final int PANEL = 0xEE1A1410;
    public static final int PANEL_INSET = 0xEE241C16;
    /** Lamp-lit label text. */
    public static final int INK = 0xFFF2E2C0;
    public static final int INK_DIM = 0xFFB49A6E;

    // ---- edges ---------------------------------------------------------------------------------

    /**
     * A raised brass frame of the given thickness around a rectangle.
     *
     * <p>Bands are drawn outermost first and graduate inward, with the top and left edges taking the
     * lighter tones and the bottom and right the darker. That single consistent light direction is
     * what makes a flat rectangle look like a moulding.
     */
    public static void raised(GuiGraphics g, int x, int y, int w, int h, int thickness) {
        int[] top = {BRASS_SHADOW, BRASS_LIGHT, BRASS_HI, BRASS, BRASS_MID};
        int[] bottom = {BRASS_SHADOW, BRASS_MID, BRASS_DARK, BRASS, BRASS_LIGHT};
        for (int i = 0; i < thickness; i++) {
            int x0 = x - thickness + i, y0 = y - thickness + i;
            int x1 = x + w + thickness - i, y1 = y + h + thickness - i;
            int lit = top[Math.min(i, top.length - 1)];
            int shade = bottom[Math.min(i, bottom.length - 1)];
            g.fill(x0, y0, x1, y0 + 1, lit);            // top
            g.fill(x0, y0, x0 + 1, y1, lit);            // left
            g.fill(x0, y1 - 1, x1, y1, shade);          // bottom
            g.fill(x1 - 1, y0, x1, y1, shade);          // right
        }
    }

    /** The reverse: a sunken rim, so whatever sits inside looks inlaid rather than laid on top. */
    public static void sunken(GuiGraphics g, int x, int y, int w, int h, int thickness) {
        for (int i = 0; i < thickness; i++) {
            int x0 = x + i, y0 = y + i, x1 = x + w - i, y1 = y + h - i;
            g.fill(x0, y0, x1, y0 + 1, BRASS_SHADOW);
            g.fill(x0, y0, x0 + 1, y1, BRASS_SHADOW);
            g.fill(x0, y1 - 1, x1, y1, BRASS_HI);
            g.fill(x1 - 1, y0, x1, y1, BRASS_HI);
        }
    }

    // ---- fasteners -----------------------------------------------------------------------------

    /** A domed rivet: shadow seat, brass dome, highlight where the light catches the top-left. */
    public static void rivet(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, BRASS_SHADOW);
        g.fill(cx - 2, cy - 2, cx + 1, cy + 1, BRASS_MID);
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, BRASS);
        g.fill(cx - 1, cy - 1, cx, cy, BRASS_HI);
    }

    /** A slotted screw head — the slot is what distinguishes it from a rivet at this size. */
    public static void screw(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 3, cy - 3, cx + 3, cy + 3, BRASS_SHADOW);
        g.fill(cx - 2, cy - 2, cx + 2, cy + 2, BRASS);
        g.fill(cx - 2, cy - 2, cx, cy, BRASS_HI);
        g.fill(cx - 2, cy, cx + 2, cy + 1, BRASS_SHADOW);   // the slot
    }

    /** Evenly spaced rivets along a horizontal or vertical run, inset from both ends. */
    public static void rivetRun(GuiGraphics g, int x0, int y0, int x1, int y1, int spacing) {
        int dx = x1 - x0, dy = y1 - y0;
        int len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len < spacing * 2) return;
        int n = len / spacing;
        for (int i = 1; i < n; i++) {
            rivet(g, x0 + dx * i / n, y0 + dy * i / n);
        }
    }

    // ---- ornament ------------------------------------------------------------------------------

    /**
     * A corner escutcheon: a small brass plate with a screw, of the kind that would hold a real
     * bezel to its case. Drawn per corner via the sign arguments.
     */
    public static void escutcheon(GuiGraphics g, int cx, int cy, int size, int sx, int sy) {
        int x0 = sx > 0 ? cx : cx - size, y0 = sy > 0 ? cy : cy - size;
        g.fill(x0, y0, x0 + size, y0 + size, BRASS_MID);
        raised(g, x0 + 1, y0 + 1, size - 2, size - 2, 1);
        // A diagonal taper on the inner corner, so the plate reads as chamfered rather than square.
        for (int i = 0; i < size / 2; i++) {
            int px = sx > 0 ? x0 + size - 1 - i : x0 + i;
            int py = sy > 0 ? y0 + size - 1 : y0;
            g.fill(px, py - (sy > 0 ? i : -i), px + 1, py - (sy > 0 ? i : -i) + 1, BRASS_SHADOW);
        }
        screw(g, x0 + size / 2, y0 + size / 2);
    }

    /**
     * Corner brackets rather than a full frame, for large views where a continuous border would eat
     * the edges of the content.
     */
    public static void corners(GuiGraphics g, int x, int y, int w, int h, int len) {
        int[][] cs = {{x, y, 1, 1}, {x + w, y, -1, 1}, {x, y + h, 1, -1}, {x + w, y + h, -1, -1}};
        for (int[] c : cs) {
            int cx = c[0], cy = c[1], sx = c[2], sy = c[3];
            int ax = sx > 0 ? cx : cx - len, ay = sy > 0 ? cy : cy - 3;
            g.fill(ax, ay, ax + len, ay + 3, BRASS);
            g.fill(ax, sy > 0 ? ay : ay + 2, ax + len, (sy > 0 ? ay : ay + 2) + 1, BRASS_HI);
            int bx = sx > 0 ? cx : cx - 3, by = sy > 0 ? cy : cy - len;
            g.fill(bx, by, bx + 3, by + len, BRASS);
            g.fill(sx > 0 ? bx : bx + 2, by, (sx > 0 ? bx : bx + 2) + 1, by + len, BRASS_HI);
            screw(g, cx + (sx > 0 ? 5 : -6), cy + (sy > 0 ? 5 : -6));
        }
    }

    /**
     * Fill the area outside a corner radius, rounding a square frame.
     *
     * <p>Filled as one span per row rather than pixel by pixel: the minimap redraws every frame, and
     * a radius of 8 is 256 rectangles per corner done naively against 8 done this way.
     */
    public static void roundCorner(GuiGraphics g, int cx, int cy, int r, int sx, int sy, int color) {
        for (int i = 0; i < r; i++) {
            // Distance from the arc centre for this row; anything beyond it is outside the radius.
            double dy = r - i - 0.5;
            int keep = (int) Math.floor(Math.sqrt(Math.max(0, r * r - dy * dy)));
            int cut = r - keep;
            if (cut <= 0) continue;
            int y = sy > 0 ? cy + i : cy - i - 1;
            int x0 = sx > 0 ? cx : cx - cut;
            g.fill(x0, y, x0 + cut, y + 1, color);
        }
    }

    // ---- panels --------------------------------------------------------------------------------

    /** A dark instrument panel in a raised brass case, with rivets along its edges. */
    public static int panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        raised(g, x, y, w, h, 2);
        sunken(g, x, y, w, h, 1);
        rivetRun(g, x + 6, y - 3, x + w - 6, y - 3, 28);
        rivetRun(g, x + 6, y + h + 2, x + w - 6, y + h + 2, 28);
        return x + 5;
    }

    /** A recessed reading, for numbers that should look set into the panel. */
    public static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_INSET);
        sunken(g, x, y, w, h, 1);
    }

    /** An engraved brass nameplate: the label drawn dark then light, one pixel apart. */
    public static void nameplate(GuiGraphics g, net.minecraft.client.gui.Font font, String label,
                                 int cx, int y) {
        int w = font.width(label) + 28;
        int x = cx - w / 2;
        g.fill(x, y - 4, x + w, y + 13, BRASS_MID);
        raised(g, x + 2, y - 2, w - 4, 13, 2);
        g.fill(x + 4, y - 1, x + w - 4, y + 11, PANEL);
        screw(g, x + 7, y + 5);
        screw(g, x + w - 8, y + 5);
        int tx = cx - font.width(label) / 2;
        g.drawString(font, label, tx + 1, y + 2, 0xFF2A1E0C);
        g.drawString(font, label, tx, y + 1, BRASS_HI);
    }

    /** A status row in its own small housing. Returns the height consumed. */
    public static int readout(GuiGraphics g, net.minecraft.client.gui.Font font, String text,
                              int x, int y, int color) {
        int w = font.width(text) + 14;
        g.fill(x, y - 3, x + w, y + 12, PANEL);
        raised(g, x + 1, y - 2, w - 2, 13, 1);
        // A brass tab on the left edge, like a labelled terminal on a instrument case.
        g.fill(x + 1, y - 1, x + 4, y + 11, BRASS_MID);
        g.fill(x + 1, y - 1, x + 2, y + 11, BRASS_HI);
        rivet(g, x + 2, y + 5);
        g.drawString(font, text, x + 8, y + 1, color);
        return 16;
    }
}
