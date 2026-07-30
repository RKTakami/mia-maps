package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.mia.aperture.client.SteamTheme;

public final class MinimapFrame {
    public static final Identifier ROUND_MASK = Identifier.fromNamespaceAndPath("mia_aperture_mod", "round_mask");
    private static final int MASK_RES = 256;
    private static final int BG = 0xFF111111;
    // The round mask is a texture, so it takes one flat colour. Brass rather than grey, to match the
    // drawn bezel on the square frame — a grey ring beside a brass frame reads as two different mods.
    private static final int BORDER = SteamTheme.BRASS;
    private static DynamicTexture maskTexture;
    /** Ring width the current mask was baked at, so a style change rebuilds it and nothing else does. */
    private static int maskWidth = -1;

    private MinimapFrame() {}

    /**
     * Ring mask: transparent everywhere except a brass ring at the rim.
     *
     * <p>Baked once into a texture rather than drawn per frame, so it has to be rebuilt when the bezel
     * width changes. Keyed on the width itself rather than on the style, because the width is what the
     * texture actually depends on — if a third style were ever added at the same width, this would
     * correctly not rebuild.
     */
    private static void ensureMask(int width) {
        if (maskTexture != null && maskWidth == width) return;
        DynamicTexture tex = new DynamicTexture(ROUND_MASK.toString(), MASK_RES, MASK_RES, true);
        NativeImage img = tex.getPixels();
        float c = (MASK_RES - 1) / 2.0f;
        float rOuter = c;
        // The mask is drawn at MASK_RES and then scaled to the widget, so the ring has to be specified
        // as a fraction of the radius — a fixed pixel count here would come out a different width on
        // every minimap size.
        float rInner = c - Math.max(1.5f, c * width / 110f);
        for (int y = 0; y < MASK_RES; y++) {
            for (int x = 0; x < MASK_RES; x++) {
                float dx = x - c, dy = y - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                img.setPixel(x, y, (d <= rOuter && d >= rInner) ? BORDER : 0x00000000);
            }
        }
        tex.upload();
        Minecraft.getInstance().getTextureManager().register(ROUND_MASK, tex);
        maskTexture = tex;
        maskWidth = width;
    }

    /** Corner radius on the square frame, in pixels. */
    public static final int CORNER_R = 10;

    /**
     * Bezel thickness, from the selected style.
     *
     * <p>Solid is cast brass wide enough to carry the cardinal studs; wire is a thin bent rim. Read
     * per call rather than cached, because the setting can change while the minimap is on screen.
     */
    public static int bezel() {
        MapSettings s = com.mia.aperture.client.MiaApertureModClient.mapSettings;
        return s == null || s.bezelStyle == null ? MapSettings.BezelStyle.SOLID.width
                : s.bezelStyle.width;
    }

    private static boolean wire() {
        MapSettings s = com.mia.aperture.client.MiaApertureModClient.mapSettings;
        return s != null && s.bezelStyle == MapSettings.BezelStyle.WIRE;
    }

    /**
     * Behind the map: the dark case the map is inlaid into.
     *
     * <p>Split from {@link #drawSquareOverlay} because the map texture is blitted between the two.
     * Anything that has to sit ON the map — the rounded corners, the bezel, the fasteners — cannot be
     * drawn here or the map would cover it.
     */
    public static void drawSquareFrame(GuiGraphics g, int x, int y, int size) {
        int b = bezel();
        // Rounded, so the case behind does not poke square corners past the rounded bezel.
        SteamTheme.roundRect(g, x - b, y - b, size + b * 2, size + b * 2, CORNER_R + b, BG);
    }

