package com.mia.aperture.client;

import com.mia.aperture.map.BeaconGeometry;
import com.mia.aperture.map.MapGeometry;
import com.mia.aperture.map.Waypoint;
import com.mia.aperture.map.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

public final class BeaconRenderer {
    private BeaconRenderer() {}

    public static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!MiaApertureModClient.mapSettings.showBeacons) return;
        if (mc.player == null || mc.level == null) return;

        Vec3 eye = mc.player.getEyePosition();
        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        // camera forward (Minecraft convention: yaw 0 looks +Z)
        double fx = -Math.sin(yaw) * cp, fy = -sp, fz = Math.cos(yaw) * cp;
        // right = normalize(forward x worldUp); left = -right; up = right x forward
        double rrx = -fz, rrz = fx;
        double rl = Math.sqrt(rrx * rrx + rrz * rrz);
        if (rl < 1e-6) rl = 1;
        double rx = rrx / rl, rz = rrz / rl;
        double lx = -rx, ly = 0, lz = -rz;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;

        int w = g.guiWidth(), h = g.guiHeight();
        double focal = (h / 2.0) / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        String key = WaypointStore.currentServerKey(mc);
        int playerSector = MapGeometry.sectorForX(eye.x);
        double[] pShift = MapGeometry.toShiftedColumn(eye.x, eye.y, eye.z);
        for (Waypoint wp : MiaApertureModClient.waypoints.list(key)) {
            if (!wp.visible) continue;
            int color = wp.color.argb();
            if (MapGeometry.sectorForX(wp.x + 0.5) == playerSector) {
                // Same layer: the waypoint sits in the player's own section, so its world position
                // is real and the beacon points straight at it.
                double relX = (wp.x + 0.5) - eye.x, relY = (wp.y + 0.5) - eye.y, relZ = (wp.z + 0.5) - eye.z;
                double dist = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
                BeaconGeometry.Screen s = BeaconGeometry.project(relX, relY, relZ,
                        fx, fy, fz, ux, uy, uz, lx, ly, lz, focal, w, h);
                if (s.onScreen()) {
                    drawIcon(g, s.x(), s.y(), color);
                    String label = wp.name + "  " + (int) dist + "m";
                    int tw = mc.font.width(label);
                    g.drawString(mc.font, label, s.x() - tw / 2, s.y() - 16, 0xFFFFFFFF);
                } else {
                    int[] e = BeaconGeometry.edgeClamp(s.dirX(), s.dirY(), w, h, 16);
                    drawIcon(g, e[0], e[1], color);
                }
            } else {
                // Off layer: the waypoint is in another Abyss section, ~16384 blocks away in world X,
                // so there is no honest world-space direction to point at — you reach it by descending
                // the shifted column. Show the true (shifted) distance and which way to travel.
                double[] wShift = MapGeometry.toShiftedColumn(wp.x + 0.5, wp.y + 0.5, wp.z + 0.5);
                double ddx = wShift[0] - pShift[0], ddy = wShift[1] - pShift[1], ddz = wShift[2] - pShift[2];
                double sdist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                boolean below = ddy < 0;
                int[] e = BeaconGeometry.edgeClamp(0, below ? 1 : -1, w, h, 16);
                drawIcon(g, e[0], e[1], color);
                String label = wp.name + "  " + (below ? "▼" : "▲") + (int) sdist + "m (layer)";
                int tw = mc.font.width(label);
                g.drawString(mc.font, label, e[0] - tw / 2, below ? e[1] - 16 : e[1] + 8, 0xFFFFFFFF);
            }
        }
    }

    private static void drawIcon(GuiGraphics g, int cx, int cy, int color) {
        // diamond with a dark halo for contrast on any background
        g.fill(cx - 4, cy - 1, cx + 5, cy + 2, 0xC0000000);
        g.fill(cx - 1, cy - 4, cx + 2, cy + 5, 0xC0000000);
        g.fill(cx - 3, cy - 1, cx + 4, cy + 2, color);
        g.fill(cx - 1, cy - 3, cx + 2, cy + 4, color);
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, color);
    }
}
