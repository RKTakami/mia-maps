package com.mia.aperture.client;

import com.mia.aperture.map.BeaconGeometry;
import com.mia.aperture.map.MinimapRenderer;
import com.mia.aperture.map.OrbitCamera;
import com.mia.aperture.map.OrbitScene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class OrbitView extends Screen {
    private double yaw = 45, pitch = 30, zoom = 1.0;
    private final Screen parent;

    public OrbitView(Screen parent) {
        super(Component.literal("Abyss 3D"));
        this.parent = parent;
    }

    private OrbitCamera camera() {
        var p = this.minecraft != null ? this.minecraft.player : null;
        double fx = p != null ? p.getX() : 0, fy = p != null ? p.getY() : 0, fz = p != null ? p.getZ() : 0;
        double dist = 160 * zoom;
        return new OrbitCamera(fx, fy, fz, yaw, pitch, dist);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0B0B10);
        if (this.minecraft != null && this.minecraft.player != null) {
            OrbitScene.render(camera(), zoom);
            int s = Math.min(this.width, this.height);
            int x0 = (this.width - s) / 2, y0 = (this.height - s) / 2;
            guiGraphics.blit(OrbitScene.TEXTURE, x0, y0, s, s, 0.0f, 1.0f, 0.0f, 1.0f);

            double dist = 160 * zoom, d = dist * 0.4; // markers ride a fixed screen ring
            drawCardinal(guiGraphics, "N", 0, 0, -d, dist, s, x0, y0, 0xFFFF5555);
            drawCardinal(guiGraphics, "S", 0, 0, d, dist, s, x0, y0, 0xFFFFFFFF);
            drawCardinal(guiGraphics, "E", d, 0, 0, dist, s, x0, y0, 0xFFFFFFFF);
            drawCardinal(guiGraphics, "W", -d, 0, 0, dist, s, x0, y0, 0xFFFFFFFF);

            int cx = x0 + s / 2, cy = y0 + s / 2; // player is the orbit focus -> screen centre
            double yr = Math.toRadians(this.minecraft.player.getYRot());
            BeaconGeometry.Screen f = OrbitScene.projectOffset(yaw, pitch, dist,
                    -Math.sin(yr) * 10, 0, Math.cos(yr) * 10, s);
            double fdx = f.x() - s / 2.0, fdy = f.y() - s / 2.0;
            if (fdx * fdx + fdy * fdy > 0.25) {
                drawFacingArrow(guiGraphics, cx, cy, (float) Math.atan2(fdy, fdx));
            }
            diamond(guiGraphics, cx, cy, 5, 0xFF000000);
            diamond(guiGraphics, cx, cy, 4, MinimapRenderer.PLAYER_COLOR);
        }
        guiGraphics.drawString(this.font, "Abyss 3D  —  drag: orbit   scroll: zoom   Esc: close", 8, 8, 0xFFFFFFFF);
        guiGraphics.drawString(this.font,
                String.format("yaw %.0f  pitch %.0f  zoom %.2fx  points %d",
                        yaw, pitch, zoom, OrbitScene.lastCloudSize()), 8, 20, 0xFFAAAAAA);
    }

    private void drawFacingArrow(GuiGraphics g, int cx, int cy, float ang) {
        g.pose().pushMatrix();
        g.pose().translate(cx + 0.5f, cy + 0.5f);
        g.pose().rotate(ang);
        int outline = 0xFF000000, c = MinimapRenderer.PLAYER_COLOR;
        g.fill(3, -2, 13, 2, outline);
        for (int i = 0; i < 6; i++) g.fill(11 + i, -(5 - i), 12 + i, (6 - i), outline);
        g.fill(3, -1, 12, 1, c);
        for (int i = 0; i < 5; i++) g.fill(11 + i, -(4 - i), 12 + i, (5 - i), c);
        g.pose().popMatrix();
    }

    private void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    private void drawCardinal(GuiGraphics g, String label, double ox, double oy, double oz,
                             double dist, int s, int x0, int y0, int color) {
        BeaconGeometry.Screen scr = OrbitScene.projectOffset(yaw, pitch, dist, ox, oy, oz, s);
        int px, py;
        if (scr.onScreen()) {
            px = x0 + scr.x();
            py = y0 + scr.y();
        } else {
            int[] e = BeaconGeometry.edgeClamp(scr.dirX(), scr.dirY(), s, s, 10);
            px = x0 + e[0];
            py = y0 + e[1];
        }
        g.drawString(this.font, label, px - this.font.width(label) / 2, py - 4, color);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        yaw += dragX * 0.4;
        pitch = Math.max(-89, Math.min(89, pitch + dragY * 0.4));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        zoom *= verticalAmount > 0 ? 0.85 : 1.18;
        zoom = Math.max(0.15, Math.min(6.0, zoom));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
