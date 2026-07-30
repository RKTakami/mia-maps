package com.mia.aperture.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Somewhere rectangles can be filled: the live screen, or an image being baked into a texture.
 *
 * <p>Everything in {@link SteamTheme} and {@link SteamOrnament} is drawn from filled rectangles, so
 * one method is the whole abstraction. Its reason for existing is that the ornament is <b>static</b> —
 * a vine does not move — yet redrawing it costs thousands of quads every frame. Given a surface, the
 * same drawing code can run once into an image instead, and the frame cost becomes a single blit.
 */
public interface Surface {
    void fill(int x0, int y0, int x1, int y1, int argb);

    /** The live screen. */
    static Surface of(GuiGraphics g) {
        return g::fill;
    }

    /**
     * A surface backed by an image, for baking ornament into a texture.
     *
     * <p>Composites properly rather than overwriting, because the ornament's shadows are translucent
     * and its highlights are laid over its own body — writing raw pixels would turn every shadow into
     * a solid black stripe.
     */
    final class Image implements Surface {
        private final NativeImage img;
        private final int ox, oy;

        /** @param ox,oy where the drawing's origin sits in the image */
        public Image(NativeImage img, int ox, int oy) {
            this.img = img;
            this.ox = ox;
            this.oy = oy;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            int sa = argb >>> 24;
            if (sa == 0 || x1 <= x0 || y1 <= y0) return;
            // Clamp rather than skip: ornament legitimately runs off the edge of its own texture, and
            // a per-pixel bounds test inside the loop is the cost of not having to trust the caller.
            int lx0 = Math.max(0, x0 + ox), ly0 = Math.max(0, y0 + oy);
            int lx1 = Math.min(img.getWidth(), x1 + ox), ly1 = Math.min(img.getHeight(), y1 + oy);
            for (int y = ly0; y < ly1; y++) {
                for (int x = lx0; x < lx1; x++) {
                    img.setPixel(x, y, sa == 255 ? argb : over(argb, img.getPixel(x, y)));
                }
            }
        }

        /** Source-over, in ARGB — which is what NativeImage.setPixel takes (setPixelABGR is the other).
         *  Package-private so the arithmetic can be tested without a GPU: getting it wrong turns every
         *  translucent shadow into a black smear, which is not a subtle failure but is an easy one. */
        static int over(int src, int dst) {
            int sa = src >>> 24, da = dst >>> 24;
            if (da == 0) return src;
            int oa = sa + da * (255 - sa) / 255;
            if (oa == 0) return 0;
            int r = comp(src, 16, sa, dst, da, oa);
            int gg = comp(src, 8, sa, dst, da, oa);
            int b = comp(src, 0, sa, dst, da, oa);
            return (oa << 24) | (r << 16) | (gg << 8) | b;
        }

        static int comp(int src, int shift, int sa, int dst, int da, int oa) {
            int s = (src >> shift) & 0xFF, d = (dst >> shift) & 0xFF;
            return Math.min(255, (s * sa + d * da * (255 - sa) / 255) / oa);
        }
    }
}
