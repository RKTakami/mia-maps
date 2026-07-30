package com.mia.aperture.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class MinimapFrame {
    public static final Identifier ROUND_MASK = Identifier.fromNamespaceAndPath("mia_aperture_mod", "round_mask");
    private static final int MASK_RES = 256;
    private static final int BG = 0xFF111111;
    // The round mask is a texture, so it takes one flat colour. Brass rather than grey, to match the
    // drawn bezel on the square frame — a grey ring beside a brass frame reads as two different mods.
    private static final int BORDER = com.mia.aperture.client.SteamTheme.BRASS;
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

    public static void drawSquareFrame(GuiGraphics g, int x, int y, int size) {
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, BG);
        // Riveted brass bezel. Sits just outside the map area so it frames without covering terrain.
        com.mia.aperture.client.SteamTheme.bezel(g, x, y, size, size, 1);
    }

    public static void drawRoundBorder(GuiGraphics g, int x, int y, int size) {
        ensureMask();
        g.blit(ROUND_MASK, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
        // Four rivets on the ring, at the diagonals rather than the cardinals — the cardinal
        // positions already carry the N/E/S/W letters and a stud under a letter reads as a smudge.
        int c = size / 2;
        double r = c - 1;
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            com.mia.aperture.client.SteamTheme.rivet(g,
                    x + c + (int) Math.round(Math.cos(a) * r),
                    y + c + (int) Math.round(Math.sin(a) * r));
        }
    }

    public static void drawCardinals(GuiGraphics g, int cx, int cy, int radius,
                                     MapSettings.Orientation orientation, float yaw) {
        Font font = Minecraft.getInstance().font;
        String[] letters = {"N", "E", "S", "W"};
        // North stays red; the rest are brass rather than white, which sits better against the bezel.
        int[] colors = {0xFFFF5555,
                com.mia.aperture.client.SteamTheme.INK,
                com.mia.aperture.client.SteamTheme.INK,
                com.mia.aperture.client.SteamTheme.INK};
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
