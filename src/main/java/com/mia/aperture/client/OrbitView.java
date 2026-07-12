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

            double dist = 160 * zoom, d = dist * 0.4; // labels/axes ride a fixed screen ring
            int cx = x0 + s / 2, cy = y0 + s / 2;     // player is the orbit focus -> screen centre

            // Electric-red axis lines radiating from the player toward each world direction.
            int axis = MinimapRenderer.PLAYER_COLOR;
            drawAxis(guiGraphics, cx, cy, 0, 0, -d, dist, s, x0, y0, axis); // N
            drawAxis(guiGraphics, cx, cy, 0, 0, d, dist, s, x0, y0, axis);  // S
            drawAxis(guiGraphics, cx, cy, d, 0, 0, dist, s, x0, y0, axis);  // E
            drawAxis(guiGraphics, cx, cy, -d, 0, 0, dist, s, x0, y0, axis); // W
            drawAxis(guiGraphics, cx, cy, 0, d, 0, dist, s, x0, y0, axis);  // Up
            drawAxis(guiGraphics, cx, cy, 0, -d, 0, dist, s, x0, y0, axis); // Down

            // Direction labels at the axis tips.
            drawCardinal(guiGraphics, "N", 0, 0, -d, dist, s, x0, y0, 0xFFFF5555);
            drawCardinal(guiGraphics, "S", 0, 0, d, dist, s, x0, y0, 0xFFFFFFFF);
            drawCardinal(guiGraphics, "E", d, 0, 0, dist, s, x0, y0, 0xFFFFFFFF);
            drawCardinal(guiGraphics, "W", -d, 0, 0, dist, s, x0, y0, 0xFFFFFFFF);
            drawCardinal(guiGraphics, "U", 0, d, 0, dist, s, x0, y0, 0xFFFFFFFF);
            drawCardinal(guiGraphics, "D", 0, -d, 0, dist, s, x0, y0, 0xFFFFFFFF);

            // Player marker at centre, hidden when terrain sits between the camera and the player.
            if (!focusOccluded(dist)) {
                double yr = Math.toRadians(this.minecraft.player.getYRot());
                BeaconGeometry.Screen f = OrbitScene.projectOffset(yaw, pitch, dist,
                        -Math.sin(yr) * 10, 0, Math.cos(yr) * 10, s);
                double fdx = f.x() - s / 2.0, fdy = f.y() - s / 2.0;
                if (fdx * fdx + fdy * fdy > 0.25) {
                    drawFacingArrow(guiGraphics, cx, cy, (float) Math.atan2(fdy, fdx));
                }
                diamond(guiGraphics, cx, cy, 3, 0xFF000000);
                diamond(guiGraphics, cx, cy, 2, MinimapRenderer.PLAYER_COLOR);
            }
        }
        guiGraphics.drawString(this.font, "Abyss 3D  —  drag: orbit   scroll: zoom   Esc: close", 8, 8, 0xFFFFFFFF);
    }

    // The map's elongated chevron (MinimapRenderer), scaled 2x, defined pointing up (-Y).
    private static final int[][] CHEVRON = {
        {0, -12, 2, -8}, {-2, -8, 4, -4}, {-4, -4, 6, 0}, {-6, 0, -2, 4}, {2, 0, 6, 4}
    };

    private void drawFacingArrow(GuiGraphics g, int cx, int cy, float ang) {
        g.pose().pushMatrix();
        g.pose().translate(cx + 0.5f, cy + 0.5f);
        g.pose().rotate(ang + (float) (Math.PI / 2)); // chevron points up; align its tip to facing
        for (int[] q : CHEVRON) g.fill(q[0] - 1, q[1] - 1, q[2] + 1, q[3] + 1, 0xFF000000);
        for (int[] q : CHEVRON) g.fill(q[0], q[1], q[2], q[3], MinimapRenderer.PLAYER_COLOR);
        g.pose().popMatrix();
    }

    // True when rock sits between the camera and the player (so the centre marker should hide).
    private boolean focusOccluded(double focusDepth) {
        int c = OrbitScene.SIZE / 2;
        float minD = Float.MAX_VALUE;
        for (int oy = -2; oy <= 2; oy++) {
            for (int ox = -2; ox <= 2; ox++) minD = Math.min(minD, OrbitScene.depthAt(c + ox, c + oy));
        }
        return minD < focusDepth - 4;
    }

    // Sample the axis along its 3D length and depth-test each point against the terrain, so
    // the line disappears where rock is in front of it. Visible runs are joined into segments.
    private void drawAxis(GuiGraphics g, int cx, int cy, double ox, double oy, double oz,
                          double dist, int s, int x0, int y0, int color) {
        int steps = 48;
        double scale = (double) OrbitScene.SIZE / s;
        boolean prevVis = false;
        int prevX = 0, prevY = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            BeaconGeometry.Screen p = OrbitScene.projectOffset(yaw, pitch, dist, ox * t, oy * t, oz * t, s);
            boolean vis = p.depth() > 0.05
                    && OrbitScene.depthAt((int) Math.round(p.x() * scale), (int) Math.round(p.y() * scale))
                        >= p.depth() - 2.0;
            if (vis) {
                int px = x0 + p.x(), py = y0 + p.y();
                if (prevVis) drawLine(g, prevX, prevY, px, py, color);
                else g.fill(px - 1, py - 1, px + 2, py + 2, color);
                prevX = px;
                prevY = py;
            }
            prevVis = vis;
        }
    }

    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1, dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) { g.fill(x1 - 1, y1 - 1, x1 + 1, y1 + 1, color); return; }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + dx * i / steps;
            int y = y1 + dy * i / steps;
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
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
