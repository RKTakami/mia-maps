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
            // Placed through the shifted column like the waypoints, so panning the focus across a
            // section boundary can't strand it a section away. The facing arrow's tip is offset in
            // shifted space rather than world space, so standing next to a boundary can't throw the
            // tip onto the neighbouring layer.
            var pl = this.minecraft.player;
            double[] ps = com.mia.aperture.map.MapGeometry.toShiftedColumn(pl.getX(), pl.getY(), pl.getZ());
            BeaconGeometry.Screen pp = OrbitScene.projectShifted(ps[0], ps[1], ps[2]);
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
                double yr = Math.toRadians(pl.getYRot());
                BeaconGeometry.Screen pf = OrbitScene.projectShifted(
                        ps[0] - Math.sin(yr) * 10, ps[1], ps[2] + Math.cos(yr) * 10);
                double fdx = pf.x() - pp.x(), fdy = pf.y() - pp.y();
                if (pp.onScreen() && fdx * fdx + fdy * fdy > 0.25) {
                    drawFacingArrow(guiGraphics, pmx, pmy, (float) Math.atan2(fdy, fdx));
                }
                diamond(guiGraphics, pmx, pmy, 3, 0xFF000000);
                diamond(guiGraphics, pmx, pmy, 2, MinimapRenderer.PLAYER_COLOR);
            }

            drawCorridor(guiGraphics, x0, y0, scale);
            drawRoute(guiGraphics, x0, y0, scale);
            drawDig(guiGraphics, x0, y0, scale);
            drawWaypoints(guiGraphics, x0, y0, scale);
        }
        guiGraphics.drawString(this.font,
                "drag: orbit  scroll: zoom  R-click: focus  Shift+R-click: waypoint  click waypoint: navigate  R: recentre  Esc: close",
                8, 8, 0xFFFFFFFF);
        boolean whole = MiaApertureModClient.mapSettings.orbitAreaBlocks
                == com.mia.aperture.map.MapSettings.ORBIT_AREA_WHOLE;
        String xrayLabel = whole ? "X-ray: n/a (whole Abyss)" : switch (OrbitScene.xrayMode()) {
            case OFF -> "X-ray: off";
            case GHOST -> "X-ray: ghost shell";
            case CAVE_ONLY -> "X-ray: caves only";
        };
        guiGraphics.drawString(this.font, xrayLabel + "  (X)", 8, 20, 0xFF88FFFF);
        // Optional 3D Stats overlay (Settings -> "3D Stats"). Shifted coords: the rim is ~3840.
        // y=32 is the route-status line and y=44 the dig hint, so sit below both.
        if (MiaApertureModClient.mapSettings.orbitStats) {
            int cap = MiaApertureModClient.mapSettings.orbitQuality.maxPoints;
            int pts = OrbitScene.lastCloudSize();
            guiGraphics.drawString(this.font,
                    "sec " + OrbitScene.statSector + "  lod " + OrbitScene.statLvl
                            + "  focusY " + OrbitScene.statFocusY
                            + "  band " + OrbitScene.statBandLo + ".." + OrbitScene.statBandHi
                            + "  voxY " + OrbitScene.statVoxMinY + ".." + OrbitScene.statVoxMaxY
                            + "  pts " + pts + "/" + cap + (pts >= cap ? " (CAPPED)" : ""),
                    8, 56, 0xFFFF66FF);
            if (whole) {
                var snap = com.mia.aperture.map.AbyssSpanStore.current();
                String state = snap.probedSections() < snap.totalSections()
                        ? "building " + (100 * snap.probedSections() / Math.max(1, snap.totalSections())) + "%"
                        : "built " + ((System.currentTimeMillis() - snap.builtAtMs()) / 1000) + "s ago";
                guiGraphics.drawString(this.font,
                        "cache " + snap.base().size() + " cols  surf " + snap.surfaceCounts()[0]
                                + "  " + state,
                        8, 68, 0xFFFF66FF);
            }
        }
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

    // Draw the coarse corridor (the whole-column "which shaft to head down" guide) as a faint amber
    // dotted trail through the cloud. The bright block-accurate route is drawn on top of it.
    private void drawCorridor(GuiGraphics g, int x0, int y0, double scale) {
        java.util.List<double[]> corridor = com.mia.aperture.map.RouteService.corridorShifted();
        if (corridor.size() < 2) return;
        int faint = 0x66FFCC33;
        for (double[] c : corridor) {
            BeaconGeometry.Screen s = OrbitScene.projectShifted(c[0], c[1], c[2]);
            if (s.depth() <= 0.05) continue;
            int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
            com.mia.aperture.map.MarkerShapes.disc(g, sx, sy, 1, faint);
        }
    }

    // Draw the active route as a trail of cyan spheres through the cloud, occluding into terrain:
    // bright where a breadcrumb is visible, dim/ghosted where rock is in front of it.
    private void drawRoute(GuiGraphics g, int x0, int y0, double scale) {
        com.mia.aperture.map.Route rt = com.mia.aperture.map.RouteService.route();
        java.util.List<double[]> pts = com.mia.aperture.map.RouteService.aheadPointsShifted();
        if (!pts.isEmpty()) {
            for (double[] wp : pts) {
                BeaconGeometry.Screen s = OrbitScene.projectShifted(wp[0], wp[1], wp[2]);
                if (s.depth() <= 0.05) continue;
                boolean vis = OrbitScene.depthAt(s.x(), s.y()) >= s.depth() - 2.0;
                int color = vis ? 0xFF33DDFF : 0x6633DDFF;
                int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
                com.mia.aperture.map.MarkerShapes.sphere(g, sx, sy, 2, color);
            }
            double[] next = pts.get(0);
            BeaconGeometry.Screen ns = OrbitScene.projectShifted(next[0], next[1], next[2]);
            if (ns.depth() > 0.05) {
                int nx = x0 + (int) Math.round(ns.x() * scale), ny = y0 + (int) Math.round(ns.y() * scale);
                com.mia.aperture.map.MarkerShapes.sphere(g, nx, ny, 4, 0xFFEAFFFF);
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
        int amber = 0xFFFFAA33;
        for (double[] c : dp.cells()) {
            BeaconGeometry.Screen s = OrbitScene.projectShifted(c[0], c[1], c[2]);
            if (s.depth() <= 0.05) continue;
            boolean vis = OrbitScene.depthAt(s.x(), s.y()) >= s.depth() - 2.0;
            int sx = x0 + (int) Math.round(s.x() * scale), sy = y0 + (int) Math.round(s.y() * scale);
            g.fill(sx - 1, sy - 1, sx + 2, sy + 2, vis ? amber : 0x66FFAA33);
        }
        double[] e = dp.entry();
        BeaconGeometry.Screen es = OrbitScene.projectShifted(e[0], e[1], e[2]);
        int ex, ey;
        if (es.onScreen()) { ex = x0 + (int) Math.round(es.x() * scale); ey = y0 + (int) Math.round(es.y() * scale); }
        else { int[] ec = BeaconGeometry.edgeClamp(es.dirX(), es.dirY(), OrbitScene.size(), OrbitScene.size(), 16);
               ex = x0 + (int) Math.round(ec[0] * scale); ey = y0 + (int) Math.round(ec[1] * scale); }
        g.drawString(this.font, "▼ Descend here", ex + 6, ey - 4, amber);
        g.drawString(this.font, "Descend: dig down, then tunnel to break out", 8, 44, amber);
    }

    private static final float OFF_LAYER_DIM = 0.55f;     // markers on another section recede
    private static final float OCCLUDED_ALPHA = 0.35f;    // ...and fade where rock is in front

    // Draw the server's waypoints in the cloud; markers edge-clamp to the rim when off-screen so
    // they're always findable.
    //
    // Each waypoint is placed via its OWN section's shift, not the focus's. Waypoints are stored in
    // world coords, where sections sit 16384 blocks apart along X; the cloud and camera live in the
    // shifted column, where those same sections stack 480 blocks apart vertically. Subtracting a
    // world-space focus (as this once did) is therefore only right for waypoints on the layer you
    // are standing on — every other one was flung a section sideways and pinned to the rim.
    private void drawWaypoints(GuiGraphics g, int x0, int y0, double scale) {
        waypointHits.clear();
        if (!MiaApertureModClient.mapSettings.showNavMarkers) return;
        var p = this.minecraft.player;
        String key = com.mia.aperture.map.WaypointStore.currentServerKey(this.minecraft);
        double[] dest = com.mia.aperture.map.RouteService.destination();
        double focusShiftedY = OrbitScene.hudFocusShiftedY();
        int focusSector = com.mia.aperture.map.MapGeometry.sectorForX(p.getX() + focusOffset[0]);
        for (com.mia.aperture.map.Waypoint w : MiaApertureModClient.waypoints.list(key)) {
            if (!w.visible) continue;
            double[] s = com.mia.aperture.map.MapGeometry.toShiftedColumn(w.x + 0.5, w.y + 0.5, w.z + 0.5);
            BeaconGeometry.Screen wp = OrbitScene.projectShifted(s[0], s[1], s[2]);
            int px, py;
            boolean onScreen = wp.onScreen();
            if (onScreen) {
                px = x0 + (int) Math.round(wp.x() * scale);
                py = y0 + (int) Math.round(wp.y() * scale);
            } else {
                int[] e = BeaconGeometry.edgeClamp(wp.dirX(), wp.dirY(), OrbitScene.size(), OrbitScene.size(), 16);
                px = x0 + (int) Math.round(e[0] * scale);
                py = y0 + (int) Math.round(e[1] * scale);
            }
            boolean active = dest != null && Math.abs(dest[0] - (w.x + 0.5)) < 0.6
                    && Math.abs(dest[1] - (w.y + 0.5)) < 0.6 && Math.abs(dest[2] - (w.z + 0.5)) < 0.6;
            boolean offLayer = com.mia.aperture.map.MapGeometry.sectorForX(w.x + 0.5) != focusSector;
            // Only a marker actually in the frustum can be behind anything; an edge-clamped one is
            // a direction hint sitting on the rim, so leave it solid.
            boolean occluded = onScreen && OrbitScene.depthAt(wp.x(), wp.y()) < wp.depth() - 2.0;

            int body = w.color.argb();
            int label = 0xFFFFFFFF;
            if (offLayer) {
                body = com.mia.aperture.map.ColorMath.shade(body, OFF_LAYER_DIM);
                label = com.mia.aperture.map.ColorMath.shade(label, OFF_LAYER_DIM);
            }
            if (occluded) {
                body = com.mia.aperture.map.ColorMath.withAlpha(body, OCCLUDED_ALPHA);
                label = com.mia.aperture.map.ColorMath.withAlpha(label, OCCLUDED_ALPHA);
            }
            int outline = com.mia.aperture.map.ColorMath.withAlpha(0xFF000000, occluded ? OCCLUDED_ALPHA : 1.0f);

            diamond(g, px, py, active ? 6 : 4, outline);
            diamond(g, px, py, active ? 5 : 3, body);
            String text = w.name;
            if (active) text += " ▶";
            if (offLayer) text += "  " + layerTag(s[1], focusShiftedY);
            g.drawString(this.font, text, px + 6, py - 4, label);
            waypointHits.add(new double[]{px, py, w.x + 0.5, w.y + 0.5, w.z + 0.5});
        }
    }

    // Depth tag for a marker on another Abyss layer: an arrow toward it plus its own depth, in
    // whichever unit the HUD readout is set to, so the two agree.
    private static String layerTag(double markerShiftedY, double focusShiftedY) {
        int physicalDepth = (int) Math.round(com.mia.aperture.map.MapGeometry.abyssDepth(markerShiftedY));
        String arrow = markerShiftedY < focusShiftedY ? "▼" : "▲";
        return MiaApertureModClient.mapSettings.depthInMeters
                ? arrow + MiaApertureModClient.depthToMeters(physicalDepth) + "m"
                : arrow + (-physicalDepth);
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