    /**
     * Over the map: rounds the corners off, then frames the result in bevelled brass with rivet runs
     * and corner escutcheons.
     */
    public static void drawSquareOverlay(GuiGraphics g, int x, int y, int size) {
        int b = bezel();
        // The map is square, so the rounding is two steps and BOTH are needed. Cutting the map's
        // corners alone leaves a square brass frame around them, which is why the first attempt did
        // not read as rounded at all: the eye follows the metal, not the terrain.
        //
        // 1. Cut the map's corners back to the case colour.
        SteamTheme.roundCorner(g, x, y, CORNER_R, 1, 1, BG);
        SteamTheme.roundCorner(g, x + size, y, CORNER_R, -1, 1, BG);
        SteamTheme.roundCorner(g, x, y + size, CORNER_R, 1, -1, BG);
        SteamTheme.roundCorner(g, x + size, y + size, CORNER_R, -1, -1, BG);

        // 2. Frame it.
        SteamTheme.sunken(g, x, y, size, size, CORNER_R, 1);
        if (wire()) {
            // A round wire bent to shape: a dark under-edge and a lit top edge, one pixel apart. That
            // pair is the whole illusion — a single brass line at this width reads as a border, and
            // the bevel used for the solid style has nowhere to graduate across two pixels.
            SteamTheme.roundRing(g, x - b - 1, y - b - 1, size + b * 2 + 2, size + b * 2 + 2,
                    CORNER_R + b + 1, 1, SteamTheme.BRASS_SHADOW, SteamTheme.BRASS_SHADOW);
            SteamTheme.roundRing(g, x - b, y - b, size + b * 2, size + b * 2,
                    CORNER_R + b, b, SteamTheme.BRASS_HI, SteamTheme.BRASS_MID);
        } else {
            SteamTheme.bezel(g, x - b, y - b, size + b * 2, size + b * 2, b, CORNER_R + b);

            // Rivets along the straight runs only — a stud on the curve would sit off the metal. The
            // wire frame gets none: there is no flange for a rivet to be driven through.
            int rr = y - b / 2;
            SteamTheme.rivetRun(g, x + CORNER_R + 4, rr, x + size - CORNER_R - 4, rr, 26);
            SteamTheme.rivetRun(g, x + CORNER_R + 4, y + size + b / 2,
                    x + size - CORNER_R - 4, y + size + b / 2, 26);
            SteamTheme.rivetRun(g, x - b / 2, y + CORNER_R + 4,
                    x - b / 2, y + size - CORNER_R - 4, 26);
            SteamTheme.rivetRun(g, x + size + b / 2, y + CORNER_R + 4,
                    x + size + b / 2, y + size - CORNER_R - 4, 26);
        }

        // No fasteners on the corners. A screw big enough to read as a screw is bigger than this
        // bezel is wide, so it inevitably sat on the map — and the minimap is small enough that every
        // pixel of it is content. The gear pair stays because it is entirely outside the frame.
        SteamTheme.gearCluster(g, x + size + b + 10, y + size - 2, 6);
    }

    public static void drawRoundBorder(GuiGraphics g, int x, int y, int size) {
        ensureMask(bezel());
        g.blit(ROUND_MASK, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
        // No applied ornament on the rim. Foliage at this scale has to be wound so tightly to fit
        // that it reads as a spring rather than as a vine — the minimap is simply too small a canvas
        // for it. The band itself does the work now, and the cardinal studs are the only thing on it.
    }

    public static void drawCardinals(GuiGraphics g, int cx, int cy, int radius,
                                     MapSettings.Orientation orientation, float yaw) {
        Font font = Minecraft.getInstance().font;
        String[] letters = {"N", "E", "S", "W"};
        for (int i = 0; i < 4; i++) {
            int[] p = MinimapMarkers.cardinalPos(cx, cy, radius, orientation, yaw, i);
            boolean north = i == 0;
            // A copper stud set into the brass, casting onto it. Two metals reads as an instrument
            // detail, and the stud doubles as a backing plate: the letters used to sit on bare
            // terrain and were unreadable over anything pale, which the offset dark copy only
            // half-solved.
            // Sized to the bezel: a stud wider than the frame overhangs onto the map, which is the
            // mistake the corner screws made. On the wire frame it is a small bead on the rim.
            SteamTheme.cardinalStud(g, p[0], p[1] - 1, wire() ? 4 : 7, north);
            int tw = font.width(letters[i]);
            // Engraved into the stud: dark copy first, then the letter a pixel up and left.
            g.drawString(font, letters[i], p[0] - tw / 2 + 1, p[1] - 3, SteamTheme.COPPER_SHADOW);
            g.drawString(font, letters[i], p[0] - tw / 2, p[1] - 4,
                    north ? 0xFFFFE8D0 : SteamTheme.INK);
        }
    }

    public static void reset() {
        maskTexture = null;
        maskWidth = -1;
    }
}
