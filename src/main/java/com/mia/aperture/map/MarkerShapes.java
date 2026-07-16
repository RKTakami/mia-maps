package com.mia.aperture.map;

import net.minecraft.client.gui.GuiGraphics;

// Screen-space marker shapes shared by every route/breadcrumb renderer. GuiGraphics only draws
// axis-aligned rectangles, so a "sphere" is a filled disc with a dark rim and an offset highlight
// to give it volume. Discs rasterise as one fill per row -> spans never overlap within a disc, so
// this stays clear of the overlapping-quad GuiRenderState.traverse crash.
public final class MarkerShapes {
    private MarkerShapes() {}

    // Half-width in pixels of the filled disc row at vertical offset dy from centre, for radius r,
    // or -1 when the row lies outside the disc. Pure -> unit-tested.
    public static int rowHalfWidth(int r, int dy) {
        if (r <= 0) return dy == 0 ? 0 : -1;
        if (dy < -r || dy > r) return -1;
        return (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
    }

    // Filled disc, one horizontal span per row.
    public static void disc(GuiGraphics g, int cx, int cy, int r, int argb) {
        for (int dy = -r; dy <= r; dy++) {
            int hw = rowHalfWidth(r, dy);
            if (hw < 0) continue;
            g.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1, argb);
        }
    }

    // A disc that reads as a little sphere: a darker rim one pixel proud, the body, and a brighter
    // highlight offset toward the top-left. Rim and highlight derive from the body so a translucent
    // (occluded) marker stays translucent throughout.
    public static void sphere(GuiGraphics g, int cx, int cy, int r, int bodyArgb) {
        disc(g, cx, cy, r + 1, ColorMath.shade(bodyArgb, 0.35f));
        disc(g, cx, cy, r, bodyArgb);
        if (r >= 2) {
            int hr = Math.max(1, r / 3);
            disc(g, cx - hr, cy - hr, hr, ColorMath.shade(bodyArgb, 1.6f));
        }
    }
}
