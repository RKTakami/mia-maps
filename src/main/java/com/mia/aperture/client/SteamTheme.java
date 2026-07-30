package com.mia.aperture.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Victorian steam-era chrome: brass frames, riveted borders and dark instrument panels.
 *
 * <p>Defined once here rather than scattered through the screens, so the look stays consistent and a
 * change of palette is a change in one file. Drawn entirely from filled rectangles — no textures, so
 * there is no asset to ship and nothing for a resource pack to override.
 *
 * <p><b>Legibility comes first.</b> Every panel lays a dark, mostly-opaque backing under its border
 * before anything else, because the status text on top of it has to stay readable over whatever the
 * map is drawing. Ornament that makes the numbers harder to read would be a bad trade, and the map
 * is an instrument before it is a decoration.
 */
public final class SteamTheme {
    private SteamTheme() {}

    // Aged brass, light to dark. Three tones is enough for a bevel: light on the top-left edge, mid
    // for the body, dark on the bottom-right, which is what reads as raised metal.
    public static final int BRASS_LIGHT = 0xFFE8C87A;
    public static final int BRASS = 0xFFC49A48;
    public static final int BRASS_DARK = 0xFF7A5A26;
    /** Verdigris, for the occasional accent so the brass does not look freshly polished. */
    public static final int PATINA = 0xFF4E7A6A;
    /** Instrument-panel backing: near-black with a warm cast, opaque enough to read text over. */
    public static final int PANEL = 0xE81A1410;
    /** Slightly lighter inlay, for a recessed area inside a panel. */
    public static final int PANEL_INSET = 0xE8241C16;
    /** Lamp-lit label text. */
    public static final int INK = 0xFFF2E2C0;
    public static final int INK_DIM = 0xFFB49A6E;

    /**
     * A raised brass bezel around a rectangle, with rivets at the corners.
     *
     * @param inset how far the bezel sits outside the given rectangle
     */
    public static void bezel(GuiGraphics g, int x, int y, int w, int h, int inset) {
        int x0 = x - inset, y0 = y - inset, x1 = x + w + inset, y1 = y + h + inset;
        // Outer dark edge, then the brass body, then a light inner line. Drawing three concentric
        // outlines is what gives the illusion of thickness without a texture.
        g.renderOutline(x0 - 1, y0 - 1, (x1 - x0) + 2, (y1 - y0) + 2, BRASS_DARK);
        g.renderOutline(x0, y0, x1 - x0, y1 - y0, BRASS);
        g.renderOutline(x0 + 1, y0 + 1, (x1 - x0) - 2, (y1 - y0) - 2, BRASS_LIGHT);
        rivet(g, x0 + 2, y0 + 2);
        rivet(g, x1 - 3, y0 + 2);
        rivet(g, x0 + 2, y1 - 3);
        rivet(g, x1 - 3, y1 - 3);
    }

    /** A single stud: dark seat, brass head, one light pixel where the light catches it. */
    public static void rivet(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, BRASS_DARK);
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, BRASS);
        g.fill(cx - 1, cy - 1, cx, cy, BRASS_LIGHT);
    }

    /**
     * A dark instrument panel with a brass bezel, sized to hold text.
     *
     * <p>Returns the x offset where content should start, so callers do not have to know the padding.
     */
    public static int panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        bezel(g, x, y, w, h, 0);
        return x + 4;
    }

    /** A recessed reading, for numbers that should look set into the panel. */
    public static void inset(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_INSET);
        // Reversed bevel — dark on top-left, light on bottom-right — which is what reads as sunken.
        g.fill(x, y, x + w, y + 1, BRASS_DARK);
        g.fill(x, y, x + 1, y + h, BRASS_DARK);
        g.fill(x, y + h - 1, x + w, y + h, BRASS_LIGHT);
        g.fill(x + w - 1, y, x + w, y + h, BRASS_LIGHT);
    }

    /**
     * Corner brackets rather than a full frame, for cases where a continuous border would crowd the
     * content — a large map view, for instance, where four brackets say "instrument" and a full
     * bezel would just eat the edges.
     */
    public static void corners(GuiGraphics g, int x, int y, int w, int h, int len) {
        int[][] cs = {{x, y, 1, 1}, {x + w, y, -1, 1}, {x, y + h, 1, -1}, {x + w, y + h, -1, -1}};
        for (int[] c : cs) {
            int cx = c[0], cy = c[1], sx = c[2], sy = c[3];
            for (int i = 0; i < len; i++) {
                g.fill(cx + (sx > 0 ? i : -i - 1), cy + (sy > 0 ? 0 : -1),
                        cx + (sx > 0 ? i + 1 : -i), cy + (sy > 0 ? 1 : 0), BRASS);
                g.fill(cx + (sx > 0 ? 0 : -1), cy + (sy > 0 ? i : -i - 1),
                        cx + (sx > 0 ? 1 : 0), cy + (sy > 0 ? i + 1 : -i), BRASS);
            }
            rivet(g, cx + (sx > 0 ? 3 : -4), cy + (sy > 0 ? 3 : -4));
        }
    }

    /**
     * A titled brass nameplate, engraved-looking: the label is drawn twice, dark then light, one
     * pixel apart.
     */
    public static void nameplate(GuiGraphics g, net.minecraft.client.gui.Font font, String label,
                                 int cx, int y) {
        int w = font.width(label) + 16;
        int x = cx - w / 2;
        g.fill(x, y - 3, x + w, y + 12, PANEL);
        g.fill(x, y - 3, x + w, y - 2, BRASS);
        g.fill(x, y + 11, x + w, y + 12, BRASS_DARK);
        rivet(g, x + 4, y + 4);
        rivet(g, x + w - 5, y + 4);
        int tx = cx - font.width(label) / 2;
        g.drawString(font, label, tx + 1, y + 1, 0xFF3A2A12);   // engraved shadow
        g.drawString(font, label, tx, y, BRASS_LIGHT);
    }

    /** A row of text on its own small panel, for a status readout. Returns the height consumed. */
    public static int readout(GuiGraphics g, net.minecraft.client.gui.Font font, String text,
                              int x, int y, int color) {
        int w = font.width(text) + 8;
        g.fill(x, y - 2, x + w, y + 11, PANEL);
        g.fill(x, y - 2, x + w, y - 1, BRASS_DARK);
        g.fill(x, y + 10, x + w, y + 11, BRASS_DARK);
        g.fill(x, y - 2, x + 1, y + 11, BRASS_DARK);
        g.fill(x + w - 1, y - 2, x + w, y + 11, BRASS_DARK);
        g.drawString(font, text, x + 4, y, color);
        return 13;
    }
}
