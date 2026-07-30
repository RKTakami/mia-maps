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
        float rInner = c - 2.0f;
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
    public static final int CORNER_R = 8;

    /**
     * Behind the map: the dark case the map is inlaid into.
     *
     * <p>Split from {@link #drawSquareOverlay} because the map texture is blitted between the two.
     * Anything that has to sit ON the map — the rounded corners, the bezel, the fasteners — cannot be
     * drawn here or the map would cover it.
     */
    public static void drawSquareFrame(GuiGraphics g, int x, int y, int size) {
        g.fill(x - 4, y - 4, x + size + 4, y + size + 4, BG);
    }

    /**
     * Over the map: rounds the corners off, then frames the result in bevelled brass with rivet runs
     * and corner escutcheons.
     */
    public static void drawSquareOverlay(GuiGraphics g, int x, int y, int size) {
        // Round the map's own corners by filling outside the arc with the case colour. Has to happen
        // before the bezel, so the bezel's own corners sit on top of the cut.
        SteamTheme.roundCorner(g, x, y, CORNER_R, 1, 1, BG);
        SteamTheme.roundCorner(g, x + size, y, CORNER_R, -1, 1, BG);
        SteamTheme.roundCorner(g, x, y + size, CORNER_R, 1, -1, BG);
        SteamTheme.roundCorner(g, x + size, y + size, CORNER_R, -1, -1, BG);

        // Sunken rim first, so the map reads as inlaid, then the raised case around it.
        SteamTheme.sunken(g, x, y, size, size, 1);
        SteamTheme.raised(g, x, y, size, size, 3);

        // Rivets along each edge, and a screwed plate at each corner.
        int inset = 4;
        SteamTheme.rivetRun(g, x + CORNER_R, y - inset,
                x + size - CORNER_R, y - inset, 22);
        SteamTheme.rivetRun(g, x + CORNER_R, y + size + inset - 1,
                x + size - CORNER_R, y + size + inset - 1, 22);
        SteamTheme.rivetRun(g, x - inset, y + CORNER_R,
                x - inset, y + size - CORNER_R, 22);
        SteamTheme.rivetRun(g, x + size + inset - 1, y + CORNER_R,
                x + size + inset - 1, y + size - CORNER_R, 22);
        SteamTheme.escutcheon(g, x - 3, y - 3, 9, -1, -1);
        SteamTheme.escutcheon(g, x + size + 3, y - 3, 9, 1, -1);
        SteamTheme.escutcheon(g, x - 3, y + size + 3, 9, -1, 1);
        SteamTheme.escutcheon(g, x + size + 3, y + size + 3, 9, 1, 1);
    }

    public static void drawRoundBorder(GuiGraphics g, int x, int y, int size) {
        ensureMask();
        g.blit(ROUND_MASK, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
        // Four screwed plates on the diagonals, matching the square frame's corners so the two
        // shapes read as the same instrument family rather than two different styles.
        int c0 = size / 2;
        double rr = c0 - 2;
        for (int i = 0; i < 4; i++) {
            double aa = Math.PI / 4 + i * Math.PI / 2;
            SteamTheme.screw(g,
                    x + c0 + (int) Math.round(Math.cos(aa) * rr),
                    y + c0 + (int) Math.round(Math.sin(aa) * rr));
        }
    }

    public static void drawCardinals(GuiGraphics g, int cx, int cy, int radius,
                                     MapSettings.Orientation orientation, float yaw) {
        Font font = Minecraft.getInstance().font;
        String[] letters = {"N", "E", "S", "W"};
        // North stays red; the rest are brass rather than white, which sits better against the bezel.
        int[] colors = {0xFFFF5555,
                SteamTheme.INK,
                SteamTheme.INK,
                SteamTheme.INK};
        for (int i = 0; i < 4; i++) {
            int[] p = MinimapMarkers.cardinalPos(cx, cy, radius, orientation, yaw, i);
            int tw = font.width(letters[i]);
            // Engraved: a dark offset copy under the letter, so it stays legible over bright terrain.
            g.drawString(font, letters[i], p[0] - tw / 2 + 1, p[1] - 3, 0xFF2A1E0C);
            g.drawString(font, letters[i], p[0] - tw / 2, p[1] - 4, colors[i]);
        }
    }

    public static void reset() {
        maskTexture = null;
    }
}
