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

    private MinimapFrame() {}

    // Ring mask: transparent everywhere except a grey ring at the rim.
    private static void ensureMask() {
        if (maskTexture != null) return;
        DynamicTexture tex = new DynamicTexture(ROUND_MASK.toString(), MASK_RES, MASK_RES, true);
        NativeImage img = tex.getPixels();
        float c = (MASK_RES - 1) / 2.0f;
        float rOuter = c;
        float rInner = c - 6.0f;   // matches BEZEL, so both frames read as the same casting
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
    }

    /** Corner radius on the square frame, in pixels. */
    public static final int CORNER_R = 10;
    /**
     * Bezel thickness. Solid brass, so this is a real width rather than a line weight.
     *
     * <p>Widened from 4: at that width the bezel could not carry anything — every fastener or scroll
     * put on it overhung onto the map. A wider band reads as cast brass on its own and leaves room
     * for the cardinal studs to sit in the metal rather than on the terrain.
     */
    public static final int BEZEL = 7;

    /**
     * Behind the map: the dark case the map is inlaid into.
     *
     * <p>Split from {@link #drawSquareOverlay} because the map texture is blitted between the two.
     * Anything that has to sit ON the map — the rounded corners, the bezel, the fasteners — cannot be
     * drawn here or the map would cover it.
     */
    public static void drawSquareFrame(GuiGraphics g, int x, int y, int size) {
        // Rounded, so the case behind does not poke square corners past the rounded bezel.
        SteamTheme.roundRect(g, x - BEZEL, y - BEZEL, size + BEZEL * 2, size + BEZEL * 2,
                CORNER_R + BEZEL, BG);
    }

    /**
     * Over the map: rounds the corners off, then frames the result in bevelled brass with rivet runs
     * and corner escutcheons.
     */
    public static void drawSquareOverlay(GuiGraphics g, int x, int y, int size) {
        // The map is square, so the rounding is two steps and BOTH are needed. Cutting the map's
        // corners alone leaves a square brass frame around them, which is why the first attempt did
        // not read as rounded at all: the eye follows the metal, not the terrain.
        //
        // 1. Cut the map's corners back to the case colour.
        SteamTheme.roundCorner(g, x, y, CORNER_R, 1, 1, BG);
        SteamTheme.roundCorner(g, x + size, y, CORNER_R, -1, 1, BG);
        SteamTheme.roundCorner(g, x, y + size, CORNER_R, 1, -1, BG);
        SteamTheme.roundCorner(g, x + size, y + size, CORNER_R, -1, -1, BG);

        // 2. Frame it in a solid brass bezel that is itself radiused, so the metal turns the corner.
        SteamTheme.sunken(g, x, y, size, size, CORNER_R, 1);
        SteamTheme.bezel(g, x - BEZEL, y - BEZEL, size + BEZEL * 2, size + BEZEL * 2,
                BEZEL, CORNER_R + BEZEL);

        // Rivets along the straight runs only — a stud on the curve would sit off the metal.
        int rr = y - BEZEL / 2;
        SteamTheme.rivetRun(g, x + CORNER_R + 4, rr, x + size - CORNER_R - 4, rr, 26);
        SteamTheme.rivetRun(g, x + CORNER_R + 4, y + size + BEZEL / 2,
                x + size - CORNER_R - 4, y + size + BEZEL / 2, 26);
        SteamTheme.rivetRun(g, x - BEZEL / 2, y + CORNER_R + 4,
                x - BEZEL / 2, y + size - CORNER_R - 4, 26);
        SteamTheme.rivetRun(g, x + size + BEZEL / 2, y + CORNER_R + 4,
                x + size + BEZEL / 2, y + size - CORNER_R - 4, 26);

        // No fasteners on the corners. A screw big enough to read as a screw is bigger than this
        // bezel is wide, so it inevitably sat on the map — and the minimap is small enough that every
        // pixel of it is content. The gear pair stays because it is entirely outside the frame.
        SteamTheme.gearCluster(g, x + size + BEZEL + 10, y + size - 2, 6);
    }

    public static void drawRoundBorder(GuiGraphics g, int x, int y, int size) {
        ensureMask();
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
            SteamTheme.cardinalStud(g, p[0], p[1] - 1, 7, north);
            int tw = font.width(letters[i]);
            // Engraved into the stud: dark copy first, then the letter a pixel up and left.
            g.drawString(font, letters[i], p[0] - tw / 2 + 1, p[1] - 3, SteamTheme.COPPER_SHADOW);
            g.drawString(font, letters[i], p[0] - tw / 2, p[1] - 4,
                    north ? 0xFFFFE8D0 : SteamTheme.INK);
        }
    }

    public static void reset() {
        maskTexture = null;
    }
}
