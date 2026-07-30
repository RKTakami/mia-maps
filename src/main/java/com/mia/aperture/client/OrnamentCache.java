package com.mia.aperture.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * A panel's vine frame and corner flourishes, baked into a texture once and blitted thereafter.
 *
 * <p>The ornament never changes — no part of a vine is a function of time — but drawing it costs
 * thousands of filled rectangles, and it was being redrawn from scratch on every frame. On a screen
 * carrying four vine runs, their leaves and tendrils, and four corner flourishes, that came to well
 * over ten thousand quads a frame, which is what made the animated gearing beside it stutter.
 *
 * <p>So it is drawn once through {@link Surface.Image} and uploaded. A frame now costs one blit. The
 * bake is redone only when the panel's geometry changes, which on these screens means a window
 * resize.
 *
 * <p>One instance per screen, each with its own texture id — a shared id would have the two screens
 * evicting each other's bake every time you moved between them.
 */
public final class OrnamentCache {
    /**
     * How far ornament reaches outside the frame it decorates. A leaf is about {@code amp * 2.6 + 14}
     * long off a stem already {@code amp} off the line, the corner flourishes sweep about twice their
     * size, and everything casts a shadow — 56 covers all of it at the sizes these screens use.
     */
    private static final int PAD = 56;

    private final Identifier id;
    private DynamicTexture texture;
    private int bakedW = -1, bakedH = -1, bakedFlourish = -1;
    private double bakedAmp = -1;
    private int texW, texH;

    public OrnamentCache(String name) {
        this.id = Identifier.fromNamespaceAndPath("mia_aperture_mod", "ornament_" + name);
    }

    /**
     * Draw the ornament for a panel of this geometry, baking it first if the geometry has changed.
     *
     * @param x,y,w,h the rectangle the vine runs around — the outside of the panel's bezel, not the
     *                panel itself
     */
    public void draw(GuiGraphics g, int x, int y, int w, int h, double amp, int flourish) {
        if (w <= 0 || h <= 0) return;
        ensure(w, h, amp, flourish);
        if (texture == null) return;
        g.blit(id, x - PAD, y - PAD, x - PAD + texW, y - PAD + texH, 0.0f, 1.0f, 0.0f, 1.0f);
    }

    private void ensure(int w, int h, double amp, int flourish) {
        if (texture != null && w == bakedW && h == bakedH && flourish == bakedFlourish
                && amp == bakedAmp) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        close();

        texW = w + PAD * 2;
        texH = h + PAD * 2;
        DynamicTexture tex = new DynamicTexture(id.toString(), texW, texH, true);
        NativeImage img = tex.getPixels();
        for (int py = 0; py < texH; py++) {
            for (int px = 0; px < texW; px++) img.setPixel(px, py, 0x00000000);
        }

        // The drawing runs in panel coordinates with the frame's top-left at the origin; the surface
        // shifts it into the padded image. Keeping the ornament code ignorant of the padding is what
        // lets the exact same calls serve both the cached and the live path.
        Surface s = new Surface.Image(img, PAD, PAD);
        SteamOrnament.vineFrame(s, 0, 0, w, h, amp);
        SteamOrnament.flourish(s, 0, 0, flourish, 1, 1);
        SteamOrnament.flourish(s, w, 0, flourish, -1, 1);
        SteamOrnament.flourish(s, 0, h, flourish, 1, -1);
        SteamOrnament.flourish(s, w, h, flourish, -1, -1);

        tex.upload();
        mc.getTextureManager().register(id, tex);
        texture = tex;
        bakedW = w;
        bakedH = h;
        bakedAmp = amp;
        bakedFlourish = flourish;
    }

    /** Release the texture. Called when a screen closes, so a bake does not outlive its screen. */
    public void close() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        bakedW = bakedH = bakedFlourish = -1;
        bakedAmp = -1;
    }
}
