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
    private static final int BORDER = 0xFF888888;
    private static DynamicTexture maskTexture;

    private MinimapFrame() {}

    // Radial mask: transparent inside the circle, opaque dark outside, grey ring at the rim.
    private static void ensureMask() {
        if (maskTexture != null) return;
        DynamicTexture tex = new DynamicTexture(ROUND_MASK.toString(), MASK_RES, MASK_RES, true);
        NativeImage img = tex.getPixels();
        float c = (MASK_RES - 1) / 2.0f;
        float rInner = c;
        float rBorder = c - 2.0f;
        for (int y = 0; y < MASK_RES; y++) {
            for (int x = 0; x < MASK_RES; x++) {
                float dx = x - c, dy = y - c;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                int argb;
                if (d <= rBorder) argb = 0x00000000;
                else if (d <= rInner) argb = BORDER;
                else argb = BG;
                img.setPixel(x, y, argb);
            }
        }
        tex.upload();
        Minecraft.getInstance().getTextureManager().register(ROUND_MASK, tex);
        maskTexture = tex;
    }

    public static void drawSquareFrame(GuiGraphics g, int x, int y, int size) {
        g.fill(x - 2, y - 2, x + size + 2, y + size + 2, BG);
        g.renderOutline(x - 1, y - 1, size + 2, size + 2, BORDER);
    }

    public static void drawRoundMask(GuiGraphics g, int x, int y, int size) {
        ensureMask();
        g.blit(ROUND_MASK, x, y, x + size, y + size, 0.0f, 1.0f, 0.0f, 1.0f);
    }

    public static void drawCardinals(GuiGraphics g, int cx, int cy, int radius,
                                     MapSettings.Orientation orientation, float yaw) {
        Font font = Minecraft.getInstance().font;
        String[] letters = {"N", "E", "S", "W"};
        int[] colors = {0xFFFF5555, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        for (int i = 0; i < 4; i++) {
            int[] p = MinimapMarkers.cardinalPos(cx, cy, radius, orientation, yaw, i);
            int tw = font.width(letters[i]);
            g.drawString(font, letters[i], p[0] - tw / 2, p[1] - 4, colors[i]);
        }
    }

    public static void reset() {
        maskTexture = null;
    }
}
