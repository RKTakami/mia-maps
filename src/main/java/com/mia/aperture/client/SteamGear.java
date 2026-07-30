package com.mia.aperture.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A small pair of meshing brass gears, drawn to show that background work is running.
 *
 * <p>Exists because several long jobs in this mod were invisible while they ran — a store transfer,
 * the first orbit frame — and a button that says nothing is indistinguishable from one that did
 * nothing. Text alone did not solve it: "Working..." sitting still looks identical to "Working..."
 * that has hung.
 *
 * <p>Drawn from filled rectangles rather than a texture, so there is no asset to ship and nothing to
 * mismatch across resource packs. Teeth are placed by trig at each frame's angle rather than by
 * rotating the pose, so a single call can spin two gears in opposite directions.
 */
public final class SteamGear {
    private SteamGear() {}

    private static final int TEETH = 8;
    /** One revolution, milliseconds. Slow enough to read as machinery rather than a loading spinner. */
    private static final long PERIOD_MS = 2600;

    // Brass, with a darker tone for the smaller gear so the two read as separate parts.
    public static final int BRASS = 0xFFD9A64A;
    public static final int BRASS_DARK = 0xFF9C6B2F;

    /**
     * Draw the pair centred on {@code (cx, cy)}.
     *
     * @param radius outer radius of the large gear in pixels; 7-9 suits a button row
     */
    public static void draw(GuiGraphics g, int cx, int cy, int radius) {
        double phase = (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS * Math.PI * 2.0;
        // The small gear sits down-right and turns the other way, as a meshed pair must.
        int smallR = Math.max(3, radius - 3);
        int sx = cx + radius + smallR - 2;
        int sy = cy + 2;
        gear(g, sx, sy, smallR, -phase * (radius / (double) smallR), BRASS_DARK);
        gear(g, cx, cy, radius, phase, BRASS);
    }

    /** True while a gear is worth drawing at all — lets callers avoid the work when idle. */
    public static int widthFor(int radius) {
        return radius * 2 + Math.max(3, radius - 3) * 2;
    }

    private static void gear(GuiGraphics g, int cx, int cy, int r, double angle, int color) {
        // Rim: dots around a slightly smaller circle. Enough of them to read as a solid ring at this
        // size, and cheaper than any curve rasterisation.
        int rim = Math.max(2, r - 2);
        int steps = Math.max(12, r * 5);
        for (int i = 0; i < steps; i++) {
            double a = angle + i * (Math.PI * 2 / steps);
            int x = cx + (int) Math.round(Math.cos(a) * rim);
            int y = cy + (int) Math.round(Math.sin(a) * rim);
            g.fill(x, y, x + 1, y + 1, color);
        }
        // Teeth: square studs on the outer radius, which is what makes it a gear and not a wheel.
        for (int i = 0; i < TEETH; i++) {
            double a = angle + i * (Math.PI * 2 / TEETH);
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int y = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
        // Hub and two spokes. The spokes carry the rotation: a plain ring of teeth reads as spinning
        // only ambiguously, whereas a spoke crossing the middle is unmistakable.
        for (int i = 0; i < 2; i++) {
            double a = angle + i * (Math.PI / 2);
            for (int d = -rim + 1; d < rim; d++) {
                int x = cx + (int) Math.round(Math.cos(a) * d);
                int y = cy + (int) Math.round(Math.sin(a) * d);
                g.fill(x, y, x + 1, y + 1, color);
            }
        }
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
    }
}
