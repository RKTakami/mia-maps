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
    // Focus offset from the player (world blocks). Right-click moves it; R resets to the player.
    private final double[] focusOffset = {0, 0, 0};
    // Screen hit-boxes for waypoint markers this frame: {screenX, screenY, wx, wy, wz}. Left-click
    // one to navigate to it.
    private final java.util.List<double[]> waypointHits = new java.util.ArrayList<>();

    public OrbitView(Screen parent) {
        super(Component.literal("Abyss 3D"));
        this.parent = parent;
    }

    private boolean panned() {
        return focusOffset[0] != 0 || focusOffset[1] != 0 || focusOffset[2] != 0;
    }

    private OrbitCamera camera() {
        var p = this.minecraft != null ? this.minecraft.player : null;
        double fx = (p != null ? p.getX() : 0) + focusOffset[0];
        double fy = (p != null ? p.getY() : 0) + focusOffset[1];
        double fz = (p != null ? p.getZ() : 0) + focusOffset[2];
        double dist = OrbitScene.cameraDistance(zoom);
        return new OrbitCamera(fx, fy, fz, yaw, pitch, dist);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Lowering the "3D Area" setting must not leave the view zoomed beyond the new ceiling.
        double zMax = OrbitScene.maxZoom(MiaApertureModClient.mapSettings.orbitAreaBlocks);
        if (zoom > zMax) zoom = zMax;
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
            double labelD = dist * 0.2; // labels stay compact near the focus

            // Compass rose radiates from the FOCUS (screen centre) toward each world direction.
            int axis = MinimapRenderer.PLAYER_COLOR;
            drawArm(guiGraphics, 0, 0, -armD, x0, y0, scale, axis); // N
            drawArm(guiGraphics, 0, 0, armD, x0, y0, scale, axis);  // S
            drawArm(guiGraphics, armD, 0, 0, x0, y0, scale, axis);  // E
            drawArm(guiGraphics, -armD, 0, 0, x0, y0, scale, axis); // W
            drawArm(guiGraphics, 0, armD, 0, x0, y0, scale, axis);  // Up
            drawArm(guiGraphics, 0, -armD, 0, x0, y0, scale, axis); // Down

            drawLabel(guiGraphics, "N", 0, 0, -labelD, x0, y0, scale, 0xFFFF5555);
            drawLabel(guiGraphics, "S", 0, 0, labelD, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "E", labelD, 0, 0, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "W", -labelD, 0, 0, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "U", 0, labelD, 0, x0, y0, scale, 0xFFFFFFFF);
            drawLabel(guiGraphics, "D", 0, -labelD, 0, x0, y0, scale, 0xFFFFFFFF);

            // When panned, mark the focus point (screen centre) with a small yellow crosshair.
            if (panned()) {
                int cxp = x0 + s / 2, cyp = y0 + s / 2;
                guiGraphics.fill(cxp - 6, cyp, cxp + 7, cyp + 1, 0xFFFFDD33);
                guiGraphics.fill(cxp, cyp - 6, cxp + 1, cyp + 7, 0xFFFFDD33);
            }

            // Player marker at ITS projected position (= centre when not panned; off-centre /
            // edge-clamped when the focus has moved). Hidden when terrain is in front of it.
            BeaconGeometry.Screen pp = OrbitScene.projectHud(-focusOffset[0], -focusOffset[1], -focusOffset[2]);
            if (!focusOccluded(pp)) {
                int pmx, pmy;
                if (pp.onScreen()) {
                    pmx = x0 + (int) Math.round(pp.x() * scale);
                    pmy = y0 + (int) Math.round(pp.y() * scale);
                } else {
                    int[] e = BeaconGeometry.edgeClamp(pp.dirX(), pp.dirY(), OrbitScene.size(), OrbitScene.size(), 24);
                    pmx = x0 + (int) Math.round(e[0] * scale);
                    pmy = y0 + (int) Math.round(e[1] * scale);
                }
                double yr = Math.toRadians(this.minecraft.player.getYRot());
                BeaconGeometry.Screen pf = OrbitScene.projectHud(
                        -focusOffset[0] - Math.sin(yr) * 10, -focusOffset[1], -focusOffset[2] + Math.cos(yr) * 10);
                double fdx = pf.x() - pp.x(), fdy = pf.y() - pp.y();
                if (pp.onScreen() && fdx * fdx + fdy * fdy > 0.25) {
                    drawFacingArrow(guiGraphics, pmx, pmy, (float) Math.atan2(fdy, fdx));
                }
                diamond(guiGraphics, pmx, pmy, 3, 0xFF000000);
                diamond(guiGraphics, pmx, pmy, 2, MinimapRenderer.PLAYER_COLOR);
            }

            drawRoute(guiGraphics, x0, y0, scale);
            drawDig(guiGraphics, x0, y0, scale);
            drawWaypoints(guiGraphics, x0, y0, scale);
        }
        guiGraphics.drawString(this.font,
                "drag: orbit  scroll: zoom  R-click: focus  Shift+R-click: waypoint  click waypoint: navigate  R: recentre  Esc: close",
                8, 8, 0xFFFFFFFF);
        String xrayLabel = switch (OrbitScene.xrayMode()) {
            case OFF -> "X-ray: off";
            case GHOST -> "X-ray: ghost shell";
            case CAVE_ONLY -> "X-ray: caves only";
        };
        guiGraphics.drawString(this.font, xrayLabel + "  (X)", 8, 20, 0xFF88FFFF);
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

    private static final int LINE_QUAD = 2;        // the fill below is 2px; step >= this => no overlap
    private static final int MAX_LINE_QUADS = 512; // hard bound on quads for one segment
    private static final int OFF_SCREEN_MARGIN = 64;

    // Rasterises a segment as a bounded run of NON-OVERLAPPING quads.
    //
    // Both properties matter. A perspective sample landing just past the near plane projects
    // millions of pixels off-screen; the old one-quad-per-pixel loop then (a) ran for millions of
    // iterations — the client freeze — and (b) submitted millions of OVERLAPPING quads. Overlap is
    // the killer: MC's GuiRenderState pushes an overlapping element into a new node up its chain,
    // and it walks that chain with a RECURSIVE traverse() — so a long overlapping run blew the
    // stack (StackOverflowError in GuiRenderState.traverse). Capping the quad count bounds the
    // loop, and stepping by >= the quad size keeps the node chain flat.
    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int w = this.width, h = this.height, m = OFF_SCREEN_MARGIN;
        if ((x1 < -m && x2 < -m) || (x1 > w + m && x2 > w + m)
                || (y1 < -m && y2 < -m) || (y1 > h + m && y2 > h + m)) return; // wholly off-screen
        int dx = x2 - x1, dy = y2 - y1;
        int span = Math.max(Math.abs(dx), Math.abs(dy));
        if (span == 0) { g.fill(x1 - 1, y1 - 1, x1 + 1, y1 + 1, color); return; }
        int quads = Math.min(MAX_LINE_QUADS, Math.max(1, span / LINE_QUAD));
        for (int i = 0; i <= quads; i++) {
            int x = x1 + (int) ((long) dx * i / quads);
            int y = y1 + (int) ((long) dy * i / quads);
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    private void diamond(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    // Draw the active route as a cyan line through the cloud, occluding into terrain.
    private void drawRoute(GuiGraphics g, int x0, int y0, double scale) {
        com.mia.aperture.map.Route rt = com.mia.aperture.map.RouteService.route();
        java.util.List<double[]> pts = com.mia.aperture.map.RouteService.aheadPoints();
        if (!pts.isEmpty()) {
            var p = this.minecraft.player;
            double fxw = p.getX() + focusOffset[0], fyw = p.getY() + focusOffset[1], fzw = p.getZ() + focusOffset[2];
            int prevX = 0, prevY = 0;
            boolean havePrev = false, prevVis = false;
            for (double[] wp : pts) {
                BeaconGeometry.Screen s = OrbitScene.projectHud(wp[0] - fxw, wp[1] - fyw, wp[2] - fzw);
                if (s.depth() <= 0.05) { havePrev = false; continue; }
                boolean vis = OrbitScene.depthAt(s.x(), s.y()) >= s.depth() - 2.0;
                int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
                if (havePrev) {
                    // hybrid: bright where the segment is visible, dim/ghosted behind terrain
                    int color = (vis && prevVis) ? 0xFF33DDFF : 0x6633DDFF;
                    drawLine(g, prevX, prevY, sx, sy, color);
                    drawLine(g, prevX, prevY + 1, sx, sy + 1, color);
                }
                prevX = sx;
                prevY = sy;
                havePrev = true;
                prevVis = vis;
            }
            double[] next = pts.get(0);
            BeaconGeometry.Screen ns = OrbitScene.projectHud(next[0] - fxw, next[1] - fyw, next[2] - fzw);
            if (ns.depth() > 0.05) {
                int nx = x0 + (int) Math.round(ns.x() * scale), ny = y0 + (int) Math.round(ns.y() * scale);
                g.fill(nx - 3, ny - 3, nx + 4, ny + 4, 0xFFEAFFFF);
            }
        }
        if (rt.status() != com.mia.aperture.map.Pathfinder.Status.FOUND) {
            String msg = rt.status() == com.mia.aperture.map.Pathfinder.Status.PARTIAL
                    ? "Route: partial (walk closer / no full path yet)"
                    : "Route: no walkable path in range";
            g.drawString(this.font, msg, 8, 32, 0xFFFFDD33);
        }
    }

    // Draw the Plan-B dig/tunnel recommendation: amber blocks-to-mine + a "dig here" beacon.
    private void drawDig(GuiGraphics g, int x0, int y0, double scale) {
        com.mia.aperture.map.Route.DigPlan dp = com.mia.aperture.map.RouteService.route().dig();
        if (dp == null) return;
        var p = this.minecraft.player;
        double fxw = p.getX() + focusOffset[0], fyw = p.getY() + focusOffset[1], fzw = p.getZ() + focusOffset[2];
        int amber = 0xFFFFAA33;
        for (double[] c : dp.cells()) {
            BeaconGeometry.Screen s = OrbitScene.projectHud(c[0] - fxw, c[1] - fyw, c[2] - fzw);
            if (s.depth() <= 0.05) continue;
            boolean vis = OrbitScene.depthAt(s.x(), s.y()) >= s.depth() - 2.0;
            int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, vis ? amber : 0x66FFAA33);
        }
        double[] e = dp.entry();
        BeaconGeometry.Screen es = OrbitScene.projectHud(e[0] - fxw, e[1] - fyw, e[2] - fzw);
        int ex, ey;
        if (es.onScreen()) { ex = x0 + (int) Math.round(es.x() * scale); ey = y0 + (int) Math.round(es.y() * scale); }
        else { int[] ec = BeaconGeometry.edgeClamp(es.dirX(), es.dirY(), OrbitScene.size(), OrbitScene.size(), 16);
               ex = x0 + (int) Math.round(ec[0] * scale); ey = y0 + (int) Math.round(ec[1] * scale); }
        g.drawString(this.font, "▼ Dig here", ex + 6, ey - 4, amber);
        g.drawString(this.font, "Descend: dig down, then tunnel to break out", 8, 44, amber);
    }

    // Draw the server's waypoints in the cloud, projected through the orbit camera; markers
    // edge-clamp to the rim when off-screen so they're always findable.
    private void drawWaypoints(GuiGraphics g, int x0, int y0, double scale) {
        var p = this.minecraft.player;
        String key = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
        double fxw = p.getX() + focusOffset[0], fyw = p.getY() + focusOffset[1], fzw = p.getZ() + focusOffset[2];
        double[] dest = com.mia.aperture.map.RouteService.destination();
        waypointHits.clear();
        for (com.mia.aperture.map.Waypoint w : MiaApertureModClient.waypoints.list(key)) {
            BeaconGeometry.Screen wp = OrbitScene.projectHud((w.x + 0.5) - fxw, (w.y + 0.5) - fyw, (w.z + 0.5) - fzw);
            int px, py;
            if (wp.onScreen()) {
                px = x0 + (int) Math.round(wp.x() * scale);
                py = y0 + (int) Math.round(wp.y() * scale);
            } else {
                int[] e = BeaconGeometry.edgeClamp(wp.dirX(), wp.dirY(), OrbitScene.size(), OrbitScene.size(), 16);
                px = x0 + (int) Math.round(e[0] * scale);
                py = y0 + (int) Math.round(e[1] * scale);
            }
            boolean active = dest != null && Math.abs(dest[0] - (w.x + 0.5)) < 0.6
                    && Math.abs(dest[1] - (w.y + 0.5)) < 0.6 && Math.abs(dest[2] - (w.z + 0.5)) < 0.6;
            diamond(g, px, py, active ? 6 : 4, 0xFF000000);
            diamond(g, px, py, active ? 5 : 3, w.color.argb());
            g.drawString(this.font, active ? (w.name + " ▶") : w.name, px + 6, py - 4, 0xFFFFFFFF);
            waypointHits.add(new double[]{px, py, w.x + 0.5, w.y + 0.5, w.z + 0.5});
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 0) { // left-click on a waypoint marker: navigate to it (else orbit-drag)
            for (double[] h : waypointHits) {
                if (Math.abs(event.x() - h[0]) <= 8 && Math.abs(event.y() - h[1]) <= 8) {
                    com.mia.aperture.map.RouteService.setDestination(h[2], h[3], h[4]);
                    return true;
                }
            }
        }
        if (event.button() == 1) { // right-click: pick the point under the cursor
            int s = Math.min(this.width, this.height);
            int x0 = (this.width - s) / 2, y0 = (this.height - s) / 2;
            double inv = (double) OrbitScene.size() / s; // screen -> texture
            int texX = (int) Math.round((event.x() - x0) * inv);
            int texY = (int) Math.round((event.y() - y0) * inv);
            double[] off = OrbitScene.unprojectOffset(texX, texY);
            if (off != null) {
                var p = this.minecraft.player;
                if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
                    // Shift+right-click: add a waypoint at the clicked world point.
                    int wx = (int) Math.floor(p.getX() + focusOffset[0] + off[0]);
                    int wy = (int) Math.floor(p.getY() + focusOffset[1] + off[1]);
                    int wz = (int) Math.floor(p.getZ() + focusOffset[2] + off[2]);
                    String key = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
                    this.minecraft.setScreen(new WaypointEditScreen(this, Component.literal("New Waypoint"),
                            "Waypoint", wx, wy, wz, com.mia.aperture.map.WaypointColor.RED, w -> {
                                MiaApertureModClient.waypoints.add(key, w);
                                com.mia.aperture.map.WaypointConfig.save(
                                        MiaApertureModClient.waypointConfigPath(), MiaApertureModClient.waypoints);
                            }));
                } else {
                    // Plain right-click: pan the orbit focus to the clicked point.
                    focusOffset[0] += off[0];
                    focusOffset[1] += off[1];
                    focusOffset[2] += off[2];
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
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
        // Ceiling follows the "3D Area" setting: zoom = area / EXTENT (2048 -> 16, 8192 -> 64).
        zoom = Math.max(0.15, Math.min(
                OrbitScene.maxZoom(MiaApertureModClient.mapSettings.orbitAreaBlocks), zoom));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_R) { // recentre the focus back on the player
            focusOffset[0] = focusOffset[1] = focusOffset[2] = 0;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_X) { // cycle 3D x-ray: off -> ghost shell -> caves only
            OrbitScene.setXrayMode(switch (OrbitScene.xrayMode()) {
                case OFF -> OrbitScene.XrayMode.GHOST;
                case GHOST -> OrbitScene.XrayMode.CAVE_ONLY;
                case CAVE_ONLY -> OrbitScene.XrayMode.OFF;
            });
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
