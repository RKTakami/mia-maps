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
        double dist = OrbitScene.cameraDistance(zoom);
        return new OrbitCamera(fx, fy, fz, yaw, pitch, dist);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0B0B10);
        if (this.minecraft != null && this.minecraft.player != null) {
            OrbitScene.render(camera(), zoom, MiaApertureModClient.mapSettings.orbitQuality);
            int s = Math.min(this.width, this.height);
            int x0 = (this.width - s) / 2, y0 = (this.height - s) / 2;
            // blit(Identifier, x0, y0, x1, y1, u0, u1, v0, v1) — the int args are CORNERS,
            // not (x, y, w, h). Pass x0+s / y0+s for the right/bottom edges.
            guiGraphics.blit(OrbitScene.TEXTURE, x0, y0, x0 + s, y0 + s, 0.0f, 1.0f, 0.0f, 1.0f);

            double scale = (double) s / OrbitScene.size(); // texture-space -> screen
            double dist = OrbitScene.cameraDistance(zoom);
            double armD = dist * 0.9;   // long reference arms for sighting against features
            double labelD = dist * 0.2; // labels stay compact near the player

            // Player's true projected position, through the SAME camera the cloud used.
            BeaconGeometry.Screen fc = OrbitScene.projectHud(0, 0, 0);
            int mcx = x0 + (int) Math.round(fc.x() * scale);
            int mcy = y0 + (int) Math.round(fc.y() * scale);

            // Electric-red axis lines radiating from the player toward each world direction.
            int axis = MinimapRenderer.PLAYER_COLOR;
            drawArm(guiGraphics, 0, 0, -armD, x0, y0, scale, axis); // N
            drawArm(guiGraphics, 0, 0, armD, x0, y0, scale, axis);  // S
            drawArm(guiGraphics, armD, 0, 0, x0, y0, scale, axis);  // E
            drawArm(guiGraphics, -armD, 0, 0, x0, y0, scale, axis); // W
            drawArm(guiGraphics, 0, armD, 0, x0, y0, scale, axis);  // Up
            drawArm(guiGraphics, 0, -armD, 0, x0, y0, scale, axis); // Down

            // Direction labels near the player (compact rose).
            drawLabel(guiGraphics, "N", 0, 0, -labelD, x0, y0, scale, 0xFFFF5555);
            drawLabel(guiGraphics, "S", 0, 0, labelD, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "E", labelD, 0, 0, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "W", -labelD, 0, 0, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "U", 0, labelD, 0, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "D", 0, -labelD, 0, x0, y0, scale, 0xFFFFFFFF);

            // Player marker, hidden when terrain sits between the camera and the player.
            if (!focusOccluded(fc)) {
                double yr = Math.toRadians(this.minecraft.player.getYRot());
                BeaconGeometry.Screen fp = OrbitScene.projectHud(-Math.sin(yr) * 10, 0, Math.cos(yr) * 10);
                double fdx = fp.x() - fc.x(), fdy = fp.y() - fc.y();
                if (fdx * fdx + fdy * fdy > 0.25) {
                    drawFacingArrow(guiGraphics, mcx, mcy, (float) Math.atan2(fdy, fdx));
                }
                diamond(guiGraphics, mcx, mcy, 3, 0xFF000000);
                diamond(guiGraphics, mcx, mcy, 2, MinimapRenderer.PLAYER_COLOR);
            }
        }
        guiGraphics.drawString(this.font, "Abyss 3D  —  drag: orbit   scroll: zoom   Esc: close", 8, 8, 0xFFFFFFFF);
    }

    // The map's elongated chevron (MinimapRenderer), scaled 2x, pointing up (-Y), and
    // shifted so its centroid sits on the pivot (rotates in place on the player's spot).
    private static final int[][] CHEVRON = {
        {0, -8, 2, -4}, {-2, -4, 4, 0}, {-4, 0, 6, 4}, {-6, 4, -2, 8}, {2, 4, 6, 8}
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
    private boolean focusOccluded(BeaconGeometry.Screen fc) {
        float minD = Float.MAX_VALUE;
        for (int oy = -2; oy <= 2; oy++) {
            for (int ox = -2; ox <= 2; ox++) minD = Math.min(minD, OrbitScene.depthAt(fc.x() + ox, fc.y() + oy));
        }
        return minD < fc.depth() - 4;
    }

    // Sample the arm along its 3D length (through the cloud camera) and depth-test each point
    // against the terrain, so it disappears where rock is in front. Visible runs are joined.
    private void drawArm(GuiGraphics g, double ox, double oy, double oz, int x0, int y0, double scale, int color) {
        int steps = 48;
        boolean prevVis = false;
        int prevX = 0, prevY = 0;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            BeaconGeometry.Screen p = OrbitScene.projectHud(ox * t, oy * t, oz * t);
            boolean vis = p.depth() > 0.05 && OrbitScene.depthAt(p.x(), p.y()) >= p.depth() - 2.0;
            if (vis) {
                int sx = x0 + (int) Math.round(p.x() * scale), sy = y0 + (int) Math.round(p.y() * scale);
                if (prevVis) drawLine(g, prevX, prevY, sx, sy, color);
                else g.fill(sx - 1, sy - 1, sx + 2, sy + 2, color);
                prevX = sx;
                prevY = sy;
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

    private void drawLabel(GuiGraphics g, String label, double ox, double oy, double oz,
                           int x0, int y0, double scale, int color) {
        BeaconGeometry.Screen p = OrbitScene.projectHud(ox, oy, oz);
        int px, py;
        if (p.onScreen()) {
            px = x0 + (int) Math.round(p.x() * scale);
            py = y0 + (int) Math.round(p.y() * scale);
        } else {
            int[] e = BeaconGeometry.edgeClamp(p.dirX(), p.dirY(), OrbitScene.size(), OrbitScene.size(), 20);
            px = x0 + (int) Math.round(e[0] * scale);
            py = y0 + (int) Math.round(e[1] * scale);
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
        zoom = Math.max(0.15, Math.min(16.0, zoom)); // 16 -> ~2048-block extent (~1/8 layer, the grid ceiling)
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
